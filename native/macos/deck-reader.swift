import AppKit
import ScreenCaptureKit
import ImageIO
import Vision
import CoreImage
import CoreMedia

// One process owns capture and Vision. Requests/responses are newline-delimited JSON.
// Temporary bitmaps pass the pixels needed by the copy-diamond reader back to the JVM.
private func emit(_ object: [String: Any]) {
    if let data = try? JSONSerialization.data(withJSONObject: object),
       let text = String(data: data, encoding: .utf8) {
        print(text)
        fflush(stdout)
    }
}

private final class LiveCapture: NSObject, SCStreamOutput {
    private var stream: SCStream?
    private let lock = NSLock()
    private var latest: CVPixelBuffer?
    private let context = CIContext(options: [.cacheIntermediates: false])
    let width: Int
    let height: Int
    let windowID: CGWindowID

    init(window: SCWindow) async throws {
        width = Int(window.frame.width)
        height = Int(window.frame.height)
        windowID = window.windowID
        super.init()
        let configuration = SCStreamConfiguration()
        configuration.width = min(width * 2, 2560)
        configuration.height = Int(Double(height) * Double(configuration.width) / Double(width))
        configuration.showsCursor = false
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 12)
        configuration.queueDepth = 3
        let stream = SCStream(filter: SCContentFilter(desktopIndependentWindow: window), configuration: configuration, delegate: nil)
        self.stream = stream
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "firstpick.capture"))
        try await stream.startCapture()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: rawStatus) == .complete,
              let buffer = sampleBuffer.imageBuffer else { return }
        lock.lock()
        latest = buffer
        lock.unlock()
    }

    private func buffer() -> CVPixelBuffer? {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }

    func image() async throws -> CGImage {
        for _ in 0..<100 {
            if let buffer = buffer() {
                let image = CIImage(cvPixelBuffer: buffer)
                if let cgImage = context.createCGImage(image, from: image.extent) { return cgImage }
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw NSError(domain: "FirstPickCapture", code: 1)
    }

    func stop() async { try? await stream?.stopCapture() }
}

private var liveCapture: LiveCapture?
private var windowCheckedAt = Date.distantPast

// Uncompressed BMP avoids spending hundreds of milliseconds compressing a temporary PNG.
private func writePixels(_ image: CGImage, to path: String) throws {
    guard var data = pixels(image) else { throw NSError(domain: "FirstPickPixels", code: 1) }
    data.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
        for i in stride(from: 0, to: bytes.count, by: 4) {
            let red = bytes[i]
            bytes[i] = bytes[i + 2]
            bytes[i + 2] = red
        }
    }
    var header = Data([0x42, 0x4d])
    func append32(_ value: UInt32) {
        var little = value.littleEndian
        withUnsafeBytes(of: &little) { header.append(contentsOf: $0) }
    }
    append32(UInt32(54 + data.count))
    append32(0)
    append32(54)
    append32(40)
    append32(UInt32(image.width))
    append32(UInt32(bitPattern: -Int32(image.height))) // top-down rows
    header.append(contentsOf: [1, 0, 32, 0])
    append32(0)
    append32(UInt32(data.count))
    append32(0); append32(0); append32(0); append32(0)
    header.append(data)
    try header.write(to: URL(fileURLWithPath: path))
}

private struct RegionCache {
    let pixels: Data
    let width: Int
    let height: Int
    let observations: [[String: Any]]
}
private var cache: [Int: RegionCache] = [:]
// Ignore tiny compositing/color fluctuations, while detecting a changed count glyph.
private func sameContent(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }
    return a.withUnsafeBytes { (left: UnsafeRawBufferPointer) in
        b.withUnsafeBytes { (right: UnsafeRawBufferPointer) in
            var changed = 0
            let limit = max(3, a.count / 4 / 10_000)
            for i in stride(from: 0, to: a.count, by: 4) {
                if abs(Int(left[i]) - Int(right[i])) > 24 ||
                   abs(Int(left[i + 1]) - Int(right[i + 1])) > 24 ||
                   abs(Int(left[i + 2]) - Int(right[i + 2])) > 24 {
                    changed += 1
                    if changed > limit { return false }
                }
            }
            return true
        }
    }
}
// The Done button pulses orange underneath unchanged white text. Compare the
// foreground glyphs instead of rejecting it whenever the background animates.
private func sameButtonText(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }
    return a.withUnsafeBytes { (left: UnsafeRawBufferPointer) in
        b.withUnsafeBytes { (right: UnsafeRawBufferPointer) in
            var union = 0
            var changed = 0
            for i in stride(from: 0, to: a.count, by: 4) {
                func foreground(_ bytes: UnsafeRawBufferPointer) -> Bool {
                    let lo = min(bytes[i], bytes[i + 1], bytes[i + 2])
                    let hi = max(bytes[i], bytes[i + 1], bytes[i + 2])
                    return lo > 160 && Int(hi) - Int(lo) < 40
                }
                let old = foreground(left)
                let new = foreground(right)
                if old || new { union += 1 }
                if old != new { changed += 1 }
            }
            return union >= 10 && Double(changed) / Double(union) < 0.15
        }
    }
}
private var lastWords: [String] = []

private func pixels(_ image: CGImage) -> Data? {
    let bytesPerRow = image.width * 4
    var data = Data(count: bytesPerRow * image.height)
    let ok = data.withUnsafeMutableBytes { bytes -> Bool in
        guard let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return true
    }
    return ok ? data : nil
}

@available(macOS 14.0, *)
private func readFrame(path: String, words: [String], windowWidth: Int, windowHeight: Int) async throws -> [String: Any] {
    let start = Date()
    let sizeChanged = windowWidth > 0 && windowHeight > 0 &&
        (liveCapture?.width != windowWidth || liveCapture?.height != windowHeight)
    if liveCapture == nil || sizeChanged || Date().timeIntervalSince(windowCheckedAt) > 2 {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        windowCheckedAt = Date()
        guard let window = content.windows.filter({ $0.owningApplication?.applicationName == "MTGA" && $0.frame.width > 100 })
            .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else {
            await liveCapture?.stop()
            liveCapture = nil
            return ["schema": 1, "ok": false, "reason": "no_window"]
        }
        if liveCapture?.windowID != window.windowID ||
            liveCapture?.width != Int(window.frame.width) || liveCapture?.height != Int(window.frame.height) {
            await liveCapture?.stop()
            liveCapture = try await LiveCapture(window: window)
            cache.removeAll()
        }
    }
    if lastWords != words {
        cache.removeAll()
        lastWords = words
    }
    guard let capture = liveCapture else { throw NSError(domain: "FirstPickCapture", code: 2) }
    let image = try await capture.image()

    let capturedAt = Date()
    // Top-left normalized regions: complete deck panel and the two supported pool title bands.
    // Pool card art/rules text are deliberately excluded from OCR.
    let regions = [CGRect(x: 0.775, y: 0.10, width: 0.225, height: 0.77),
                   CGRect(x: 0, y: 0.23, width: 0.78, height: 0.12),
                   CGRect(x: 0, y: 0.60, width: 0.78, height: 0.12),
                   CGRect(x: 0.83, y: 0.88, width: 0.14, height: 0.08)]
    var observations: [[String: Any]] = []
    var scanned = 0
    for (index, region) in regions.enumerated() {
        let rect = CGRect(x: region.minX * Double(image.width), y: region.minY * Double(image.height),
                          width: region.width * Double(image.width), height: region.height * Double(image.height)).integral
            .intersection(CGRect(x: 0, y: 0, width: image.width, height: image.height))
        guard let crop = image.cropping(to: rect), let data = pixels(crop) else { continue }
        if let old = cache[index], old.width == image.width, old.height == image.height, sameContent(old.pixels, data) {
            observations += old.observations
            continue
        }
        scanned += 1
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]
        request.customWords = words
        request.minimumTextHeight = Float(0.006 / region.height)
        try VNImageRequestHandler(cgImage: crop, options: [:]).perform([request])
        let found: [[String: Any]] = (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let box = observation.boundingBox
            let x = (rect.minX + box.minX * rect.width) / Double(image.width)
            let y = (rect.minY + (1 - box.maxY) * rect.height) / Double(image.height)
            let w = box.width * rect.width / Double(image.width)
            let h = box.height * rect.height / Double(image.height)
            // The overlapping title bands must not duplicate deck-panel observations.
            if (index == 1 || index == 2) && x + w / 2 >= 0.775 { return nil }
            return ["text": candidate.string, "confidence": Double(candidate.confidence),
                    "x": x, "y": y, "w": w, "h": h]
        }
        cache[index] = RegionCache(pixels: data, width: image.width, height: image.height, observations: found)
        observations += found
    }
    let recognizedAt = Date()
    let latest = try await capture.image()
    // Check recognized text against the newest stream frame. A changed count or a
    // scrolled title is withheld immediately rather than displayed at an old position.
    observations = observations.filter { observation in
        guard let x = observation["x"] as? Double, let y = observation["y"] as? Double,
              let w = observation["w"] as? Double, let h = observation["h"] as? Double else { return false }
        let rect = CGRect(x: x * Double(image.width), y: y * Double(image.height),
                          width: w * Double(image.width), height: h * Double(image.height)).integral
        guard let before = image.cropping(to: rect), let after = latest.cropping(to: rect),
              let oldPixels = pixels(before), let newPixels = pixels(after) else { return false }
        if (observation["text"] as? String)?.lowercased() == "done" {
            return sameButtonText(oldPixels, newPixels)
        }
        return sameContent(oldPixels, newPixels)
    }
    try writePixels(latest, to: path)
    return ["schema": 1, "ok": true, "width": image.width, "height": image.height,
            "observations": observations, "regionsScanned": scanned,
            "captureMs": Int(capturedAt.timeIntervalSince(start) * 1000),
            "ocrMs": Int(recognizedAt.timeIntervalSince(capturedAt) * 1000),
            "writeMs": Int(Date().timeIntervalSince(recognizedAt) * 1000),
            "elapsedMs": Int(Date().timeIntervalSince(start) * 1000)]
}

let application = NSApplication.shared
application.setActivationPolicy(.accessory)
Task.detached {
    while let line = readLine() {
        do {
            guard let data = line.data(using: .utf8),
                  let request = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let path = request["path"] as? String else {
                emit(["schema": 1, "ok": false, "reason": "request"])
                continue
            }
            if #available(macOS 14.0, *) {
                emit(try await readFrame(path: path, words: request["words"] as? [String] ?? [],
                                         windowWidth: request["windowWidth"] as? Int ?? 0,
                                         windowHeight: request["windowHeight"] as? Int ?? 0))
            } else {
                emit(["schema": 1, "ok": false, "reason": "requires_macos_14"])
            }
        } catch {
            if #available(macOS 14.0, *) {
                await liveCapture?.stop()
                liveCapture = nil
            }
            cache.removeAll()
            emit(["schema": 1, "ok": false, "reason": "capture_or_ocr"])
        }
    }
    exit(0)
}
application.run()
