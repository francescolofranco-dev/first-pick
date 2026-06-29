// Captures the MTG Arena window to a PNG via ScreenCaptureKit (CGWindowListCreateImage is
// obsoleted in macOS 15+). Needs Screen Recording permission. Prints JSON result.
//   window-capture [AppName] <outPath>
//
// SCK's real capture path talks to the WindowServer. A plain CLI tool is a background-only
// process with no WindowServer connection, so once capture is actually permitted it aborts
// with `CGS_REQUIRE_INIT (did_initialize)`. Becoming an .accessory NSApplication establishes
// the connection, and running the main run loop lets SCK's async capture pump to completion.
import ScreenCaptureKit
import CoreGraphics
import ImageIO
import AppKit
import Foundation

let args = CommandLine.arguments
let app = args.count > 2 ? args[1] : "MTGA"
let outPath = args.last ?? "/tmp/mtga-capture.png"

func esc(_ s: String) -> String { s.replacingOccurrences(of: "\"", with: "'") }
func finish(_ s: String) -> Never { print(s); exit(0) }

let nsApp = NSApplication.shared
nsApp.setActivationPolicy(.accessory)

Task {
    do {
        guard #available(macOS 14.0, *) else { finish("{\"captured\":false,\"reason\":\"needs macOS 14+\"}") }
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let win = content.windows
            .filter { $0.owningApplication?.applicationName == app && $0.frame.width > 100 }
            .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height })
        guard let w = win else { finish("{\"captured\":false,\"reason\":\"no \(app) window\"}") }
        let cfg = SCStreamConfiguration()
        cfg.width = Int(w.frame.width * 2)
        cfg.height = Int(w.frame.height * 2)
        let filter = SCContentFilter(desktopIndependentWindow: w)
        let img = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
        let url = URL(fileURLWithPath: outPath) as CFURL
        guard let dest = CGImageDestinationCreateWithURL(url, "public.png" as CFString, 1, nil) else {
            finish("{\"captured\":false,\"reason\":\"dest\"}")
        }
        CGImageDestinationAddImage(dest, img, nil)
        CGImageDestinationFinalize(dest)
        finish("{\"captured\":true,\"path\":\"\(outPath)\",\"w\":\(img.width),\"h\":\(img.height)}")
    } catch {
        finish("{\"captured\":false,\"reason\":\"\(esc(error.localizedDescription))\"}")
    }
}
nsApp.run()
