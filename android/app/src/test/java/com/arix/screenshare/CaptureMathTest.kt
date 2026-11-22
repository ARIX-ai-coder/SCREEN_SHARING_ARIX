package com.arix.screenshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMathTest {
    @Test fun portraitPhoneIsScaledKeepingAspect() {
        val (w, h) = CaptureMath.scaledSize(1080, 2400, 1280)
        assertEquals(1280, h)
        assertEquals(576, w)
    }

    @Test fun smallScreenIsNotUpscaled() = assertEquals(720 to 1280, CaptureMath.scaledSize(720, 1280, 1920))

    @Test fun resultsAreEvenAndPositive() {
        for ((w, h) in listOf(1081 to 2401, 1 to 1, 1439 to 3119)) {
            val (a, b) = CaptureMath.scaledSize(w, h, 1280)
            assertTrue(a >= 2 && b >= 2 && a % 2 == 0 && b % 2 == 0)
        }
    }

    @Test fun qualityLookupFallsBackToBalanced() {
        assertEquals(Quality.BALANCED, Quality.fromName(null))
        assertEquals(Quality.HIGH, Quality.fromName("HIGH"))
        assertEquals(Quality.BALANCED, Quality.fromName("nonsense"))
    }
}
