package com.example

import com.example.data.remote.StreamingBlobRequestBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingBlobRequestBodyTest {

  @Test
  fun streamsExactJsonAndContentLengthAcrossSizes() {
    val sizes = listOf(0, 1, 2, 3, 49_151, 49_152, 49_153, 120_005)
    for (size in sizes) {
      val payload = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
      var trackedDelta = 0L
      val body = StreamingBlobRequestBody(payload) { delta -> trackedDelta += delta }
      val buffer = Buffer()
      body.writeTo(buffer)
      val actualJson = buffer.readUtf8()

      val expectedJson = "{\"encoding\":\"base64\",\"content\":\"${payload.toByteString().base64()}\"}"
      assertEquals("Mismatch for size=$size", expectedJson, actualJson)
      assertEquals("ContentLength mismatch for size=$size", expectedJson.length.toLong(), body.contentLength())
      assertEquals("Tracked delta mismatch for size=$size", size.toLong(), trackedDelta)

      body.rollbackReportedBytes()
      assertEquals("Rollback mismatch for size=$size", 0L, trackedDelta)
    }
  }
}
