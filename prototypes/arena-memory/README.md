# Arena memory feasibility prototype

These isolated developer tools test read-only access to Arena's process and a
build-specific active-deck reader. They are not shipped in FirstPick and do not
change its overlay.
Python 3.9+ and the standard library are sufficient. Nothing is downloaded.

## What constitutes success

`arena_memory_read_verified` means the probe obtained a task port, read the live
process's dyld image list, and read the in-memory Mach-O headers of both `MTGA`
and `GameAssembly.dylib`. Both headers must exactly match their on-disk slices
for the running architecture. This is an actual `mach_vm_read_overwrite` read,
not a screenshot, log-derived approximation, or a file-only inspection.

**The transport probe does not retrieve the current deck.** Its `deck_snapshot`
remains `not_implemented`, including on successful transport verification. Do not claim
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

Validation on 2026-10-28: all 11 checks passed. The real self-process image test
successfully enumerated loaded images and verified its executable header. Arena
access at normal privileges was denied. The user then ran the approved probe
with one administrator authorization: `task_read_for_pid` succeeded, 296,688
bytes were read, and both live ARM64 image headers matched the installed files.
The measured read phase took 6 ms, excluding authorization and build inspection;
this is not a deck-reader or overlay latency measurement.

The sanitized result is retained in
[verified-transport-macos-arm64.json](verified-transport-macos-arm64.json). It
contains no process addresses, PID, account data, or heap contents. This proves
memory transport for the recorded Arena 2026.64.50 build on this Mac. Deck
extraction is not proven by that result; future builds and ordinary app-level permission
UX are separate work.

## Active Limited deck prototype (live verified 2026-10-28)

`deck_reader.py` implements the next, separate milestone. It supports only the
reviewed arm64 Arena 2026.64.50 build, with metadata v39. `inspect-layout` checks
the metadata and complete assembly SHA-256, selected native code anchors, class
names, and field offsets against the installed files. It performs no live reads.

```sh
python3 -I -B prototypes/arena-memory/deck_reader.py inspect-layout
python3 -I -B prototypes/arena-memory/deck_reader.py snapshot
```

The snapshot path is explicit:

1. `WrapperController.Instance` → `SceneLoader.CurrentNavContent` → active
   `WrapperDeckBuilder` and `DeckBuilderWidget`.
2. `Wizards.Mtga.Pantry.instance` → service scope tables → the unique initialized
   `DeckBuilderModelProvider` and `DeckBuilderContextProvider`. Registry entries
   contain dispatch pointers; only referenced objects' class pointers are checked
   while selecting these two services. Other service object fields are not read.
3. The model must be non-Constructed and have an active event context. Its
   `_mainDeck` and `_sideboard` point to `CardList` objects.
4. Each list's **unfiltered** `_grpQuantityTable` supplies exact Arena group IDs
   and quantities, including duplicate copies and basic lands. Rendered rows,
   `_filteredSortedList`, card-name strings, saved-deck collections, and OCR are
   not consulted.

The class table address and non-generic field offsets in
`layout-2026.64.50-arm64.json` were derived from the installed binary/metadata.
Offsets in that file are relative VM offsets, not captured process addresses.
Generic field offsets are read from runtime `FieldInfo` metadata after checking
the expected class identity and generic argument widths. The tool reads data;
it does not invoke the getter functions used to derive this path.

Before following objects, live module headers, load commands (including UUID),
and selected native code bytes must match disk. Dictionaries have bounded sizes,
validated entry accounting, type/field checks, and repeated byte/version reads.
The active editor roots and both piles must agree across two observations.
This detects many concurrent changes; it does **not** create an atomic snapshot
or pause the game. A consistently observed intermediate game transaction is
still possible. Production integration would need settling and reconciliation.

The existing 2 MiB read budget, eight-second read deadline, ten-second process
alarm, exact target-path checks, and lesser `task_read_for_pid` right apply.
There is no control-port fallback. Output contains card IDs/counts, totals,
build fingerprints, timing, and sanitized status/checkpoint information. It does
not emit process addresses, account identifiers, deck names, or raw memory.
The script itself writes no files. Unknown builds, other screens (including
in-draft deck views), Constructed editors, ambiguous providers, and inconsistent
reads fail without emitting partial deck contents.

If separately authorized for this deck-reader test, open the Limited deck
builder and run one administrator invocation in Terminal:

```sh
sudo -- /usr/bin/python3 -I -B prototypes/arena-memory/deck_reader.py snapshot
```

The prior one-time transport authorization is not a persistent privileged
helper. The tool never asks for a password or escalates itself. User authorization
and entering a password in Terminal remain separate from OS grant of the read port.

Current verification: `inspect-layout` passes against the installed build, and
32 tests pass outside the filesystem/IPC sandbox, including the original 11
transport tests. Synthetic IL2CPP graphs
exercise exact IDs, duplicates, unfiltered piles, zero/freed dictionary entries,
fresh reads after edits, equal-total swaps, concurrent writes before version
increments, invalid shapes, inactive/Constructed editors, wrong objects, and
address/size limits. Synthetic results are not evidence of live deck extraction.

Metadata names occupy packed, byte-aligned strings; the selected class name and
empty namespace have offsets congruent to 7 and 1 modulo 8. String pointers use
byte alignment, while object/array pointers retain their stricter checks. The
synthetic graph uses unaligned metadata strings throughout, with a regression
test confirming that unaligned object pointers still fail.

### Live results

The reader returned `active_limited_deck_read` for the actual editor: **40 cards,
25 distinct IDs**, including 7 Islands, 7 Swamps, 2 Mountains, and two copies each
of Protege's Awakening and Apex Witchstalker. IDs were resolved by a read-only
join against Arena's installed card catalog and compared with the visible deck.

Real Arena interactions verified:

- Scrolling to the lands and filtering the pool preserved the exact snapshot.
- Adding an Island produced 41 total cards and 8 Islands; removing it restored
  every original ID and quantity.
- Adding Desert Fang produced both Hapatra IDs at once. Removing Desert Frost
  then produced an equal-total 40-card swap: **106460 (Desert Frost)** absent,
  **106516 (Desert Fang)** present. The original selection was then restored.
- All 180 repeated reads succeeded. With cached runtime class/field metadata,
  two matching deck observations over the temporary socket took a median
  **115.05 ms**, p95 **135 ms**, and maximum **249.9 ms**. The 180 reads requested
  4,082,336 bytes. Initial module verification/authorization are excluded; socket
  overhead is included. These are reader timings, not action-to-overlay latency.

Sanitized evidence is in [verified-deck-macos-arm64.json](verified-deck-macos-arm64.json).
It retains totals, selected test IDs, hashes confirming restoration, measurements,
and limitations. It contains no account identifiers, addresses, credentials, raw
memory, or complete user deck list. The temporary read session was explicitly
closed after the checks.

The native automation initially raised Arena without making it the key window,
so Unity ignored simulated input. Choosing the actual MTGA window in its Window
menu made clicks and scrolling work. Native drag gestures remained unreliable.
UI automation is used to validate the reader; it is not part of the memory path.

### Optional temporary development session

Repeated one-shot Terminal commands can be replaced by a separately authorized,
finite session while debugging this prototype:

```sh
sudo -- /usr/bin/python3 -I -B prototypes/arena-memory/read_session.py
```

The launcher verifies the pinned installed/running build and obtains only the
Arena `task_read_for_pid` right. It then drops supplementary groups, sets the
invoking user's real/effective/saved group and user IDs, verifies the drop, and
tests that the retained read right works. Only afterward does it create an
owner-only local Unix socket and a private authentication manifest in a temporary
directory. No elevated process remains serving requests. The process neither
executes client-supplied commands nor reloads code while privileged.

The session expires after **15 minutes or 64 MiB of reads**. Requests have a
64 KiB maximum, bounded framing/timeouts, a per-session random authentication
token, and one fixed, verified Arena target. No target changes, writes, process
control, injection, network listener, helper installation, or permission-setting
changes are supported. A retained read right is a capability for Arena memory;
the socket is a low-level development transport, not a production API that
restricts reads to particular deck fields. The client remains responsible for
following only the reviewed deck/metadata paths. This scope requires explicit
session authorization rather than silently reusing a one-shot approval.

The launcher prints `read_session_ready` and the manifest path; leave that
Terminal process open. Its manifest contains a local secret and must not be
copied into chat or committed. The normal, unprivileged reader can then use:

```sh
python3 -I -B prototypes/arena-memory/deck_reader.py snapshot --session /tmp/firstpick-arena-session-EXAMPLE/session.json
```

Each snapshot retains its own 2 MiB/eight-second limits, build verification, and
deck validation. An explicit authenticated `close` request releases the right
early; otherwise the deadline or process exit releases it. Normal exit removes
the temporary socket/manifest. A hard deadline termination can leave expired,
private temporary files, with no live service. The session test uses a known
buffer in the test process itself and confirms local socket transport and
shutdown; it proves no Arena access or actual privilege drop.

Privilege-drop semantics follow Apple's
[setuid/setgid documentation](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/setuid.2.html).

### Remaining production work

This proves exact active main-deck extraction for one pinned arm64 build. The
formal `_sideboard` was empty, so non-empty formal sideboards have synthetic
coverage only. Arena's available Limited cards live in a separate `_cardPool`
which includes basic-land catalog entries and selected cards; do not treat the
empty formal sideboard as an empty available pool. That pool is not exported by
the prototype. In-draft deck views and Constructed editors remain unsupported.

The model also remained readable behind a disconnect dialog. Before presenting
live guidance, production needs connection/modal/phase validity, session identity,
settling across non-atomic edits, a reviewed permission lifecycle, and explicit
unknown-build fallback. Integrate a verified provider into deck reconciliation
while keeping geometry/highlight acquisition separate. Draft-picking support
needs its own model path and interaction corpus.

The standalone prototype changes no app/native/package code. There is no app
version bump, rebuild, restart, production integration, or claimed overlay UX
improvement in this milestone.

API/layout references consulted: Apple's SDK `mach/task_info.h`,
`mach-o/dyld_images.h`, and `mach/mach_vm.h`, plus
[Apple's mach_vm_read documentation](https://developer.apple.com/documentation/kernel/1402405-mach_vm_read).
No third-party memory-reader implementation is vendored.

IL2CPP format references consulted while inspecting the installed files:
[Cpp2IL type definitions](https://github.com/SamboyCoding/Cpp2IL/blob/development/LibCpp2IL/Metadata/Il2CppTypeDefinition.cs),
[metadata registration](https://github.com/SamboyCoding/Cpp2IL/blob/development/LibCpp2IL/BinaryStructures/Il2CppMetadataRegistration.cs),
and [generic classes](https://github.com/SamboyCoding/Cpp2IL/blob/development/LibCpp2IL/BinaryStructures/Il2CppGenericClass.cs).
This is a narrow, pinned layout profile rather than a general IL2CPP extractor.
