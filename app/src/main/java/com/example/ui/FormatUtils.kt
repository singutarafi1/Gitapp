package com.example.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
  val safe = bytes.coerceAtLeast(0L)
  if (safe < 1024L) return "$safe B"
  val kb = safe / 1024.0
  if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bytesPerSecond: Long): String {
  if (bytesPerSecond <= 0L) return ""
  return "${formatBytes(bytesPerSecond)}/s"
}

fun formatDuration(totalSeconds: Long): String {
  val clamped = totalSeconds.coerceAtLeast(0L)
  val hours = clamped / 3600L
  val minutes = (clamped % 3600L) / 60L
  val seconds = clamped % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%02d:%02d", minutes, seconds)
  }
}
