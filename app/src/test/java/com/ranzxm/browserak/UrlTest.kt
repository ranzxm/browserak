package com.ranzxm.browserak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTest {
    @Test fun onlyHttpWithoutEmbeddedCredentials() {
        assertTrue(MainActivity.isWebUrl("https://example.com/video.mp4?token=abc"))
        assertTrue(MainActivity.isWebUrl("http://127.0.0.1:8080/"))
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.com/", "https:///broken", "https://exa mple.com")) {
            assertFalse(url, MainActivity.isWebUrl(url))
        }
    }
}
