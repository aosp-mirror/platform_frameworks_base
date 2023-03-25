/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.credentialmanager

import android.app.PendingIntent
import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.content.Context
import android.content.Intent
import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.credentials.ui.AuthenticationEntry
import android.credentials.ui.Entry
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.RemoteEntry

import java.time.Instant

// TODO: remove once testing is complete
class GetTestUtils {
    companion object {
        internal fun newAuthenticationEntry(
            context: Context,
            key: String,
            subkey: String,
            title: String,
            status: Int
        ): AuthenticationEntry {
            val slice = Slice.Builder(
                Uri.EMPTY, SliceSpec("AuthenticationAction", 0)
            )
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            slice.addAction(
                pendingIntent,
                Slice.Builder(slice)
                    .addHints(listOf("androidx.credentials.provider.authenticationAction" +
                        ".SLICE_HINT_PENDING_INTENT"))
                    .build(),
                /*subType=*/null
            )
            slice.addText(
                title,
                null,
                listOf("androidx.credentials.provider.authenticationAction.SLICE_HINT_TITLE")
            )
            return AuthenticationEntry(
                key,
                subkey,
                slice.build(),
                status
            )
        }

        internal fun newRemoteCredentialEntry(
            context: Context,
            key: String,
            subkey: String,
        ): Entry {
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            return Entry(
                key,
                subkey,
                RemoteEntry.toSlice(RemoteEntry(pendingIntent))
            )
        }

        internal fun newActionEntry(
            context: Context,
            key: String,
            subkey: String,
            text: String,
            subtext: String? = null,
        ): Entry {
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val sliceBuilder = Slice.Builder(Uri.EMPTY, SliceSpec("Action", 0))
                .addText(
                    text, /*subType=*/null,
                    listOf("androidx.credentials.provider.action.HINT_ACTION_TITLE")
                )
                .addText(
                    subtext, /*subType=*/null,
                    listOf("androidx.credentials.provider.action.HINT_ACTION_SUBTEXT")
                )
            sliceBuilder.addAction(
                pendingIntent,
                Slice.Builder(sliceBuilder)
                    .addHints(
                        listOf("androidx.credentials.provider.action.SLICE_HINT_PENDING_INTENT")
                    )
                    .build(),
                /*subType=*/null
            )
            return Entry(
                key,
                subkey,
                sliceBuilder.build()
            )
        }

        internal fun newPasswordEntry(
            context: Context,
            key: String,
            subkey: String,
            userName: String,
            userDisplayName: String?,
            lastUsedTime: Instant?,
        ): Entry {
            val intent = Intent("com.androidauth.androidvault.CONFIRM_PASSWORD")
                .setPackage("com.androidauth.androidvault")
            intent.putExtra("provider_extra_sample", "testprovider")
            val pendingIntent = PendingIntent.getActivity(
                context, 1,
                intent, (PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                or PendingIntent.FLAG_ONE_SHOT)
            )
            val passwordEntry = PasswordCredentialEntry.Builder(
                context, userName, pendingIntent, BeginGetPasswordOption(Bundle(), "id"))
                .setDisplayName(userDisplayName).setLastUsedTime(lastUsedTime).build()
            return Entry(key, subkey, passwordEntry.slice, Intent())
        }

        internal fun newPasskeyEntry(
            context: Context,
            key: String,
            subkey: String,
            userName: String,
            userDisplayName: String?,
            lastUsedTime: Instant?,
            isAutoSelectAllowed: Boolean = false,
        ): Entry {
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val candidateQueryData = Bundle()
            candidateQueryData.putBoolean(
                "androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED",
                isAutoSelectAllowed
            )
            val passkeyEntry = PublicKeyCredentialEntry.Builder(
                context,
                userName,
                pendingIntent,
                BeginGetPublicKeyCredentialOption(candidateQueryData, "id", "requestjson")
            ).setDisplayName(userDisplayName).setLastUsedTime(lastUsedTime)
                .setAutoSelectAllowed(isAutoSelectAllowed).build()
            return Entry(key, subkey, passkeyEntry.slice, Intent())
        }
    }
}

class CreateTestUtils {
    companion object {
        private const val TYPE_TOTAL_CREDENTIAL = "TOTAL_CREDENTIAL_COUNT_TYPE"
        private const val SLICE_HINT_ACCOUNT_NAME =
            "androidx.credentials.provider.createEntry.SLICE_HINT_USER_PROVIDER_ACCOUNT_NAME"
        private const val SLICE_HINT_NOTE =
            "androidx.credentials.provider.createEntry.SLICE_HINT_NOTE"
        private const val SLICE_HINT_ICON =
            "androidx.credentials.provider.createEntry.SLICE_HINT_PROFILE_ICON"
        private const val SLICE_HINT_CREDENTIAL_COUNT_INFORMATION =
            "androidx.credentials.provider.createEntry.SLICE_HINT_CREDENTIAL_COUNT_INFORMATION"
        private const val SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.createEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
        private const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.createEntry.SLICE_HINT_PENDING_INTENT"

        internal fun newCreateEntry(
            context: Context,
            key: String,
            subkey: String,
            providerUserDisplayName: String,
            passwordCount: Int?,
            passkeyCount: Int?,
            totalCredentialCount: Int?,
            lastUsedTime: Instant?,
            footerDescription: String?,
        ): Entry {
            val intent = Intent("com.androidauth.androidvault.CONFIRM_PASSWORD")
                .setPackage("com.androidauth.androidvault")
            intent.putExtra("provider_extra_sample", "testprovider")
            val pendingIntent = PendingIntent.getActivity(
                context, 1,
                intent, (PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                or PendingIntent.FLAG_ONE_SHOT)
            )
            val credCountMap = mutableMapOf<String, Int>()
            passwordCount?.let { credCountMap.put(TYPE_PASSWORD_CREDENTIAL, it) }
            passkeyCount?.let {
                credCountMap.put("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", it)
            }
            totalCredentialCount?.let { credCountMap.put(TYPE_TOTAL_CREDENTIAL, it) }
            return Entry(
                key,
                subkey,
                CreateEntry.toSlice(
                    CreateEntry(
                            accountName = providerUserDisplayName,
                            pendingIntent = pendingIntent,
                            description = footerDescription,
                            lastUsedTime = lastUsedTime,
                            icon = null,
                            passwordCredentialCount = passwordCount,
                            publicKeyCredentialCount = passkeyCount,
                            totalCredentialCount = totalCredentialCount,
                    )
                ),
                Intent()
            )
        }

        internal fun newRemoteCreateEntry(
            context: Context,
            key: String,
            subkey: String,
        ): Entry {
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            return Entry(
                key,
                subkey,
                RemoteEntry.toSlice(RemoteEntry(pendingIntent))
            )
        }
    }
}