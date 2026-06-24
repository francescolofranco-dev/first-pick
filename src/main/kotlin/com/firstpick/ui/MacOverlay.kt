package com.firstpick.ui

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Best-effort macOS click-through for FirstPick's own overlay windows.
 *
 * Compose/AWT exposes no API for `NSWindow.ignoresMouseEvents`, so we reach the
 * Objective-C runtime directly via JNA. We deliberately avoid JDK internals
 * (`sun.lwawt.*`): the JVM *is* the running `NSApplication`, so we walk
 * `[NSApp windows]`, find ours by title, and flip the flag. When a window ignores
 * mouse events, clicks fall through to whatever is beneath it (Arena) — exactly
 * what an info overlay wants. (An earlier dead-end tried to modify *Arena's*
 * window from a separate process, which macOS forbids; this modifies our own.)
 *
 * Every path is wrapped so any failure (non-mac, JNA missing, selector/ABI issue)
 * is a silent no-op — the overlay still renders, just without pass-through.
 *
 * Threading caveat: AppKit prefers its main thread for window mutations. We call
 * this from a Compose `LaunchedEffect` (the AWT EDT); on macOS the EDT is not
 * literally AppKit's main thread, but flipping a single window flag from it is
 * reliable in practice. Because everything is fail-safe, the worst case is a
 * no-op, never a crash. Verify behavior against a live Arena window.
 */
object MacOverlay {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    private val objc: NativeLibrary? =
        if (isMac) runCatching { NativeLibrary.getInstance("objc") }.getOrNull() else null
    private val msgSend: Function? = objc?.let { runCatching { it.getFunction("objc_msgSend") }.getOrNull() }
    private val getClassFn: Function? = objc?.let { runCatching { it.getFunction("objc_getClass") }.getOrNull() }
    private val selRegFn: Function? = objc?.let { runCatching { it.getFunction("sel_registerName") }.getOrNull() }

    /** Whether native interop is wired up on this platform. */
    val available: Boolean get() = msgSend != null && getClassFn != null && selRegFn != null

    private fun cls(name: String): Pointer? = getClassFn?.invokePointer(arrayOf<Any>(name))
    private fun sel(name: String): Pointer? = selRegFn?.invokePointer(arrayOf<Any>(name))

    /**
     * Toggle click-through on the overlay window whose `NSWindow.title == [title]`.
     * @return true if a matching window was found and updated.
     */
    fun setClickThrough(title: String, enabled: Boolean): Boolean {
        val send = msgSend ?: return false
        if (!available) return false
        return runCatching {
            val appClass = cls("NSApplication") ?: return false
            val sharedSel = sel("sharedApplication") ?: return false
            val nsApp = send.invokePointer(arrayOf<Any>(appClass, sharedSel)) ?: return false
            val windows = send.invokePointer(arrayOf<Any>(nsApp, sel("windows") ?: return false)) ?: return false
            val count = send.invokeLong(arrayOf<Any>(windows, sel("count") ?: return false))

            val objectAtIndex = sel("objectAtIndex:") ?: return false
            val titleSel = sel("title") ?: return false
            val utf8Sel = sel("UTF8String") ?: return false
            val setSel = sel("setIgnoresMouseEvents:") ?: return false

            for (i in 0 until count) {
                val win = send.invokePointer(arrayOf<Any>(windows, objectAtIndex, i)) ?: continue
                val nsTitle = send.invokePointer(arrayOf<Any>(win, titleSel)) ?: continue
                val utf8 = send.invokePointer(arrayOf<Any>(nsTitle, utf8Sel)) ?: continue
                if (utf8.getString(0, "UTF-8") == title) {
                    // BOOL is a 1-byte value in the Obj-C ABI.
                    send.invokeVoid(arrayOf<Any>(win, setSel, (if (enabled) 1 else 0).toByte()))
                    return true
                }
            }
            false
        }.getOrDefault(false)
    }
}
