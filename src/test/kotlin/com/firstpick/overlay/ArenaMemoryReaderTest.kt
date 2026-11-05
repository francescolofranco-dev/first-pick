package com.firstpick.overlay

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArenaMemoryReaderTest {
    @Test fun savedApprovalReconnectsAcrossAppAndArenaRestartsWithoutInstallingAgainAndCanBeRevoked() {
        val directory = Files.createTempDirectory("firstpick-saved-access").toFile()
        try {
            directory.resolve("session.py").writeText("""
                    |#!/usr/bin/python3
                    |import json, pathlib, socket, sys
                    |root = pathlib.Path(__file__).parent
                    |mode = sys.argv[1]
                    |with (root / 'calls').open('a') as log: log.write(mode + '\n')
                    |if mode == '--connect':
                    |    path, token = sys.argv[2:4]
                    |    server = socket.socket(socket.AF_UNIX)
                    |    server.bind(path)
                    |    server.listen(4)
                    |    server.settimeout(15)
                    |    try:
                    |        while True:
                    |            client, _ = server.accept()
                    |            with client:
                    |                request = json.loads(client.makefile('r').readline())
                    |                assert request['token'] == token
                    |                close = request['op'] == 'close'
                    |                restarting = (root / 'restart').exists()
                    |                if restarting: (root / 'restart').unlink()
                    |                status = 'closed' if close else 'arena_closed' if restarting else 'outside_builder'
                    |                client.sendall((json.dumps({'status': status}) + '\n').encode())
                    |            if close or restarting: break
                    |    finally:
                    |        server.close()
                    |        pathlib.Path(path).unlink(missing_ok=True)
                    |elif mode == '--revoke-access':
                    |    (root / 'revoked').touch()
                    |    print('{"status":"revoked"}')
                    |else:
                    |    raise RuntimeError('A saved grant must never request installation')
                """.trimMargin())
            // Keep the worker's 2.5-second production deadline independent of cold Python startup.
            // Python still serves the real authenticated private sockets for each connection.
            val helper = directory.resolve("reader").apply {
                writeText("""
                    |#!/bin/sh
                    |root=${'$'}(dirname "${'$'}0")
                    |if [ "${'$'}1" = --worker ]; then
                    |  printf '%s\n' --worker >> "${'$'}root/calls"
                    |  while IFS= read -r request; do
                    |    if [ -f "${'$'}root/revoked" ]; then status=access_required; else status=saved_access; fi
                    |    printf '{"status":"%s"}\n' "${'$'}status"
                    |  done
                    |else
                    |  exec /usr/bin/python3 "${'$'}root/session.py" "${'$'}@"
                    |fi
                """.trimMargin())
                check(setExecutable(true))
            }
            ArenaMemoryReader(helper).use { reader ->
                assertIs<ArenaMemoryRead.Paused>(reader.read())
                assertTrue(reader.access.value.savedApproval)
            }
            ArenaMemoryReader(helper).use { reader ->
                assertIs<ArenaMemoryRead.Paused>(reader.read())
                directory.resolve("restart").createNewFile()
                assertIs<ArenaMemoryRead.Unavailable>(reader.read())
                assertIs<ArenaMemoryRead.Paused>(reader.read())
                reader.revokeAccess()
                assertEquals(LiveDeckAccessStatus.ACCESS_REQUIRED, reader.access.value.status)
                kotlin.test.assertFalse(reader.access.value.savedApproval)
                assertIs<ArenaMemoryRead.Unavailable>(reader.read())
            }
            val calls = directory.resolve("calls").readLines()
            assertEquals(3, calls.count { it == "--connect" })
            kotlin.test.assertFalse("--grant" in calls, "Saved access must not launch an administrator prompt")
            assertEquals(1, calls.count { it == "--revoke-access" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun aFreshSupportedProcessRestoresTheGrantButtonAfterAnUnsupportedReading() {
        val directory = Files.createTempDirectory("firstpick-reader-recovery").toFile()
        try {
            val helper = directory.resolve("reader").apply {
                writeText("""
                    |#!/bin/sh
                    |first=1
                    |while IFS= read -r request; do
                    |  if [ "${'$'}first" = 1 ]; then
                    |    printf '%s\n' '{"status":"unsupported_build"}'
                    |    first=0
                    |  else
                    |    printf '%s\n' '{"status":"access_required"}'
                    |  fi
                    |done
                """.trimMargin())
                check(setExecutable(true))
            }
            ArenaMemoryReader(helper).use { reader ->
                assertEquals(MemoryUnavailableKind.UNSUPPORTED, assertIs<ArenaMemoryRead.Unavailable>(reader.read()).kind)
                assertEquals(LiveDeckAccessStatus.UNSUPPORTED, reader.access.value.status)
                assertEquals(MemoryUnavailableKind.ACCESS_REQUIRED, assertIs<ArenaMemoryRead.Unavailable>(reader.read()).kind)
                assertEquals(LiveDeckAccessStatus.ACCESS_REQUIRED, reader.access.value.status)
                assertTrue(reader.access.value.canGrant)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
