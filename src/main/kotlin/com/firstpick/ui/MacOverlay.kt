package com.firstpick.ui

import androidx.compose.ui.awt.ComposeWindow
import com.firstpick.core.Log
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MacOverlay {
    private const val TAG = "MacOverlay"
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Callback>()

    private val objc: NativeLibrary? =
        if (isMac) runCatching { NativeLibrary.getInstance("objc") }.getOrNull() else null
    private val msgSend: Function? = objc?.let { runCatching { it.getFunction("objc_msgSend") }.getOrNull() }
    private val selRegFn: Function? = objc?.let { runCatching { it.getFunction("sel_registerName") }.getOrNull() }

    private val retainFn: Function? = objc?.let { runCatching { it.getFunction("objc_retain") }.getOrNull() }
    private val releaseFn: Function? = objc?.let { runCatching { it.getFunction("objc_release") }.getOrNull() }

    private val libSystem: NativeLibrary? =
        if (isMac) runCatching { NativeLibrary.getInstance("System") }.getOrNull() else null
    private val dispatchAsyncF: Function? =
        libSystem?.let { runCatching { it.getFunction("dispatch_async_f") }.getOrNull() }
    // dispatch_get_main_queue() is a macro for &_dispatch_main_q — the symbol address IS the queue.
    private val mainQueue: Pointer? =
        libSystem?.let { runCatching { it.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull() }

    private fun sel(name: String): Pointer? = selRegFn?.invokePointer(arrayOf<Any>(name))

    /**
     * Makes the window invisible to mouse events so clicks land on whatever is beneath it.
     * Targets the NSWindow through the Compose window handle (no title lookup) and runs the
     * AppKit call on the main queue. Returns true only after reading the property back — a
     * cover-the-game overlay that silently keeps stealing clicks is worse than no overlay.
     */
    fun setClickThrough(window: ComposeWindow, enabled: Boolean): Boolean {
        if (!isMac) return false
        val send = msgSend ?: return false
        val setSel = sel("setIgnoresMouseEvents:") ?: return false
        val getSel = sel("ignoresMouseEvents") ?: return false
        val handle = runCatching { window.windowHandle }.getOrDefault(0L)
        if (handle == 0L) return false
        val nsWindow = Pointer(handle)

        val apply = {
            send.invokeVoid(arrayOf<Any>(nsWindow, setSel, (if (enabled) 1 else 0).toByte()))
            (send.invokeInt(arrayOf<Any>(nsWindow, getSel)) and 0xFF != 0) == enabled
        }

        val queue = mainQueue
        val dispatch = dispatchAsyncF
        if (queue == null || dispatch == null) {
            // No libdispatch (shouldn't happen on macOS) — apply directly and hope AppKit tolerates it.
            return runCatching(apply).getOrDefault(false)
        }

        // The window can be disposed (and the NSWindow freed) while the block is still queued;
        // retain it until the block has run.
        retainFn?.invokePointer(arrayOf<Any>(nsWindow))
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val work = object : Callback {
            @Suppress("unused")
            fun callback(context: Pointer?) {
                runCatching { ok.set(apply()) }
                releaseFn?.invokeVoid(arrayOf<Any>(nsWindow))
                done.countDown()
                pending.remove(this)
            }
        }
        // JNA references callbacks weakly; the queued block may outlive our await timeout, so the
        // callback must stay strongly reachable until it has actually fired.
        pending.add(work)
        return try {
            dispatch.invokeVoid(arrayOf<Any>(queue, Pointer.createConstant(0L), work))
            // The AWT main thread runs the AppKit run loop, which drains the main queue; a stall
            // here means the app is wedged anyway, so a short wait is safe.
            done.await(2, TimeUnit.SECONDS) && ok.get()
        } catch (t: Throwable) {
            Log.warn(TAG, "click-through dispatch failed: $t")
            pending.remove(work)
            releaseFn?.invokeVoid(arrayOf<Any>(nsWindow))
            false
        }
    }
}
