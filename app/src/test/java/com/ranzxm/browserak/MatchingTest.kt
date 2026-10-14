package com.ranzxm.browserak

import org.junit.Assert.*
import org.junit.Test

class MatchingTest {
    @Test fun protectionSubset() {
        val rules = ProtectionRules.load(listOf("""
            ||ads.example^
            @@||ads.example/allowed^
            ||scoped.example^${'$'}domain=news.example|~private.news.example
            ||unsupported.example^${'$'}third-party
            /regular[.]expression/
            |https://cdn.example/ad*pixel|
            news.example##.advert
            news.example#@#.allowed
            ##.allowed
        """.trimIndent()))
        assertTrue(rules.blocked("https://sub.ads.example/banner", "news.example"))
        assertTrue(rules.blocked("https://ADS.EXAMPLE/banner", "news.example"))
        assertFalse(rules.blocked("https://ads.example.evil.test/banner", "news.example"))
        assertFalse(rules.blocked("https://ads.example/allowed?id=1", "news.example"))
        assertTrue(rules.blocked("https://scoped.example/x", "sub.news.example"))
        assertFalse(rules.blocked("https://scoped.example/x", "private.news.example"))
        assertFalse(rules.blocked("https://scoped.example/x", "other.example"))
        assertFalse(rules.blocked("https://unsupported.example/x", "news.example"))
        assertTrue(rules.blocked("https://cdn.example/ad-1-pixel", "news.example"))
        assertFalse(rules.blocked("https://cdn.example/ad-1-pixel/extra", "news.example"))
        assertFalse(rules.blocked("data:text/plain,https://ads.example/x", "news.example"))
        assertTrue(rules.css("news.example").contains(".advert{"))
        assertFalse(rules.css("news.example").contains(".allowed{"))
        assertFalse(rules.css("other.example").contains(".advert{"))
        assertTrue(ProtectionRules.load(emptyList()).blocked("https://www.google-analytics.com/collect", "news.example"))
    }

    @Test fun userscriptMatchingAndValidation() {
        val match = UserscriptMatching.match("*://*.example.com/account/*")
        assertTrue(match.matches("https://example.com/account/1?x=y#fragment"))
        assertTrue(match.matches("http://sub.example.com:8080/account/1"))
        assertFalse(match.matches("https://example.com.evil.test/account/1"))
        assertFalse(match.matches("https://example.com/Account/1"))
        assertFalse(match.matches("file://example.com/account/1"))
        assertFalse(match.matches("https://example.com@evil.test/account/1"))
        assertTrue(UserscriptMatching.match("<all_urls>").matches("https://other.example/"))
        val source = """
            // ==UserScript==
            // @name Test
            // @match https://example.com/*
            // @exclude https://example.com/private/*
            // @grant GM_getValue
            // @run-at document-start
            // ==/UserScript==
            void 0;
        """.trimIndent()
        val metadata = UserscriptMatching.parse(source)
        assertTrue(metadata.matches("https://example.com/public/a"))
        assertFalse(metadata.matches("https://example.com/private/a"))
        assertEquals("document-start", metadata.phase)
        listOf(
            source.replace("// @match https://example.com/*", ""),
            source.replace("GM_getValue", "GM_xmlhttpRequest"),
            source.replace("// @grant GM_getValue", "// @require https://example.com/lib.js"),
            source.replace("https://example.com/*", "https://example.com:443/*")
        ).forEach { invalid ->
            try { UserscriptMatching.parse(invalid); fail("Invalid script accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
