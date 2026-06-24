import Cocoa

let appName = "FirstPick"
let windowTitle = "FirstPick Overlay"

// We can't easily modify another process's NSWindow from outside without code injection.
// Wait. macOS security prevents modifying NSWindow of other processes.
