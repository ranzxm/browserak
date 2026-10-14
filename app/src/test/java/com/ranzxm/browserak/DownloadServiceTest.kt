package com.ranzxm.browserak

import java.io.IOException
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadServiceTest {
    @Test fun acceptsMatchingResumeRanges() {
        assertEquals(50L to 100L, DownloadService.validatedRange("bytes 50-99/100", 50, 50, 100))
        assertEquals(25L to 100L, DownloadService.validatedRange("bytes 50-74/100", 50, -1, -1))
        assertEquals(100L to 100L, DownloadService.validatedRange("bytes 0-99/100", 0, 100, -1))
        assertEquals(Long.MAX_VALUE to Long.MAX_VALUE,
            DownloadService.validatedRange("bytes 0-9223372036854775806/9223372036854775807", 0, -1, -1))
    }

    @Test fun rejectsMismatchedResumeRanges() {
        for (header in listOf("bytes 0-49/100", "bytes 51-99/100", "bytes 50-49/100", "bytes 50-100/100", "bytes 50-99/101")) {
            assertThrows(header, IOException::class.java) { DownloadService.validatedRange(header, 50, -1, 100) }
        }
        assertThrows(IOException::class.java) { DownloadService.validatedRange("bytes 50-99/100", 50, 49, 100) }
    }

    @Test fun rejectsMalformedUnknownAndOverflowingRanges() {
        for (header in listOf(null, "", "bytes */100", "bytes 50-99/*", "bytes -1-99/100", "bytes 50-99/0",
            "bytes 50-99/9223372036854775808", "bytes 9223372036854775808-99/100", "bytes 50-9223372036854775808/100")) {
            assertThrows(header.orEmpty(), IOException::class.java) { DownloadService.validatedRange(header, 50, -1, -1) }
        }
    }

    @Test fun cookieOriginIncludesSchemeHostAndEffectivePort() {
        val original = URL("https://EXAMPLE.com/a")
        assertTrue(DownloadService.sameOrigin(original, URL("https://example.com:443/b?token=x")))
        assertTrue(DownloadService.sameOrigin(URL("http://example.com"), URL("http://example.com:80/a")))
        assertTrue(DownloadService.sameOrigin(URL("https://example.com:8443/a"), URL("https://example.com:8443/b")))
        for (other in listOf("http://example.com:443/a", "https://example.com:8443/a", "https://sub.example.com/a", "https://example.com.evil/a")) {
            assertFalse(other, DownloadService.sameOrigin(original, URL(other)))
        }
    }

    @Test fun resolvesRedirectsWithoutLeakingCookiesToAnotherOrigin() {
        val original = URL("https://example.com/a/playlist.m3u8")
        assertEquals("https://example.com/video.ts", DownloadService.redirectTarget(original, "../video.ts").toString())
        val cdn = DownloadService.redirectTarget(original, "//cdn.example.com/segment.ts")
        assertEquals("https://cdn.example.com/segment.ts", cdn.toString())
        assertFalse(DownloadService.sameOrigin(original, cdn))
        assertEquals("https://example.com/a", DownloadService.redirectTarget(URL("http://example.com"), "https://example.com/a").toString())
    }

    @Test fun refusesHttpsDowngradeAndUnsafeRedirects() {
        val original = URL("https://example.com/a")
        assertThrows(IOException::class.java) { DownloadService.redirectTarget(original, "http://example.com/a") }
        for (location in listOf("file:///etc/passwd", "javascript:alert(1)", "https://user:pass@example.com/a", "https://example.com:65536/a")) {
            assertThrows(location, IllegalArgumentException::class.java) { DownloadService.redirectTarget(original, location) }
        }
    }
}
