package com.lissy.fibcam

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updates for directly distributed beta builds (never in a Play build: see BuildConfig.UPDATER_ENABLED).
 *
 * The CI release publishes a manifest at a fixed address next to the APK the QR code points to:
 *   https://github.com/leisuresuitlissy/fibonacci-camera/releases/download/android-latest/latest.json
 * and each build's APK at its own, never-reused address (release tag android-b<code>).
 *
 * Nothing about the user or their photos is ever sent: the only requests are a GET of that manifest
 * and, if the user asks, a GET of the APK.
 */
object Updater {
    const val MANIFEST_URL = "https://github.com/leisuresuitlissy/fibonacci-camera/releases/download/android-latest/latest.json"
    /** APKs are only ever accepted from this project's own GitHub releases. */
    private const val ALLOWED_PREFIX = "https://github.com/leisuresuitlissy/fibonacci-camera/releases/download/"
    private const val AUTO_INTERVAL = 6L * 3600_000        // automatic checks at most every six hours
    private const val REMIND_AFTER = 48L * 3600_000         // "Not now" comes back after two days

    data class Release(val versionCode: Long, val versionName: String, val url: String, val notes: List<String>,
                       val date: String, val minSdk: Int, val sha256: String)

    sealed class Status {
        object Idle : Status()
        object Checking : Status()
        object UpToDate : Status()
        object Failed : Status()
        data class Available(val r: Release) : Status()
        data class Downloading(val r: Release, val pct: Int) : Status()
        data class Ready(val r: Release, val apk: File) : Status()
    }

    @Volatile var status: Status = Status.Idle
        private set
    var listener: ((Status) -> Unit)? = null
    private val cancel = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    val enabled: Boolean get() = BuildConfig.UPDATER_ENABLED

    private fun set(s: Status) { status = s; listener?.invoke(s) }

    fun installedCode(ctx: Context): Long = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(Long.MAX_VALUE)
    fun installedName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

    /** Strict parsing: anything missing, malformed or pointing somewhere unexpected is rejected. */
    fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        val code = o.getLong("versionCode")
        val name = o.getString("versionName").trim()
        val url = o.getString("downloadUrl").trim()
        val sha = o.getString("sha256").trim().lowercase()
        require(code > 0 && name.isNotEmpty() && name.length < 40)
        require(url.startsWith(ALLOWED_PREFIX) && url.endsWith(".apk") && !url.contains(".."))
        require(Regex("^[0-9a-f]{64}$").matches(sha))
        val notesArr = o.optJSONArray("releaseNotes")
        val notes = (0 until (notesArr?.length() ?: 0)).mapNotNull { notesArr?.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }.take(12)
        Release(code, name, url, notes, o.optString("releaseDate", ""), o.optInt("minSdk", 0), sha)
    }.getOrNull()

    /**
     * Background thread only. Automatic checks respect the six-hour interval; manual ones don't.
     * Returns the release worth offering now (newer, compatible, not recently dismissed), or null.
     */
    fun check(ctx: Context, prefs: SharedPreferences, manual: Boolean): Release? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("upd:lastCheck", 0) < AUTO_INTERVAL) {
            // still within the interval: re-offer a known release if its reminder is due, without the network
            return savedRelease(prefs)?.takeIf { offerable(ctx, prefs, it, false) }?.also { set(Status.Available(it)) }
        }
        if (status is Status.Downloading) return null
        set(Status.Checking)
        val r = runCatching {
            val c = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }.let { parse(it) ?: error("bad manifest") }
        }
        prefs.edit().putLong("upd:lastCheck", now).apply()
        val rel = r.getOrNull()
        if (rel == null) { set(Status.Failed); return null }
        rel.save(prefs)
        val newer = rel.versionCode > installedCode(ctx) && rel.minSdk <= Build.VERSION.SDK_INT
        if (!newer) { set(Status.UpToDate); return null }
        set(Status.Available(rel))
        return rel.takeIf { offerable(ctx, prefs, it, manual) }
    }

    private fun offerable(ctx: Context, prefs: SharedPreferences, r: Release, manual: Boolean): Boolean {
        if (r.versionCode <= installedCode(ctx) || r.minSdk > Build.VERSION.SDK_INT) return false
        if (manual) return true
        val dismissed = prefs.getLong("upd:dismissedCode", -1)
        val at = prefs.getLong("upd:dismissedAt", 0)
        return r.versionCode != dismissed || System.currentTimeMillis() - at > REMIND_AFTER
    }

    fun dismiss(prefs: SharedPreferences, r: Release) {
        prefs.edit().putLong("upd:dismissedCode", r.versionCode).putLong("upd:dismissedAt", System.currentTimeMillis()).apply()
    }

    private fun Release.save(p: SharedPreferences) {
        p.edit().putString("upd:release", JSONObject().apply {
            put("versionCode", versionCode); put("versionName", versionName); put("downloadUrl", url)
            put("releaseNotes", org.json.JSONArray(notes)); put("releaseDate", date); put("minSdk", minSdk); put("sha256", sha256)
        }.toString()).apply()
    }
    private fun savedRelease(p: SharedPreferences): Release? = p.getString("upd:release", null)?.let { parse(it) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Removes downloaded APKs that are no longer needed (installed, superseded or abandoned). */
    fun cleanUp(ctx: Context) {
        val mine = installedCode(ctx)
        dir(ctx).listFiles()?.forEach { f ->
            val code = Regex("fibcam-(\\d+)").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            if (code <= mine || f.name.endsWith(".part")) f.delete()
        }
    }

    fun cancelDownload() { cancel.set(true) }

    /**
     * Downloads the APK (background thread), then checks it before anything is offered to the installer:
     * the SHA-256 must match the manifest, and the package must be this app, newer, and signed with the
     * same key as the installed copy. Returns null with a user-readable reason on failure.
     */
    fun download(ctx: Context, r: Release): Pair<File?, String?> {
        if (!busy.compareAndSet(false, true)) return null to "A download is already running."
        cancel.set(false)
        val d = dir(ctx)
        val part = File(d, "fibcam-${r.versionCode}.apk.part")
        val apk = File(d, "fibcam-${r.versionCode}.apk")
        try {
            set(Status.Downloading(r, 0))
            val c = URL(r.url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null to "The download failed (HTTP ${c.responseCode})."
            val total = c.contentLengthLong
            if (total > 0 && d.usableSpace < total * 2) return null to "There isn't enough free space for the update."
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L; var lastPct = -1
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel.get()) { part.delete(); set(Status.Available(r)); return null to null }
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val pct = if (total > 0) ((got * 100) / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; set(Status.Downloading(r, pct)) }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != r.sha256) { part.delete(); return null to "The downloaded file didn't match its checksum, so it wasn't installed." }
            apk.delete(); part.renameTo(apk)
            verifyPackage(ctx, apk, r)?.let { apk.delete(); return null to it }
            set(Status.Ready(r, apk))
            return apk to null
        } catch (e: Exception) {
            part.delete()
            return null to "The download didn't finish. Check your connection and try again."
        } finally {
            busy.set(false)
            if (status is Status.Downloading) set(Status.Available(r))
        }
    }

    /** Same app, newer, same signing key — otherwise it couldn't install over this one without losing data. */
    private fun verifyPackage(ctx: Context, apk: File, r: Release): String? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file isn't a valid app package."
        if (info.packageName != ctx.packageName) return "The download isn't Fibonacci Camera, so it wasn't installed."
        if (info.longVersionCode <= installedCode(ctx)) return "The download isn't newer than this version."
        if (Build.VERSION.SDK_INT >= 28) {
            fun certs(p: android.content.pm.PackageInfo?) = p?.signingInfo?.let { si ->
                (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.map { it.toByteArray().contentHashCode() }?.toSet()
            } ?: emptySet()
            val mine = certs(runCatching { pm.getPackageInfo(ctx.packageName, flags) }.getOrNull())
            val theirs = certs(info)
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty())
                return "This update is signed differently, so Android can't install it over your current app. Your app and photos are untouched."
        }
        return null
    }
}
