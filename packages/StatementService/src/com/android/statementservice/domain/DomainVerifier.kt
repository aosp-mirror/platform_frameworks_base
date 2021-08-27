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

package com.android.statementservice.domain

import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.net.Network
import android.util.Log
import androidx.collection.LruCache
import com.android.statementservice.network.retriever.StatementRetriever
import com.android.statementservice.retriever.AbstractAsset
import com.android.statementservice.retriever.AbstractAssetMatcher
import com.android.statementservice.utils.Result
import com.android.statementservice.utils.StatementUtils
import com.android.statementservice.utils.component1
import com.android.statementservice.utils.component2
import com.android.statementservice.utils.component3
import java.net.HttpURLConnection
import java.util.Optional
import java.util.UUID

private typealias WorkResult = androidx.work.ListenableWorker.Result

class DomainVerifier private constructor(
    private val appContext: Context,
    private val manager: DomainVerificationManager
) {
    companion object {
        private val TAG = DomainVerifier::class.java.simpleName
        private const val DEBUG = false

        private var singleton: DomainVerifier? = null

        fun getInstance(context: Context) = when {
            singleton != null -> singleton!!
            else -> synchronized(this) {
                if (singleton == null) {
                    val appContext = context.applicationContext
                    val manager =
                        appContext.getSystemService(DomainVerificationManager::class.java)!!
                    singleton = DomainVerifier(appContext, manager)
                }
                singleton!!
            }
        }
    }

    private val retriever = StatementRetriever()

    private val targetAssetCache = AssetLruCache()

    fun collectHosts(packageNames: Iterable<String>): Iterable<Triple<UUID, String, String>> {
        return packageNames.mapNotNull { packageName ->
            val (domainSetId, _, hostToStateMap) = try {
                manager.getDomainVerificationInfo(packageName)
            } catch (ignored: Exception) {
                // Package disappeared, assume it will be rescheduled if the package reappears
                null
            } ?: return@mapNotNull null

            val hostsToRetry = hostToStateMap
                .filterValues(VerifyStatus::shouldRetry)
                .takeIf { it.isNotEmpty() }
                ?.map { it.key }
                ?: return@mapNotNull null

            hostsToRetry.map { Triple(domainSetId, packageName, it) }
        }
            .flatten()
    }

    suspend fun verifyHost(
        host: String,
        packageName: String,
        network: Network? = null
    ): Pair<WorkResult, VerifyStatus> {
        val assetMatcher = synchronized(targetAssetCache) { targetAssetCache[packageName] }
            .takeIf { it!!.isPresent }
            ?: return WorkResult.failure() to VerifyStatus.FAILURE_PACKAGE_MANAGER
        return verifyHost(host, assetMatcher.get(), network)
    }

    private suspend fun verifyHost(
        host: String,
        assetMatcher: AbstractAssetMatcher,
        network: Network? = null
    ): Pair<WorkResult, VerifyStatus> {
        var exception: Exception? = null
        val resultAndStatus = try {
            val sourceAsset = StatementUtils.createWebAssetString(host)
                .let(AbstractAsset::create)
            val result = retriever.retrieve(sourceAsset, network)
                ?: return WorkResult.success() to VerifyStatus.FAILURE_UNKNOWN
            when (result.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP -> {
                    WorkResult.failure() to VerifyStatus.FAILURE_REDIRECT
                }
                else -> {
                    val isVerified = result.statements.any { statement ->
                        (StatementUtils.RELATION.matches(statement.relation) &&
                                assetMatcher.matches(statement.target))
                    }

                    if (isVerified) {
                        WorkResult.success() to VerifyStatus.SUCCESS
                    } else {
                        WorkResult.failure() to VerifyStatus.FAILURE_REJECTED_BY_SERVER
                    }
                }
            }
        } catch (e: Exception) {
            exception = e
            WorkResult.retry() to VerifyStatus.FAILURE_UNKNOWN
        }

        if (DEBUG) {
            Log.d(TAG, "Verifying $host: ${resultAndStatus.second}", exception)
        }

        return resultAndStatus
    }

    private inner class AssetLruCache : LruCache<String, Optional<AbstractAssetMatcher>>(50) {
        override fun create(packageName: String) =
            StatementUtils.getCertFingerprintsFromPackageManager(appContext, packageName)
                .let { (it as? Result.Success)?.value }
                ?.let { StatementUtils.createAndroidAsset(packageName, it) }
                ?.let(AbstractAssetMatcher::createMatcher)
                .let { Optional.ofNullable(it) }
    }
}
