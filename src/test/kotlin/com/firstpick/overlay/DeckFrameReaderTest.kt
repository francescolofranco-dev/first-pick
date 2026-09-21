package com.firstpick.overlay

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeckFrameReaderTest {
    @Test
    fun reusesWorkerAndDeletesFrameFilesAfterEachResponse() {
        val dir = Files.createTempDirectory("fp-deck-reader").toFile()
        try {
            val png = dir.resolve("image.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png)
            val pid = dir.resolve("pid")
            val paths = dir.resolve("paths")
            val helper = dir.resolve("helper")
            helper.writeText("""
                |#!/usr/bin/env python3
                |import os,json,sys,shutil
                |open('${pid.path}', 'a').write(str(os.getpid())+'\n')
                |for line in sys.stdin:
                |    req=json.loads(line)
                |    open('${paths.path}', 'a').write(req['path']+'\n')
                |    shutil.copyfile('${png.path}',req['path'])
                |    print(json.dumps({'schema':1,'ok':True,'width':2,'height':2,'observations':[]}),flush=True)
            """.trimMargin()+"\n")
            helper.setExecutable(true)
            DeckFrameReader(helper).use { reader ->
                repeat(2) { assertNotNull(reader.read(listOf("Card Name"))) }
            }
            assertEquals(1, pid.readLines().size)
            paths.readLines().forEach { assertFalse(java.io.File(it).exists()) }
            assertFalse(ProcessHandle.of(pid.readText().trim().toLong()).map { it.isAlive }.orElse(false))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun hungWorkerIsKilledAndTheNextReadCanRestart() {
        val dir = Files.createTempDirectory("fp-deck-reader-timeout").toFile()
        try {
            val pid = dir.resolve("pid")
            val helper = dir.resolve("helper")
            helper.writeText("#!/bin/sh\nprintf '%s' \"$$\" > '${pid.path}'\nexec sleep 30\n")
            helper.setExecutable(true)
            DeckFrameReader(helper, timeoutMs = 3_000).use { reader ->
                repeat(2) {
                    assertNull(reader.read(emptyList()))
                    assertFalse(ProcessHandle.of(pid.readText().toLong()).map { it.isAlive }.orElse(false))
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
