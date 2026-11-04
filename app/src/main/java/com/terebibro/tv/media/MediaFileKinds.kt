package com.terebibro.tv.media

/**
 * Extensions the media band can play. Pure-JVM so the local-library rules can
 * be unit-tested without touching the filesystem.
 *
 * A name whose last dot is its first character (`.mp4`) or its last character
 * (`clip.`) has no extension: Android's document providers treat such names as
 * extensionless, and so does this.
 */
object MediaFileKinds {

    fun of(fileName: String): MediaItem.Kind? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return when (fileName.substring(dot + 1).lowercase()) {
            "mp4" -> MediaItem.Kind.VIDEO
            "jpg", "jpeg", "png" -> MediaItem.Kind.IMAGE
            else -> null
        }
    }
}
