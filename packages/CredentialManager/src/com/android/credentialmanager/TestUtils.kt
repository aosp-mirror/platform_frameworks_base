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
import android.credentials.ui.Entry
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings
import androidx.credentials.provider.CreateEntry

import java.time.Instant

// TODO: remove once testing is complete
class GetTestUtils {
    companion object {
        internal fun newAuthenticationEntry(
            context: Context,
            key: String,
            subkey: String,
        ): Entry {
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
            return Entry(
                key,
                subkey,
                slice.build()
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

        private const val SLICE_HINT_TYPE_DISPLAY_NAME =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
        private const val SLICE_HINT_TITLE =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_USER_NAME"
        private const val SLICE_HINT_SUBTITLE =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
        private const val SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
        private const val SLICE_HINT_ICON =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_PROFILE_ICON"
        private const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_PENDING_INTENT"
        private const val SLICE_HINT_AUTO_ALLOWED =
            "androidx.credentials.provider.passwordCredentialEntry.SLICE_HINT_AUTO_ALLOWED"
        private const val AUTO_SELECT_TRUE_STRING = "true"
        private const val AUTO_SELECT_FALSE_STRING = "false"

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
            return Entry(
                key,
                subkey,
                toPasswordSlice(userName, userDisplayName, pendingIntent, lastUsedTime),
                Intent()
            )
        }

        private fun toPasswordSlice(
            title: CharSequence,
            subTitle: CharSequence?,
            pendingIntent: PendingIntent,
            lastUsedTime: Instant?,
            icon: Icon? = null,
            isAutoSelectAllowed: Boolean = true
        ): Slice {
            val type = TYPE_PASSWORD_CREDENTIAL
            val autoSelectAllowed = if (isAutoSelectAllowed) {
                AUTO_SELECT_TRUE_STRING
            } else {
                AUTO_SELECT_FALSE_STRING
            }
            val sliceBuilder = Slice.Builder(
                Uri.EMPTY, SliceSpec(
                    type, 1
                )
            )
                .addText(
                    "Password", /*subType=*/null,
                    listOf(SLICE_HINT_TYPE_DISPLAY_NAME)
                )
                .addText(
                    title, /*subType=*/null,
                    listOf(SLICE_HINT_TITLE)
                )
                .addText(
                    subTitle, /*subType=*/null,
                    listOf(SLICE_HINT_SUBTITLE)
                )
                .addText(
                    autoSelectAllowed, /*subType=*/null,
                    listOf(SLICE_HINT_AUTO_ALLOWED)
                )
            if (lastUsedTime != null) {
                sliceBuilder.addLong(
                    lastUsedTime.toEpochMilli(),
                    /*subType=*/null,
                    listOf(SLICE_HINT_LAST_USED_TIME_MILLIS)
                )
            }
            if (icon != null) {
                sliceBuilder.addIcon(
                    icon, /*subType=*/null,
                    listOf(SLICE_HINT_ICON)
                )
            }
            sliceBuilder.addAction(
                pendingIntent,
                Slice.Builder(sliceBuilder)
                    .addHints(listOf(SLICE_HINT_PENDING_INTENT))
                    .build(),
                /*subType=*/null
            )
            return sliceBuilder.build()
        }


        private const val PASSKEY_SLICE_HINT_TYPE_DISPLAY_NAME =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
        private const val PASSKEY_SLICE_HINT_TITLE =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_USER_NAME"
        private const val PASSKEY_SLICE_HINT_SUBTITLE =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
        private const val PASSKEY_SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
        private const val PASSKEY_SLICE_HINT_ICON =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_PROFILE_ICON"
        private const val PASSKEY_SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_PENDING_INTENT"
        private const val PASSKEY_SLICE_HINT_AUTO_ALLOWED =
            "androidx.credentials.provider.publicKeyCredEntry.SLICE_HINT_AUTO_ALLOWED"

        internal fun newPasskeyEntry(
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
            return Entry(
                key, subkey, toPasskeySlice(
                    userName, userDisplayName, pendingIntent, lastUsedTime
                ),
                Intent()
            )
        }

        private fun toPasskeySlice(
            title: CharSequence,
            subTitle: CharSequence?,
            pendingIntent: PendingIntent,
            lastUsedTime: Instant?,
            icon: Icon? = null,
            isAutoSelectAllowed: Boolean = true
        ): Slice {
            val type = "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL"
            val autoSelectAllowed = if (isAutoSelectAllowed) {
                AUTO_SELECT_TRUE_STRING
            } else {
                AUTO_SELECT_FALSE_STRING
            }
            val sliceBuilder = Slice.Builder(
                Uri.EMPTY, SliceSpec(
                    type, 1
                )
            )
                .addText(
                    "Passkey", /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_TYPE_DISPLAY_NAME)
                )
                .addText(
                    title, /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_TITLE)
                )
                .addText(
                    subTitle, /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_SUBTITLE)
                )
                .addText(
                    autoSelectAllowed, /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_AUTO_ALLOWED)
                )
            if (lastUsedTime != null) {
                sliceBuilder.addLong(
                    lastUsedTime.toEpochMilli(),
                    /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_LAST_USED_TIME_MILLIS)
                )
            }
            if (icon != null) {
                sliceBuilder.addIcon(
                    icon, /*subType=*/null,
                    listOf(PASSKEY_SLICE_HINT_ICON)
                )
            }
            sliceBuilder.addAction(
                pendingIntent,
                Slice.Builder(sliceBuilder)
                    .addHints(listOf(PASSKEY_SLICE_HINT_PENDING_INTENT))
                    .build(),
                /*subType=*/null
            )
            return sliceBuilder.build()
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
                    providerUserDisplayName,
                    null,
                    footerDescription,
                    lastUsedTime,
                    credCountMap,
                    pendingIntent
                ),
                Intent()
            )
        }
    }
}