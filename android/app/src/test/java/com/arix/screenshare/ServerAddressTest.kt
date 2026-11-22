package com.arix.screenshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test fun privateHosts() {
        for (h in listOf("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "localhost", "laptop.local", "mylaptop", "::1", "fd00::1", "fe80::1")) assertTrue(h, ServerAddress.isPrivateHost(h))
    }

    @Test fun publicHosts() {
        for (h in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "example.com", "300.1.1.1", "100.128.0.1", "")) assertFalse(h, ServerAddress.isPrivateHost(h))
    }

    @Test fun normalizeAddsSchemeByHostType() {
        assertEquals(ServerAddress.Result.Ok("http://192.168.1.20:8000"), ServerAddress.normalize("192.168.1.20:8000"))
        assertEquals(ServerAddress.Result.Ok("https://example.com"), ServerAddress.normalize("example.com"))
    }

    @Test fun normalizeStripsPathAndDefaultPort() {
        assertEquals(ServerAddress.Result.Ok("https://x.example.com:8443"), ServerAddress.normalize("https://x.example.com:8443/some/path"))
        assertEquals(ServerAddress.Result.Ok("https://example.com"), ServerAddress.normalize("https://example.com:443/"))
    }

    @Test fun plainHttpToPublicHostIsRejected() {
        assertTrue(ServerAddress.normalize("http://example.com") is ServerAddress.Result.Bad)
        assertTrue(ServerAddress.normalize("http://8.8.8.8:8000") is ServerAddress.Result.Bad)
    }

    @Test fun emptyIsRejected() {
        assertTrue(ServerAddress.normalize("   ") is ServerAddress.Result.Bad)
    }

    @Test fun wsUrl() {
        assertEquals("ws://192.168.1.20:8000/ws/123456/sender?token=abc", ServerAddress.senderWsUrl("http://192.168.1.20:8000", "123456", "abc"))
        assertEquals("wss://example.com/ws/123456/sender?token=abc", ServerAddress.senderWsUrl("https://example.com", "123456", "abc"))
    }
}
