package com.carcast.mirror

import com.carcast.mirror.engine.discovery.DeviceIdHelper
import com.carcast.mirror.engine.discovery.DiscoveredReceiver
import com.carcast.mirror.engine.discovery.ReceiverPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarCastDiscoveryTest {

    @Test
    fun testDiscoveredReceiverPlatformMapping() {
        val carReceiver = DiscoveredReceiver(
            id = "cc-112233445566",
            name = "My Car LCD",
            host = "192.168.43.1",
            port = 8080,
            platform = ReceiverPlatform.CAR_LCD
        )
        assertEquals(ReceiverPlatform.CAR_LCD, carReceiver.platform)
        assertEquals("Car LCD", carReceiver.platform.label)

        val tvReceiver = DiscoveredReceiver(
            id = "cc-tv-99887766",
            name = "Living Room Android TV",
            host = "192.168.1.50",
            port = 8080,
            platform = ReceiverPlatform.ANDROID_TV
        )
        assertEquals(ReceiverPlatform.ANDROID_TV, tvReceiver.platform)
        assertEquals("Android TV", tvReceiver.platform.label)

        val winReceiver = DiscoveredReceiver(
            id = "cc-win-aabbccdd",
            name = "CarCast-PC (WORKSTATION)",
            host = "192.168.1.120",
            port = 8080,
            platform = ReceiverPlatform.WINDOWS_PC
        )
        assertEquals(ReceiverPlatform.WINDOWS_PC, winReceiver.platform)
        assertEquals("Windows PC", winReceiver.platform.label)
    }

    @Test
    fun testStableIdentifierMatchingAcrossIpChanges() {
        val stableId = "cc-c7b3f91234ab"
        val rememberedName = "My Car LCD"

        // Initial connection on Hotspot IP: 192.168.43.1
        val initialReceiver = DiscoveredReceiver(
            id = stableId,
            name = "My Car LCD",
            host = "192.168.43.1",
            port = 8080,
            platform = ReceiverPlatform.CAR_LCD
        )

        // Device changes IP to home Wi-Fi: 192.168.1.188
        val changedIpReceiver = DiscoveredReceiver(
            id = stableId,
            name = "My Car LCD",
            host = "192.168.1.188",
            port = 8080,
            platform = ReceiverPlatform.CAR_LCD
        )

        // Verify that matching by stable ID succeeds despite IP change
        val matchesInitial = DeviceIdHelper.matchesRememberedOrPreferred(initialReceiver, stableId, rememberedName)
        val matchesAfterIpChange = DeviceIdHelper.matchesRememberedOrPreferred(changedIpReceiver, stableId, rememberedName)

        assertTrue("Must match remembered receiver before IP change", matchesInitial)
        assertTrue("Must match remembered receiver by stable ID even after IP change", matchesAfterIpChange)
        assertEquals(initialReceiver.id, changedIpReceiver.id)
    }

    @Test
    fun testFallbackMatchingByDefaultReceiverName() {
        val defaultReceiver = DiscoveredReceiver(
            id = "cc-random-9876",
            name = "My Car LCD",
            host = "192.168.43.1",
            port = 8080,
            platform = ReceiverPlatform.CAR_LCD
        )

        // When no receiver has been explicitly saved yet (null ID and null name)
        val matchesDefault = DeviceIdHelper.matchesRememberedOrPreferred(defaultReceiver, null, null)
        assertTrue("Default receiver 'My Car LCD' should auto-match for first-time pairing", matchesDefault)

        val otherDevice = DiscoveredReceiver(
            id = "cc-random-1111",
            name = "Office Projector",
            host = "192.168.1.99",
            port = 8080,
            platform = ReceiverPlatform.UNKNOWN
        )
        val matchesOther = DeviceIdHelper.matchesRememberedOrPreferred(otherDevice, null, null)
        assertFalse("Unrelated devices should not auto-match default name", matchesOther)
    }

    @Test
    fun testMultiPlatformSortingPriority() {
        val list = listOf(
            DiscoveredReceiver(id = "1", name = "Zeta Car LCD", host = "192.168.1.10", platform = ReceiverPlatform.CAR_LCD, isAutoDetected = false),
            DiscoveredReceiver(id = "2", name = "My Car LCD", host = "192.168.1.20", platform = ReceiverPlatform.CAR_LCD, isAutoDetected = true),
            DiscoveredReceiver(id = "3", name = "Alpha Android TV", host = "192.168.1.30", platform = ReceiverPlatform.ANDROID_TV, isAutoDetected = false),
            DiscoveredReceiver(id = "4", name = "Office Windows PC", host = "192.168.1.40", platform = ReceiverPlatform.WINDOWS_PC, isAutoDetected = false)
        )

        val sorted = list.sortedWith(compareByDescending<DiscoveredReceiver> { it.isAutoDetected }.thenBy { it.name })

        // Auto-detected device must always be at index 0
        assertEquals("My Car LCD", sorted[0].name)
        assertTrue(sorted[0].isAutoDetected)
        assertEquals("Alpha Android TV", sorted[1].name)
        assertEquals("Office Windows PC", sorted[2].name)
        assertEquals("Zeta Car LCD", sorted[3].name)
    }

    @Test
    fun testOneTapConnectSelectionAndEndpointFormatting() {
        val tvReceiver = DiscoveredReceiver(
            id = "cc-tv-1234",
            name = "Bedroom Google TV",
            host = "192.168.1.77",
            port = 8080,
            httpPort = 8088,
            platform = ReceiverPlatform.ANDROID_TV
        )

        val wsEndpoint = "ws://${tvReceiver.host}:${tvReceiver.port}"
        val httpPortal = "http://${tvReceiver.host}:${tvReceiver.httpPort}"

        assertEquals("ws://192.168.1.77:8080", wsEndpoint)
        assertEquals("http://192.168.1.77:8088", httpPortal)
    }
}
