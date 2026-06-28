// Captures the MTG Arena window to a PNG via ScreenCaptureKit (CGWindowListCreateImage is
// obsoleted in macOS 15+). Needs Screen Recording permission. Prints JSON result.
//   window-capture [AppName] <outPath>
import ScreenCaptureKit
import CoreGraphics
import ImageIO
import Foundation

let args = CommandLine.arguments
let app = args.count > 2 ? args[1] : "MTGA"
let outPath = args.last ?? "/tmp/mtga-capture.png"

let sem = DispatchSemaphore(value: 0)
var result = "{\"captured\":false,\"reason\":\"unknown\"}"

func esc(_ s: String) -> String { s.replacingOccurrences(of: "\"", with: "'") }

Task {
    defer { sem.signal() }
    do {
        guard #available(macOS 14.0, *) else { result = "{\"captured\":false,\"reason\":\"needs macOS 14+\"}"; return }
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let win = content.windows
            .filter { $0.owningApplication?.applicationName == app && $0.frame.width > 100 }
            .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height })
        guard let w = win else { result = "{\"captured\":false,\"reason\":\"no \(app) window\"}"; return }
        let cfg = SCStreamConfiguration()
        cfg.width = Int(w.frame.width * 2)
        cfg.height = Int(w.frame.height * 2)
        let filter = SCContentFilter(desktopIndependentWindow: w)
        let img = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
        let url = URL(fileURLWithPath: outPath) as CFURL
        guard let dest = CGImageDestinationCreateWithURL(url, "public.png" as CFString, 1, nil) else {
            result = "{\"captured\":false,\"reason\":\"dest\"}"; return
        }
        CGImageDestinationAddImage(dest, img, nil)
        CGImageDestinationFinalize(dest)
        result = "{\"captured\":true,\"path\":\"\(outPath)\",\"w\":\(img.width),\"h\":\(img.height)}"
    } catch {
        result = "{\"captured\":false,\"reason\":\"\(esc(error.localizedDescription))\"}"
    }
}
sem.wait()
print(result)
