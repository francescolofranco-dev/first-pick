# Arena memory feasibility prototype

This isolated developer tool establishes whether macOS permits a real read from
Arena's process. It is not shipped in FirstPick and does not change its overlay.
Python 3.9+ and the standard library are sufficient. Nothing is downloaded.

## What constitutes success

`arena_memory_read_verified` means the probe obtained a task port, read the live
process's dyld image list, and read the in-memory Mach-O headers of both `MTGA`
and `GameAssembly.dylib`. Both headers must exactly match their on-disk slices
for the running architecture. This is an actual `mach_vm_read_overwrite` read,
not a screenshot, log-derived approximation, or a file-only inspection.

**This does not yet retrieve the current deck.** `deck_snapshot` remains
`not_implemented`, including on successful transport verification. Do not claim
card IDs, quantities, edit latency, or Untapped parity from this milestone.

The installed build inspected on 2026-10-27 has IL2CPP metadata version 39 and a
universal arm64/x86_64 executable. Mono layouts must not be assumed applicable.
The metadata contains deck-builder type-name markers; their existence does not
establish initialized runtime objects or field offsets.

## Run

From the repository root:

```sh
python3 -I -B prototypes/arena-memory/probe.py inspect-build
python3 -I -B prototypes/arena-memory/probe.py self-test
python3 -I -B prototypes/arena-memory/probe.py probe
```

`inspect-build` reads installed files only. `self-test` verifies the Mach read API
against a known buffer in the probe's own process and explicitly does not prove
Arena access. `probe` finds exactly one `MTGA` process and verifies its executable
path against the app bundle before and after obtaining a task port and reading.
Pass `--app /absolute/MTGA.app` for another installation or `--pid N` to resolve
multiple running Arena processes. Arbitrary process targets and addresses are
not supported.

Exit codes: 0 verified success (or completed static inspection/self-test),
2 invalid target, 3 access denied, 4 unsupported/failed verification. The JSON
`status` and `arena_access_proven` distinguish these results.

The initial unprivileged test on this Mac returned `EPERM` for
`task_read_for_pid`, and `KERN_FAILURE` for a separate `task_for_pid` check.
**That is a permission finding, not a successful memory read.**

If the user authorizes one administrator run, the concrete command is:

```sh
sudo -- /usr/bin/python3 -I -B prototypes/arena-memory/probe.py probe
```

Enter any password directly into the operating system's normal terminal prompt.
The script never asks for, reads, stores, or transmits a password. It does not
invoke sudo or relaunch itself. Administrator execution may still be denied by
macOS; it is not a promise that the OS will grant the port.

The default requests the lesser `task_read_for_pid` right. Only the explicit
`--allow-control-port` option permits a `task_for_pid` fallback for systems where
the read port is unavailable; all subsequent operations remain reads. Do not use
this option without reviewing that broader port request. Neither path attaches
a debugger, pauses Arena, injects code, or calls game functions.

## Limits

- The target bundle must identify as `com.wizards.mtga`, with the expected
  executable and IL2CPP assembly inside that bundle.
- At most 2 MiB of target memory is requested. Image count and path length are
  bounded. Only module names, architectures, header hashes, and verification
  results are emitted; no raw memory, object strings, account data, or addresses
  are written. The probe writes no files.
- Reads have an eight-second budget and a ten-second OS alarm which terminates
  the probe itself if a native call stalls. Task rights are released on normal
  exit and by the OS if the process terminates.
- The image-list header must stay consistent across the snapshot. Missing,
  changing, mismatched, unreadable, and unsupported input fails closed.
- No persistent helper, launch service, permission setting, code signature,
  hardened-runtime setting, SIP setting, or game file is installed or changed.
- The production app is not rebuilt/restarted for this standalone tool. Its
  version and overlay behavior remain unchanged.

## Checks

```sh
python3 -B -m unittest discover -s prototypes/arena-memory -p 'test_*.py' -v
```

These cover read/address/time bounds, partial reads, changing dyld snapshots,
architecture selection, malformed universal binaries, image identity checks,
and wrong-target rejection. On macOS a further test enumerates the real test
process's loaded images and verifies its live executable header against disk.
`self-test` separately verifies a known buffer. Neither self-read proves Arena
access.

Validation on 2026-10-28: all 11 checks passed; the real self-process image test
successfully read 365 image records and verified the executable header. Arena
access at normal privileges was denied. The approved administrator attempt
requires a password, which the user must enter locally; Arena memory access
remains unproven until that run returns `arena_memory_read_verified`.

## Next milestone after access is verified

Use the exact installed build/metadata fingerprint to locate IL2CPP deck-editor
objects and resolve card IDs and quantities. Start with the active Limited main
deck and sideboard only. Prove a single-card add/remove, duplicate quantities,
the two Hapatra cards, and independence from scrolling/filtering before wiring
anything into the production state provider. Do not read unrelated account or
match state, and do not export a heap dump to find it.

API/layout references consulted: Apple's SDK `mach/task_info.h`,
`mach-o/dyld_images.h`, and `mach/mach_vm.h`, plus
[Apple's mach_vm_read documentation](https://developer.apple.com/documentation/kernel/1402405-mach_vm_read).
No third-party memory-reader implementation is vendored.
