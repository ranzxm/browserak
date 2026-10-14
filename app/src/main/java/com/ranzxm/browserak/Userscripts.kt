package com.ranzxm.browserak

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** User-installed JavaScript, not a security sandbox. No Android JavaScript bridge. */
class Userscripts(context: Context) {
    data class Script(val id: String, val name: String, val source: String, val enabled: Boolean)

    private val file = AtomicFile(File(context.applicationContext.filesDir, "userscripts.json"))
    private var loadFailure: Exception? = null
    private var scripts: List<Script> = try {
        val bytes = file.openRead().use { ProtectionRules.readBounded(it, MAX_STORAGE_BYTES) }
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= MAX_SCRIPTS) { "Too many saved scripts" }
        val loaded = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(ID.matches(id)) { "Invalid saved script ID" }
            val source = item.getString("source")
            val metadata = UserscriptMatching.parse(source)
            Script(id, metadata.name, source, item.getBoolean("enabled"))
        }
        require(loaded.map { it.id }.distinct().size == loaded.size) { "Duplicate saved script IDs" }
        loaded
    } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) loadFailure = error
        emptyList()
    } catch (error: Exception) {
        // Do not overwrite an unreadable store with a seemingly empty script list.
        loadFailure = error
        emptyList()
    }

    @Synchronized fun list(): List<Script> {
        checkReadable()
        return scripts.toList()
    }

    /** Invalid metadata/unknown IDs throw IllegalArgumentException; failed writes preserve the old file. */
    @Synchronized fun save(source: String, id: String? = null): Script {
        checkReadable()
        val metadata = UserscriptMatching.parse(source)
        val previous = id?.let { key -> scripts.find { it.id == key } ?: throw IllegalArgumentException("Unknown script ID") }
        require(previous != null || scripts.size < MAX_SCRIPTS) { "Limit: $MAX_SCRIPTS scripts" }
        val script = Script(previous?.id ?: UUID.randomUUID().toString(), metadata.name, source, previous?.enabled ?: true)
        persist(if (previous == null) scripts + script else scripts.map { if (it.id == id) script else it })
        return script
    }

    @Synchronized fun toggle(id: String): Script {
        checkReadable()
        val previous = scripts.find { it.id == id } ?: throw IllegalArgumentException("Unknown script ID")
        val changed = previous.copy(enabled = !previous.enabled)
        persist(scripts.map { if (it.id == id) changed else it })
        return changed
    }

    @Synchronized fun delete(id: String) {
        checkReadable()
        require(scripts.any { it.id == id }) { "Unknown script ID" }
        persist(scripts.filterNot { it.id == id })
    }

    /** document-end also schedules document-idle scripts. Early injection remains best-effort. */
    @Synchronized fun javascript(url: String, phase: String): String {
        checkReadable()
        require(phase in UserscriptMatching.PHASES) { "Unknown userscript phase: $phase" }
        return scripts.asSequence().filter { it.enabled }.map { it to UserscriptMatching.parse(it.source) }
            .filter { (_, metadata) -> (metadata.phase == phase || (phase == "document-end" && metadata.phase == "document-idle")) && metadata.matches(url) }
            .joinToString("\n") { (script, metadata) ->
                val key = JSONObject.quote("browserak:gm:${script.id}:")
                val grants = metadata.grants
                val helpers = buildString {
                    if ("GM_addStyle" in grants) append("const GM_addStyle=function(css){const e=document.createElement('style');e.textContent=String(css);(document.head||document.documentElement).appendChild(e);return e;};\n")
                    if (grants.any { it != "none" && it != "GM_addStyle" }) append("const __gmPrefix=$key;\n")
                    if ("GM_getValue" in grants) append("const GM_getValue=function(k,d){try{const s=localStorage.getItem(__gmPrefix+String(k));return s===null?d:JSON.parse(s);}catch(e){return d;}};\n")
                    if ("GM_setValue" in grants) append("const GM_setValue=function(k,v){if(v!==null&&!['string','boolean','number'].includes(typeof v))throw new TypeError('GM_setValue supports JSON primitives only');if(typeof v==='number'&&!Number.isFinite(v))throw new TypeError('GM_setValue requires a finite number');localStorage.setItem(__gmPrefix+String(k),JSON.stringify(v));};\n")
                    if ("GM_deleteValue" in grants) append("const GM_deleteValue=function(k){localStorage.removeItem(__gmPrefix+String(k));};\n")
                }
                // Session marker prevents repeated progress callbacks executing a script twice per document.
                val marker = JSONObject.quote("browserak:script:${script.id}")
                val name = JSONObject.quote(script.name)
                """
                ;(function(){
                  const marker=$marker;
                  if(Object.prototype.hasOwnProperty.call(document,marker))return;
                  Object.defineProperty(document,marker,{value:true});
                  const run=function(){try{(function(){
                $helpers
                ${script.source}
                  }).call(window);}catch(e){console.error('Userscript '+$name,e);}};
                  if(${JSONObject.quote(metadata.phase)}==='document-start'||document.readyState!=='loading'){
                    if(${JSONObject.quote(metadata.phase)}==='document-idle')setTimeout(run,0);else run();
                  }else document.addEventListener('DOMContentLoaded',function(){
                    if(${JSONObject.quote(metadata.phase)}==='document-idle')setTimeout(run,0);else run();
                  },{once:true});
                })();
                """.trimIndent()
            }
    }

    private fun checkReadable() {
        check(loadFailure == null) { "Cannot read userscripts.json; preserve/repair the file before editing: ${loadFailure?.message}" }
    }

    private fun persist(next: List<Script>) {
        val array = JSONArray()
        next.forEach { array.put(JSONObject().put("id", it.id).put("source", it.source).put("enabled", it.enabled)) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STORAGE_BYTES) { "Userscript storage limit: 4 MiB" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        scripts = next
    }

    private companion object {
        const val MAX_SCRIPTS = 64
        const val MAX_STORAGE_BYTES = 4 * 1024 * 1024
        val ID = Regex("[a-zA-Z0-9-]{1,64}")
    }
}

/** No Android/JSON calls: metadata and URL semantics are runnable in local JVM tests. */
internal object UserscriptMatching {
    val PHASES = setOf("document-start", "document-end", "document-idle")
    private val GRANTS = setOf("none", "GM_addStyle", "GM_getValue", "GM_setValue", "GM_deleteValue")
    private val INFORMATIONAL = setOf("description", "version", "namespace", "author", "license")

    data class Metadata(val name: String, val matches: List<Match>, val excludes: List<String>, val phase: String, val grants: Set<String>) {
        fun matches(url: String): Boolean = url.length <= 8192 && matches.any { it.matches(url) } && excludes.none { glob(url, it) }
    }

    data class Match(val scheme: String, val host: String, val path: String) {
        fun matches(url: String): Boolean {
            if (url.length > 8192) return false
            val uri = try { URI(url) } catch (_: Exception) { return false }
            val actualScheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return false
            if (actualScheme !in setOf("http", "https") || (scheme != "*" && scheme != actualScheme)) return false
            val actualHost = uri.host?.let(ProtectionRules::host) ?: return false
            val hostMatches = when {
                host == "*" -> true
                host.startsWith("*.") -> actualHost == host.substring(2) || actualHost.endsWith(host.substring(1))
                else -> actualHost == host
            }
            val actualPath = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            return hostMatches && glob(actualPath, path)
        }
    }

    fun match(pattern: String): Match {
        require(pattern.length in 1..2048) { "Invalid @match length" }
        if (pattern == "<all_urls>") return Match("*", "*", "/*")
        val pieces = Regex("^(https?|\\*)://([^/]+)(/.*)$").matchEntire(pattern)
            ?: throw IllegalArgumentException("Invalid @match '$pattern'; use https://example.com/*")
        val scheme = pieces.groupValues[1]
        val rawHost = pieces.groupValues[2]
        val host = when {
            rawHost == "*" -> "*"
            rawHost.startsWith("*.") -> "*." + requireNotNull(ProtectionRules.host(rawHost.substring(2))) { "Invalid @match host" }
            else -> requireNotNull(ProtectionRules.host(rawHost)) { "Invalid @match host; ports and credentials are unsupported" }
        }
        val path = pieces.groupValues[3]
        require('#' !in path && path.none { it.isWhitespace() } && path.count { it == '*' } <= 32) { "Invalid @match path" }
        return Match(scheme, host, path)
    }

    fun parse(source: String): Metadata {
        require(source.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Script limit: 256 KiB" }
        val lines = source.removePrefix("\uFEFF").lineSequence().iterator()
        var started = false
        var ended = false
        var name: String? = null
        val matches = ArrayList<Match>()
        val excludes = ArrayList<String>()
        val grants = HashSet<String>()
        var phase: String? = null
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (!started) {
                if (line.isEmpty()) continue
                require(line == "// ==UserScript==") { "Start with // ==UserScript== metadata" }
                started = true
                continue
            }
            if (line == "// ==/UserScript==") { ended = true; break }
            require(line.startsWith("//")) { "Metadata lines must be // comments" }
            val body = line.removePrefix("//").trim()
            if (!body.startsWith('@')) continue
            val tag = body.substringBefore(' ').substringBefore('\t').removePrefix("@")
            val value = body.substring(1 + tag.length).trim()
            require(value.isNotEmpty()) { "@$tag requires a value" }
            when (tag) {
                "name" -> { require(name == null && value.length <= 128) { "Provide one @name, at most 128 characters" }; name = value }
                "match" -> { require(matches.size < 64) { "Limit: 64 @match patterns" }; matches.add(match(value)) }
                "exclude" -> {
                    require(excludes.size < 64 && value.length <= 2048 && !value.startsWith('/') && value.count { it == '*' } <= 32) { "@exclude supports bounded URL globs, not regex" }
                    excludes.add(value)
                }
                "run-at" -> { require(phase == null && value in PHASES) { "@run-at must be document-start, document-end, or document-idle" }; phase = value }
                "grant" -> { require(value in GRANTS) { "Unsupported @grant $value; supported: ${GRANTS.joinToString()}" }; grants.add(value) }
                in INFORMATIONAL -> Unit
                "require" -> throw IllegalArgumentException("@require is unsupported; inline dependencies into the script")
                "include" -> throw IllegalArgumentException("@include is unsupported; use an explicit @match")
                else -> throw IllegalArgumentException("Unsupported metadata @$tag")
            }
        }
        require(started && ended) { "Missing userscript metadata block/end marker" }
        require(matches.isNotEmpty()) { "At least one @match is required; all sites require explicit *://*/*" }
        require("none" !in grants || grants.size == 1) { "@grant none cannot be combined with GM grants" }
        return Metadata(name ?: "Unnamed script", matches.toList(), excludes.toList(), phase ?: "document-end", grants.toSet())
    }

    /** Literal, case-sensitive URL glob; only * has special meaning. */
    internal fun glob(value: String, pattern: String): Boolean {
        var input = 0
        var token = 0
        var star = -1
        var retry = 0
        while (input < value.length) {
            when {
                token < pattern.length && pattern[token] == '*' -> { star = token++; retry = input }
                token < pattern.length && pattern[token] == value[input] -> { input++; token++ }
                star >= 0 -> { token = star + 1; input = ++retry }
                else -> return false
            }
        }
        while (token < pattern.length && pattern[token] == '*') token++
        return token == pattern.length
    }
}
