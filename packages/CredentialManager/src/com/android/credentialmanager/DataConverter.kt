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

import android.content.Context
import android.credentials.ui.Entry
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.CreateCredentialProviderData
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.getflow.ActionEntryInfo
import com.android.credentialmanager.getflow.AuthenticationEntryInfo
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderInfo
import com.android.credentialmanager.jetpack.provider.CredentialEntryUi
import com.android.credentialmanager.jetpack.provider.SaveEntryUi

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
  companion object {

    fun toProviderList(
      providerDataList: List<GetCredentialProviderData>,
      context: Context,
    ): List<ProviderInfo> {
      return providerDataList.map {
        ProviderInfo(
          id = it.providerFlattenedComponentName,
          // TODO: replace to extract from the service data structure when available
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          // TODO: get the service display name and icon from the component name.
          displayName = it.providerFlattenedComponentName,
          credentialEntryList = getCredentialOptionInfoList(
            it.providerFlattenedComponentName, it.credentialEntries, context),
          authenticationEntry = getAuthenticationEntry(
            it.providerFlattenedComponentName, it.authenticationEntry, context),
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
          credentialType = credentialEntryUi.credentialType.toString(),
          credentialTypeDisplayName = credentialEntryUi.credentialTypeDisplayName.toString(),
          userName = credentialEntryUi.userName.toString(),
          displayName = credentialEntryUi.userDisplayName?.toString(),
          // TODO: proper fallback
          icon = credentialEntryUi.entryIcon.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_passkey)!!,
          lastUsedTimeMillis = credentialEntryUi.lastUsedTimeMillis,
        )
      }
    }

    private fun getAuthenticationEntry(
      providerId: String,
      authEntry: Entry?,
      context: Context,
    ): AuthenticationEntryInfo? {
      // TODO: implement
      return null
    }

    private fun getActionEntryList(
      providerId: String,
      actionEntries: List<Entry>,
      context: Context,
    ): List<ActionEntryInfo> {
      // TODO: implement
      return emptyList()
    }
  }
}

class CreateFlowUtils {
  companion object {

    fun toProviderList(
      providerDataList: List<CreateCredentialProviderData>,
      context: Context,
    ): List<com.android.credentialmanager.createflow.ProviderInfo> {
      return providerDataList.map {
        com.android.credentialmanager.createflow.ProviderInfo(
          // TODO: replace to extract from the service data structure when available
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = it.providerFlattenedComponentName,
          displayName = it.providerFlattenedComponentName,
          createOptions = toCreationOptionInfoList(it.saveEntries, context),
          isDefault = it.isDefaultProvider,
        )
      }
    }

    private fun toCreationOptionInfoList(
      creationEntries: List<Entry>,
      context: Context,
    ): List<CreateOptionInfo> {
      return creationEntries.map {
        val saveEntryUi = SaveEntryUi.fromSlice(it.slice)

        return@map CreateOptionInfo(
          // TODO: remove fallbacks
          entryKey = it.key,
          entrySubkey = it.subkey,
          userProviderDisplayName = saveEntryUi.userProviderAccountName as String,
          credentialTypeIcon = saveEntryUi.credentialTypeIcon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_passkey)!!,
          profileIcon = saveEntryUi.profileIcon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_profile)!!,
          passwordCount = saveEntryUi.passwordCount ?: 0,
          passkeyCount = saveEntryUi.passkeyCount ?: 0,
          totalCredentialCount = saveEntryUi.totalCredentialCount ?: 0,
          lastUsedTimeMillis = saveEntryUi.lastUsedTimeMillis ?: 0,
        )
      }
    }
  }
}
