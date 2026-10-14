package com.ranzxm.browserak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded ABP subset, not an ABP engine. update callbacks always run on the main thread. */
class Protection(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "protection").apply { mkdirs() }
    private val preferences = context.applicationContext.getSharedPreferences("protection", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val updating = AtomicBoolean()
    @Volatile private var disabled = preferences.getStringSet("disabled", emptySet())!!.toSet()
    @Volatile private var rules = ProtectionRules.load(listOf("easylist.txt", "easyprivacy.txt").mapNotNull {
        val atomic = AtomicFile(File(directory, it))
        try { atomic.openRead().use { input ->
            val bytes = ProtectionRules.readBounded(input, MAX_BYTES)
            if (bytes.size <= MAX_BYTES) bytes.toString(Charsets.UTF_8) else null
        } } catch (_: Exception) { null }
    })

    fun enabled(host: String): Boolean = ProtectionRules.host(host)?.let { it !in disabled } ?: false

    /** Returns the new enabled state; disabling applies to this exact page host. */
    @Synchronized fun toggle(host: String): Boolean {
        val normalized = requireNotNull(ProtectionRules.host(host)) { "Invalid site host" }
        disabled = if (normalized in disabled) disabled - normalized else disabled + normalized
        preferences.edit().putStringSet("disabled", disabled).apply()
        return normalized !in disabled
    }

    fun blocked(url: String, pageUrl: String): Boolean {
        val pageHost = ProtectionRules.urlHost(pageUrl) ?: return false
        return enabled(pageHost) && rules.blocked(url, pageHost)
    }

    fun css(pageUrl: String): String {
        val host = ProtectionRules.urlHost(pageUrl) ?: return ""
        return if (enabled(host)) rules.css(host) else ""
    }

    /** No redirects; only the two official HTTPS endpoints. Existing rules survive any failure. */
    fun update(callback: (String) -> Unit) {
        fun report(message: String) { main.post { callback(message) } }
        if (!updating.compareAndSet(false, true)) { report("Filter update already running"); return }
        Thread({
            try {
                val names = listOf("easylist.txt", "easyprivacy.txt")
                val texts = names.map { name ->
                    val connection = URL("https://easylist.to/easylist/$name").openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 20_000
                        connection.setRequestProperty("User-Agent", "Browserak/1.0")
                        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                        check(connection.contentLengthLong <= MAX_BYTES) { "Filter list too large" }
                        val bytes = connection.inputStream.use { ProtectionRules.readBounded(it, MAX_BYTES) }
                        check(bytes.size <= MAX_BYTES) { "Filter list too large" }
                        val text = bytes.toString(Charsets.UTF_8)
                        check(text.startsWith("[Adblock Plus")) { "Invalid filter list" }
                        text
                    } finally { connection.disconnect() }
                }
                val next = ProtectionRules.load(texts)
                names.zip(texts).forEach { (name, text) ->
                    val file = AtomicFile(File(directory, name))
                    val output = file.startWrite()
                    try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                    catch (error: Exception) { file.failWrite(output); throw error }
                }
                rules = next
                report("Filters updated: ${next.count} supported rules (bounded ABP subset)")
            } catch (error: Exception) {
                report("Filter update failed: ${error.message ?: error.javaClass.simpleName}")
            } finally { updating.set(false) }
        }, "browserak-filters").start()
    }

    private companion object { const val MAX_BYTES = 6 * 1024 * 1024 }
}

/** Pure Kotlin matching core, also exercised by local JVM tests. */
internal object ProtectionRules {
    private const val MAX_RULES = 50_000
    private const val MAX_GENERAL = 512
    private const val MAX_COSMETIC = 8_000
    private val seeds = listOf("doubleclick.net", "google-analytics.com", "googlesyndication.com", "scorecardresearch.com", "connect.facebook.net", "analytics.tiktok.com")

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + 60_000_000_000L
        while (true) {
            check(System.nanoTime() < deadline) { "Read timed out" }
            val size = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (size < 0) return output.toByteArray()
            output.write(buffer, 0, size)
            check(output.size() <= limit) { "File too large" }
        }
    }

    fun host(value: String): String? = try {
        val result = IDN.toASCII(value.trim().trimEnd('.')).lowercase(java.util.Locale.ROOT)
        result.takeIf { it.length in 1..253 && it.split('.').all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        } }
    } catch (_: IllegalArgumentException) { null }

    fun urlHost(url: String): String? = try {
        if (url.length > 8192) null else URI(url).let { uri ->
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) uri.host?.let(::host) else null
        }
    } catch (_: Exception) { null }

    private fun suffixes(host: String): Sequence<String> = generateSequence(host) {
        it.substringAfter('.', "").takeIf(String::isNotEmpty)
    }
    private fun domainMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    internal data class Rule(val pattern: String, val anchor: String?, val include: Set<String>, val exclude: Set<String>) {
        fun applies(pageHost: String) = exclude.none { domainMatches(pageHost, it) } &&
            (include.isEmpty() || include.any { domainMatches(pageHost, it) })
        fun matches(url: String, requestHost: String): Boolean {
            if (anchor == null) return wildcard(url, pattern)
            if (!domainMatches(requestHost, anchor)) return false
            val uri = try { URI(url) } catch (_: Exception) { return false }
            val tail = (if (uri.port >= 0) ":${uri.port}" else "") + (uri.rawPath ?: "") +
                (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
            return wildcard(anchor + tail, pattern, left = true)
        }
    }

    internal data class Cosmetic(val selector: String, val include: Set<String>, val exclude: Set<String>, val exception: Boolean) {
        fun applies(host: String) = exclude.none { domainMatches(host, it) } &&
            (include.isEmpty() || include.any { domainMatches(host, it) })
    }

    class Snapshot internal constructor(
        private val blocks: Map<String, List<Rule>>,
        private val exceptions: Map<String, List<Rule>>,
        private val cosmetic: Map<String, List<Cosmetic>>,
        val count: Int
    ) {
        fun blocked(url: String, pageHost: String): Boolean {
            val requestHost = urlHost(url) ?: return false
            fun matches(index: Map<String, List<Rule>>) = (sequenceOf("") + suffixes(requestHost)).any { key ->
                index[key].orEmpty().any { it.applies(pageHost) && it.matches(url, requestHost) }
            }
            return matches(blocks) && !matches(exceptions)
        }
        fun css(host: String): String {
            val applicable = (sequenceOf("") + suffixes(host)).flatMap { cosmetic[it].orEmpty().asSequence() }
                .filter { it.applies(host) }.distinct().toList()
            val allowed = applicable.filter { it.exception }.map { it.selector }.toSet()
            val output = StringBuilder()
            applicable.asSequence().filter { !it.exception && it.selector !in allowed }.map { it.selector }.distinct()
                .take(300).forEach { selector ->
                    if (output.length + selector.length + 27 <= 32_768) output.append(selector).append("{display:none!important;}\n")
                }
            return output.toString()
        }
    }

    fun load(texts: List<String>): Snapshot {
        val blocks = HashMap<String, MutableList<Rule>>()
        val exceptions = HashMap<String, MutableList<Rule>>()
        val cosmetic = HashMap<String, MutableList<Cosmetic>>()
        var count = 0
        var general = 0
        var cosmetics = 0
        fun add(rule: Rule, exception: Boolean) {
            if (count >= MAX_RULES || (rule.anchor == null && general >= MAX_GENERAL)) return
            (if (exception) exceptions else blocks).getOrPut(rule.anchor ?: "") { ArrayList() }.add(rule)
            count++
            if (rule.anchor == null) general++
        }
        seeds.forEach { add(Rule("$it^", it, emptySet(), emptySet()), false) }
        // Reserve bounded capacity for exceptions before accepting blocking rules.
        val lines = texts.asSequence().flatMap { it.lineSequence() }.take(200_000)
        val ordered = sequenceOf(true, false).flatMap { exceptionsFirst ->
            lines.filter { raw -> (raw.trimStart().startsWith("@@") || "#@#" in raw) == exceptionsFirst }
        }
        ordered.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.length > 2048 || line.startsWith('!') || line.startsWith('[')) return@forEach
            val separator = when { "#@#" in line -> "#@#"; "##" in line -> "##"; else -> null }
            if (separator != null) {
                if (cosmetics >= MAX_COSMETIC) return@forEach
                val domains = parseDomains(line.substringBefore(separator), ',') ?: return@forEach
                val selector = line.substringAfter(separator)
                // ponytail: plain CSS only; procedural/extended cosmetics need a real ABP engine.
                if (selector.isBlank() || selector.length > 512 || selector.any { it in "{};<>@\\" || it.code < 32 } ||
                    ":has(" in selector || ":-abp-" in selector || ":matches-css" in selector) return@forEach
                val rule = Cosmetic(selector, domains.first, domains.second, separator == "#@#")
                val keys = rule.include.ifEmpty { setOf("") }
                if (keys.size > 32) return@forEach
                keys.forEach { cosmetic.getOrPut(it) { ArrayList() }.add(rule) }
                cosmetics++
                return@forEach
            }
            if ('#' in line) return@forEach
            val exception = line.startsWith("@@")
            var pattern = if (exception) line.substring(2) else line
            var domains = emptySet<String>() to emptySet<String>()
            if ('$' in pattern) {
                val options = pattern.substringAfter('$')
                // No guessing resource types, party status, redirects, CSP, or scriptlets.
                if (!options.startsWith("domain=") || ',' in options) return@forEach
                domains = parseDomains(options.removePrefix("domain="), '|') ?: return@forEach
                if (domains.first.size + domains.second.size > 32) return@forEach
                pattern = pattern.substringBefore('$')
            }
            if (pattern.isEmpty() || pattern.startsWith('/') || pattern.length > 512 || pattern.count { it == '*' } > 8) return@forEach
            var anchor: String? = null
            if (pattern.startsWith("||")) {
                pattern = pattern.substring(2)
                val candidate = pattern.takeWhile { it != '^' && it != '/' && it != ':' && it != '*' && it != '|' }
                anchor = host(candidate) ?: return@forEach
                if (candidate != anchor || (pattern.length > candidate.length && pattern[candidate.length] !in "^/:|")) return@forEach
            }
            if ('|' in pattern.trim('|')) return@forEach
            add(Rule(pattern, anchor, domains.first, domains.second), exception)
        }
        return Snapshot(blocks.mapValues { it.value.toList() }, exceptions.mapValues { it.value.toList() },
            cosmetic.mapValues { it.value.toList() }, count + cosmetics)
    }

    private fun parseDomains(value: String, separator: Char): Pair<Set<String>, Set<String>>? {
        if (value.isEmpty()) return emptySet<String>() to emptySet()
        val include = HashSet<String>()
        val exclude = HashSet<String>()
        for (part in value.split(separator)) {
            val domain = host(part.removePrefix("~")) ?: return null
            (if (part.startsWith('~')) exclude else include).add(domain)
        }
        return include to exclude
    }

    /** Greedy glob, no regex/backtracking explosion; ^ is an ABP separator or end-of-URL. */
    internal fun wildcard(value: String, source: String, left: Boolean = false): Boolean {
        val anchoredLeft = left || source.startsWith('|')
        val anchoredRight = source.endsWith('|')
        val pattern = (if (anchoredLeft) "" else "*") + source.trim('|') + (if (anchoredRight) "" else "*")
        var input = 0
        var token = 0
        var star = -1
        var retry = 0
        while (input < value.length) {
            when {
                token < pattern.length && pattern[token] == '*' -> { star = token++; retry = input }
                token < pattern.length && (pattern[token].equals(value[input], ignoreCase = true) ||
                    (pattern[token] == '^' && value[input] !in 'a'..'z' && value[input] !in 'A'..'Z' && value[input] !in '0'..'9' && value[input] !in "_.%-")) -> { input++; token++ }
                star >= 0 -> { token = star + 1; input = ++retry }
                else -> return false
            }
        }
        while (token < pattern.length && (pattern[token] == '*' || pattern[token] == '^')) token++
        return token == pattern.length
    }
}
