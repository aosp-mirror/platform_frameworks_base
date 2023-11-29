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

package com.android.credentialmanager.ktx

import android.app.slice.Slice
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.credentials.Credential
import android.credentials.flags.Flags
import android.credentials.ui.AuthenticationEntry
import android.credentials.ui.Entry
import android.credentials.ui.GetCredentialProviderData
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.Action
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.RemoteEntry
import com.android.credentialmanager.IS_AUTO_SELECTED_KEY
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.model.get.RemoteEntryInfo
import com.android.credentialmanager.TAG

fun CredentialEntryInfo.getIntentSenderRequest(
    isAutoSelected: Boolean = false
): IntentSenderRequest? {
    val entryIntent = fillInIntent?.putExtra(IS_AUTO_SELECTED_KEY, isAutoSelected)

    return pendingIntent?.let{
        IntentSenderRequest
            .Builder(pendingIntent = it)
            .setFillInIntent(entryIntent)
            .build()
    }
}

// Returns the list (potentially empty) of enabled provider.
fun List<GetCredentialProviderData>.toProviderList(
    context: Context,
): List<ProviderInfo> {
    val providerList: MutableList<ProviderInfo> = mutableListOf()
    this.forEach {
        val providerLabelAndIcon = getServiceLabelAndIcon(
            context.packageManager,
            it.providerFlattenedComponentName
        ) ?: return@forEach
        val (providerLabel, providerIcon) = providerLabelAndIcon
        providerList.add(
            ProviderInfo(
                id = it.providerFlattenedComponentName,
                icon = providerIcon,
                displayName = providerLabel,
                credentialEntryList = getCredentialOptionInfoList(
                    providerId = it.providerFlattenedComponentName,
                    providerLabel = providerLabel,
                    credentialEntries = it.credentialEntries,
                    context = context
                ),
                authenticationEntryList = getAuthenticationEntryList(
                    it.providerFlattenedComponentName,
                    providerLabel,
                    providerIcon,
                    it.authenticationEntries),
                remoteEntry = getRemoteEntry(
                    it.providerFlattenedComponentName,
                    it.remoteEntry
                ),
                actionEntryList = getActionEntryList(
                    it.providerFlattenedComponentName, it.actionChips, providerIcon
                ),
            )
        )
    }
    return providerList
}

/**
 * Note: caller required handle empty list due to parsing error.
 */
private fun getCredentialOptionInfoList(
    providerId: String,
    providerLabel: String,
    credentialEntries: List<Entry>,
    context: Context,
): List<CredentialEntryInfo> {
    val result: MutableList<CredentialEntryInfo> = mutableListOf()
    credentialEntries.forEach {
        val credentialEntry = it.slice.credentialEntry
        when (credentialEntry) {
            is PasswordCredentialEntry -> {
                result.add(
                    CredentialEntryInfo(
                    providerId = providerId,
                    providerDisplayName = providerLabel,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = credentialEntry.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    credentialType = CredentialType.PASSWORD,
                    credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                    userName = credentialEntry.username.toString(),
                    displayName = credentialEntry.displayName?.toString(),
                    icon = credentialEntry.icon.loadDrawable(context),
                    shouldTintIcon = credentialEntry.isDefaultIcon,
                    lastUsedTimeMillis = credentialEntry.lastUsedTime,
                    isAutoSelectable = credentialEntry.isAutoSelectAllowed &&
                            credentialEntry.autoSelectAllowedFromOption,
                )
                )
            }
            is PublicKeyCredentialEntry -> {
                result.add(
                    CredentialEntryInfo(
                    providerId = providerId,
                    providerDisplayName = providerLabel,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = credentialEntry.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    credentialType = CredentialType.PASSKEY,
                    credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                    userName = credentialEntry.username.toString(),
                    displayName = credentialEntry.displayName?.toString(),
                    icon = credentialEntry.icon.loadDrawable(context),
                    shouldTintIcon = credentialEntry.isDefaultIcon,
                    lastUsedTimeMillis = credentialEntry.lastUsedTime,
                    isAutoSelectable = credentialEntry.isAutoSelectAllowed &&
                            credentialEntry.autoSelectAllowedFromOption,
                )
                )
            }
            is CustomCredentialEntry -> {
                result.add(
                    CredentialEntryInfo(
                    providerId = providerId,
                    providerDisplayName = providerLabel,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = credentialEntry.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    credentialType = CredentialType.UNKNOWN,
                    credentialTypeDisplayName =
                    credentialEntry.typeDisplayName?.toString().orEmpty(),
                    userName = credentialEntry.title.toString(),
                    displayName = credentialEntry.subtitle?.toString(),
                    icon = credentialEntry.icon.loadDrawable(context),
                    shouldTintIcon = credentialEntry.isDefaultIcon,
                    lastUsedTimeMillis = credentialEntry.lastUsedTime,
                    isAutoSelectable = credentialEntry.isAutoSelectAllowed &&
                            credentialEntry.autoSelectAllowedFromOption,
                )
                )
            }
            else -> Log.d(
                TAG,
                "Encountered unrecognized credential entry ${it.slice.spec?.type}"
            )
        }
    }
    return result
}
val Slice.credentialEntry: CredentialEntry?
    get() =
        try {
            when (spec?.type) {
                Credential.TYPE_PASSWORD_CREDENTIAL -> PasswordCredentialEntry.fromSlice(this)!!
                PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL ->
                    PublicKeyCredentialEntry.fromSlice(this)!!

                else -> CustomCredentialEntry.fromSlice(this)!!
            }
        } catch (e: Exception) {
            // Try CustomCredentialEntry.fromSlice one last time in case the cause was a failed
            // password / passkey parsing attempt.
            CustomCredentialEntry.fromSlice(this)
        }


/**
 * Note: caller required handle empty list due to parsing error.
 */
private fun getAuthenticationEntryList(
    providerId: String,
    providerDisplayName: String,
    providerIcon: Drawable,
    authEntryList: List<AuthenticationEntry>,
): List<AuthenticationEntryInfo> {
    val result: MutableList<AuthenticationEntryInfo> = mutableListOf()
    authEntryList.forEach { entry ->
        val structuredAuthEntry =
            AuthenticationAction.fromSlice(entry.slice) ?: return@forEach

        val title: String =
            structuredAuthEntry.title.toString().ifEmpty { providerDisplayName }

        result.add(
            AuthenticationEntryInfo(
            providerId = providerId,
            entryKey = entry.key,
            entrySubkey = entry.subkey,
            pendingIntent = structuredAuthEntry.pendingIntent,
            fillInIntent = entry.frameworkExtrasIntent,
            title = title,
            providerDisplayName = providerDisplayName,
            icon = providerIcon,
            isUnlockedAndEmpty = entry.status != AuthenticationEntry.STATUS_LOCKED,
            isLastUnlocked =
            entry.status == AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT
        )
        )
    }
    return result
}

private fun getRemoteEntry(providerId: String, remoteEntry: Entry?): RemoteEntryInfo? {
    if (remoteEntry == null) {
        return null
    }
    val structuredRemoteEntry = RemoteEntry.fromSlice(remoteEntry.slice)
        ?: return null
    return RemoteEntryInfo(
        providerId = providerId,
        entryKey = remoteEntry.key,
        entrySubkey = remoteEntry.subkey,
        pendingIntent = structuredRemoteEntry.pendingIntent,
        fillInIntent = remoteEntry.frameworkExtrasIntent,
    )
}

/**
 * Note: caller required handle empty list due to parsing error.
 */
private fun getActionEntryList(
    providerId: String,
    actionEntries: List<Entry>,
    providerIcon: Drawable,
): List<ActionEntryInfo> {
    val result: MutableList<ActionEntryInfo> = mutableListOf()
    actionEntries.forEach {
        val actionEntryUi = Action.fromSlice(it.slice) ?: return@forEach
        result.add(
            ActionEntryInfo(
            providerId = providerId,
            entryKey = it.key,
            entrySubkey = it.subkey,
            pendingIntent = actionEntryUi.pendingIntent,
            fillInIntent = it.frameworkExtrasIntent,
            title = actionEntryUi.title.toString(),
            icon = providerIcon,
            subTitle = actionEntryUi.subtitle?.toString(),
        )
        )
    }
    return result
}



private fun getServiceLabelAndIcon(
    pm: PackageManager,
    providerFlattenedComponentName: String
): Pair<String, Drawable>? {
    var providerLabel: String? = null
    var providerIcon: Drawable? = null
    val component = ComponentName.unflattenFromString(providerFlattenedComponentName)
    if (component == null) {
        // Test data has only package name not component name.
        // For test data usage only.
        try {
            val pkgInfo = if (Flags.instantAppsEnabled()) {
                getPackageInfo(pm, providerFlattenedComponentName)
            } else {
                pm.getPackageInfo(
                    providerFlattenedComponentName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            }
            val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
            providerLabel =
                applicationInfo.loadSafeLabel(
                    pm, 0f,
                    TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
                ).toString()
            providerIcon = applicationInfo.loadIcon(pm)
        } catch (e: Exception) {
            Log.e(TAG, "Provider package info not found", e)
        }
    } else {
        try {
            val si = pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
            providerLabel = si.loadSafeLabel(
                pm, 0f,
                TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
            ).toString()
            providerIcon = si.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Provider service info not found", e)
            // Added for mdoc use case where the provider may not need to register a service and
            // instead only relies on the registration api.
            try {
                val pkgInfo = if (Flags.instantAppsEnabled()) {
                    getPackageInfo(pm, providerFlattenedComponentName)
                } else {
                    pm.getPackageInfo(
                        component.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                }
                val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
                providerLabel =
                    applicationInfo.loadSafeLabel(
                        pm, 0f,
                        TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
                    ).toString()
                providerIcon = applicationInfo.loadIcon(pm)
            } catch (e: Exception) {
                Log.e(TAG, "Provider package info not found", e)
            }
        }
    }
    return if (providerLabel == null || providerIcon == null) {
        Log.d(
            TAG,
            "Failed to load provider label/icon for provider $providerFlattenedComponentName"
        )
        null
    } else {
        Pair(providerLabel, providerIcon)
    }
}

private fun getPackageInfo(
    pm: PackageManager,
    packageName: String
): PackageInfo {
    val packageManagerFlags = PackageManager.MATCH_INSTANT

    return pm.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(
            (packageManagerFlags).toLong())
    )
}