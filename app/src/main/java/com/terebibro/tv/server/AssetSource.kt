package com.terebibro.tv.server

import android.content.Context

/** Reads the bundled controller assets from `assets/controller`. */
class AssetSource(private val context: Context) {

    fun read(fileName: String): String? {
        return try {
            context.assets.open("controller/$fileName").use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun mimeType(fileName: String): String = when {
        fileName.endsWith(".html") -> "text/html; charset=utf-8"
        fileName.endsWith(".css") -> "text/css; charset=utf-8"
        fileName.endsWith(".js") -> "text/javascript; charset=utf-8"
        else -> "application/octet-stream"
    }
}
