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

    private val mainQueue: Pointer? =
        libSystem?.let { runCatching { it.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull() }

    private fun sel(name: String): Pointer? = selRegFn?.invokePointer(arrayOf<Any>(name))


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

            return runCatching(apply).getOrDefault(false)
        }


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


        pending.add(work)
        return try {
            dispatch.invokeVoid(arrayOf<Any>(queue, Pointer.createConstant(0L), work))


            done.await(2, TimeUnit.SECONDS) && ok.get()
        } catch (t: Throwable) {
            Log.warn(TAG, "click-through dispatch failed: $t")
            pending.remove(work)
            releaseFn?.invokeVoid(arrayOf<Any>(nsWindow))
            false
        }
    }
}
