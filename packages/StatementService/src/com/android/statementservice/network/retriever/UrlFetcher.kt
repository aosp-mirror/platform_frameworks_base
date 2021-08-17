/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.statementservice.network.retriever

import android.net.Network
import android.net.TrafficStats
import android.util.Log
import com.android.statementservice.utils.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import javax.net.ssl.HttpsURLConnection

class UrlFetcher {

    companion object {
        private val TAG = UrlFetcher::class.java.simpleName
    }

    suspend fun fetch(
        url: URL,
        connectionTimeoutMillis: Int,
        fileSizeLimit: Long,
        network: Network? = null
    ) = withContext(Dispatchers.IO) {
        TrafficStats.setThreadStatsTag(Thread.currentThread().id.toInt())
        @Suppress("BlockingMethodInNonBlockingContext")
        val connection =
            ((network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection)
        try {
            connection.apply {
                connectTimeout = connectionTimeoutMillis
                readTimeout = connectionTimeoutMillis
                useCaches = true
                instanceFollowRedirects = false
                addRequestProperty("Cache-Control", "max-stale=60")
            }
            val responseCode = connection.responseCode
            when {
                responseCode != HttpURLConnection.HTTP_OK -> {
                    Log.w(TAG, "The responses code is not 200 but $responseCode")
                    Result.Success(Response(responseCode))
                }
                connection.contentLength > fileSizeLimit -> {
                    Log.w(TAG, "The content size of the url is larger than $fileSizeLimit")
                    Result.Success(Response(responseCode))
                }
                else -> {
                    val content = async {
                        connection.inputStream
                            .bufferedReader(Charset.forName("UTF-8"))
                            .readText()
                    }

                    Result.Success(Response(responseCode, content.await()))
                }
            }
        } catch (ignored: Throwable) {
            Result.Failure(ignored)
        } finally {
            connection.disconnect()
        }
    }

    data class Response(
        val responseCode: Int,
        val content: String? = null
    )
}
