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

import android.content.Intent
import android.net.Network
import com.android.statementservice.retriever.AbstractAsset
import com.android.statementservice.retriever.AndroidAppAsset
import com.android.statementservice.retriever.Statement
import com.android.statementservice.retriever.WebAsset
import com.android.statementservice.utils.StatementUtils.tryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Retrieves the JSON configured at a given domain that's compliant with the Digital Asset Links
 * specification, returning the list of statements which serve as assertions by the web server as
 * to what other assets it can be connected with.
 *
 * Relevant to this app, it allows the website to report which Android app package and signature
 * digest has been approved by the website owner, which considers them as the same author and safe
 * to automatically delegate web [Intent]s to.
 *
 * The relevant data classes are [WebAsset], [AndroidAppAsset], and [Statement].
 */
class StatementRetriever {

    companion object {
        private const val HTTP_CONNECTION_TIMEOUT_MILLIS = 5000
        private const val HTTP_CONTENT_SIZE_LIMIT_IN_BYTES = (1024 * 1024).toLong()
        private const val MAX_INCLUDE_LEVEL = 1
        private const val WELL_KNOWN_STATEMENT_PATH = "/.well-known/assetlinks.json"
    }

    private val fetcher = UrlFetcher()

    data class Result(
        val statements: List<Statement>,
        val responseCode: Int?
    ) {
        companion object {
            val EMPTY = Result(emptyList(), null)
        }

        constructor(statements: List<Statement>, webResult: UrlFetcher.Response) : this(
            statements,
            webResult.responseCode
        )
    }

    suspend fun retrieve(source: AbstractAsset, network: Network? = null) = when (source) {
        // TODO:(b/171219506): Does this have to be implemented?
        is AndroidAppAsset -> null
        is WebAsset -> retrieveFromWeb(source, network)
        else -> null
    }

    private suspend fun retrieveFromWeb(asset: WebAsset, network: Network? = null): Result? {
        val url = computeAssociationJsonUrl(asset) ?: return null
        return retrieve(url, MAX_INCLUDE_LEVEL, asset, network)
    }

    private fun computeAssociationJsonUrl(asset: WebAsset) = tryOrNull {
        URL(asset.scheme, asset.domain, asset.port, WELL_KNOWN_STATEMENT_PATH).toExternalForm()
    }

    private suspend fun retrieve(
        urlString: String,
        maxIncludeLevel: Int,
        source: AbstractAsset,
        network: Network? = null
    ): Result {
        if (maxIncludeLevel < 0) {
            return Result.EMPTY
        }

        return withContext(Dispatchers.IO) {
            val url = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                URL(urlString)
            } catch (ignored: Exception) {
                return@withContext Result.EMPTY
            }

            val webResponse = fetcher.fetch(
                url = url,
                connectionTimeoutMillis = HTTP_CONNECTION_TIMEOUT_MILLIS,
                fileSizeLimit = HTTP_CONTENT_SIZE_LIMIT_IN_BYTES,
                network
            ).successValueOrNull() ?: return@withContext Result.EMPTY

            val content = webResponse.content ?: return@withContext Result(emptyList(), webResponse)
            val (statements, delegates) = StatementParser.parseStatementList(content, source)
                .successValueOrNull() ?: return@withContext Result(emptyList(), webResponse)

            val delegatedStatements = delegates
                .map { async { retrieve(it, maxIncludeLevel - 1, source).statements } }
                .awaitAll()
                .flatten()

            Result(statements + delegatedStatements, webResponse)
        }
    }
}
