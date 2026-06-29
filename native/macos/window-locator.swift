// Prints the bounds of MTG Arena's main window as one line of JSON, e.g.
//   {"found":true,"x":97,"y":58,"w":1280,"h":748,"frontmost":true}
//   {"found":false}
// Uses CoreGraphics window services — reads window owner + bounds only, which needs
// NO Screen Recording / Accessibility permission (only window *titles* would). "frontmost"
// (whether Arena is the active app) comes from NSWorkspace, also permission-free, so the
// overlay can hide when the user tabs away from the game.
// Coordinates are global display points (top-left origin), matching AWT/Compose.
//
// Build: native/macos/build.sh   (universal arm64+x86_64)
import CoreGraphics
import AppKit
import Foundation

let target = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "MTGA"
var out = "{\"found\":false}"

let frontmost = NSWorkspace.shared.frontmostApplication?.localizedName == target

if let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] {
    var best: (Int, Int, Int, Int)? = nil
    var bestArea = 0
    for w in list {
        guard (w[kCGWindowOwnerName as String] as? String) == target else { continue }
        guard (w[kCGWindowLayer as String] as? Int) == 0 else { continue }  // normal window
        guard let b = w[kCGWindowBounds as String] as? [String: Any] else { continue }
        let x = Int(b["X"] as? Double ?? 0), y = Int(b["Y"] as? Double ?? 0)
        let ww = Int(b["Width"] as? Double ?? 0), hh = Int(b["Height"] as? Double ?? 0)
        let area = ww * hh
        if area > bestArea { bestArea = area; best = (x, y, ww, hh) }  // main = largest
    }
    if let (x, y, ww, hh) = best {
        out = "{\"found\":true,\"x\":\(x),\"y\":\(y),\"w\":\(ww),\"h\":\(hh),\"frontmost\":\(frontmost)}"
    }
}
print(out)
