package com.firstpick.ui

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

object MacOverlay {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    private val objc: NativeLibrary? =
        if (isMac) runCatching { NativeLibrary.getInstance("objc") }.getOrNull() else null
    private val msgSend: Function? = objc?.let { runCatching { it.getFunction("objc_msgSend") }.getOrNull() }
    private val getClassFn: Function? = objc?.let { runCatching { it.getFunction("objc_getClass") }.getOrNull() }
    private val selRegFn: Function? = objc?.let { runCatching { it.getFunction("sel_registerName") }.getOrNull() }

    val available: Boolean get() = msgSend != null && getClassFn != null && selRegFn != null

    private fun cls(name: String): Pointer? = getClassFn?.invokePointer(arrayOf<Any>(name))
    private fun sel(name: String): Pointer? = selRegFn?.invokePointer(arrayOf<Any>(name))

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
                    send.invokeVoid(arrayOf<Any>(win, setSel, (if (enabled) 1 else 0).toByte()))
                    return true
                }
            }
            false
        }.getOrDefault(false)
    }
}
