package com.example

import com.example.ui.formatBytes
import com.example.ui.formatDuration
import com.example.ui.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {
  @Test
  fun formatBytesHandlesUnitsCorrectly() {
    assertEquals("0 B", formatBytes(0L))
    assertEquals("512 B", formatBytes(512L))
    assertEquals("1.5 KB", formatBytes(1536L))
    assertEquals("2.0 MB", formatBytes(2L * 1024L * 1024L))
  }

  @Test
  fun formatSpeedFormatsPerSecond() {
    assertEquals("", formatSpeed(0L))
    assertEquals("1.5 MB/s", formatSpeed((1.5 * 1024 * 1024).toLong()))
  }

  @Test
  fun formatDurationFormatsMinutesAndHours() {
    assertEquals("00:05", formatDuration(5L))
    assertEquals("02:05", formatDuration(125L))
    assertEquals("1:01:05", formatDuration(3665L))
  }
}
