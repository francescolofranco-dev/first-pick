// The build prefixes this file with the reviewed, immutable layout JSON.
import Foundation
import AppKit
import CryptoKit
import Darwin

@_silgen_name("task_read_for_pid")
private func taskReadForPID(_ task: mach_port_t, _ pid: pid_t, _ out: UnsafeMutablePointer<mach_port_t>) -> kern_return_t

private struct ReadFailure: Error {
    let status: String
    let reason: String
    let resetPresentation: Bool
    init(_ status: String = "unavailable", _ reason: String, resetPresentation: Bool = false) {
        self.status = status; self.reason = reason; self.resetPresentation = resetPresentation
    }
}
private func require(_ condition: Bool, _ reason: String) throws {
    if !condition { throw ReadFailure("unsupported_build", reason) }
}
private func json(_ object: [String: Any]) -> Data {
    (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
}
private func failure(_ error: Error) -> [String: Any] {
    if let error = error as? ReadFailure { return ["status": error.status, "reason": error.reason, "presentation_reset":error.resetPresentation] }
    return ["status": "unavailable", "reason": "The Arena reader could not complete this observation."]
}
private func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
private func integer<T: FixedWidthInteger>(_ data: Data, _ offset: Int = 0, _ type: T.Type = T.self) throws -> T {
    try require(offset >= 0 && offset + MemoryLayout<T>.size <= data.count, "Truncated data.")
    return data.withUnsafeBytes { T(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: T.self)) }
}
private func number(_ object: [String: Any], _ key: String) throws -> UInt64 {
    guard let n = object[key] as? NSNumber, n.int64Value >= 0 else { throw ReadFailure("unsupported_build", "Invalid layout field.") }
    return n.uint64Value
}
private func processPath(_ pid: pid_t) throws -> URL {
    var path = [CChar](repeating: 0, count: 4096)
    guard proc_pidpath(pid, &path, UInt32(path.count)) > 0 else { throw ReadFailure("arena_closed", "Arena is no longer available.") }
    return URL(fileURLWithPath: String(cString: path)).resolvingSymlinksInPath()
}
private func processIdentity(_ pid: pid_t) throws -> proc_bsdinfo {
    var info = proc_bsdinfo()
    try require(proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, Int32(MemoryLayout.size(ofValue: info))) == MemoryLayout.size(ofValue: info), "Process identity is unavailable.")
    return info
}
private struct Profile {
    let object: [String: Any]
    let types: [String: [String: Any]]
    init() throws {
        guard let value = try JSONSerialization.jsonObject(with: Data(pinnedMemoryProfileJSON.utf8)) as? [String: Any],
              let types = value["types"] as? [String: [String: Any]] else { throw ReadFailure("unsupported_build", "Missing layout profile.") }
        self.object = value; self.types = types
    }
    init(object value: [String: Any]) throws {
        guard let types = value["types"] as? [String: [String: Any]] else { throw ReadFailure("unsupported_build", "Invalid fixture profile.") }
        self.object = value; self.types = types
    }
    func offset(_ key: String, _ field: String) throws -> UInt64 {
        guard let fields = types[key]?["fields"] as? [String: Any] else { throw ReadFailure("unsupported_build", "Unknown layout type.") }
        return try number(fields, field)
    }
}
private struct MachImage {
    let slice: Data
    let segments: [(UInt64, UInt64, UInt64)]
    let commandsSize: Int
    init(_ bytes: Data) throws {
        var start = 0, length = bytes.count
        let magic: UInt32 = try integer(bytes)
        if magic == 0xbebafeca {
            let count = UInt32(bigEndian: bytes.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 4, as: UInt32.self) })
            try require(count > 0 && count <= 32 && bytes.count >= 8 + Int(count) * 20, "Invalid architecture table.")
            var found = false
            for i in 0..<Int(count) {
                let row = 8 + i * 20
                let cpu = UInt32(bigEndian: bytes.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: row, as: UInt32.self) })
                if cpu == 0x100000c {
                    try require(!found, "Ambiguous arm64 slice."); found = true
                    start = Int(UInt32(bigEndian: bytes.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: row + 8, as: UInt32.self) }))
                    length = Int(UInt32(bigEndian: bytes.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: row + 12, as: UInt32.self) }))
                    try require(start >= 8 + Int(count) * 20 && length >= 32 && start + length <= bytes.count, "Invalid arm64 slice.")
                }
            }
            try require(found, "No supported arm64 slice.")
        }
        let data = bytes.subdata(in: start..<start + length)
        try require(try integer(data, 0, UInt32.self) == 0xfeedfacf && integer(data, 4, UInt32.self) == 0x100000c, "Unsupported Arena architecture.")
        let n = Int(try integer(data, 16, UInt32.self)), size = Int(try integer(data, 20, UInt32.self))
        try require(size <= 65536 && 32 + size <= data.count, "Invalid load commands.")
        var offset = 32, result: [(UInt64, UInt64, UInt64)] = []
        for _ in 0..<n {
            let cmd: UInt32 = try integer(data, offset), count = Int(try integer(data, offset + 4, UInt32.self))
            try require(count >= 8 && offset + count <= 32 + size, "Invalid load command.")
            if cmd == 0x19 {
                try require(count >= 72, "Invalid segment.")
                let vm: UInt64 = try integer(data, offset + 24), file: UInt64 = try integer(data, offset + 40), len: UInt64 = try integer(data, offset + 48)
                try require(file <= UInt64(length) && len <= UInt64(length) - file, "Segment exceeds file bounds.")
                result.append((vm, file, len))
            }
            offset += count
        }
        try require(offset == size + 32, "Load command count differs.")
        slice = data; segments = result; commandsSize = size
    }
    func read(_ vm: UInt64, _ size: Int) throws -> Data {
        guard let (start, file, length) = segments.first(where: { vm >= $0.0 && UInt64(size) <= $0.2 && vm - $0.0 <= $0.2 - UInt64(size) }) else { throw ReadFailure("unsupported_build", "Unbacked native code anchor.") }
        _ = length
        let offset = Int(file + vm - start)
        return slice.subdata(in: offset..<offset + size)
    }
}
private final class Target {
    let pid: pid_t
    let executable: URL
    let app: URL
    let profile: Profile
    let assembly: MachImage
    let unity: MachImage
    let launch: proc_bsdinfo
    init(pid supplied: pid_t? = nil) throws {
        #if !arch(arm64)
        throw ReadFailure("unsupported_build", "Live Arena access is not available for this architecture. The overlay is paused.")
        #else
        let pid: pid_t
        if let supplied = supplied { pid = supplied }
        else {
            let matches = NSRunningApplication.runningApplications(withBundleIdentifier: "com.wizards.mtga")
            guard matches.count == 1 else { throw ReadFailure("arena_closed", "Open MTG Arena to connect live deck sync.") }
            pid = matches[0].processIdentifier
        }
        self.pid = pid; executable = try processPath(pid); launch = try processIdentity(pid)
        try require(executable.lastPathComponent == "MTGA" && executable.deletingLastPathComponent().lastPathComponent == "MacOS", "Unexpected Arena executable.")
        app = executable.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        guard let bundle = Bundle(url: app), bundle.bundleIdentifier == "com.wizards.mtga" else { throw ReadFailure("unsupported_build", "This is not the expected Arena bundle.") }
        profile = try Profile()
        try require(bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String == profile.object["app_version"] as? String, "This Arena update is not supported by live deck sync yet.")
        let metadata = try Data(contentsOf: app.appendingPathComponent("Contents/Resources/Data/il2cpp_data/Metadata/global-metadata.dat"), options: .mappedIfSafe)
        try require(hash(metadata) == profile.object["metadata_sha256"] as? String, "This Arena update is not supported by live deck sync yet.")
        let assemblyURL = app.appendingPathComponent("Contents/Frameworks/GameAssembly.dylib").resolvingSymlinksInPath()
        try require(assemblyURL.path.hasPrefix(app.path + "/"), "Unexpected Arena assembly path.")
        let bytes = try Data(contentsOf: assemblyURL, options: .mappedIfSafe)
        try require(hash(bytes) == profile.object["assembly_sha256"] as? String, "This Arena update is not supported by live deck sync yet.")
        assembly = try MachImage(bytes)
        let unityBytes = try Data(contentsOf: app.appendingPathComponent("Contents/Frameworks/UnityPlayer.dylib"), options: .mappedIfSafe)
        try require(hash(unityBytes) == profile.object["unity_sha256"] as? String, "This Arena graphics layout is not supported yet.")
        unity = try MachImage(unityBytes)
        #endif
    }
    func validate() throws {
        guard let current = try? processIdentity(pid), let path = try? processPath(pid),
              path == executable && current.pbi_start_tvsec == launch.pbi_start_tvsec && current.pbi_start_tvusec == launch.pbi_start_tvusec else {
            throw ReadFailure("arena_closed", "Arena restarted. Grant access again to reconnect.", resetPresentation:true)
        }
    }
}
private final class Memory {
    let port: mach_port_t
    private let fixture: Data?
    var frameBytes = 0
    var totalBytes: UInt64 = 0
    var started = ProcessInfo.processInfo.systemUptime
    init(_ pid: pid_t, selfTest: Bool = false) throws {
        var value: mach_port_t = 0
        if selfTest { value = mach_task_self_ }
        else if taskReadForPID(mach_task_self_, pid, &value) != KERN_SUCCESS || value == 0 {
            throw ReadFailure("access_required", "Grant Arena access in FirstPick to enable the live overlay.")
        }
        port = value; fixture = nil
    }
    init(fixture: Data) { self.fixture = fixture; self.port = 0 }
    deinit { if port != 0 && port != mach_task_self_ { mach_port_deallocate(mach_task_self_, port) } }
    func begin() { frameBytes = 0; started = ProcessInfo.processInfo.systemUptime }
    func read(_ address: UInt64, _ size: Int) throws -> Data {
        guard address >= 4096 && address < 1 << 48 && size > 0 && size <= 131072 && UInt64(size) <= (1 << 48) - address,
              frameBytes + size <= 2 * 1024 * 1024 && totalBytes + UInt64(size) <= 8 * 1024 * 1024 * 1024,
              ProcessInfo.processInfo.systemUptime - started < 2 else { throw ReadFailure("unavailable", "Arena observation exceeded its read limits.") }
        frameBytes += size; totalBytes += UInt64(size)
        if let fixture = fixture {
            try require(address + UInt64(size) <= fixture.count, "Fixture read exceeds bounds.")
            return fixture.subdata(in: Int(address)..<Int(address) + size)
        }
        var bytes = Data(count: size), actual: mach_vm_size_t = 0
        let result = bytes.withUnsafeMutableBytes { mach_vm_read_overwrite(port, address, mach_vm_size_t(size), mach_vm_address_t(UInt(bitPattern: $0.baseAddress!)), &actual) }
        guard result == KERN_SUCCESS && actual == size else { throw ReadFailure("unavailable", "Arena changed while its deck was being read.") }
        return bytes
    }
    func u64(_ address: UInt64) throws -> UInt64 { try integer(read(address, 8)) }
    func pointer(_ address: UInt64, nullable: Bool = false, alignment: UInt64 = 8) throws -> UInt64 {
        let value = try u64(address)
        if nullable && value == 0 { return 0 }
        try require(value >= 4096 && value < 1 << 48 && value % alignment == 0, "Unexpected Arena data layout.")
        return value
    }
    func string(_ address: UInt64, limit: Int = 128) throws -> String {
        var bytes = Data()
        while bytes.count < limit {
            let size = min(32, limit - bytes.count, 4096 - Int((address + UInt64(bytes.count)) % 4096))
            let part = try read(address + UInt64(bytes.count), size)
            if let end = part.firstIndex(of: 0) {
                bytes.append(part.prefix(upTo: end))
                guard let text = String(data: bytes, encoding: .utf8) else { throw ReadFailure("unsupported_build", "Invalid metadata string.") }
                return text
            }
            bytes.append(part)
        }
        throw ReadFailure("unsupported_build", "Metadata string exceeded its bounds.")
    }
    func imagesAddress() throws -> UInt64 {
        var values = [integer_t](repeating: 0, count: 5), count: mach_msg_type_number_t = 5
        let result = values.withUnsafeMutableBufferPointer { task_info(port, task_flavor_t(17), $0.baseAddress!, &count) }
        try require(result == KERN_SUCCESS && count == 5, "Arena image information is unavailable.")
        let bytes = values.withUnsafeBytes { Data($0) }
        try require(try integer(bytes, 16, UInt32.self) == 1, "Unsupported Arena image format.")
        return try integer(bytes, 0)
    }
}
private final class DeckRuntime {
    let target: Target?
    let profile: Profile
    let memory: Memory
    let slide: UInt64
    var unitySlide: UInt64 = 0
    let nonce = UUID().uuidString
    var classes: [String: UInt64] = [:]
    var fieldCache: [UInt64: [String: UInt64]] = [:]
    init(_ target: Target, _ memory: Memory) throws {
        self.target = target; self.memory = memory; self.profile = target.profile
        let list = try memory.imagesAddress(), before = try memory.read(list, 16)
        let count = Int(try integer(before, 4, UInt32.self)), table: UInt64 = try integer(before, 8)
        try require(count > 0 && count <= 4096, "Invalid Arena image count.")
        let rows = try memory.read(table, count * 24)
        try require(try memory.read(list, 16) == before, "Arena's image list is changing.")
        var found: [UInt64] = [], unityFound: [UInt64] = []
        for i in 0..<count {
            let path = try memory.string(memory.u64(table + UInt64(i * 24 + 8)), limit: 4096)
            if path.hasSuffix("/GameAssembly.dylib") {
                try require(URL(fileURLWithPath: path).resolvingSymlinksInPath() == target.app.appendingPathComponent("Contents/Frameworks/GameAssembly.dylib").resolvingSymlinksInPath(), "Unexpected loaded Arena assembly.")
                found.append(try integer(rows, i * 24, UInt64.self))
            }
            if path.hasSuffix("/UnityPlayer.dylib") {
                try require(URL(fileURLWithPath: path).resolvingSymlinksInPath() == target.app.appendingPathComponent("Contents/Frameworks/UnityPlayer.dylib").resolvingSymlinksInPath(), "Unexpected loaded Unity image.")
                unityFound.append(try integer(rows, i * 24, UInt64.self))
            }
        }
        try require(found.count == 1, "The Arena assembly is unavailable.")
        slide = found[0]
        try require(unityFound.count == 1, "The Arena graphics image is unavailable.")
        unitySlide = unityFound[0]
        try require(try memory.read(unitySlide, 32 + target.unity.commandsSize) == target.unity.slice.prefix(32 + target.unity.commandsSize), "The running graphics image differs from its installed files.")
        try require(try memory.read(slide, 32 + target.assembly.commandsSize) == target.assembly.slice.prefix(32 + target.assembly.commandsSize), "The running Arena build differs from its installed files.")
        for anchor in profile.object["code_anchors"] as? [[String: Any]] ?? [] {
            guard let hex = anchor["hex"] as? String else { throw ReadFailure("unsupported_build", "Invalid code anchor.") }
            let bytes = Data(stride(from: 0, to: hex.count, by: 2).compactMap { i -> UInt8? in
                let a = hex.index(hex.startIndex, offsetBy: i), b = hex.index(a, offsetBy: 2)
                return UInt8(hex[a..<b], radix: 16)
            })
            try require(!bytes.isEmpty && bytes.count * 2 == hex.count && (try memory.read(slide + number(anchor, "vm"), bytes.count)) == bytes, "The running Arena layout differs from its reviewed profile.")
        }
        for anchor in profile.object["unity_code_anchors"] as? [[String: Any]] ?? [] {
            guard let hex = anchor["hex"] as? String else { throw ReadFailure("unsupported_build", "Invalid graphics anchor.") }
            let bytes = Data(stride(from: 0, to: hex.count, by: 2).compactMap { i -> UInt8? in
                let a = hex.index(hex.startIndex, offsetBy: i), b = hex.index(a, offsetBy: 2)
                return UInt8(hex[a..<b], radix: 16)
            })
            try require(!bytes.isEmpty && (try memory.read(unitySlide + number(anchor, "vm"), bytes.count)) == bytes, "The running graphics layout differs from its reviewed profile.")
        }
    }
    init(fixture: Data, profile: Profile) {
        self.target = nil; self.profile = profile; self.memory = Memory(fixture: fixture); self.slide = 0
    }
    func identity(_ klass: UInt64) throws -> (String, String) {
        (try memory.string(memory.pointer(klass + 16, alignment: 1)), try memory.string(memory.pointer(klass + 24, alignment: 1)))
    }
    func klass(_ key: String) throws -> UInt64 {
        if let value = classes[key] { return value }
        guard let spec = profile.types[key], let name = spec["name"] as? String, let ns = spec["namespace"] as? String else { throw ReadFailure("unsupported_build", "Unknown reviewed type.") }
        let table = try memory.pointer(slide + number(profile.object, "class_table_vm")), value = try memory.pointer(table + number(spec, "index") * 8)
        let actual = try identity(value)
        try require(actual == (name, ns), "Unexpected Arena runtime class: \(key).")
        classes[key] = value; return value
    }
    @discardableResult func check(_ object: UInt64, _ key: String) throws -> UInt64 {
        try require(try memory.pointer(object) == klass(key), "Unexpected Arena object: \(key).")
        return object
    }
    func field(_ object: UInt64, _ key: String, _ name: String, _ next: String) throws -> UInt64 {
        try check(object, key)
        return try check(memory.pointer(object + profile.offset(key, name)), next)
    }
    func flag(_ object: UInt64, _ key: String, _ name: String) throws -> Bool {
        let byte = try memory.read(object + profile.offset(key, name), 1)[0]
        try require(byte <= 1, "Invalid Arena Boolean."); return byte == 1
    }
    func singleton(_ key: String, _ name: String) throws -> UInt64 {
        let storage = try memory.pointer(klass(key) + 0xb8)
        return try check(memory.pointer(storage + profile.offset(key, name)), key)
    }
    func fields(_ klass: UInt64) throws -> [String: UInt64] {
        if let value = fieldCache[klass] { return value }
        let count = Int(try integer(memory.read(klass + 0x124, 2), 0, UInt16.self))
        try require(count > 0 && count <= 64, "Invalid metadata field count.")
        let data = try memory.read(memory.pointer(klass + 0x80), count * 32)
        var result: [String: UInt64] = [:]
        for i in 0..<count {
            let parent: UInt64 = try integer(data, i * 32 + 16), offset: Int32 = try integer(data, i * 32 + 24)
            try require(parent == klass && offset >= 0 && offset <= 4096, "Invalid runtime field layout.")
            let name = try memory.string(integer(data, i * 32, UInt64.self))
            try require(result[name] == nil, "Ambiguous field metadata.")
            result[name] = UInt64(offset)
        }
        fieldCache[klass] = result; return result
    }
    func collectionSize(_ object: UInt64) throws -> Int {
        let k = try memory.pointer(object), id = try identity(k), offsets = try fields(k)
        try require(id == ("List`1", "System.Collections.Generic"), "Unexpected message collection.")
        guard let offset = offsets["_size"] else { throw ReadFailure("unsupported_build", "Missing message size.") }
        let n = Int(try integer(memory.read(object + offset, 4), 0, Int32.self))
        try require(n >= 0 && n <= 2048, "Invalid message count."); return n
    }
    enum Kind { case quantities, scopes, services }
    func dictionary(_ object: UInt64, _ kind: Kind) throws -> [(UInt64, UInt64)] {
        let k = try memory.pointer(object)
        try require(try identity(k) == ("Dictionary`2", "System.Collections.Generic"), "Unexpected card dictionary.")
        let type = try memory.read(k + 0x20, 16), bits: UInt32 = try integer(type, 8)
        try require((bits >> 16) & 255 == 21, "Unexpected dictionary type.")
        let generic: UInt64 = try integer(type), inst = try memory.pointer(generic + 8)
        try require(try memory.u64(inst) == 2, "Unexpected dictionary arity.")
        let args = try memory.read(memory.pointer(inst + 8), 16)
        var actual: [UInt32] = []
        for i in 0..<2 { actual.append((try integer(memory.read(integer(args, i * 8, UInt64.self), 16), 8, UInt32.self) >> 16) & 255) }
        let expected: [UInt32] = kind == .quantities ? [9,9] : kind == .scopes ? [17,18] : [18,28]
        try require(actual == expected, "Unexpected dictionary arguments.")
        let f = try fields(k)
        guard let entriesOffset = f["_entries"], let countOffset = f["_count"], let freeOffset = f["_freeCount"], let versionOffset = f["_version"] else { throw ReadFailure("unsupported_build", "Missing dictionary bookkeeping.") }
        func header() throws -> [UInt64] {
            [try memory.pointer(object + entriesOffset, nullable: true), UInt64(try integer(memory.read(object + countOffset, 4), 0, UInt32.self)),
             UInt64(try integer(memory.read(object + freeOffset, 4), 0, UInt32.self)), UInt64(try integer(memory.read(object + versionOffset, 4), 0, UInt32.self))]
        }
        let before = try header(), entries = before[0], count = Int(before[1]), free = Int(before[2])
        try require(free <= count && count <= 2048, "Dictionary exceeds its bounds.")
        if entries == 0 { try require(count == 0 && free == 0 && (try header()) == before, "Invalid empty dictionary."); return [] }
        let array = try memory.read(entries + 16, 16), capacity: UInt64 = try integer(array, 8)
        try require(try integer(array, 0, UInt64.self) == 0 && capacity >= count && capacity <= 2048, "Invalid dictionary array.")
        let stride = kind == .quantities ? 16 : 24
        let raw = count > 0 ? try memory.read(entries + 32, count * stride) : Data()
        let again = count > 0 ? try memory.read(entries + 32, count * stride) : Data()
        guard try raw == again && header() == before else { throw ReadFailure("changing", "Arena is updating the deck.") }
        var result: [(UInt64, UInt64)] = [], keys = Set<UInt64>()
        for i in 0..<count {
            let off = i * stride, hashCode: Int32 = try integer(raw, off), next: Int32 = try integer(raw, off + 4)
            if hashCode < 0 { continue }
            try require(next >= -1 && next < capacity, "Invalid dictionary collision link.")
            let key = kind == .services ? try integer(raw, off + 8, UInt64.self) : UInt64(try integer(raw, off + 8, UInt32.self))
            let value = kind == .quantities ? UInt64(try integer(raw, off + 12, UInt32.self)) : try integer(raw, off + 16, UInt64.self)
            try require(keys.insert(key).inserted, "Duplicate dictionary key.")
            result.append((key, value))
        }
        try require(result.count == count - free, "Inconsistent dictionary accounting.")
        return result
    }
    func connectionAndMessages(_ wrapper: UInt64) throws {
        let service = try field(wrapper, "wrapper", "<FrontDoorConnectionServiceWrapper>k__BackingField", "connection_wrapper")
        let conn = try field(service, "connection_wrapper", "_frontDoorConnectionAWS", "connection")
        let tcp = try field(conn, "connection", "_tcpConn", "tcp"), state = try field(tcp, "tcp", "_state", "tcp_state")
        guard try integer(memory.read(state + profile.offset("tcp_state", "<ConnectState>k__BackingField"), 4), 0, Int32.self) == 4 else { throw ReadFailure("blocked", "Arena is reconnecting. Deck guidance will resume when it is connected.") }
        let messages = try singleton("messages", "_instance"), list = try memory.pointer(messages + profile.offset("messages", "_messages"))
        guard try collectionSize(list) == 0 else { throw ReadFailure("blocked", "Close the Arena dialog to resume deck guidance.") }
        let host = try field(wrapper, "wrapper", "<SettingsMenuHost>k__BackingField", "settings_host")
        let menu = try memory.pointer(host + profile.offset("settings_host", "_menuInstance"), nullable: true)
        if menu != 0 {
            try check(menu, "settings_menu")
            if try flag(menu, "settings_menu", "<IsOpen>k__BackingField") { throw ReadFailure("blocked", "Close Arena's settings to resume deck guidance.") }
        }
    }
    func activeServices(_ keys: [String]) throws -> [String: UInt64] {
        let pantry = try singleton("pantry", "instance"), parent = try memory.pointer(klass("pantry") + 0x58)
        try require(try identity(parent) == ("Pantry`1", "SharedClientCore.SharedClientCore.Code"), "Unexpected service registry.")
        guard let offset = try fields(parent)["_containers"] else { throw ReadFailure("unsupported_build", "Missing registry scope table.") }
        let scopes = try dictionary(memory.pointer(pantry + offset), .scopes)
        try require(scopes.count <= 5 && scopes.allSatisfy { $0.0 <= 4 }, "Unexpected registry scopes.")
        var found = Dictionary(uniqueKeysWithValues: keys.map { ($0, Set<UInt64>()) })
        for (_, container) in scopes {
            try check(container, "container")
            for (_, value) in try dictionary(memory.pointer(container + profile.offset("container", "_references")), .services) where value != 0 {
                let type = try memory.pointer(value)
                for key in found.keys { if type == (try klass(key)) { found[key]!.insert(value) } }
            }
        }
        try require(found.values.allSatisfy { $0.count == 1 }, "A unique active deck service could not be found.")
        return found.mapValues { $0.first! }
    }
    func checkPopup(_ manager: UInt64) throws {
        let popup = try memory.pointer(manager + profile.offset("popup_manager", "_activePopup"), nullable: true)
        if popup != 0 {
            var parent = try memory.pointer(popup), matches = false
            for _ in 0..<10 {
                if parent == (try klass("popup_base")) { matches = true; break }
                parent = try memory.pointer(parent + 0x58, nullable: true)
                if parent == 0 { break }
            }
            try require(matches, "Unexpected active popup type.")
            if try flag(popup, "popup_base", "<IsShowing>k__BackingField") { throw ReadFailure("blocked", "Close the Arena popup to resume deck guidance.") }
        }
    }
    func checkDeckDetails(_ owner: UInt64, _ key: String, _ name: String) throws {
        let popup = try memory.pointer(owner + profile.offset(key, name), nullable: true)
        if popup != 0 {
            try check(popup, "deck_details_popup")
            if try flag(popup, "popup_base", "<IsShowing>k__BackingField") {
                throw ReadFailure("blocked", "Close Arena's deck details to resume card advice.")
            }
        }
    }
    func roots() throws -> [UInt64] {
        let wrapper = try singleton("wrapper", "<Instance>k__BackingField")
        let scene = try field(wrapper, "wrapper", "<SceneLoader>k__BackingField", "scene")
        let nav = try memory.pointer(scene + profile.offset("scene", "<CurrentNavContent>k__BackingField"), nullable: true)
        guard try nav != 0 && identity(memory.pointer(nav)) == ("WrapperDeckBuilder", "") else { throw ReadFailure("outside_builder", "Open the Limited deck builder for this draft.") }
        guard try flag(nav, "editor", "_isActive") else { throw ReadFailure("outside_builder", "The deck builder is inactive.") }
        let widget = try field(nav, "editor", "_deckbuilder", "widget")
        guard try flag(widget, "widget", "_isActive") else { throw ReadFailure("outside_builder", "The deck builder is inactive.") }
        try checkDeckDetails(widget, "widget", "_detailsPopup")
        try connectionAndMessages(wrapper)
        let found = try activeServices(["model_provider", "context_provider", "popup_manager"])
        try checkPopup(found["popup_manager"]!)
        let model = try field(found["model_provider"]!, "model_provider", "_model", "model")
        let context = try field(found["context_provider"]!, "context_provider", "_context", "context")
        guard try !flag(model, "model", "_isConstructed") else { throw ReadFailure("outside_builder", "Live deck sync supports Limited decks.") }
        let event = try memory.pointer(context + profile.offset("context", "Event"), nullable: true)
        guard event != 0 else { throw ReadFailure("outside_builder", "No Limited event is active.") }
        let main = try field(model, "model", "_mainDeck", "card_list"), side = try field(model, "model", "_sideboard", "card_list"), pool = try field(model, "model", "_cardPool", "card_list")
        return [nav, model, context, event, main, side, pool]
    }
    func quantities(_ pile: UInt64, pool: Bool = false) throws -> [[UInt64]] {
        let rows = try dictionary(memory.pointer(pile + profile.offset("card_list", "_grpQuantityTable")), .quantities)
        try require(rows.allSatisfy { $0.0 > 0 && $0.0 <= UInt32.max && $0.1 <= 300 }, "Invalid card quantity.")
        let copies = rows.reduce(UInt64(0)) { $0 + $1.1 }
        try require(copies <= (pool ? 10000 : 300), "Card pile exceeds its supported bounds.")
        return rows.filter { $0.1 > 0 }.sorted { $0.0 < $1.0 }.map { [$0.0, $0.1] }
    }
    func listObjects(_ object: UInt64, maximum: Int) throws -> [UInt64] {
        let k = try memory.pointer(object), id = try identity(k), f = try fields(k)
        try require(id == ("List`1", "System.Collections.Generic"), "Unexpected draft collection.")
        guard let size = f["_size"], let items = f["_items"], let version = f["_version"] else { throw ReadFailure("unsupported_build", "Missing draft collection bookkeeping.") }
        let before = try memory.read(object + version, 4)
        let count = Int(try integer(memory.read(object + size, 4), 0, Int32.self))
        try require(count >= 0 && count <= maximum, "Draft collection exceeds its bounds.")
        let array = try memory.pointer(object + items, nullable: count == 0)
        if count == 0 { return [] }
        try require(try memory.u64(array + 16) == 0 && memory.u64(array + 24) >= count && memory.u64(array + 24) <= 2048, "Invalid draft array.")
        let data = try memory.read(array + 32, count * 8)
        guard try before == memory.read(object + version, 4) && data == memory.read(array + 32, count * 8) && count == integer(memory.read(object + size, 4), 0, Int32.self) else { throw ReadFailure("changing", "Arena is updating the draft.") }
        return try (0..<count).map { i in
            let value: UInt64 = try integer(data, i * 8)
            try require(value >= 4096 && value < 1 << 48 && value % 8 == 0, "Invalid draft object.")
            return value
        }
    }
    func cardID(_ object: UInt64) throws -> UInt64 {
        var printing = object
        if try identity(memory.pointer(object)) == ("CardData", "GreClient.CardData") {
            printing = try field(object, "card_data", "_printing", "card_printing")
        } else { try check(printing, "card_printing") }
        // CardPrintingRecord is an inline value type; its offsets include the
        // 16-byte boxed header in IL2CPP metadata, absent in this embedded field.
        let id = UInt64(try integer(memory.read(printing + profile.offset("card_printing", "Record") + profile.offset("card_printing_record", "GrpId") - 16, 4), 0, UInt32.self))
        try require(id > 0 && id <= Int32.max, "Invalid draft card identity.")
        return id
    }
    func collection(_ object: UInt64, maximum: Int) throws -> [[UInt64]] {
        try check(object, "card_collection")
        let items = try listObjects(memory.pointer(object + profile.offset("card_collection", "_itemList")), maximum: maximum)
        var counts: [UInt64: UInt64] = [:]
        for item in items {
            try check(item, "card_collection_item")
            let count = Int(try integer(memory.read(item + profile.offset("card_collection_item", "<Quantity>k__BackingField"), 4), 0, Int32.self))
            try require(count >= 0 && count <= 300, "Invalid draft card quantity.")
            // Collections can retain an empty item after moving/removing its
            // copies. It contributes no card, even if its Card was cleared.
            if count == 0 { continue }
            let card = try memory.pointer(item + profile.offset("card_collection_item", "<Card>k__BackingField"))
            let id = try cardID(card)
            try require(counts[id] == nil, "Duplicate draft collection entry.")
            counts[id] = UInt64(count)
        }
        try require(counts.values.reduce(0, +) <= maximum, "Draft collection exceeds its copy limit.")
        return counts.keys.sorted().map { [$0, counts[$0]!] }
    }
    func draftSnapshot(_ nav: UInt64) throws -> [String: Any] {
        try check(nav, "draft_controller")
        guard try flag(nav, "draft_controller", "_active") else { throw ReadFailure("outside_builder", "The draft screen is inactive.") }
        try checkDeckDetails(nav, "draft_controller", "_deckDetailsPopup")
        let wrapper = try singleton("wrapper", "<Instance>k__BackingField")
        try connectionAndMessages(wrapper)
        try checkPopup(activeServices(["popup_manager"])["popup_manager"]!)
        guard try flag(nav, "draft_controller", "_okToPickCard") else { throw ReadFailure("changing", "Waiting for Arena's current pack.", resetPresentation:true) }
        let holder = try field(nav, "draft_controller", "_draftPackHolder", "draft_holder")
        guard try flag(holder, "draft_holder", "_isInitialized") && memory.pointer(holder + profile.offset("draft_holder", "_pendingPackCollection"), nullable: true) == 0 else { throw ReadFailure("changing", "Arena is changing packs.", resetPresentation:true) }
        let pack = try field(nav, "draft_controller", "_packCollection", "card_collection")
        guard try memory.pointer(holder + profile.offset("draft_holder", "_packCollection")) == pack else { throw ReadFailure("changing", "Arena is changing packs.", resetPresentation:true) }
        let expected = try collection(pack, maximum: 30)
        let views = try listObjects(memory.pointer(holder + profile.offset("draft_holder", "_allCardViews")), maximum: 30)
        var ids: [UInt64] = []
        for view in views {
            try check(view, "draft_card_view")
            guard try !flag(view, "meta_card_view", "<IsDragging>k__BackingField") else { throw ReadFailure("changing", "A draft card is being moved.") }
            // CurrentCard implements ICardCollectionItem. Its Card field holds
            // the CardData; the view does not point directly to a printing.
            let item = try field(view, "draft_card_view", "<CurrentCard>k__BackingField", "card_collection_item")
            let card = try field(item, "card_collection_item", "<Card>k__BackingField", "card_data")
            ids.append(try cardID(card))
        }
        let actual = Dictionary(grouping: ids, by: { $0 }).mapValues { UInt64($0.count) }
        let wanted = Dictionary(uniqueKeysWithValues: expected.map { ($0[0], $0[1]) })
        guard !ids.isEmpty && actual == wanted else { throw ReadFailure("changing", "Arena's displayed cards are updating.") }
        let manager = try field(nav, "draft_controller", "_draftDeckManager", "draft_deck_manager")
        let deck = try field(manager, "draft_deck_manager", "_deck", "draft_deck")
        let main = try collection(field(deck, "draft_deck", "_main", "card_collection"), maximum: 300)
        let side = try collection(field(deck, "draft_deck", "_sideboard", "card_collection"), maximum: 300)
        var picked: [UInt64: UInt64] = [:]
        for row in main + side { picked[row[0], default: 0] += row[1] }
        let session = hash(Data((nonce + ":draft:\(nav):\(manager):\(pack)").utf8))
        // These layout records are kept inside the process until live projection
        // and clipping are verified. Local/world positions are not screen bounds.
        var result: [String: Any] = ["status": "draft_ready", "editor_session": session, "cards": ids,
                "picked": picked.keys.sorted().map { [$0, picked[$0]!] }]
        do {
            for field in ["_layoutChangeCoroutine", "_settingCardsCoroutine"] {
                guard try memory.pointer(nav + profile.offset("draft_controller", field), nullable:true) == 0 else { throw ReadFailure("changing","Arena is arranging card positions.") }
            }
            guard try !flag(holder,"draft_holder","<IsAnimating>k__BackingField") else { throw ReadFailure("changing","Arena is animating card positions.") }
            result["geometry"] = try ArenaGeometry(self).pack(views)
        }
        catch { result["geometry_reason"] = failure(error)["reason"] }
        return result
    }
    func snapshot() throws -> [String: Any] {
        try target?.validate(); memory.begin()
        let wrapper = try singleton("wrapper", "<Instance>k__BackingField")
        let scene = try field(wrapper, "wrapper", "<SceneLoader>k__BackingField", "scene")
        let nav = try memory.pointer(scene + profile.offset("scene", "<CurrentNavContent>k__BackingField"), nullable: true)
        if try nav != 0 && identity(memory.pointer(nav)) == ("DraftContentController", "Wotc.Mtga.Wrapper.Draft") {
            let a = try draftSnapshot(nav), b = try draftSnapshot(nav)
            var identityA=a, identityB=b
            for key in ["geometry","geometry_reason"] { identityA.removeValue(forKey:key);identityB.removeValue(forKey:key) }
            guard try memory.pointer(scene + profile.offset("scene", "<CurrentNavContent>k__BackingField")) == nav,
                  NSDictionary(dictionary: identityA).isEqual(to: identityB) else { throw ReadFailure("changing", "Arena is changing the draft pack.", resetPresentation:true) }
            try target?.validate()
            var result=b
            result["elapsed_ms"]=Int((ProcessInfo.processInfo.systemUptime-memory.started)*1000)
            return result
        }
        for _ in 0..<3 {
            do {
                let a = try roots(), main = try quantities(a[4]), side = try quantities(a[5]), pool = try quantities(a[6], pool: true)
                var geometry: [String:Any]?, geometryReason: String?
                do { geometry=try ArenaGeometry(self).deck(a[0]) }
                catch { geometryReason=failure(error)["reason"] as? String }
                guard try a == roots() && main == quantities(a[4]) && side == quantities(a[5]) && pool == quantities(a[6], pool: true) else { throw ReadFailure("changing", "Arena is updating the deck.") }
                try target?.validate()
                let session = hash(Data((nonce + ":" + a.prefix(4).map(String.init).joined(separator: ":")).utf8))
                var result: [String: Any] = ["status": "ready", "editor_session": session, "main": main, "sideboard": side, "pool": pool,
                        "total": main.reduce(UInt64(0)) { $0 + $1[1] }, "elapsed_ms": Int((ProcessInfo.processInfo.systemUptime - memory.started) * 1000)]
                if let geometry=geometry { result["geometry"]=geometry }
                if let reason=geometryReason { result["geometry_reason"]=reason }
                result["elapsed_ms"] = Int((ProcessInfo.processInfo.systemUptime - memory.started) * 1000)
                return result
            } catch let error as ReadFailure where error.status == "changing" { continue }
        }
        throw ReadFailure("changing", "Arena is updating the deck.")
    }
}

private func validToken(_ value: String) -> Bool { value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }
private func sameToken(_ a: String, _ b: String) -> Bool {
    let aa = Array(a.utf8), bb = Array(b.utf8)
    if aa.count != bb.count { return false }
    return zip(aa,bb).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
}
private func shellQuote(_ text: String) -> String { "'" + text.replacingOccurrences(of: "'", with: "'\"'\"'") + "'" }
private func authorizationScript(_ command: String) -> NSAppleScript? {
    let escaped = command.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    return NSAppleScript(source: "with timeout of 28860 seconds\n do shell script \"\(escaped)\" with administrator privileges with prompt \"FirstPick needs read-only access to your live Arena deck while this Arena session is open.\"\nend timeout")
}
private func validateDirectory(_ path: String, _ uid: uid_t) throws {
    var info = stat()
    let directory = URL(fileURLWithPath: path).deletingLastPathComponent().path
    try require(path.hasPrefix("/tmp/fp-live-") && path.hasSuffix("/read.sock") && path.utf8.count < 104 && lstat(directory, &info) == 0 && info.st_uid == uid && info.st_mode & S_IFMT == S_IFDIR && info.st_mode & 0o777 == 0o700, "The private session directory could not be verified.")
}
private func grant(_ path: String, _ token: String, _ parent: pid_t) throws -> [String: Any] {
    try require(getuid() != 0 && validToken(token), "Invalid access request.")
    try validateDirectory(path, getuid())
    let target = try Target()
    let binary = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath().path
    let args = [binary, "--authorized", path, token, String(parent), String(getuid()), String(getgid()), String(target.pid)]
    // Keep the authorization call attached to its child. Detaching here can
    // discard launch failures (or the child itself) when the privileged shell ends.
    // The app observes socket readiness concurrently; no UI thread waits on this.
    let command = args.map(shellQuote).joined(separator: " ")
    var error: NSDictionary?
    let script = authorizationScript(command)
    guard let result = script?.executeAndReturnError(&error), error == nil else { return ["status": "access_required", "reason": "Access was not granted. Grant Arena access to enable the overlay."] }
    if let text = result.stringValue, text.utf8.count <= 4096,
       let data = text.data(using: .utf8), let response = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { return response }
    return ["status": "closed"]
}
private func serveAuthorized(_ args: [String]) throws {
    try require(args.count == 6 && geteuid() == 0 && validToken(args[1]), "Invalid authorized session.")
    guard let parent = pid_t(args[2]), let uid = uid_t(args[3]), let gid = gid_t(args[4]), let pid = pid_t(args[5]), uid > 0, gid > 0 else { throw ReadFailure("unavailable", "Invalid session owner.") }
    let owner = try processIdentity(parent)
    try require(owner.pbi_uid == uid && owner.pbi_ruid == uid && owner.pbi_rgid == gid, "The calling application is no longer available.")
    // Acquire the read right while elevated, but perform runtime discovery and
    // write any startup diagnostics only after permanently dropping privileges.
    let acquired = Result { () throws -> (Target, Memory) in
        let target = try Target(pid: pid)
        return (target, try Memory(target.pid))
    }
    try require(setgroups(0, nil) == 0 && setgid(gid) == 0 && setuid(uid) == 0 && getuid() == uid && geteuid() == uid && getgid() == gid && getegid() == gid, "Could not drop administrator privileges.")
    try validateDirectory(args[0], uid)
    let target: Target, memory: Memory, runtime: DeckRuntime
    do {
        (target, memory) = try acquired.get()
        runtime = try DeckRuntime(target, memory)
    } catch {
        let path = URL(fileURLWithPath: args[0]).deletingLastPathComponent().appendingPathComponent("bootstrap.json").path
        let fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        if fd >= 0 {
            defer { Darwin.close(fd) }
            let data = json(failure(error))
            _ = data.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, $0.count) }
        }
        throw error
    }
    memory.begin(); _ = try memory.read(memory.imagesAddress(), 16)
    let listener = socket(AF_UNIX, SOCK_STREAM, 0)
    try require(listener >= 0, "Could not open the private session socket.")
    defer { Darwin.close(listener); unlink(args[0]) }
    var addr = sockaddr_un(); addr.sun_family = sa_family_t(AF_UNIX); addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
    let path = args[0].utf8CString
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in path.withUnsafeBytes { raw.copyBytes(from: $0) } }
    let bound = withUnsafePointer(to: &addr) { p in p.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(listener, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
    try require(bound == 0 && chmod(args[0], 0o600) == 0 && listen(listener, 4) == 0, "Could not bind the private session socket.")
    signal(SIGALRM, SIG_DFL); signal(SIGPIPE, SIG_IGN)
    let deadline = ProcessInfo.processInfo.systemUptime + 8 * 3600
    while ProcessInfo.processInfo.systemUptime < deadline {
        var socketInfo = stat()
        guard lstat(args[0], &socketInfo) == 0 && socketInfo.st_uid == uid && socketInfo.st_mode & S_IFMT == S_IFSOCK else { break }
        guard let current = try? processIdentity(parent), current.pbi_start_tvsec == owner.pbi_start_tvsec && current.pbi_start_tvusec == owner.pbi_start_tvusec else { break }
        do { try target.validate() } catch { break }
        var item = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
        if poll(&item, 1, 1000) <= 0 { continue }
        let client = accept(listener, nil, nil)
        if client < 0 { continue }
        var stop = false
        do {
            defer { Darwin.close(client) }
            var peer: uid_t = 0, peerGroup: gid_t = 0
            try require(getpeereid(client, &peer, &peerGroup) == 0 && peer == uid, "Wrong session owner.")
            var timeout = timeval(tv_sec: 3, tv_usec: 0)
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var input = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while input.count < 4096 && !input.contains(10) {
                let n = recv(client, &buffer, min(buffer.count, 4096 - input.count), 0)
                if n <= 0 { break }; input.append(contentsOf: buffer.prefix(n))
            }
            guard input.last == 10, let request = try JSONSerialization.jsonObject(with: input) as? [String: Any], let token = request["token"] as? String, sameToken(token, args[1]) else { continue }
            var result: [String: Any]
            if request["op"] as? String == "close" { result = ["status": "closed"]; stop = true }
            else if request["op"] as? String == "snapshot" {
                alarm(3); defer { alarm(0) }
                do { result = try runtime.snapshot() } catch { result = failure(error) }
            } else { result = ["status": "unavailable", "reason": "Unsupported session request."] }
            var output = json(result); output.append(10)
            try require(output.count <= 65536, "Deck response exceeds its bounds.")
            try output.withUnsafeBytes { p in
                var sent = 0
                while sent < p.count {
                    let n = Darwin.write(client, p.baseAddress!.advanced(by: sent), p.count - sent)
                    if n <= 0 { throw ReadFailure("unavailable", "Session client closed.") }; sent += n
                }
            }
        } catch { /* No raw process data or client requests are logged. */ }
        if stop || memory.totalBytes >= 8 * 1024 * 1024 * 1024 { break }
    }
}

private let mode = CommandLine.arguments.dropFirst().first ?? "--worker"
do {
    switch mode {
    case "--authorized": try serveAuthorized(Array(CommandLine.arguments.dropFirst(2)))
    case "--grant":
        let args = Array(CommandLine.arguments.dropFirst(2))
        guard args.count == 3, let parent = pid_t(args[2]) else { throw ReadFailure("unavailable", "Invalid grant request.") }
        print(String(decoding: json(try grant(args[0], args[1], parent)), as: UTF8.self))
    case "--inspect":
        _ = try Target(); print("{\"status\":\"supported\"}")
    case "--fixture":
        try require(geteuid() != 0 && CommandLine.arguments.count == 3, "Fixture tests cannot run elevated.")
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
        try require(data.count <= 12 * 1024 * 1024, "Fixture exceeds bounds.")
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any], let profile = value["profile"] as? [String: Any], let encoded = value["memory"] as? String, let bytes = Data(base64Encoded: encoded), bytes.count <= 8 * 1024 * 1024 else { throw ReadFailure("unsupported_build", "Invalid fixture.") }
        var result = try DeckRuntime(fixture: bytes, profile: Profile(object: profile)).snapshot()
        result["fixture"] = true
        print(String(decoding: json(result), as: UTF8.self))
    case "--self-test":
        let memory = try Memory(getpid(), selfTest: true), sample = Data("FirstPick native memory check".utf8)
        try sample.withUnsafeBytes { p in try require(memory.read(UInt64(UInt(bitPattern: p.baseAddress!)), p.count) == sample, "Self-read differs.") }
        try require(shellQuote("a'b") == "'a'\"'\"'b'" && !validToken("wrong"), "Authorization argument validation differs.")
        var scriptError: NSDictionary?
        try require(authorizationScript("/usr/bin/true")?.compileAndReturnError(&scriptError) == true && scriptError == nil, "The macOS permission request could not be compiled.")
        print("{\"status\":\"self_read_verified\",\"arena_access_proven\":false}")
    default:
        try require(geteuid() != 0, "Use the authorized session mode for administrator access.")
        var runtime: DeckRuntime?
        var candidate: Target?
        while let line = readLine(), line.utf8.count <= 4096 {
            do {
                if runtime == nil {
                    if candidate == nil { candidate = try Target() }
                    try candidate!.validate()
                    runtime = try DeckRuntime(candidate!, Memory(candidate!.pid))
                }
                print(String(decoding: json(try runtime!.snapshot()), as: UTF8.self))
            } catch {
                let result = failure(error)
                if result["status"] as? String == "arena_closed" { runtime = nil; candidate = nil }
                print(String(decoding: json(result), as: UTF8.self))
            }
            fflush(stdout)
        }
    }
} catch { print(String(decoding: json(failure(error)), as: UTF8.self)); exit(mode == "--authorized" ? 0 : 1) }
