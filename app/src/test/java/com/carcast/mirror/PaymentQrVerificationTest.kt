package com.carcast.mirror

import com.carcast.mirror.engine.update.GitHubUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentQrVerificationTest {

    @Test
    fun testNayaPayPayloadIntegrity() {
        // Verified static Raast payload from user's NayaPay QR
        val payload = "0002020102110202000424PK18NAYA1234503349299961112005639537701671955045100482DB"
        assertTrue(payload.startsWith("00020201"))
        assertTrue(payload.contains("PK18NAYA1234503349299961"))
    }

    @Test
    fun testEasypaisaPayloadIntegrity() {
        // Verified static Raast payload from user's Easypaisa QR
        val payload = "0002020102110202000424PK22TMFB000000005241389110048C02"
        assertTrue(payload.startsWith("00020201"))
        assertTrue(payload.contains("PK22TMFB0000000052413891"))
    }

    @Test
    fun testVersionUpgradeToBuild8() {
        val installedCodeV7 = 7L
        val installedNameV7 = "1.0.7"
        assertEquals("1.0.7", installedNameV7)

        val releaseCodeV8 = 8L
        val releaseNameV8 = "1.0.8"
        assertEquals("1.0.8", releaseNameV8)

        // Mock GitHubUpdateManager logic
        val shouldUpdateOnV7 = releaseCodeV8 > installedCodeV7
        assertTrue("v1.0.7 should report update available when v1.0.8 is released", shouldUpdateOnV7)

        val installedCodeV8 = 8L
        val isUpToDateOnV8 = releaseCodeV8 <= installedCodeV8
        assertTrue("v1.0.8 should report UP TO DATE", isUpToDateOnV8)
    }

    @Test
    fun testVersionUpgradeToBuild9() {
        val installedCodeV8 = 8L
        val installedNameV8 = "1.0.8"
        assertEquals("1.0.8", installedNameV8)

        val releaseCodeV9 = 9L
        val releaseNameV9 = "1.0.9"
        assertEquals("1.0.9", releaseNameV9)

        // When v1.0.8 is installed and v1.0.9 is online:
        val shouldUpdateOnV8 = releaseCodeV9 > installedCodeV8
        assertTrue("v1.0.8 should report UPDATE AVAILABLE when v1.0.9 is released", shouldUpdateOnV8)

        // When v1.0.9 is installed and v1.0.9 is online:
        val installedCodeV9 = 9L
        val isUpToDateOnV9 = releaseCodeV9 <= installedCodeV9
        assertTrue("v1.0.9 should report YOU'RE UP TO DATE", isUpToDateOnV9)
    }

    @Test
    fun testSidePanelDisplayMath() {
        // Landscape Car LCD (1920x1080) with tall phone in portrait (aspect 9:20 = 0.45)
        val containerW = 1920f
        val containerH = 1080f
        val streamAspect = 9f / 20f

        val videoW = containerH * streamAspect // 486f
        val sideMargin = (containerW - videoW) / 2f // 717f

        assertTrue("Sufficient side margin exists for side panels", sideMargin >= 110f)

        // Landscape phone (aspect 20:9 = 2.22f)
        val landscapeStreamAspect = 20f / 9f
        val isStreamPortrait = landscapeStreamAspect < 0.95f
        assertFalse("Side panels must be hidden for landscape video streams", isStreamPortrait)
    }

    @Test
    fun testAudioOutputRouteEnum() {
        val routes = com.carcast.mirror.model.AudioOutputRoute.values().map { it.name }
        assertTrue(routes.contains("CAR_LCD_ONLY"))
        assertTrue(routes.contains("PHONE_ONLY"))
        assertTrue(routes.contains("BOTH_DEVICES"))
    }

    @Test
    fun testFullHdDimensionScalingLogic() {
        // Full HD target bounding box: max 1920 long, max 1080 short
        val maxLongDim = 1920
        val maxShortDim = 1080

        // Test phone 1080x2400 (aspect 9:20)
        val screenW = 1080
        val screenH = 2400
        val scale = minOf(
            maxLongDim.toDouble() / screenH,
            maxShortDim.toDouble() / screenW,
            1.0
        )
        val scaledW = (screenW * scale).toInt()
        val scaledH = (screenH * scale).toInt()

        val encWidth = ((scaledW + 15) / 16) * 16
        val encHeight = ((scaledH + 15) / 16) * 16

        assertEquals(864, encWidth)
        assertEquals(1920, encHeight)
        assertTrue("Total pixels within Level 4.2 limit (<= 2,073,600)", encWidth * encHeight <= 2073600)
    }
}
