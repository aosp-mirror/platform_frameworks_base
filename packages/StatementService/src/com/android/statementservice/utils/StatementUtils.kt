/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.statementservice.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Patterns
import com.android.statementservice.retriever.Relation
import java.net.URL
import java.security.MessageDigest

internal object StatementUtils {

    /**
     * Field name for namespace.
     */
    const val NAMESPACE_FIELD = "namespace"

    /**
     * Supported asset namespaces.
     */
    const val NAMESPACE_WEB = "web"
    const val NAMESPACE_ANDROID_APP = "android_app"

    /**
     * Field names in a web asset descriptor.
     */
    const val WEB_ASSET_FIELD_SITE = "site"

    /**
     * Field names in a Android app asset descriptor.
     */
    const val ANDROID_APP_ASSET_FIELD_PACKAGE_NAME = "package_name"
    const val ANDROID_APP_ASSET_FIELD_CERT_FPS = "sha256_cert_fingerprints"

    /**
     * Field names in a statement.
     */
    const val ASSET_DESCRIPTOR_FIELD_RELATION = "relation"
    const val ASSET_DESCRIPTOR_FIELD_TARGET = "target"
    const val DELEGATE_FIELD_DELEGATE = "include"

    val HEX_DIGITS =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    val RELATION by lazy { Relation.create("delegate_permission/common.handle_all_urls") }
    private const val ANDROID_ASSET_FORMAT =
        """{"namespace": "android_app", "package_name": "%s", "sha256_cert_fingerprints": [%s]}"""
    private const val WEB_ASSET_FORMAT = """{"namespace": "web", "site": "%s"}"""

    private val digesterSha256 by lazy { tryOrNull { MessageDigest.getInstance("SHA-256") } }

    internal inline fun <T> tryOrNull(block: () -> T) =
        try {
            block()
        } catch (ignored: Exception) {
            null
        }

    /**
     * Returns the normalized sha-256 fingerprints of a given package according to the Android
     * package manager.
     */
    fun getCertFingerprintsFromPackageManager(
        context: Context,
        packageName: String
    ): Result<List<String>> {
        val signingInfo = try {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_ANY_USER
            )
                .signingInfo
        } catch (e: Exception) {
            return Result.Failure(e)
        }
        checkNotNull(signingInfo)
        return if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }.map {
            val result = computeNormalizedSha256Fingerprint(it.toByteArray())
            if (result is Result.Failure) {
                return result.asType()
            } else {
                (result as Result.Success).value
            }
        }.let { Result.Success(it) }
    }

    /**
     * Computes the hash of the byte array using the specified algorithm, returning a hex string
     * with a colon between each byte.
     */
    fun computeNormalizedSha256Fingerprint(signature: ByteArray) =
        digesterSha256?.digest(signature)
            ?.let(StatementUtils::bytesToHexString)
            ?.let { Result.Success(it) }
            ?: Result.Failure()

    private fun bytesToHexString(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 3 - 1)
        var bufIndex = 0
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xFF
            if (index > 0) {
                hexChars[bufIndex++] = ':'
            }

            hexChars[bufIndex++] = HEX_DIGITS[byte ushr 4]
            hexChars[bufIndex++] = HEX_DIGITS[byte and 0x0F]
        }
        return String(hexChars)
    }

    fun createAndroidAssetString(context: Context, packageName: String): Result<String> {
        val result = getCertFingerprintsFromPackageManager(context, packageName)
        if (result is Result.Failure) {
            return result.asType()
        }
        return Result.Success(
            ANDROID_ASSET_FORMAT.format(
                packageName,
                (result as Result.Success).value.joinToString(separator = "\", \"")
            )
        )
    }

    fun createAndroidAsset(packageName: String, certFingerprints: List<String>) =
        String.format(
            ANDROID_ASSET_FORMAT,
            packageName,
            certFingerprints.joinToString(separator = ", ") { "\"$it\"" })

    fun createWebAssetString(scheme: String, host: String): Result<String> {
        if (!Patterns.DOMAIN_NAME.matcher(host).matches()) {
            return Result.Failure("Input host is not valid.")
        }
        if (scheme != "http" && scheme != "https") {
            return Result.Failure("Input scheme is not valid.")
        }
        return Result.Success(WEB_ASSET_FORMAT.format(URL(scheme, host, "").toString()))
    }

    // Hosts with *. for wildcard subdomain support are verified against their root domain
    fun createWebAssetString(host: String) =
        WEB_ASSET_FORMAT.format(URL("https", host.removePrefix("*."), "").toString())
}
