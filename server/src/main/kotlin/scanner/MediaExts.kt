package wtf.jobin.scanner

/**
 * Shared media file extension allowlist. Lowercase. Used by [MediaScanner] when walking
 * a library root and by [LibraryWatcher] when filtering filesystem events.
 */
internal val MEDIA_EXTS: Set<String> = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts")
