package com.example.app // TODO: your package

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTest {
    @Test fun letGoOnTheRightHalfSnapsRight() {
        val s = bubbleSnap(centerX = 1500f, y = 300, size = 100, left = 0, right = 2000, minY = 40, maxY = 900)
        assertEquals(BubbleSpot(1900, 300, true), s)
    }

    @Test fun letGoOnTheLeftHalfSnapsLeft() {
        assertEquals(BubbleSpot(0, 300, false), bubbleSnap(400f, 300, 100, 0, 2000, 40, 900))
    }

    @Test fun heightStaysInsideTheMargins() {
        assertEquals(40, bubbleSnap(400f, -50, 100, 0, 2000, 40, 900).y)
        assertEquals(900, bubbleSnap(400f, 5000, 100, 0, 2000, 40, 900).y)
    }

    @Test fun staysInsideTheNavigationButtonsAndCutout() {
        // Landscape, 3-button navigation on the right (120 px), a camera cutout on the left (80 px).
        assertEquals(BubbleSpot(1780, 300, true), bubbleSnap(1700f, 300, 100, 80, 1880, 40, 900))
        assertEquals(BubbleSpot(80, 300, false), bubbleSnap(200f, 300, 100, 80, 1880, 40, 900))
    }

    @Test fun hideTargetCatchesOnlyNearbyDrops() {
        assertTrue(overHideTarget(1000f, 1000f, 1030f, 1030f, 56f))
        assertFalse(overHideTarget(1000f, 1000f, 1100f, 1000f, 56f))
    }
}
