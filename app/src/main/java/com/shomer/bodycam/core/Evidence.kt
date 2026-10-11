package com.shomer.bodycam.core

import com.shomer.bodycam.data.Recording
import java.io.File
import java.security.MessageDigest

/**
 * Evidence integrity: every saved recording gets a SHA-256 fingerprint the moment it is written.
 * The fingerprint is kept in the app and ALSO written next to the video as `<video>.sha256` in the
 * standard `sha256sum` format, so anyone can check the file on a computer (`sha256sum -c`).
 * If even one byte of the video changes later, the fingerprint no longer matches.
 */
object Evidence {
    enum class Check { INTACT, CHANGED, MISSING, NO_FINGERPRINT }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sidecar(video: File) = File(video.parentFile, video.name + ".sha256")

    fun writeSidecar(r: Recording) {
        val hash = r.sha256 ?: return
        sidecar(r.file).writeText("$hash  ${r.file.name}\n")
    }

    fun verify(r: Recording): Check = when {
        !r.file.exists() -> Check.MISSING
        r.sha256 == null -> Check.NO_FINGERPRINT
        sha256(r.file).equals(r.sha256, ignoreCase = true) -> Check.INTACT
        else -> Check.CHANGED
    }

    /** Short, readable form for the screen: first and last 8 characters. */
    fun short(hash: String) = if (hash.length <= 20) hash else hash.take(8) + "…" + hash.takeLast(8)
}
