package com.example

import com.example.data.model.DeleteTreeEntryRequest
import com.example.data.model.DeleteTreeRequest
import com.squareup.moshi.Moshi
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteTreeSerializationTest {

  @Test
  fun deleteTreeRequest_serializesExplicitNullSha() {
    val moshi = Moshi.Builder().build()
    val adapter = moshi.adapter(DeleteTreeRequest::class.java).serializeNulls()
    val json =
      adapter.toJson(
        DeleteTreeRequest(
          baseTree = "tree123",
          tree =
            listOf(
              DeleteTreeEntryRequest(
                path = "Da/test.bin",
                mode = "100644",
                type = "blob",
                sha = null,
              )
            ),
        )
      )
    assertTrue("Expected base_tree in JSON: $json", json.contains(""""base_tree":"tree123""""))
    assertTrue("Expected explicit sha:null in JSON: $json", json.contains(""""sha":null"""))
  }
}
