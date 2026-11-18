package com.carcast.mirror

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidStreamingAndAspectRatioTest {

    // Helper mirroring ScreenCaptureService host/port parsing logic
    private fun parseHostAndPort(rawInput: String): Pair<String, Int> {
        var clean = rawInput.trim()
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        var port = 8080
        if (clean.contains(":")) {
            val parts = clean.split(":")
            clean = parts[0].trim()
            port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
        }
        return Pair(clean, port)
    }

    // Helper mirroring ScreenCaptureService aspect-preserving resolution calculation
    private fun calculateEncodingDimensions(screenW: Int, screenH: Int): Pair<Int, Int> {
        val encWidth: Int
        val encHeight: Int
        if (screenW <= screenH) {
            // Portrait mode: fixed width 720, height scaled to aspect ratio
            encWidth = 720
            val rawH = (720.0 * screenH / screenW).roundToInt()
            encHeight = ((rawH + 15) / 16) * 16 // Nearest multiple of 16
        } else {
            // Landscape mode: fixed height 720, width scaled to aspect ratio
            encHeight = 720
            val rawW = (720.0 * screenW / screenH).roundToInt()
            encWidth = ((rawW + 15) / 16) * 16 // Nearest multiple of 16
        }
        return Pair(encWidth, encHeight)
    }

    // Helper mirroring VideoPipeline FIT_CENTER viewport calculation
    private fun calculateFitCenterViewport(videoW: Int, videoH: Int, winW: Int, winH: Int): IntArray {
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val winAspect = winW.toFloat() / winH.toFloat()

        val vpW: Int
        val vpH: Int
        val vpX: Int
        val vpY: Int

        if (videoAspect < winAspect) {
            // Video is narrower/taller than window (portrait on landscape) -> Pillarbox
            vpH = winH
            vpW = max(1, (winH * videoAspect).roundToInt())
            vpX = (winW - vpW) / 2
            vpY = 0
        } else {
            // Video is wider than window -> Letterbox
            vpW = winW
            vpH = max(1, (winW / videoAspect).roundToInt())
            vpX = 0
            vpY = (winH - vpH) / 2
        }
        return intArrayOf(vpX, vpY, vpW, vpH)
    }

    @Test
    fun testIpParsingAndSanitization() {
        // Plain IP
        val (h1, p1) = parseHostAndPort("192.168.1.100")
        assertEquals("192.168.1.100", h1)
        assertEquals(8080, p1)

        // With port
        val (h2, p2) = parseHostAndPort("192.168.1.100:9090")
        assertEquals("192.168.1.100", h2)
        assertEquals(9090, p2)

        // With ws:// scheme and trailing slash
        val (h3, p3) = parseHostAndPort("ws://192.168.43.1:8080/")
        assertEquals("192.168.43.1", h3)
        assertEquals(8080, p3)

        // With http:// scheme
        val (h4, p4) = parseHostAndPort("http://10.0.0.5:8080")
        assertEquals("10.0.0.5", h4)
        assertEquals(8080, p4)

        // With whitespace
        val (h5, p5) = parseHostAndPort("   192.168.228.11   ")
        assertEquals("192.168.228.11", h5)
        assertEquals(8080, p5)
    }

    @Test
    fun testEncodingDimensionsPreserveAspectRatioAndMultipleOf16() {
        // Typical portrait smartphone (1080x2400, 20:9)
        val (pw1, ph1) = calculateEncodingDimensions(1080, 2400)
        assertEquals(720, pw1)
        assertEquals(1600, ph1) // 720 * (2400/1080) = 1600.0, multiple of 16
        assertEquals(0, pw1 % 16)
        assertEquals(0, ph1 % 16)

        // 16:9 portrait phone (1080x1920)
        val (pw2, ph2) = calculateEncodingDimensions(1080, 1920)
        assertEquals(720, pw2)
        assertEquals(1280, ph2)
        assertEquals(0, pw2 % 16)
        assertEquals(0, ph2 % 16)

        // Landscape phone or tablet (2400x1080)
        val (lw1, lh1) = calculateEncodingDimensions(2400, 1080)
        assertEquals(1600, lw1)
        assertEquals(720, lh1)
        assertEquals(0, lw1 % 16)
        assertEquals(0, lh1 % 16)

        // 16:9 landscape (1920x1080)
        val (lw2, lh2) = calculateEncodingDimensions(1920, 1080)
        assertEquals(1280, lw2)
        assertEquals(720, lh2)
        assertEquals(0, lw2 % 16)
        assertEquals(0, lh2 % 16)
    }

    @Test
    fun testFitCenterViewportCalculation_PortraitOnLandscapeCarScreen() {
        val carW = 1920
        val carH = 1080

        // iPhone / Android Portrait stream (1080x1920, 9:16 aspect ratio)
        val vp = calculateFitCenterViewport(1080, 1920, carW, carH)
        val vpX = vp[0]
        val vpY = vp[1]
        val vpW = vp[2]
        val vpH = vp[3]

        // Height must match the screen height completely
        assertEquals(carH, vpH)
        // Width must be fitted: 1080 * (9/16) = 608px (approx 607.5)
        assertEquals(608, vpW)
        // Centered horizontally: (1920 - 608) / 2 = 656
        assertEquals((carW - vpW) / 2, vpX)
        assertEquals(0, vpY)
        // Ensure sidebars exist (dark pillarbox)
        assertTrue("Left sidebar must be present", vpX > 0)
        assertTrue("Right sidebar must be present", carW - (vpX + vpW) > 0)
    }

    @Test
    fun testFitCenterViewportCalculation_LandscapeOnLandscapeCarScreen() {
        val carW = 1920
        val carH = 1080

        // 16:9 landscape stream (1920x1080 or 1280x720)
        val vp = calculateFitCenterViewport(1920, 1080, carW, carH)
        assertEquals(0, vp[0])
        assertEquals(0, vp[1])
        assertEquals(1920, vp[2])
        assertEquals(1080, vp[3])
    }

    @Test
    fun testJsonMetaParsing() {
        val metaJson = """{"type":"meta","width":720,"height":1600,"fps":60}"""
        val widthMatch = Regex(""""width"\s*:\s*(\d+)""").find(metaJson)
        val heightMatch = Regex(""""height"\s*:\s*(\d+)""").find(metaJson)
        val w = widthMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val h = heightMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        assertEquals(720, w)
        assertEquals(1600, h)
    }

    @Test
    fun testVersionComparisonLogic() {
        fun isVersionNewer(current: String, latest: String): Boolean {
            val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")
            val cleanLatest = latest.trim().removePrefix("v").removePrefix("V")

            val currParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        // Case 1: When same version (after update installed) -> must report false ("Up to Date")
        org.junit.Assert.assertFalse(isVersionNewer("1.0.0", "1.0.0"))
        org.junit.Assert.assertFalse(isVersionNewer("1.0.0", "v1.0.0"))
        org.junit.Assert.assertFalse(isVersionNewer("1.0.1", "v1.0.1"))
        org.junit.Assert.assertFalse(isVersionNewer("2.0.0", "2.0.0"))

        // Case 2: When GitHub has a newer version -> must report true ("Update Available")
        org.junit.Assert.assertTrue(isVersionNewer("1.0.0", "1.0.1"))
        org.junit.Assert.assertTrue(isVersionNewer("1.0.0", "v1.0.1"))
        org.junit.Assert.assertTrue(isVersionNewer("1.0.9", "1.1.0"))
        org.junit.Assert.assertTrue(isVersionNewer("1.0.0", "v2.0.0"))

        // Case 3: When installed version is newer than GitHub release -> must report false ("Up to Date")
        org.junit.Assert.assertFalse(isVersionNewer("1.1.0", "1.0.9"))
        org.junit.Assert.assertFalse(isVersionNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun testReverseTouchJsonParsing() {
        val touchJson = """{"type":"touch","action":"move","x":0.452,"y":0.781,"time":1700000000000}"""
        val actionMatch = Regex(""""action"\s*:\s*"(\w+)"""").find(touchJson)
        val xMatch = Regex(""""x"\s*:\s*([0-9.]+)""").find(touchJson)
        val yMatch = Regex(""""y"\s*:\s*([0-9.]+)""").find(touchJson)

        val action = actionMatch?.groupValues?.getOrNull(1)
        val x = xMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val y = yMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()

        assertEquals("move", action)
        assertEquals(0.452f, x ?: 0f, 0.001f)
        assertEquals(0.781f, y ?: 0f, 0.001f)
    }

    @Test
    fun testGestureClassificationLogic() {
        fun classifyGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): String {
            val dx = endX - startX
            val dy = endY - startY
            val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

            return if (distance < 25f && durationMs < 400L) {
                "TAP"
            } else if (distance < 25f && durationMs >= 400L) {
                "LONG_PRESS"
            } else {
                "SWIPE_OR_DRAG"
            }
        }

        // Tap: small movement, short duration
        assertEquals("TAP", classifyGesture(100f, 100f, 105f, 102f, 80L))
        assertEquals("TAP", classifyGesture(500f, 500f, 500f, 500f, 150L))

        // Long Press: small movement, held for > 400ms
        assertEquals("LONG_PRESS", classifyGesture(100f, 100f, 102f, 103f, 500L))
        assertEquals("LONG_PRESS", classifyGesture(300f, 400f, 305f, 402f, 750L))

        // Swipe / Scroll: larger distance
        assertEquals("SWIPE_OR_DRAG", classifyGesture(100f, 100f, 100f, 450f, 250L)) // Vertical swipe
        assertEquals("SWIPE_OR_DRAG", classifyGesture(100f, 100f, 400f, 100f, 200L)) // Horizontal swipe
    }

    @Test
    fun testRotationDimensionTransitions() {
        // Phone rotated to Landscape: 1080x2400 -> 2400x1080
        val (portraitW, portraitH) = calculateEncodingDimensions(1080, 2400)
        assertEquals(720, portraitW)
        assertEquals(1600, portraitH)

        val (landscapeW, landscapeH) = calculateEncodingDimensions(2400, 1080)
        assertEquals(1600, landscapeW)
        assertEquals(720, landscapeH)

        // Multiple of 16 verification
        assertEquals(0, portraitW % 16)
        assertEquals(0, portraitH % 16)
        assertEquals(0, landscapeW % 16)
        assertEquals(0, landscapeH % 16)
    }

    @Test
    fun testReverseTouchRotationCoordinateMapping() {
        fun mapCoordinates(xRatio: Float, yRatio: Float, rotationDegrees: Int): Pair<Float, Float> {
            var x = xRatio
            var y = yRatio
            when (rotationDegrees) {
                90 -> {
                    val oldX = x
                    x = y
                    y = 1f - oldX
                }
                180 -> {
                    x = 1f - x
                    y = 1f - y
                }
                270 -> {
                    val oldX = x
                    x = 1f - y
                    y = oldX
                }
            }
            return Pair(x, y)
        }

        // 0 degrees (normal)
        val (x0, y0) = mapCoordinates(0.3f, 0.7f, 0)
        assertEquals(0.3f, x0, 0.001f)
        assertEquals(0.7f, y0, 0.001f)

        // 180 degrees (upside down)
        val (x180, y180) = mapCoordinates(0.3f, 0.7f, 180)
        assertEquals(0.7f, x180, 0.001f)
        assertEquals(0.3f, y180, 0.001f)
    }

    @Test
    fun testQualityProfilesAndDimensions() {
        val autoProfile = com.carcast.mirror.model.QualityProfile.AUTO
        val hdProfile = com.carcast.mirror.model.QualityProfile.HD
        val fhdProfile = com.carcast.mirror.model.QualityProfile.FULL_HD
        val smoothProfile = com.carcast.mirror.model.QualityProfile.SMOOTH

        assertEquals(1080, autoProfile.baseDim)
        assertEquals(720, hdProfile.baseDim)
        assertEquals(1080, fhdProfile.baseDim)
        assertEquals(720, smoothProfile.baseDim)
        assertEquals(60, smoothProfile.targetFps)
        assertEquals(30, hdProfile.targetFps)

        // Verify aspect ratio preservation with multiple of 16 for Full HD (e.g. 1080x2400 phone)
        val screenW = 1080
        val screenH = 2400
        val targetDim = fhdProfile.baseDim
        val rawH = (targetDim.toDouble() * screenH / screenW).roundToInt()
        val encH = ((rawH + 15) / 16) * 16
        val alignedTargetDim = ((targetDim + 15) / 16) * 16
        assertEquals(0, encH % 16)
        assertEquals(0, alignedTargetDim % 16)
    }

    @Test
    fun testAudioPacketDemuxCollisionFree() {
        // Audio packets start with ASCII 'A', 'U' (0x41, 0x55)
        val audioPacket = byteArrayOf('A'.code.toByte(), 'U'.code.toByte(), 0x12, 0x34)
        val isAudio = audioPacket.size >= 2 && audioPacket[0] == 'A'.code.toByte() && audioPacket[1] == 'U'.code.toByte()
        assertTrue("Audio packet must be recognized by AU prefix", isAudio)

        // H.264 NAL header with 4-byte start code: 00 00 00 01
        val h264FourByte = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        val isH264FourByteAudio = h264FourByte.size >= 2 && h264FourByte[0] == 'A'.code.toByte() && h264FourByte[1] == 'U'.code.toByte()
        assertTrue("H.264 4-byte NAL must never be identified as audio", !isH264FourByteAudio)

        // H.264 NAL header with 3-byte start code: 00 00 01
        val h264ThreeByte = byteArrayOf(0x00, 0x00, 0x01, 0x65)
        val isH264ThreeByteAudio = h264ThreeByte.size >= 2 && h264ThreeByte[0] == 'A'.code.toByte() && h264ThreeByte[1] == 'U'.code.toByte()
        assertTrue("H.264 3-byte NAL must never be identified as audio", !isH264ThreeByteAudio)
    }



    @Test
    fun testFitVsFillScreenScaling() {
        // Container: Car LCD 1024x600 (widescreen, aspect 1.7067)
        val containerW = 1024f
        val containerH = 600f
        val containerAspect = containerW / containerH

        // Phone: Portrait 1080x1920 (aspect 0.5625)
        val phoneAspect = 1080f / 1920f

        // FIT mode: show whole phone screen centered with black sidebars
        // Since phoneAspect < containerAspect, fit to height first
        val fitMatchHeightFirst = phoneAspect < containerAspect
        assertTrue("In FIT mode with portrait phone on landscape screen, match height first", fitMatchHeightFirst)
        val fitHeight = containerH
        val fitWidth = fitHeight * phoneAspect
        assertTrue("Fitted width ($fitWidth) must be less than screen width ($containerW)", fitWidth < containerW)
        assertEquals(600f, fitHeight, 0.001f)
        assertEquals(337.5f, fitWidth, 0.001f)

        // FILL mode: center crop to fill whole screen without distortion
        // Since phoneAspect < containerAspect, fit to width first
        val fillMatchHeightFirst = phoneAspect > containerAspect
        assertTrue("In FILL mode with portrait phone on landscape screen, match width first", !fillMatchHeightFirst)
        val fillWidth = containerW
        val fillHeight = fillWidth / phoneAspect
        assertTrue("Filled height ($fillHeight) must exceed screen height ($containerH)", fillHeight > containerH)
        assertEquals(1024f, fillWidth, 0.001f)
        assertEquals(1820.44f, fillHeight, 0.1f)
    }

    @Test
    fun testNumericVersionCodeAndStatusLogic() {
        fun computeStatus(
            installedCode: Long,
            installedName: String,
            serverCode: Long,
            serverName: String,
            hasPendingDownload: Boolean
        ): com.carcast.mirror.engine.update.UpdateStatus {
            if (hasPendingDownload) return com.carcast.mirror.engine.update.UpdateStatus.READY_TO_INSTALL
            if (serverCode > 0 && installedCode > 0) {
                if (serverCode > installedCode) return com.carcast.mirror.engine.update.UpdateStatus.UPDATE_AVAILABLE
                if (serverCode <= installedCode) return com.carcast.mirror.engine.update.UpdateStatus.UP_TO_DATE
            }
            return com.carcast.mirror.engine.update.UpdateStatus.UP_TO_DATE
        }

        // Scenario 1: Same version installed as latest on server (e.g. v1.0.5 installed, server has v1.0.5)
        assertEquals(
            com.carcast.mirror.engine.update.UpdateStatus.UP_TO_DATE,
            computeStatus(5L, "1.0.5", 5L, "1.0.5", false)
        )

        // Scenario 2: Installed version is higher than server version (e.g. dev build v1.0.6, server has v1.0.5)
        assertEquals(
            com.carcast.mirror.engine.update.UpdateStatus.UP_TO_DATE,
            computeStatus(6L, "1.0.6", 5L, "1.0.5", false)
        )

        // Scenario 3: Server has genuinely newer version (e.g. v1.0.5 installed, server has v1.0.6)
        assertEquals(
            com.carcast.mirror.engine.update.UpdateStatus.UPDATE_AVAILABLE,
            computeStatus(5L, "1.0.5", 6L, "1.0.6", false)
        )

        // Scenario 4: Download finished, pending install
        assertEquals(
            com.carcast.mirror.engine.update.UpdateStatus.READY_TO_INSTALL,
            computeStatus(5L, "1.0.5", 6L, "1.0.6", true)
        )
    }

    @Test
    fun testVersionCodeParsingFromRelease() {
        fun parseCode(tagName: String, body: String): Long {
            val regex = Regex("""(?i)versioncode\s*[:=]\s*(\d+)""")
            val match = regex.find(body)
            if (match != null) {
                val code = match.groupValues[1].toLongOrNull()
                if (code != null && code > 0) return code
            }
            val clean = tagName.trim().removePrefix("v").removePrefix("V")
            clean.toLongOrNull()?.let { return it }
            val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.isNotEmpty()) {
                val major = parts.getOrElse(0) { 0 }
                val minor = parts.getOrElse(1) { 0 }
                val patch = parts.getOrElse(2) { 0 }
                if (major == 1 && minor == 0 && patch > 0) {
                    return patch.toLong()
                }
                return (major * 10000 + minor * 100 + patch).toLong()
            }
            return 0L
        }

        // Test explicit body tag
        assertEquals(42L, parseCode("v1.0.5", "Bug fixes\nversionCode: 42\nDetails"))
        assertEquals(10L, parseCode("v1.0.5", "Release notes (versionCode = 10)"))

        // Test semantic tag parsing
        assertEquals(5L, parseCode("v1.0.5", "Regular release without explicit code"))
        assertEquals(6L, parseCode("1.0.6", "Regular release"))
        assertEquals(20100L, parseCode("v2.1.0", "Major upgrade"))
    }
}
