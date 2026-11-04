// Compiled with arena-memory.swift: a persistent, root-owned capability broker.
// Only the signed reader can connect. Each reader still drops root permanently
// after acquiring Arena's read right; the broker never returns raw memory.
import Security

private let accessServiceName = "com.firstpick.arena-reader"
private let installedReader = "/Library/PrivilegedHelperTools/com.firstpick.arena-reader"
private let accessServicePlist = "/Library/LaunchDaemons/com.firstpick.arena-reader.plist"

@objc private protocol ArenaAccessBroker {
    func startSession(_ request: Data, withReply reply: @escaping (Data) -> Void)
    func refresh(_ executable: String, withReply reply: @escaping (Data) -> Void)
    func revoke(withReply reply: @escaping (Data) -> Void)
}

private func readerRequirement() throws -> (SecRequirement, String) {
    var code: SecCode?, staticCode: SecStaticCode?, requirement: SecRequirement?, text: CFString?
    try require(SecCodeCopySelf([], &code) == errSecSuccess && code != nil,
                "The reader signature is unavailable.")
    try require(SecCodeCopyStaticCode(code!, [], &staticCode) == errSecSuccess && staticCode != nil &&
        SecCodeCopyDesignatedRequirement(staticCode!, [], &requirement) == errSecSuccess && requirement != nil,
                "The reader needs a persistent signing identity.")
    try require(SecRequirementCopyString(requirement!, [], &text) == errSecSuccess && text != nil,
                "The reader signature requirement is unavailable.")
    // Persist a certificate identity rather than an ad-hoc hash or an identifier-only requirement.
    var information: CFDictionary?
    try require(SecCodeCopySigningInformation(staticCode!, SecCSFlags(rawValue: kSecCSSigningInformation), &information) == errSecSuccess,
                "The reader signing certificate is unavailable.")
    let certificates = (information as? [String: Any])?[kSecCodeInfoCertificates as String] as? [SecCertificate]
    try require(certificates?.isEmpty == false && !(text! as String).contains("cdhash") &&
        (text! as String).contains("certificate"), "A certificate-signed FirstPick build is required.")
    return (requirement!, text! as String)
}

private func verifiedReader(_ path: String, requirement: SecRequirement) throws {
    var code: SecStaticCode?
    try require(SecStaticCodeCreateWithPath(URL(fileURLWithPath: path) as CFURL, [], &code) == errSecSuccess && code != nil &&
        SecStaticCodeCheckValidity(code!, SecCSFlags(rawValue: kSecCSStrictValidate | kSecCSCheckAllArchitectures), requirement) == errSecSuccess,
        "The new reader is not signed by FirstPick's approved identity.")
}

private func protectedDirectory(_ path: String) throws {
    let manager = FileManager.default
    if !manager.fileExists(atPath: path) {
        try manager.createDirectory(atPath: path, withIntermediateDirectories: false,
            attributes: [.posixPermissions: 0o755, .ownerAccountID: 0, .groupOwnerAccountID: 0])
    }
    var info = stat()
    try require(lstat(path, &info) == 0 && info.st_mode & S_IFMT == S_IFDIR && info.st_uid == 0 &&
        info.st_mode & 0o022 == 0, "The helper installation directory is not protected.")
}

/** Copy first, then verify a root-owned staging file; never execute a mutable app path as root. */
private func stageReader(_ source: String, requirement: SecRequirement) throws -> String {
    try protectedDirectory("/Library/PrivilegedHelperTools")
    var info = stat()
    try require(lstat(source, &info) == 0 && info.st_mode & S_IFMT == S_IFREG && info.st_size > 0 &&
        info.st_size < 32 * 1024 * 1024, "Invalid reader update.")
    let staged = installedReader + "." + UUID().uuidString
    do {
        try FileManager.default.copyItem(atPath: source, toPath: staged)
        try require(lstat(staged, &info) == 0 && info.st_mode & S_IFMT == S_IFREG,
                    "Invalid staged reader.")
        try require(chown(staged, 0, 0) == 0 && chmod(staged, 0o755) == 0,
                    "Could not protect the installed reader.")
        try verifiedReader(staged, requirement: requirement)
        return staged
    } catch {
        try? FileManager.default.removeItem(atPath: staged)
        throw error
    }
}

private func installAccessService(_ uid: uid_t) throws {
    try require(geteuid() == 0 && uid > 0, "Administrator approval is required to install Arena access.")
    let (requirement, _) = try readerRequirement()
    let staged = try stageReader(URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath().path,
                                 requirement: requirement)
    defer { try? FileManager.default.removeItem(atPath: staged) }
    try protectedDirectory("/Library/LaunchDaemons")
    // Refuse to overwrite a service belonging to another signer.
    if FileManager.default.fileExists(atPath: installedReader) {
        try verifiedReader(installedReader, requirement: requirement)
    }
    try require(rename(staged, installedReader) == 0, "Could not install the Arena reader.")
    let plist: [String: Any] = ["Label": accessServiceName,
        "ProgramArguments": [installedReader, "--access-service", String(uid)],
        "MachServices": [accessServiceName: true], "KeepAlive": true,
        "ProcessType": "Background", "ThrottleInterval": 3]
    let bytes = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
    try bytes.write(to: URL(fileURLWithPath: accessServicePlist), options: .atomic)
    try require(chown(accessServicePlist, 0, 0) == 0 && chmod(accessServicePlist, 0o644) == 0,
                "Could not protect the service registration.")
    let launch = Process(); launch.executableURL = URL(fileURLWithPath: "/bin/launchctl")
    launch.arguments = ["bootstrap", "system", accessServicePlist]
    launch.standardOutput = FileHandle.nullDevice; launch.standardError = FileHandle.nullDevice
    try launch.run(); launch.waitUntilExit()
    try require(launch.terminationStatus == 0, "Could not start the installed Arena access helper.")
}

private final class AccessSessions {
    let operations = NSLock()
    var revoked = false
    private let lock = NSLock()
    private var active: [pid_t: Process] = [:]
    func start(_ child: Process, parent: pid_t) throws {
        lock.lock(); defer { lock.unlock() }
        active = active.filter { $0.value.isRunning }
        if let previous = active[parent], previous.isRunning { previous.terminate() }
        try require(active.count < 8 || active[parent] != nil, "Too many Arena sessions are connected.")
        try child.run(); active[parent] = child
    }
    func stopAll() {
        lock.lock(); defer { lock.unlock() }
        for child in active.values { if child.isRunning { child.terminate() } }
        active.removeAll()
    }
}

private final class AccessBrokerClient: NSObject, ArenaAccessBroker {
    private let uid: uid_t
    private let gid: gid_t
    private let requirement: SecRequirement
    private let sessions: AccessSessions
    private var children: [pid_t: Process] = [:]
    init(uid: uid_t, gid: gid_t, requirement: SecRequirement, sessions: AccessSessions) {
        self.uid = uid; self.gid = gid; self.requirement = requirement; self.sessions = sessions
    }
    deinit { for child in children.values { if child.isRunning { child.terminate() } } }

    func startSession(_ request: Data, withReply reply: @escaping (Data) -> Void) {
        sessions.operations.lock(); defer { sessions.operations.unlock() }
        do {
            try require(!sessions.revoked, "Saved Arena access has been revoked.")
            try require(request.count <= 4096, "Invalid session request.")
            guard let value = try JSONSerialization.jsonObject(with: request) as? [String: Any],
                  let path = value["socket"] as? String, let token = value["token"] as? String,
                  let parent = value["parent"] as? Int32, let arenaPID = value["arena"] as? Int32 else {
                throw ReadFailure("access_required", "Invalid session request.")
            }
            try require(validToken(token), "Invalid session token.")
            try validateDirectory(path, uid)
            let owner = try processIdentity(parent), arena = try processIdentity(arenaPID)
            try require(owner.pbi_uid == uid && owner.pbi_ruid == uid && owner.pbi_rgid == gid &&
                arena.pbi_uid == uid && arena.pbi_ruid == uid, "The session belongs to another user.")
            // Limit each authenticated connection to one session. No caller-supplied executable or arguments.
            for child in children.values { if child.isRunning { child.terminate() } }
            children.removeAll()
            let child = Process(); child.executableURL = URL(fileURLWithPath: installedReader)
            child.arguments = ["--authorized", path, token, String(parent), String(uid), String(gid), String(arenaPID)]
            child.environment = ["PATH": "/usr/bin:/bin:/usr/sbin:/sbin"]
            child.standardOutput = FileHandle.nullDevice; child.standardError = FileHandle.nullDevice
            try sessions.start(child, parent: parent); children[parent] = child
            reply(json(["status": "starting"]))
        } catch { reply(json(failure(error))) }
    }

    func revoke(withReply reply: @escaping (Data) -> Void) {
        sessions.operations.lock(); defer { sessions.operations.unlock() }
        do {
            sessions.revoked = true
            sessions.stopAll()
            for path in [accessServicePlist, installedReader] where FileManager.default.fileExists(atPath: path) {
                try FileManager.default.removeItem(atPath: path)
            }
            reply(json(["status": "revoked"]))
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                let stop = Process(); stop.executableURL = URL(fileURLWithPath: "/bin/launchctl")
                stop.arguments = ["bootout", "system/" + accessServiceName]
                try? stop.run()
            }
        } catch { reply(json(failure(error))) }
    }

    func refresh(_ executable: String, withReply reply: @escaping (Data) -> Void) {
        sessions.operations.lock(); defer { sessions.operations.unlock() }
        do {
            try require(!sessions.revoked, "Saved Arena access has been revoked.")
            // Only a fully verified binary signed by the already-approved identity can replace the helper.
            let source = URL(fileURLWithPath: executable).resolvingSymlinksInPath().path
            var info = stat()
            try require(lstat(source, &info) == 0 && info.st_mode & S_IFMT == S_IFREG && info.st_size > 0 &&
                info.st_size < 32 * 1024 * 1024, "Invalid reader update.")
            if try Data(contentsOf: URL(fileURLWithPath: source), options: .mappedIfSafe) ==
                Data(contentsOf: URL(fileURLWithPath: installedReader), options: .mappedIfSafe) {
                reply(json(["status": "current"])); return
            }
            let staged = try stageReader(source, requirement: requirement)
            defer { try? FileManager.default.removeItem(atPath: staged) }
            try require(rename(staged, installedReader) == 0, "Could not update the Arena reader.")
            reply(json(["status": "updated"]))
            // launchd relaunches the new, root-owned executable. Existing app-bound readers are independent.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { exit(0) }
        } catch { reply(json(failure(error))) }
    }
}

private final class AccessBrokerListener: NSObject, NSXPCListenerDelegate {
    let requirement: SecRequirement
    let requirementText: String
    let sessions = AccessSessions()
    init(requirement: SecRequirement, text: String) { self.requirement = requirement; requirementText = text }
    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection connection: NSXPCConnection) -> Bool {
        guard CommandLine.arguments.count == 3, let approvedUID = uid_t(CommandLine.arguments[2]),
            connection.effectiveUserIdentifier == approvedUID && approvedUID > 0 else { return false }
        connection.setCodeSigningRequirement(requirementText)
        connection.exportedInterface = NSXPCInterface(with: ArenaAccessBroker.self)
        connection.exportedObject = AccessBrokerClient(uid: connection.effectiveUserIdentifier,
            gid: connection.effectiveGroupIdentifier, requirement: requirement, sessions: sessions)
        connection.resume()
        return true
    }
}

private func runAccessService() throws {
    try require(geteuid() == 0, "The access service must be started by macOS.")
    let (requirement, text) = try readerRequirement()
    let delegate = AccessBrokerListener(requirement: requirement, text: text)
    let listener = NSXPCListener(machServiceName: accessServiceName)
    listener.delegate = delegate
    listener.setConnectionCodeSigningRequirement(text)
    listener.resume()
    withExtendedLifetime((delegate, listener)) { RunLoop.current.run() }
}

private func brokerCall(_ connection: NSXPCConnection, _ action: (ArenaAccessBroker, @escaping (Data) -> Void) -> Void) throws -> [String: Any] {
    let semaphore = DispatchSemaphore(value: 0), lock = NSLock()
    var response: Data?
    let complete: (Data) -> Void = { data in
        lock.lock(); response = data; lock.unlock(); semaphore.signal()
    }
    guard let proxy = connection.remoteObjectProxyWithErrorHandler({ _ in complete(json(["status": "access_required", "reason": "The installed Arena helper could not be reached."])) }) as? ArenaAccessBroker else {
        throw ReadFailure("access_required", "The installed Arena helper could not be reached.")
    }
    action(proxy, complete)
    try require(semaphore.wait(timeout: .now() + 10) == .success, "The Arena access helper timed out.")
    lock.lock(); let data = response; lock.unlock()
    guard let data, let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw ReadFailure("access_required", "The Arena access helper returned an invalid response.")
    }
    return value
}

private func persistentGrant(_ path: String, _ token: String, _ parent: pid_t, allowInstall: Bool) throws -> [String: Any] {
    try require(getuid() != 0 && validToken(token), "Invalid access request.")
    try validateDirectory(path, getuid())
    let target = try Target()
    let binary = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath().path
    let (_, text) = try readerRequirement()
    if !FileManager.default.fileExists(atPath: installedReader) {
        guard allowInstall else { throw ReadFailure("access_required", "Grant Arena access to install the read-only helper.") }
        var error: NSDictionary?
        let script = authorizationScript([binary, "--install-access-service", String(getuid())].map(shellQuote).joined(separator: " "))
        guard script?.executeAndReturnError(&error) != nil && error == nil else {
            throw ReadFailure("access_required", "The Arena access helper was not installed.")
        }
    }
    // Refresh on each connection, using the same certificate across app versions.
    // A root-owned staging file is verified before the approved helper is replaced.
    for _ in 0..<4 {
        let connection = NSXPCConnection(machServiceName: accessServiceName, options: .privileged)
        connection.setCodeSigningRequirement(text)
        connection.remoteObjectInterface = NSXPCInterface(with: ArenaAccessBroker.self)
        connection.resume()
        let refresh = try brokerCall(connection) { $0.refresh(binary, withReply: $1) }
        if refresh["status"] as? String == "updated" {
            connection.invalidate(); Thread.sleep(forTimeInterval: 2); continue
        }
        guard refresh["status"] as? String == "current" else { connection.invalidate(); return refresh }
        let request = json(["socket": path, "token": token, "parent": parent, "arena": target.pid])
        let started = try brokerCall(connection) { $0.startSession(request, withReply: $1) }
        guard started["status"] as? String == "starting" else { connection.invalidate(); return started }
        // Retain the authenticated connection for this session. Dropping it ends its child reader.
        let owner = try processIdentity(parent)
        var socketSeen = false
        let startupDeadline = ProcessInfo.processInfo.systemUptime + 20
        while let current = try? processIdentity(parent), current.pbi_start_tvsec == owner.pbi_start_tvsec &&
            current.pbi_start_tvusec == owner.pbi_start_tvusec {
            Thread.sleep(forTimeInterval: 0.2)
            let exists = FileManager.default.fileExists(atPath: path)
            if socketSeen && !exists { break }
            socketSeen = socketSeen || exists
            if !socketSeen && ProcessInfo.processInfo.systemUptime > startupDeadline { break }
            if FileManager.default.fileExists(atPath: URL(fileURLWithPath: path).deletingLastPathComponent().appendingPathComponent("bootstrap.json").path) { break }
        }
        connection.invalidate()
        return ["status": "closed"]
    }
    throw ReadFailure("access_required", "The Arena access helper is restarting. Try reconnecting.")
}

private func revokeAccessService() throws -> [String: Any] {
    let (_, text) = try readerRequirement()
    let connection = NSXPCConnection(machServiceName: accessServiceName, options: .privileged)
    connection.setCodeSigningRequirement(text)
    connection.remoteObjectInterface = NSXPCInterface(with: ArenaAccessBroker.self)
    connection.resume(); defer { connection.invalidate() }
    return try brokerCall(connection) { $0.revoke(withReply: $1) }
}
