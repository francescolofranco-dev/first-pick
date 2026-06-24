import Cocoa

let options = CGWindowListOption(arrayLiteral: .excludeDesktopElements, .optionOnScreenOnly)
if let windowInfoList = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] {
    for window in windowInfoList {
        if let ownerName = window[kCGWindowOwnerName as String] as? String, ownerName == "MTGA" {
            if let boundsDict = window[kCGWindowBounds as String] as? [String: Any] {
                let x = boundsDict["X"] as? Double ?? 0
                let y = boundsDict["Y"] as? Double ?? 0
                let w = boundsDict["Width"] as? Double ?? 0
                let h = boundsDict["Height"] as? Double ?? 0
                print("\(Int(x)),\(Int(y)),\(Int(w)),\(Int(h))")
            }
            break
        }
    }
}
