package com.firstpick.overlay

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OverlayPollingTest {
    @Test fun foregroundReadsReachTheNextHoverFrameWithoutAddingWorkTimeToTheInterval() = runTest {
        val starts = mutableListOf<Long>()
        backgroundScope.launch {
            runOverlayReadLoop({ true }, { testScheduler.currentTime }) {
                starts += testScheduler.currentTime
                delay(8)
            }
        }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0L, 33L, 66L, 99L), starts)
    }
    @Test fun backgroundReadsStayThrottled() = runTest {
        val starts = mutableListOf<Long>()
        backgroundScope.launch {
            runOverlayReadLoop({ false }, { testScheduler.currentTime }) {
                starts += testScheduler.currentTime
                delay(8)
            }
        }
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf(0L, 400L), starts)
    }

    @Test fun slowReadsFinishBeforeAnotherReadStartsWithoutBuildingABacklog() = runTest {
        val starts = mutableListOf<Long>()
        backgroundScope.launch {
            runOverlayReadLoop({ true }, { testScheduler.currentTime }) {
                starts += testScheduler.currentTime
                delay(50)
            }
        }
        advanceTimeBy(103)
        runCurrent()
        assertEquals(listOf(0L, 51L, 102L), starts)
    }

}
