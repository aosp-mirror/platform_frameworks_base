/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.slice.Slice
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.credentials.ui.AuthenticationEntry
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.DisabledProviderData
import android.credentials.ui.Entry
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.RequestInfo
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.CredentialType
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RemoteInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.ActionEntryInfo
import com.android.credentialmanager.getflow.AuthenticationEntryInfo
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderInfo
import com.android.credentialmanager.getflow.RemoteEntryInfo
import com.android.credentialmanager.getflow.TopBrandingContent
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCustomCredentialRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL
import androidx.credentials.provider.Action
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.RemoteEntry
import org.json.JSONObject
import java.time.Instant

fun getAppLabel(
    pm: PackageManager,
    appPackageName: String
): String? {
    return try {
        val pkgInfo = pm.getPackageInfo(appPackageName, PackageManager.PackageInfoFlags.of(0))
        val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
        applicationInfo.loadSafeLabel(
            pm, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
        ).toString()
    } catch (e: Exception) {
        Log.e(Constants.LOG_TAG, "Caller app not found", e)
        null
    }
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
            val pkgInfo = pm.getPackageInfo(
                providerFlattenedComponentName,
                PackageManager.PackageInfoFlags.of(0)
            )
            val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
            providerLabel =
                applicationInfo.loadSafeLabel(
                    pm, 0f,
                    TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
                ).toString()
            providerIcon = applicationInfo.loadIcon(pm)
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Provider package info not found", e)
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
            Log.e(Constants.LOG_TAG, "Provider service info not found", e)
            // Added for mdoc use case where the provider may not need to register a service and
            // instead only relies on the registration api.
            try {
                val pkgInfo = pm.getPackageInfo(
                    component.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
                val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
                providerLabel =
                    applicationInfo.loadSafeLabel(
                        pm, 0f,
                        TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
                    ).toString()
                providerIcon = applicationInfo.loadIcon(pm)
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Provider package info not found", e)
            }
        }
    }
    return if (providerLabel == null || providerIcon == null) {
        Log.d(
            Constants.LOG_TAG,
            "Failed to load provider label/icon for provider $providerFlattenedComponentName"
        )
        null
    } else {
        Pair(providerLabel, providerIcon)
    }
}

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
    companion object {
        // Returns the list (potentially empty) of enabled provider.
        fun toProviderList(
            providerDataList: List<GetCredentialProviderData>,
            context: Context,
        ): List<ProviderInfo> {
            val providerList: MutableList<ProviderInfo> = mutableListOf()
            providerDataList.forEach {
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

        fun toRequestDisplayInfo(
            requestInfo: RequestInfo?,
            context: Context,
            originName: String?,
        ): com.android.credentialmanager.getflow.RequestDisplayInfo? {
            val getCredentialRequest = requestInfo?.getCredentialRequest ?: return null
            val preferImmediatelyAvailableCredentials = getCredentialRequest.data.getBoolean(
                "androidx.credentials.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS")
            val preferUiBrandingComponentName =
                getCredentialRequest.data.getParcelable(
                    "androidx.credentials.BUNDLE_KEY_PREFER_UI_BRANDING_COMPONENT_NAME",
                    ComponentName::class.java
                )
            val preferTopBrandingContent: TopBrandingContent? =
                if (!requestInfo.hasPermissionToOverrideDefault() ||
                    preferUiBrandingComponentName == null) null
                else {
                    val (displayName, icon) = getServiceLabelAndIcon(
                        context.packageManager, preferUiBrandingComponentName.flattenToString())
                        ?: Pair(null, null)
                    if (displayName != null && icon != null) {
                        TopBrandingContent(icon, displayName)
                    } else {
                        null
                    }
                }
            return com.android.credentialmanager.getflow.RequestDisplayInfo(
                appName = originName?.ifEmpty { null }
                    ?: getAppLabel(context.packageManager, requestInfo.appPackageName)
                    ?: return null,
                preferImmediatelyAvailableCredentials = preferImmediatelyAvailableCredentials,
                preferIdentityDocUi = getCredentialRequest.data.getBoolean(
                    // TODO(b/276777444): replace with direct library constant reference once
                    // exposed.
                    "androidx.credentials.BUNDLE_KEY_PREFER_IDENTITY_DOC_UI"),
                preferTopBrandingContent = preferTopBrandingContent,
            )
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
                val credentialEntry = parseCredentialEntryFromSlice(it.slice)
                when (credentialEntry) {
                    is PasswordCredentialEntry -> {
                        result.add(CredentialEntryInfo(
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
                        ))
                    }
                    is PublicKeyCredentialEntry -> {
                        result.add(CredentialEntryInfo(
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
                        ))
                    }
                    is CustomCredentialEntry -> {
                        result.add(CredentialEntryInfo(
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
                        ))
                    }
                    else -> Log.d(
                        Constants.LOG_TAG,
                        "Encountered unrecognized credential entry ${it.slice.spec?.type}"
                    )
                }
            }
            return result
        }

        private fun parseCredentialEntryFromSlice(slice: Slice): CredentialEntry? {
            try {
                when (slice.spec?.type) {
                    TYPE_PASSWORD_CREDENTIAL -> return PasswordCredentialEntry.fromSlice(slice)!!
                    TYPE_PUBLIC_KEY_CREDENTIAL -> return PublicKeyCredentialEntry.fromSlice(slice)!!
                    else -> return CustomCredentialEntry.fromSlice(slice)!!
                }
            } catch (e: Exception) {
                // Try CustomCredentialEntry.fromSlice one last time in case the cause was a failed
                // password / passkey parsing attempt.
                return CustomCredentialEntry.fromSlice(slice)
            }
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

                result.add(AuthenticationEntryInfo(
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
                ))
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
                result.add(ActionEntryInfo(
                    providerId = providerId,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = actionEntryUi.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    title = actionEntryUi.title.toString(),
                    icon = providerIcon,
                    subTitle = actionEntryUi.subtitle?.toString(),
                ))
            }
            return result
        }
    }
}

class CreateFlowUtils {
    companion object {
        /**
         * Note: caller required handle empty list due to parsing error.
         */
        fun toEnabledProviderList(
            providerDataList: List<CreateCredentialProviderData>,
            context: Context,
        ): List<EnabledProviderInfo> {
            val providerList: MutableList<EnabledProviderInfo> = mutableListOf()
            providerDataList.forEach {
                val providerLabelAndIcon = getServiceLabelAndIcon(
                    context.packageManager,
                    it.providerFlattenedComponentName
                ) ?: return@forEach
                val (providerLabel, providerIcon) = providerLabelAndIcon
                providerList.add(EnabledProviderInfo(
                    id = it.providerFlattenedComponentName,
                    displayName = providerLabel,
                    icon = providerIcon,
                    sortedCreateOptions = toSortedCreationOptionInfoList(
                        it.providerFlattenedComponentName, it.saveEntries, context
                    ),
                    remoteEntry = toRemoteInfo(it.providerFlattenedComponentName, it.remoteEntry),
                ))
            }
            return providerList
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        fun toDisabledProviderList(
            providerDataList: List<DisabledProviderData>?,
            context: Context,
        ): List<DisabledProviderInfo> {
            val providerList: MutableList<DisabledProviderInfo> = mutableListOf()
            providerDataList?.forEach {
                val providerLabelAndIcon = getServiceLabelAndIcon(
                    context.packageManager,
                    it.providerFlattenedComponentName
                ) ?: return@forEach
                val (providerLabel, providerIcon) = providerLabelAndIcon
                providerList.add(DisabledProviderInfo(
                    icon = providerIcon,
                    id = it.providerFlattenedComponentName,
                    displayName = providerLabel,
                ))
            }
            return providerList
        }

        fun toRequestDisplayInfo(
            requestInfo: RequestInfo?,
            context: Context,
            originName: String?,
        ): RequestDisplayInfo? {
            if (requestInfo == null) {
                return null
            }
            val appLabel = originName?.ifEmpty { null }
                ?: getAppLabel(context.packageManager, requestInfo.appPackageName)
                ?: return null
            val createCredentialRequest = requestInfo.createCredentialRequest ?: return null
            val createCredentialRequestJetpack = CreateCredentialRequest.createFrom(
                createCredentialRequest.type,
                createCredentialRequest.credentialData,
                createCredentialRequest.candidateQueryData,
                createCredentialRequest.isSystemProviderRequired,
                createCredentialRequest.origin,
            )
            val appPreferredDefaultProviderId: String? =
                if (!requestInfo.hasPermissionToOverrideDefault()) null
                else createCredentialRequestJetpack?.displayInfo?.preferDefaultProvider
            return when (createCredentialRequestJetpack) {
                is CreatePasswordRequest -> RequestDisplayInfo(
                    createCredentialRequestJetpack.id,
                    createCredentialRequestJetpack.password,
                    CredentialType.PASSWORD,
                    appLabel,
                    context.getDrawable(R.drawable.ic_password_24) ?: return null,
                    preferImmediatelyAvailableCredentials =
                    createCredentialRequestJetpack.preferImmediatelyAvailableCredentials,
                    appPreferredDefaultProviderId = appPreferredDefaultProviderId,
                    userSetDefaultProviderIds = requestInfo.defaultProviderIds.toSet(),
                    // The jetpack library requires a fix to parse this value correctly for
                    // the password type. For now, directly parse it ourselves.
                    isAutoSelectRequest = createCredentialRequest.credentialData.getBoolean(
                        Constants.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS, false),
                )
                is CreatePublicKeyCredentialRequest -> {
                    newRequestDisplayInfoFromPasskeyJson(
                        requestJson = createCredentialRequestJetpack.requestJson,
                        appLabel = appLabel,
                        context = context,
                        preferImmediatelyAvailableCredentials =
                        createCredentialRequestJetpack.preferImmediatelyAvailableCredentials,
                        appPreferredDefaultProviderId = appPreferredDefaultProviderId,
                        userSetDefaultProviderIds = requestInfo.defaultProviderIds.toSet(),
                        // The jetpack library requires a fix to parse this value correctly for
                        // the passkey type. For now, directly parse it ourselves.
                        isAutoSelectRequest = createCredentialRequest.credentialData.getBoolean(
                            Constants.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS, false),
                    )
                }
                is CreateCustomCredentialRequest -> {
                    // TODO: directly use the display info once made public
                    val displayInfo = CreateCredentialRequest.DisplayInfo
                        .parseFromCredentialDataBundle(createCredentialRequest.credentialData)
                        ?: return null
                    RequestDisplayInfo(
                        title = displayInfo.userId.toString(),
                        subtitle = displayInfo.userDisplayName?.toString(),
                        type = CredentialType.UNKNOWN,
                        appName = appLabel,
                        typeIcon = displayInfo.credentialTypeIcon?.loadDrawable(context)
                            ?: context.getDrawable(R.drawable.ic_other_sign_in_24) ?: return null,
                        preferImmediatelyAvailableCredentials =
                        createCredentialRequestJetpack.preferImmediatelyAvailableCredentials,
                        appPreferredDefaultProviderId = appPreferredDefaultProviderId,
                        userSetDefaultProviderIds = requestInfo.defaultProviderIds.toSet(),
                        isAutoSelectRequest = createCredentialRequestJetpack.isAutoSelectAllowed,
                    )
                }
                else -> null
            }
        }

        fun toCreateCredentialUiState(
            enabledProviders: List<EnabledProviderInfo>,
            disabledProviders: List<DisabledProviderInfo>?,
            defaultProviderIdPreferredByApp: String?,
            defaultProviderIdsSetByUser: Set<String>,
            requestDisplayInfo: RequestDisplayInfo,
            isOnPasskeyIntroStateAlready: Boolean,
            isPasskeyFirstUse: Boolean,
        ): CreateCredentialUiState? {
            var remoteEntry: RemoteInfo? = null
            var remoteEntryProvider: EnabledProviderInfo? = null
            var defaultProviderPreferredByApp: EnabledProviderInfo? = null
            var defaultProviderSetByUser: EnabledProviderInfo? = null
            var createOptionsPairs:
                MutableList<Pair<CreateOptionInfo, EnabledProviderInfo>> = mutableListOf()
            enabledProviders.forEach { enabledProvider ->
                if (defaultProviderIdPreferredByApp != null) {
                    if (enabledProvider.id == defaultProviderIdPreferredByApp) {
                        defaultProviderPreferredByApp = enabledProvider
                    }
                }
                if (enabledProvider.sortedCreateOptions.isNotEmpty() &&
                    defaultProviderIdsSetByUser.contains(enabledProvider.id)) {
                    if (defaultProviderSetByUser == null) {
                        defaultProviderSetByUser = enabledProvider
                    } else {
                        val newLastUsedTime = enabledProvider.sortedCreateOptions.firstOrNull()
                          ?.lastUsedTime
                        val curLastUsedTime = defaultProviderSetByUser?.sortedCreateOptions
                        ?.firstOrNull()?.lastUsedTime ?: Instant.MIN
                        if (newLastUsedTime != null) {
                            if (curLastUsedTime == null || newLastUsedTime > curLastUsedTime) {
                                defaultProviderSetByUser = enabledProvider
                            }
                        }
                    }
                }
                if (enabledProvider.sortedCreateOptions.isNotEmpty()) {
                    enabledProvider.sortedCreateOptions.forEach {
                        createOptionsPairs.add(Pair(it, enabledProvider))
                    }
                }
                val currRemoteEntry = enabledProvider.remoteEntry
                if (currRemoteEntry != null) {
                    if (remoteEntry != null) {
                        // There can only be at most one remote entry
                        Log.d(Constants.LOG_TAG, "Found more than one remote entry.")
                        return null
                    }
                    remoteEntry = currRemoteEntry
                    remoteEntryProvider = enabledProvider
                }
            }
            val defaultProvider = defaultProviderPreferredByApp ?: defaultProviderSetByUser
            val initialScreenState = toCreateScreenState(
                createOptionSize = createOptionsPairs.size,
                isOnPasskeyIntroStateAlready = isOnPasskeyIntroStateAlready,
                requestDisplayInfo = requestDisplayInfo,
                remoteEntry = remoteEntry,
                isPasskeyFirstUse = isPasskeyFirstUse
            ) ?: return null
            val sortedCreateOptionsPairs = createOptionsPairs.sortedWith(
                compareByDescending { it.first.lastUsedTime }
            )
            return CreateCredentialUiState(
                enabledProviders = enabledProviders,
                disabledProviders = disabledProviders,
                currentScreenState = initialScreenState,
                requestDisplayInfo = requestDisplayInfo,
                sortedCreateOptionsPairs = sortedCreateOptionsPairs,
                activeEntry = toActiveEntry(
                    defaultProvider = defaultProvider,
                    sortedCreateOptionsPairs = sortedCreateOptionsPairs,
                    remoteEntry = remoteEntry,
                    remoteEntryProvider = remoteEntryProvider,
                ),
                remoteEntry = remoteEntry,
                foundCandidateFromUserDefaultProvider = defaultProviderSetByUser != null,
            )
        }

        fun toCreateScreenState(
            createOptionSize: Int,
            isOnPasskeyIntroStateAlready: Boolean,
            requestDisplayInfo: RequestDisplayInfo,
            remoteEntry: RemoteInfo?,
            isPasskeyFirstUse: Boolean,
        ): CreateScreenState? {
            return if (isPasskeyFirstUse && requestDisplayInfo.type == CredentialType.PASSKEY &&
                !isOnPasskeyIntroStateAlready) {
                CreateScreenState.PASSKEY_INTRO
            } else if (createOptionSize == 0 && remoteEntry != null) {
                CreateScreenState.EXTERNAL_ONLY_SELECTION
            } else {
                CreateScreenState.CREATION_OPTION_SELECTION
            }
        }

        private fun toActiveEntry(
            defaultProvider: EnabledProviderInfo?,
            sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>,
            remoteEntry: RemoteInfo?,
            remoteEntryProvider: EnabledProviderInfo?,
        ): ActiveEntry? {
            return if (
                sortedCreateOptionsPairs.isEmpty() && remoteEntry != null &&
                remoteEntryProvider != null
            ) {
                ActiveEntry(remoteEntryProvider, remoteEntry)
            } else if (defaultProvider != null &&
                defaultProvider.sortedCreateOptions.isNotEmpty()) {
                ActiveEntry(defaultProvider, defaultProvider.sortedCreateOptions.first())
            } else if (sortedCreateOptionsPairs.isNotEmpty()) {
                val (topEntry, topEntryProvider) = sortedCreateOptionsPairs.first()
                ActiveEntry(topEntryProvider, topEntry)
            } else null
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        private fun toSortedCreationOptionInfoList(
            providerId: String,
            creationEntries: List<Entry>,
            context: Context,
        ): List<CreateOptionInfo> {
            val result: MutableList<CreateOptionInfo> = mutableListOf()
            creationEntries.forEach {
                val createEntry = CreateEntry.fromSlice(it.slice) ?: return@forEach
                result.add(CreateOptionInfo(
                    providerId = providerId,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = createEntry.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    userProviderDisplayName = createEntry.accountName.toString(),
                    profileIcon = createEntry.icon?.loadDrawable(context),
                    passwordCount = createEntry.getPasswordCredentialCount(),
                    passkeyCount = createEntry.getPublicKeyCredentialCount(),
                    totalCredentialCount = createEntry.getTotalCredentialCount(),
                    lastUsedTime = createEntry.lastUsedTime ?: Instant.MIN,
                    footerDescription = createEntry.description?.toString(),
                    // TODO(b/281065680): replace with official library constant once available
                    allowAutoSelect =
                    it.slice.items.firstOrNull {
                        it.hasHint("androidx.credentials.provider.createEntry.SLICE_HINT_AUTO_" +
                            "SELECT_ALLOWED")
                    }?.text == "true",
                ))
            }
            return result.sortedWith(
                compareByDescending { it.lastUsedTime }
            )
        }

        private fun toRemoteInfo(
            providerId: String,
            remoteEntry: Entry?,
        ): RemoteInfo? {
            return if (remoteEntry != null) {
                val structuredRemoteEntry = RemoteEntry.fromSlice(remoteEntry.slice)
                    ?: return null
                RemoteInfo(
                    providerId = providerId,
                    entryKey = remoteEntry.key,
                    entrySubkey = remoteEntry.subkey,
                    pendingIntent = structuredRemoteEntry.pendingIntent,
                    fillInIntent = remoteEntry.frameworkExtrasIntent,
                )
            } else null
        }

        private fun newRequestDisplayInfoFromPasskeyJson(
            requestJson: String,
            appLabel: String,
            context: Context,
            preferImmediatelyAvailableCredentials: Boolean,
            appPreferredDefaultProviderId: String?,
            userSetDefaultProviderIds: Set<String>,
            isAutoSelectRequest: Boolean
        ): RequestDisplayInfo? {
            val json = JSONObject(requestJson)
            var passkeyUsername = ""
            var passkeyDisplayName = ""
            if (json.has("user")) {
                val user: JSONObject = json.getJSONObject("user")
                passkeyUsername = user.getString("name")
                passkeyDisplayName = user.getString("displayName")
            }
            val (username, displayname) = userAndDisplayNameForPasskey(
                passkeyUsername = passkeyUsername,
                passkeyDisplayName = passkeyDisplayName,
            )
            return RequestDisplayInfo(
                username,
                displayname,
                CredentialType.PASSKEY,
                appLabel,
                context.getDrawable(R.drawable.ic_passkey_24) ?: return null,
                preferImmediatelyAvailableCredentials,
                appPreferredDefaultProviderId,
                userSetDefaultProviderIds,
                isAutoSelectRequest,
            )
        }
    }
}

/**
 * Returns the actual username and display name for the UI display purpose for the passkey use case.
 *
 * Passkey has some special requirements:
 * 1) display-name on top (turned into UI username) if one is available, username on second line.
 * 2) username on top if display-name is not available.
 * 3) don't show username on second line if username == display-name
 */
fun userAndDisplayNameForPasskey(
    passkeyUsername: String,
    passkeyDisplayName: String,
): Pair<String, String> {
    if (!TextUtils.isEmpty(passkeyUsername) && !TextUtils.isEmpty(passkeyDisplayName)) {
        if (passkeyUsername == passkeyDisplayName) {
            return Pair(passkeyUsername, "")
        } else {
            return Pair(passkeyDisplayName, passkeyUsername)
        }
    } else if (!TextUtils.isEmpty(passkeyUsername)) {
        return Pair(passkeyUsername, passkeyDisplayName)
    } else if (!TextUtils.isEmpty(passkeyDisplayName)) {
        return Pair(passkeyDisplayName, passkeyUsername)
    } else {
        return Pair(passkeyDisplayName, passkeyUsername)
    }
}
