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
        guard (w[kCGWindowLayer as String] as? Int) == 0 else { continue }
        guard let b = w[kCGWindowBounds as String] as? [String: Any] else { continue }
        let x = Int(b["X"] as? Double ?? 0), y = Int(b["Y"] as? Double ?? 0)
        let ww = Int(b["Width"] as? Double ?? 0), hh = Int(b["Height"] as? Double ?? 0)
        let area = ww * hh
        if area > bestArea { bestArea = area; best = (x, y, ww, hh) }
    }
    if let (x, y, ww, hh) = best {
        out = "{\"found\":true,\"x\":\(x),\"y\":\(y),\"w\":\(ww),\"h\":\(hh),\"frontmost\":\(frontmost)}"
    }
}
print(out)
