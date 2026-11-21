package com.example.data.remote

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ByteString.Companion.toByteString

internal class StreamingBlobRequestBody(
  private val bytes: ByteArray,
  private val onBytesDelta: (Long) -> Unit = {},
) : RequestBody() {

  private var reportedBytesInLastAttempt: Long = 0L

  override fun contentType(): MediaType = JSON_MEDIA_TYPE

  override fun contentLength(): Long {
    val encodedLength =
      if (bytes.isEmpty()) 0L else ((bytes.size.toLong() + 2L) / 3L) * 4L
    return PREFIX_BYTES.size.toLong() + encodedLength + SUFFIX_BYTES.size.toLong()
  }

  override fun writeTo(sink: BufferedSink) {
    if (reportedBytesInLastAttempt > 0L) {
      onBytesDelta(-reportedBytesInLastAttempt)
      reportedBytesInLastAttempt = 0L
    }

    sink.write(PREFIX_BYTES)
    var offset = 0
    while (offset < bytes.size) {
      val chunkLen = minOf(CHUNK_SIZE_BYTES, bytes.size - offset)
      val base64Chunk = bytes.toByteString(offset, chunkLen).base64()
      sink.writeUtf8(base64Chunk)
      offset += chunkLen
      reportedBytesInLastAttempt += chunkLen.toLong()
      onBytesDelta(chunkLen.toLong())
    }
    sink.write(SUFFIX_BYTES)
  }

  fun rollbackReportedBytes() {
    if (reportedBytesInLastAttempt > 0L) {
      onBytesDelta(-reportedBytesInLastAttempt)
      reportedBytesInLastAttempt = 0L
    }
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val PREFIX_BYTES = "{\"encoding\":\"base64\",\"content\":\"".toByteArray(Charsets.UTF_8)
    private val SUFFIX_BYTES = "\"}".toByteArray(Charsets.UTF_8)

    // Must remain divisible by 3 so intermediate chunks have no '=' padding.
    private const val CHUNK_SIZE_BYTES = 48 * 1024
  }
}
