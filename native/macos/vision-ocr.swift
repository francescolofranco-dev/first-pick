import Foundation
import ImageIO
import Vision

private let schema = 1

private func emit(_ object: [String: Any], exitCode: Int32 = 0) -> Never {
    if let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
       let line = String(data: data, encoding: .utf8) {
        print(line)
    } else {
        print("{\"schema\":1,\"ok\":false,\"error\":\"json encoding failed\"}")
    }
    exit(exitCode)
}

private func fail(_ message: String) -> Never {
    emit(["schema": schema, "ok": false, "error": message], exitCode: 1)
}

guard CommandLine.arguments.count == 2 else {
    fail("usage: vision-ocr <image>")
}

let imageURL = URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL
guard let source = CGImageSourceCreateWithURL(imageURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fail("cannot decode image")
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
request.recognitionLanguages = ["en-US"]
request.minimumTextHeight = 0.006

do {
    try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
} catch {
    fail("Vision request failed: \(error.localizedDescription)")
}

let observations: [[String: Any]] = (request.results ?? []).compactMap { observation in
    guard let candidate = observation.topCandidates(1).first else { return nil }
    let box = observation.boundingBox

    // Vision uses a bottom-left origin. Everything after this helper uses a
    // top-left origin so coordinates line up directly with Compose/AWT.
    let x = max(0.0, min(1.0, box.minX))
    let y = max(0.0, min(1.0, 1.0 - box.maxY))
    let width = max(0.0, min(1.0 - x, box.width))
    let height = max(0.0, min(1.0 - y, box.height))
    guard width > 0.0, height > 0.0 else { return nil }

    return [
        "text": candidate.string,
        "confidence": Double(candidate.confidence),
        "x": x,
        "y": y,
        "w": width,
        "h": height,
    ]
}.sorted {
    let ay = $0["y"] as? Double ?? 0.0
    let by = $1["y"] as? Double ?? 0.0
    if abs(ay - by) > 0.0005 { return ay < by }
    return ($0["x"] as? Double ?? 0.0) < ($1["x"] as? Double ?? 0.0)
}

emit([
    "schema": schema,
    "ok": true,
    "width": image.width,
    "height": image.height,
    "observations": observations,
])
