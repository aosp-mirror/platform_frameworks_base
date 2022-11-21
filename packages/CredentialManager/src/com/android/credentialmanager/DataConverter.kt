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
import android.graphics.drawable.Drawable
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.RemoteInfo
import com.android.credentialmanager.getflow.ActionEntryInfo
import com.android.credentialmanager.getflow.AuthenticationEntryInfo
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderInfo
import com.android.credentialmanager.getflow.RemoteEntryInfo
import com.android.credentialmanager.jetpack.provider.ActionUi
import com.android.credentialmanager.jetpack.provider.CredentialEntryUi
import com.android.credentialmanager.jetpack.provider.SaveEntryUi

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
  companion object {

    fun toProviderList(
      providerDataList: List<GetCredentialProviderData>,
      context: Context,
    ): List<ProviderInfo> {
      val packageManager = context.packageManager
      return providerDataList.map {
        // TODO: get from the actual service info
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
            it.providerFlattenedComponentName, it.actionChips, context),
        )
      }
    }


    /* From service data structure to UI credential entry list representation. */
    private fun getCredentialOptionInfoList(
      providerId: String,
      credentialEntries: List<Entry>,
      context: Context,
    ): List<CredentialEntryInfo> {
      return credentialEntries.map {
        val credentialEntryUi = CredentialEntryUi.fromSlice(it.slice)

        // Consider directly move the UI object into the class.
        return@map CredentialEntryInfo(
          providerId = providerId,
          entryKey = it.key,
          entrySubkey = it.subkey,
          pendingIntent = it.pendingIntent,
          fillInIntent = it.frameworkExtrasIntent,
          credentialType = credentialEntryUi.credentialType.toString(),
          credentialTypeDisplayName = credentialEntryUi.credentialTypeDisplayName.toString(),
          userName = credentialEntryUi.userName.toString(),
          displayName = credentialEntryUi.userDisplayName?.toString(),
          // TODO: proper fallback
          icon = credentialEntryUi.entryIcon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_passkey)!!,
          lastUsedTimeMillis = credentialEntryUi.lastUsedTimeMillis,
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
      context: Context,
    ): List<ActionEntryInfo> {
      return actionEntries.map {
        val actionEntryUi = ActionUi.fromSlice(it.slice)

        return@map ActionEntryInfo(
          providerId = providerId,
          entryKey = it.key,
          entrySubkey = it.subkey,
          pendingIntent = it.pendingIntent,
          fillInIntent = it.frameworkExtrasIntent,
          title = actionEntryUi.text.toString(),
          // TODO: gracefully fail
          icon = actionEntryUi.icon.loadDrawable(context)!!,
          subTitle = actionEntryUi.subtext?.toString(),
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
    ): List<com.android.credentialmanager.createflow.EnabledProviderInfo> {
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
        com.android.credentialmanager.createflow.EnabledProviderInfo(
          // TODO: decide what to do when failed to load a provider icon
          icon = pkgInfo.applicationInfo.loadIcon(packageManager)!!,
          name = it.providerFlattenedComponentName,
          displayName = pkgInfo.applicationInfo.loadLabel(packageManager).toString(),
          createOptions = toCreationOptionInfoList(
            it.providerFlattenedComponentName, it.saveEntries, context),
          isDefault = it.isDefaultProvider,
          remoteEntry = toRemoteInfo(it.providerFlattenedComponentName, it.remoteEntry),
        )
      }
    }

    fun toDisabledProviderList(
      providerDataList: List<DisabledProviderData>,
      context: Context,
    ): List<com.android.credentialmanager.createflow.DisabledProviderInfo> {
      // TODO: get from the actual service info
      val packageManager = context.packageManager
      return providerDataList.map {
        val pkgInfo = packageManager
          .getPackageInfo(it.providerFlattenedComponentName,
            PackageManager.PackageInfoFlags.of(0))
        com.android.credentialmanager.createflow.DisabledProviderInfo(
          icon = pkgInfo.applicationInfo.loadIcon(packageManager)!!,
          name = it.providerFlattenedComponentName,
          displayName = pkgInfo.applicationInfo.loadLabel(packageManager).toString(),
        )
      }
    }

    private fun toCreationOptionInfoList(
      providerId: String,
      creationEntries: List<Entry>,
      context: Context,
    ): List<CreateOptionInfo> {
      return creationEntries.map {
        val saveEntryUi = SaveEntryUi.fromSlice(it.slice)

        return@map CreateOptionInfo(
          // TODO: remove fallbacks
          providerId = providerId,
          entryKey = it.key,
          entrySubkey = it.subkey,
          pendingIntent = it.pendingIntent,
          fillInIntent = it.frameworkExtrasIntent,
          userProviderDisplayName = saveEntryUi.userProviderAccountName as String,
          profileIcon = saveEntryUi.profileIcon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_profile)!!,
          passwordCount = saveEntryUi.passwordCount ?: 0,
          passkeyCount = saveEntryUi.passkeyCount ?: 0,
          totalCredentialCount = saveEntryUi.totalCredentialCount ?: 0,
          lastUsedTimeMillis = saveEntryUi.lastUsedTimeMillis ?: 0,
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
