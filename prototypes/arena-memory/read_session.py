#!/usr/bin/env python3
"""One explicitly launched, expiring Arena read session. No persistent install.

Obtain only task_read_for_pid for the verified Arena process, permanently drop
sudo privileges, then serve bounded reads over an owner-only local Unix socket.
No commands, writes, target changes, injection, debugger or network listener.
"""
from __future__ import annotations

import argparse
import base64
import hmac
import importlib.util
import json
import os
from pathlib import Path
import pwd
import secrets
import signal
import socket
import stat
import struct
import sys
import tempfile
import time

_spec = importlib.util.spec_from_file_location('session_deck_reader', Path(__file__).with_name('deck_reader.py'))
decks = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(decks)
transport = decks.transport
MAX_SECONDS = 15 * 60
MAX_BYTES = 64 * 1024 * 1024
MAX_READ = 65536
MAX_REQUEST = 4096
MAX_RESPONSE = 100000


def receive(connection, maximum):
    prefix = bytearray()
    while len(prefix) < 4:
        part = connection.recv(4 - len(prefix))
        if not part:
            raise transport.ProbeError('Session connection closed.')
        prefix.extend(part)
    size = struct.unpack('!I', prefix)[0]
    if not 0 < size <= maximum:
        raise transport.ProbeError('Session message exceeds bounds.')
    data = bytearray()
    while len(data) < size:
        part = connection.recv(size - len(data))
        if not part:
            raise transport.ProbeError('Truncated session message.')
        data.extend(part)
    message = json.loads(data)
    if not isinstance(message, dict):
        raise transport.ProbeError('Expected a session message object.')
    return message


def send(connection, message):
    data = json.dumps(message, separators=(',', ':')).encode()
    if len(data) > MAX_RESPONSE:
        raise transport.ProbeError('Session response exceeds bounds.')
    connection.sendall(struct.pack('!I', len(data)) + data)


def drop_administrator():
    if os.getuid() != 0 or os.geteuid() != 0:
        raise transport.AccessDenied('Launch this session once using sudo in Terminal.')
    uid, gid = int(os.environ.get('SUDO_UID', '0')), int(os.environ.get('SUDO_GID', '0'))
    if uid <= 0 or gid <= 0 or pwd.getpwuid(uid).pw_uid != uid:
        raise transport.AccessDenied('A non-root invoking sudo user is required.')
    # setuid on macOS sets real/effective/saved IDs. Drop groups first.
    os.setgroups([])
    os.setgid(gid)
    os.setuid(uid)
    if (os.getuid(), os.geteuid(), os.getgid(), os.getegid()) != (uid, uid, gid, gid):
        raise transport.AccessDenied('Privilege drop could not be verified; session refused.')
    os.umask(0o077)
    return uid


class Session:
    def __init__(self, memory, pid, executable, token, *, seconds=MAX_SECONDS, byte_limit=MAX_BYTES):
        self.memory, self.pid, self.executable, self.token = memory, pid, executable, token
        self.deadline = time.monotonic() + min(seconds, MAX_SECONDS)
        self.bytes_left = min(byte_limit, MAX_BYTES)
        self.closed = False

    def handle(self, message):
        if time.monotonic() >= self.deadline or self.closed:
            raise transport.AccessDenied('The read session has expired or closed.')
        if not isinstance(message.get('token'), str) or not hmac.compare_digest(message['token'], self.token):
            raise transport.AccessDenied('Session authentication failed.')
        op = message.get('op')
        if op == 'close':
            self.closed = True
            return {'ok': True}
        if transport.process_path(self.pid) != self.executable:
            self.closed = True
            raise transport.InvalidTarget('Arena exited or changed; the session cannot retarget.')
        if op == 'info':
            return {'ok': True, 'pid': self.pid, 'read_api': self.memory.api,
                    'remaining_seconds': max(0, int(self.deadline - time.monotonic())),
                    'remaining_bytes': self.bytes_left}
        if op == 'images':
            return {'ok': True, 'address': self.memory.images_address()}
        if op != 'read' or set(message) != {'token', 'op', 'address', 'size'}:
            raise transport.ProbeError('Unsupported read-session operation.')
        address, size = message['address'], message['size']
        if (type(address) is not int or type(size) is not int or address < 4096 or
                not 0 < size <= MAX_READ or address + size > 1 << 48):
            raise transport.ProbeError('Read request exceeds session bounds.')
        if size > self.bytes_left:
            self.closed = True
            raise transport.ProbeError('Session byte limit exhausted.')
        self.bytes_left -= size
        data = self.memory.read(address, size)
        if len(data) != size:
            raise transport.ProbeError('Short session read.')
        return {'ok': True, 'data': base64.b64encode(data).decode('ascii')}

    def serve(self, listener, *, hard_alarm=True):
        while not self.closed and time.monotonic() < self.deadline:
            listener.settimeout(min(1, max(.001, self.deadline - time.monotonic())))
            try:
                connection, _ = listener.accept()
            except socket.timeout:
                continue
            with connection:
                connection.settimeout(min(3, max(.001, self.deadline - time.monotonic())))
                try:
                    message = receive(connection, MAX_REQUEST)
                    if hard_alarm:
                        signal.setitimer(signal.ITIMER_REAL, min(8, max(.001, self.deadline - time.monotonic())))
                    response = self.handle(message)
                except (transport.ProbeError, OSError, ValueError, TypeError) as error:
                    response = {'ok': False, 'reason': str(error) if isinstance(error, transport.ProbeError) else type(error).__name__}
                finally:
                    if hard_alarm:
                        signal.setitimer(signal.ITIMER_REAL, max(.001, self.deadline - time.monotonic()))
                try:
                    send(connection, response)
                except OSError:
                    pass


def load_manifest(path):
    path = Path(path)
    for item, mode in [(path.parent, 0o700), (path, 0o600)]:
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != mode:
            raise transport.AccessDenied('Read-session files must be private and owned by the current user.')
    if not path.is_file() or path.stat().st_size > 4096:
        raise transport.AccessDenied('Invalid read-session manifest.')
    value = json.loads(path.read_text())
    if value.get('schema') != 1 or value.get('uid') != os.getuid() or time.time() >= value.get('expires', 0):
        raise transport.AccessDenied('Read-session manifest is invalid or expired.')
    if not isinstance(value.get('token'), str) or len(value['token']) != 64:
        raise transport.AccessDenied('Invalid read-session authentication.')
    sock = Path(value.get('socket', ''))
    info = sock.lstat()
    if sock.parent != path.parent or info.st_uid != os.getuid() or not stat.S_ISSOCK(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise transport.AccessDenied('Read-session socket is not a private local socket.')
    return value


class RemoteMemory:
    def __init__(self, manifest, pid):
        self.manifest = load_manifest(manifest)
        info = self.request('info')
        if info['pid'] != pid or info['read_api'] != 'task_read_for_pid':
            raise transport.InvalidTarget('Session does not refer to this Arena process/read right.')
        self.api = 'task_read_for_pid (temporary session)'

    def request(self, op, **values):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(5)
            connection.connect(self.manifest['socket'])
            send(connection, dict(token=self.manifest['token'], op=op, **values))
            response = receive(connection, MAX_RESPONSE)
        if not response.get('ok'):
            raise transport.ProbeError(response.get('reason', 'Session request failed.'))
        return response

    def read(self, address, size):
        return base64.b64decode(self.request('read', address=address, size=size)['data'], validate=True)

    def images_address(self):
        return self.request('images')['address']

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass  # The explicit close operation or deadline releases the shared right.


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, default=transport.DEFAULT_APP)
    args = parser.parse_args(argv)
    try:
        app, executable, report = transport.inspect_build(args.app)
        profile = json.loads(decks.PROFILE_PATH.read_text())
        image = decks.inspect_profile(app, report, profile)
        pid = transport.resolve_pid(executable)
        signal.signal(signal.SIGALRM, signal.SIG_DFL)
        signal.setitimer(signal.ITIMER_REAL, 10)
        with transport.DarwinMemory(pid) as memory:
            reader = transport.ReadBudget(memory.read)
            records = transport.image_snapshot(reader, memory.images_address())
            images = transport.verify_images(reader, records, app)
            if any(i['architecture'] != 'arm64' for i in images):
                raise decks.Unsupported('Only the reviewed arm64 runtime is supported.')
            decks.verify_live_assembly(reader, records, app, image, profile)
            uid = drop_administrator()
            # Confirm that the read capability still works after privilege drop.
            reader.read(memory.images_address(), 16)
            with tempfile.TemporaryDirectory(prefix='firstpick-arena-session-', dir='/tmp') as directory:
                root = Path(directory)
                socket_path = root / 'read.sock'
                manifest_path = root / 'session.json'
                token = secrets.token_hex(32)
                session = Session(memory, pid, executable, token)
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                    listener.bind(str(socket_path))
                    socket_path.chmod(0o600)
                    listener.listen(4)
                    manifest = {'schema': 1, 'uid': uid, 'expires': time.time() + MAX_SECONDS,
                                'socket': str(socket_path), 'token': token}
                    manifest_path.write_text(json.dumps(manifest))
                    manifest_path.chmod(0o600)
                    signal.setitimer(signal.ITIMER_REAL, MAX_SECONDS)
                    print(json.dumps({'status': 'read_session_ready', 'manifest': str(manifest_path),
                                      'administrator_privileges_dropped': True,
                                      'expires_after_seconds': MAX_SECONDS, 'max_bytes': MAX_BYTES}), flush=True)
                    session.serve(listener)
            print(json.dumps({'status': 'read_session_closed'}), flush=True)
        return 0
    except (transport.ProbeError, OSError, ValueError, KeyError) as error:
        print(json.dumps({'status': 'read_session_failed',
                          'reason': str(error) if isinstance(error, transport.ProbeError) else type(error).__name__}), flush=True)
        return 4
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    sys.exit(main())
