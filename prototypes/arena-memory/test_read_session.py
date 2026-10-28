import ctypes
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('test_arena_session', Path(__file__).with_name('read_session.py'))
sessions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sessions)


class SessionTests(unittest.TestCase):
    def make_session(self, **kwargs):
        class Memory:
            api = 'task_read_for_pid'
            def __init__(self): self.calls = []
            def read(self, address, size):
                self.calls.append((address, size))
                return b'X' * size
            def images_address(self): return 8192
        memory = Memory()
        session = sessions.Session(memory, 123, Path('/verified/MTGA'), 'a' * 64, **kwargs)
        return session, memory

    def test_authentication_expiration_and_budget_prevent_backend_reads(self):
        session, memory = self.make_session(byte_limit=4)
        with patch.object(sessions.transport, 'process_path', return_value=Path('/verified/MTGA')):
            with self.assertRaises(sessions.transport.AccessDenied):
                session.handle({'op': 'read', 'token': 'wrong', 'address': 4096, 'size': 1})
            self.assertTrue(session.handle({'op': 'read', 'token': 'a' * 64, 'address': 4096, 'size': 4})['ok'])
            with self.assertRaises(sessions.transport.ProbeError):
                session.handle({'op': 'read', 'token': 'a' * 64, 'address': 4096, 'size': 1})
        self.assertEqual([(4096, 4)], memory.calls)
        session, memory = self.make_session(seconds=-1)
        with self.assertRaises(sessions.transport.AccessDenied):
            session.handle({'op': 'images', 'token': 'a' * 64})
        self.assertEqual([], memory.calls)

    def test_no_writes_retarget_commands_or_out_of_bounds_reads(self):
        session, memory = self.make_session()
        with patch.object(sessions.transport, 'process_path', return_value=Path('/verified/MTGA')):
            invalid = [dict(op='write', address=4096, size=1), dict(op='exec', command='anything'),
                       dict(op='read', address=0, size=1), dict(op='read', address=4096, size=65537),
                       dict(op='read', address=(1 << 48) - 1, size=8), dict(op='read', address=True, size=1),
                       dict(op='read', address=4096, size=1, pid=456)]
            for request in invalid:
                with self.subTest(request=request), self.assertRaises(sessions.transport.ProbeError):
                    session.handle(dict(token='a' * 64, **request))
        self.assertEqual([], memory.calls)
        with patch.object(sessions.transport, 'process_path', return_value=Path('/another/MTGA')):
            with self.assertRaises(sessions.transport.InvalidTarget):
                session.handle({'op': 'info', 'token': 'a' * 64})
        self.assertTrue(session.closed)

    def test_message_size_is_rejected_without_reading_a_body(self):
        a, b = socket.socketpair()
        with a, b:
            a.sendall(struct.pack('!I', sessions.MAX_REQUEST + 1))
            with self.assertRaises(sessions.transport.ProbeError):
                sessions.receive(b, sessions.MAX_REQUEST)

    def test_privileges_drop_groups_before_user_and_verify_result(self):
        values = {'uid': 0, 'gid': 0}
        calls = []
        def groups(v): calls.append(('groups', v))
        def gid(v): calls.append(('gid', v)); values['gid'] = v
        def uid(v): calls.append(('uid', v)); values['uid'] = v
        with patch.dict(os.environ, SUDO_UID='501', SUDO_GID='20'), \
                patch.object(sessions.os, 'getuid', side_effect=lambda: values['uid']), \
                patch.object(sessions.os, 'geteuid', side_effect=lambda: values['uid']), \
                patch.object(sessions.os, 'getgid', side_effect=lambda: values['gid']), \
                patch.object(sessions.os, 'getegid', side_effect=lambda: values['gid']), \
                patch.object(sessions.pwd, 'getpwuid', return_value=type('User', (), {'pw_uid': 501})()), \
                patch.object(sessions.os, 'setgroups', side_effect=groups), \
                patch.object(sessions.os, 'setgid', side_effect=gid), \
                patch.object(sessions.os, 'setuid', side_effect=uid), patch.object(sessions.os, 'umask'):
            self.assertEqual(501, sessions.drop_administrator())
        self.assertEqual([('groups', []), ('gid', 20), ('uid', 501)], calls)

    @unittest.skipUnless(sys.platform == 'darwin', 'real read-port/socket check requires macOS')
    def test_real_self_read_over_private_local_socket_and_explicit_shutdown(self):
        executable = sessions.transport.process_path(os.getpid())
        sample = ctypes.create_string_buffer(b'known local test bytes')
        # This uses only our own task port; it proves no Arena access.
        with sessions.transport.DarwinMemory(os.getpid(), self_test=True) as memory, \
                tempfile.TemporaryDirectory(prefix='firstpick-session-test-', dir='/tmp') as tmp:
            root = Path(tmp)
            sock = root / 'read.sock'
            manifest = root / 'session.json'
            token = 'b' * 64
            session = sessions.Session(memory, os.getpid(), executable, token, seconds=10)
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                listener.bind(str(sock))
                sock.chmod(0o600)
                listener.listen(2)
                manifest.write_text(json.dumps({'schema': 1, 'uid': os.getuid(), 'expires': time.time() + 10,
                                                'socket': str(sock), 'token': token}))
                manifest.chmod(0o600)
                thread = threading.Thread(target=session.serve, args=(listener,), kwargs={'hard_alarm': False})
                thread.start()
                try:
                    # RemoteMemory normally rejects self-test rights, correctly.
                    with self.assertRaises(sessions.transport.InvalidTarget):
                        sessions.RemoteMemory(manifest, os.getpid())
                    remote = object.__new__(sessions.RemoteMemory)
                    remote.manifest = sessions.load_manifest(manifest)
                    self.assertEqual(sample.raw, remote.read(ctypes.addressof(sample), len(sample.raw)))
                    self.assertGreater(remote.images_address(), 0)
                    manifest.chmod(0o644)
                    with self.assertRaises(sessions.transport.AccessDenied):
                        sessions.load_manifest(manifest)
                finally:
                    remote.request('close')
                    thread.join(timeout=3)
                self.assertFalse(thread.is_alive())
                self.assertTrue(session.closed)


if __name__ == '__main__':
    unittest.main()
