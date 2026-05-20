package com.example

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun testFetchWolfxHistory() {
    try {
      println("--- FETCHING WOLFX CENC EQLIST ---")
      val client = OkHttpClient()
      val request = Request.Builder()
        .url("https://api.wolfx.jp/cenc_eqlist.json")
        .header("User-Agent", "Mozilla/5.0")
        .build()
      
      val response = client.newCall(request).execute()
      val text = response.body?.string() ?: ""
      println("Length: ${text.length}")
      println(text.take(3000))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}









