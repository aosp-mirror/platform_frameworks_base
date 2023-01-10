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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.ui.Entry
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.DisabledProviderData
import android.credentials.ui.RequestInfo
import android.graphics.drawable.Drawable
import android.text.TextUtils
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.RemoteInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.getflow.ActionEntryInfo
import com.android.credentialmanager.getflow.AuthenticationEntryInfo
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderInfo
import com.android.credentialmanager.getflow.RemoteEntryInfo
import com.android.credentialmanager.jetpack.developer.CreateCredentialRequest
import com.android.credentialmanager.jetpack.developer.CreatePasswordRequest
import com.android.credentialmanager.jetpack.developer.CreatePublicKeyCredentialRequest
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL
import com.android.credentialmanager.jetpack.provider.Action
import com.android.credentialmanager.jetpack.provider.CredentialCountInformation
import com.android.credentialmanager.jetpack.provider.CredentialEntry
import com.android.credentialmanager.jetpack.provider.CreateEntry
import org.json.JSONObject

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
  companion object {

    fun toProviderList(
            providerDataList: List<GetCredentialProviderData>,
            context: Context,
    ): List<ProviderInfo> {
      val packageManager = context.packageManager
      return providerDataList.map {
        val componentName = ComponentName.unflattenFromString(it.providerFlattenedComponentName)
        var packageName = componentName?.packageName
        if (componentName == null) {
          // TODO: Remove once test data is fixed
          packageName = it.providerFlattenedComponentName
        }

        val pkgInfo = packageManager
                .getPackageInfo(packageName!!,
                        PackageManager.PackageInfoFlags.of(0))
        val providerDisplayName = pkgInfo.applicationInfo.loadLabel(packageManager).toString()
        // TODO: decide what to do when failed to load a provider icon
        val providerIcon = pkgInfo.applicationInfo.loadIcon(packageManager)!!
        ProviderInfo(
                id = it.providerFlattenedComponentName,
                // TODO: decide what to do when failed to load a provider icon
                icon = providerIcon,
                displayName = providerDisplayName,
                credentialEntryList = getCredentialOptionInfoList(
                        it.providerFlattenedComponentName, it.credentialEntries, context),
                authenticationEntry = getAuthenticationEntry(
                        it.providerFlattenedComponentName,
                        providerDisplayName,
                        providerIcon,
                        it.authenticationEntry),
                remoteEntry = getRemoteEntry(it.providerFlattenedComponentName, it.remoteEntry),
                actionEntryList = getActionEntryList(
                        it.providerFlattenedComponentName, it.actionChips, providerIcon),
        )
      }
    }

    fun toRequestDisplayInfo(
            requestInfo: RequestInfo,
            context: Context,
    ): com.android.credentialmanager.getflow.RequestDisplayInfo {
        val packageName = requestInfo.appPackageName
        val pkgInfo = context.packageManager.getPackageInfo(packageName,
                PackageManager.PackageInfoFlags.of(0))
        val appLabel = pkgInfo.applicationInfo.loadSafeLabel(context.packageManager, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM)
        return com.android.credentialmanager.getflow.RequestDisplayInfo(
              appName = appLabel.toString()
      )
    }


    /* From service data structure to UI credential entry list representation. */
    private fun getCredentialOptionInfoList(
            providerId: String,
            credentialEntries: List<Entry>,
            context: Context,
    ): List<CredentialEntryInfo> {
      return credentialEntries.map {
        // TODO: handle NPE gracefully
        val credentialEntry = CredentialEntry.fromSlice(it.slice)!!

        // Consider directly move the UI object into the class.
        return@map CredentialEntryInfo(
                providerId = providerId,
                entryKey = it.key,
                entrySubkey = it.subkey,
                pendingIntent = credentialEntry.pendingIntent,
                fillInIntent = it.frameworkExtrasIntent,
                credentialType = credentialEntry.type.toString(),
                credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                userName = credentialEntry.username.toString(),
                displayName = credentialEntry.displayName?.toString(),
                // TODO: proper fallback
                icon = credentialEntry.icon?.loadDrawable(context),
                lastUsedTimeMillis = credentialEntry.lastUsedTimeMillis,
        )
      }
    }

    private fun getAuthenticationEntry(
            providerId: String,
            providerDisplayName: String,
            providerIcon: Drawable,
            authEntry: Entry?,
    ): AuthenticationEntryInfo? {
      // TODO: should also call fromSlice after getting the official jetpack code.

      if (authEntry == null) {
        return null
      }
      return AuthenticationEntryInfo(
              providerId = providerId,
              entryKey = authEntry.key,
              entrySubkey = authEntry.subkey,
              pendingIntent = authEntry.pendingIntent,
              fillInIntent = authEntry.frameworkExtrasIntent,
              title = providerDisplayName,
              icon = providerIcon,
      )
    }

    private fun getRemoteEntry(providerId: String, remoteEntry: Entry?): RemoteEntryInfo? {
      // TODO: should also call fromSlice after getting the official jetpack code.
      if (remoteEntry == null) {
        return null
      }
      return RemoteEntryInfo(
              providerId = providerId,
              entryKey = remoteEntry.key,
              entrySubkey = remoteEntry.subkey,
              pendingIntent = remoteEntry.pendingIntent,
              fillInIntent = remoteEntry.frameworkExtrasIntent,
      )
    }

    private fun getActionEntryList(
            providerId: String,
            actionEntries: List<Entry>,
            providerIcon: Drawable,
    ): List<ActionEntryInfo> {
      return actionEntries.map {
        // TODO: handle NPE gracefully
        val actionEntryUi = Action.fromSlice(it.slice)!!

        return@map ActionEntryInfo(
                providerId = providerId,
                entryKey = it.key,
                entrySubkey = it.subkey,
                pendingIntent = actionEntryUi.pendingIntent,
                fillInIntent = it.frameworkExtrasIntent,
                title = actionEntryUi.title.toString(),
                // TODO: gracefully fail
                icon = providerIcon,
                subTitle = actionEntryUi.subTitle?.toString(),
        )
      }
    }
  }
}

class CreateFlowUtils {
  companion object {

    fun toEnabledProviderList(
            providerDataList: List<CreateCredentialProviderData>,
            context: Context,
    ): List<EnabledProviderInfo> {
      // TODO: get from the actual service info
      val packageManager = context.packageManager

      return providerDataList.map {
        val componentName = ComponentName.unflattenFromString(it.providerFlattenedComponentName)
        var packageName = componentName?.packageName
        if (componentName == null) {
          // TODO: Remove once test data is fixed
          packageName = it.providerFlattenedComponentName
        }

        val pkgInfo = packageManager
                .getPackageInfo(packageName!!,
                        PackageManager.PackageInfoFlags.of(0))
        EnabledProviderInfo(
                // TODO: decide what to do when failed to load a provider icon
                icon = pkgInfo.applicationInfo.loadIcon(packageManager)!!,
                name = it.providerFlattenedComponentName,
                displayName = pkgInfo.applicationInfo.loadLabel(packageManager).toString(),
                createOptions = toCreationOptionInfoList(
                        it.providerFlattenedComponentName, it.saveEntries, context),
                remoteEntry = toRemoteInfo(it.providerFlattenedComponentName, it.remoteEntry),
        )
      }
    }

    fun toDisabledProviderList(
            providerDataList: List<DisabledProviderData>?,
            context: Context,
    ): List<DisabledProviderInfo>? {
      // TODO: get from the actual service info
      val packageManager = context.packageManager
      return providerDataList?.map {
        val componentName = ComponentName.unflattenFromString(it.providerFlattenedComponentName)
        var packageName = componentName?.packageName
        if (componentName == null) {
          // TODO: Remove once test data is fixed
          packageName = it.providerFlattenedComponentName
        }
        val pkgInfo = packageManager
                .getPackageInfo(packageName!!,
                        PackageManager.PackageInfoFlags.of(0))
        DisabledProviderInfo(
                icon = pkgInfo.applicationInfo.loadIcon(packageManager)!!,
                name = it.providerFlattenedComponentName,
                displayName = pkgInfo.applicationInfo.loadLabel(packageManager).toString(),
        )
      }
    }

    fun toRequestDisplayInfo(
            requestInfo: RequestInfo,
            context: Context,
    ): RequestDisplayInfo {
      val packageName = requestInfo.appPackageName
      val pkgInfo = context.packageManager.getPackageInfo(packageName,
            PackageManager.PackageInfoFlags.of(0))
      val appLabel = pkgInfo.applicationInfo.loadSafeLabel(context.packageManager, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM)
      val createCredentialRequest = requestInfo.createCredentialRequest
      val createCredentialRequestJetpack = createCredentialRequest?.let {
        CreateCredentialRequest.createFrom(
                it.type, it.credentialData, it.candidateQueryData, it.requireSystemProvider()
        )
      }
      when (createCredentialRequestJetpack) {
        is CreatePasswordRequest -> {
          return RequestDisplayInfo(
                  createCredentialRequestJetpack.id,
                  createCredentialRequestJetpack.password,
                  createCredentialRequestJetpack.type,
                  appLabel.toString(),
                  context.getDrawable(R.drawable.ic_password)!!
          )
        }
        is CreatePublicKeyCredentialRequest -> {
          val requestJson = createCredentialRequestJetpack.requestJson
          val json = JSONObject(requestJson)
          var name = ""
          var displayName = ""
          if (json.has("user")) {
            val user: JSONObject = json.getJSONObject("user")
            name = user.getString("name")
            displayName = user.getString("displayName")
          }
          return RequestDisplayInfo(
                  name,
                  displayName,
                  createCredentialRequestJetpack.type,
                  appLabel.toString(),
                  context.getDrawable(R.drawable.ic_passkey)!!)
        }
        // TODO: correctly parsing for other sign-ins
        else -> {
          return RequestDisplayInfo(
                  "beckett-bakert@gmail.com",
                  "Elisa Beckett",
                  "other-sign-ins",
                  appLabel.toString(),
                  context.getDrawable(R.drawable.ic_other_sign_in)!!)
        }
      }
    }

    fun toCreateCredentialUiState(
            enabledProviders: List<EnabledProviderInfo>,
            disabledProviders: List<DisabledProviderInfo>?,
            defaultProviderId: String?,
            requestDisplayInfo: RequestDisplayInfo,
            isOnPasskeyIntroStateAlready: Boolean,
            isPasskeyFirstUse: Boolean,
    ): CreateCredentialUiState {
      var lastSeenProviderWithNonEmptyCreateOptions: EnabledProviderInfo? = null
      var remoteEntry: RemoteInfo? = null
      var defaultProvider: EnabledProviderInfo? = null
      var createOptionsPairs:
              MutableList<Pair<CreateOptionInfo, EnabledProviderInfo>> = mutableListOf()
      enabledProviders.forEach {
        enabledProvider ->
        if (defaultProviderId != null) {
          if (enabledProvider.id == defaultProviderId) {
            defaultProvider = enabledProvider
          }
        }
        if (enabledProvider.createOptions.isNotEmpty()) {
          lastSeenProviderWithNonEmptyCreateOptions = enabledProvider
          enabledProvider.createOptions.forEach {
            createOptionsPairs.add(Pair(it, enabledProvider))
          }
        }
        if (enabledProvider.remoteEntry != null) {
          remoteEntry = enabledProvider.remoteEntry!!
        }
      }
      return CreateCredentialUiState(
              enabledProviders = enabledProviders,
              disabledProviders = disabledProviders,
              toCreateScreenState(
                      /*createOptionSize=*/createOptionsPairs.size,
          /*isOnPasskeyIntroStateAlready=*/isOnPasskeyIntroStateAlready,
          /*requestDisplayInfo=*/requestDisplayInfo,
          /*defaultProvider=*/defaultProvider, /*remoteEntry=*/remoteEntry,
          /*isPasskeyFirstUse=*/isPasskeyFirstUse),
        requestDisplayInfo,
        createOptionsPairs.sortedWith(compareByDescending{ it.first.lastUsedTimeMillis }),
        defaultProvider != null,
        toActiveEntry(
          /*defaultProvider=*/defaultProvider,
          /*createOptionSize=*/createOptionsPairs.size,
                      /*lastSeenProviderWithNonEmptyCreateOptions=*/
                      lastSeenProviderWithNonEmptyCreateOptions,
                      /*remoteEntry=*/remoteEntry),
      )
    }

    private fun toCreateScreenState(
            createOptionSize: Int,
            isOnPasskeyIntroStateAlready: Boolean,
            requestDisplayInfo: RequestDisplayInfo,
            defaultProvider: EnabledProviderInfo?,
            remoteEntry: RemoteInfo?,
            isPasskeyFirstUse: Boolean,
    ): CreateScreenState {
      return if (
              isPasskeyFirstUse && requestDisplayInfo
                      .type == TYPE_PUBLIC_KEY_CREDENTIAL && !isOnPasskeyIntroStateAlready) {
        CreateScreenState.PASSKEY_INTRO
      } else if (
              (defaultProvider == null || defaultProvider.createOptions.isEmpty()
                      ) && createOptionSize > 1) {
        CreateScreenState.PROVIDER_SELECTION
      } else if (
              ((defaultProvider == null || defaultProvider.createOptions.isEmpty()
                      ) && createOptionSize == 1) || (
                      defaultProvider != null && defaultProvider.createOptions.isNotEmpty())) {
        CreateScreenState.CREATION_OPTION_SELECTION
      } else if (createOptionSize == 0 && remoteEntry != null) {
        CreateScreenState.EXTERNAL_ONLY_SELECTION
      } else {
        // TODO: properly handle error and gracefully finish itself
        throw java.lang.IllegalStateException("Empty provider list.")
      }
    }

    private fun toActiveEntry(
            defaultProvider: EnabledProviderInfo?,
            createOptionSize: Int,
            lastSeenProviderWithNonEmptyCreateOptions: EnabledProviderInfo?,
            remoteEntry: RemoteInfo?,
    ): ActiveEntry? {
      return if (
              defaultProvider != null && defaultProvider.createOptions.isEmpty() &&
              remoteEntry != null) {
        ActiveEntry(defaultProvider, remoteEntry)
      } else if (
              defaultProvider != null && defaultProvider.createOptions.isNotEmpty()
      ) {
        ActiveEntry(defaultProvider, defaultProvider.createOptions.first())
      } else if (createOptionSize == 1) {
        ActiveEntry(lastSeenProviderWithNonEmptyCreateOptions!!,
                lastSeenProviderWithNonEmptyCreateOptions.createOptions.first())
      } else null
    }

    private fun toCreationOptionInfoList(
            providerId: String,
            creationEntries: List<Entry>,
            context: Context,
    ): List<CreateOptionInfo> {
      return creationEntries.map {
        // TODO: handle NPE gracefully
        val createEntry = CreateEntry.fromSlice(it.slice)!!

        return@map CreateOptionInfo(
                // TODO: remove fallbacks
                providerId = providerId,
                entryKey = it.key,
                entrySubkey = it.subkey,
                pendingIntent = createEntry.pendingIntent,
                fillInIntent = it.frameworkExtrasIntent,
                userProviderDisplayName = createEntry.accountName.toString(),
                profileIcon = createEntry.icon?.loadDrawable(context),
                passwordCount = CredentialCountInformation.getPasswordCount(
                        createEntry.credentialCountInformationList) ?: 0,
                passkeyCount = CredentialCountInformation.getPasskeyCount(
                        createEntry.credentialCountInformationList) ?: 0,
                totalCredentialCount = CredentialCountInformation.getTotalCount(
                        createEntry.credentialCountInformationList) ?: 0,
                lastUsedTimeMillis = createEntry.lastUsedTimeMillis ?: 0,
        )
      }
    }

    private fun toRemoteInfo(
            providerId: String,
            remoteEntry: Entry?,
    ): RemoteInfo? {
      // TODO: should also call fromSlice after getting the official jetpack code.
      return if (remoteEntry != null) {
        RemoteInfo(
                providerId = providerId,
                entryKey = remoteEntry.key,
                entrySubkey = remoteEntry.subkey,
                pendingIntent = remoteEntry.pendingIntent,
                fillInIntent = remoteEntry.frameworkExtrasIntent,
        )
      } else null
    }
  }
}
