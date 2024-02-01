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

package com.android.credentialmanager.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.credentials.Credential
import android.credentials.CredentialManager
import android.credentials.CredentialOption
import android.credentials.GetCandidateCredentialsException
import android.credentials.GetCandidateCredentialsResponse
import android.credentials.GetCredentialRequest
import android.credentials.GetCredentialResponse
import android.credentials.selection.Entry
import android.credentials.selection.GetCredentialProviderData
import android.credentials.selection.ProviderData
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.provider.Settings
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.service.credentials.CredentialProviderService
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.autofill.IAutoFillManagerClient
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.android.credentialmanager.GetFlowUtils
import com.android.credentialmanager.common.ui.RemoteViewsFactory
import com.android.credentialmanager.getflow.ProviderDisplayInfo
import com.android.credentialmanager.getflow.toProviderDisplayInfo
import com.android.credentialmanager.ktx.credentialEntry
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.CredentialEntryInfo
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject


class CredentialAutofillService : AutofillService() {

    companion object {
        private const val TAG = "CredAutofill"

        private const val SESSION_ID_KEY = "autofill_session_id"
        private const val REQUEST_ID_KEY = "autofill_request_id"
        private const val CRED_HINT_PREFIX = "credential="
        private const val REQUEST_DATA_KEY = "requestData"
        private const val CANDIDATE_DATA_KEY = "candidateQueryData"
        private const val SYS_PROVIDER_REQ_KEY = "isSystemProviderRequired"
        private const val CRED_OPTIONS_KEY = "credentialOptions"
        private const val TYPE_KEY = "type"
        private const val REQ_TYPE_KEY = "get"
    }

    override fun onFillRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback
    ) {
    }

    override fun onFillCredentialRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback,
            autofillCallback: IAutoFillManagerClient
    ) {
        val context = request.fillContexts
        val structure = context[context.size - 1].structure
        val callingPackage = structure.activityComponent.packageName
        Log.i(TAG, "onFillCredentialRequest called for $callingPackage")

        val clientState = request.clientState
        if (clientState == null) {
            Log.i(TAG, "Client state not found")
            callback.onFailure("Client state not found")
            return
        }
        val sessionId = clientState.getInt(SESSION_ID_KEY)
        val requestId = clientState.getInt(REQUEST_ID_KEY)
        Log.i(TAG, "Autofill sessionId: $sessionId, autofill requestId: $requestId")
        if (sessionId == 0 || requestId == 0) {
            Log.i(TAG, "Session Id or request Id not found")
            callback.onFailure("Session Id or request Id not found")
            return
        }

        val getCredRequest: GetCredentialRequest? = getCredManRequest(structure, sessionId,
                requestId)
        if (getCredRequest == null) {
            Log.i(TAG, "No credential manager request found")
            callback.onFailure("No credential manager request found")
            return
        }
        val credentialManager: CredentialManager =
                getSystemService(Context.CREDENTIAL_SERVICE) as CredentialManager

        val outcome = object : OutcomeReceiver<GetCandidateCredentialsResponse,
                GetCandidateCredentialsException> {
            override fun onResult(result: GetCandidateCredentialsResponse) {
                Log.i(TAG, "getCandidateCredentials onResponse")

                if (result.getCredentialResponse != null) {
                    val autofillId: AutofillId? = result.getCredentialResponse
                            .credential.data.getParcelable(
                                    CredentialProviderService.EXTRA_AUTOFILL_ID,
                                    AutofillId::class.java)
                    Log.i(TAG, "getCandidateCredentials final response, autofillId: " +
                            autofillId)

                    if (autofillId != null) {
                        autofillCallback.autofill(
                                sessionId,
                                mutableListOf(autofillId),
                                mutableListOf(
                                        AutofillValue.forText(
                                                convertResponseToJson(result.getCredentialResponse)
                                        )
                                ),
                                false)
                    }
                    return
                }

                val fillResponse = convertToFillResponse(result, request)
                if (fillResponse != null) {
                    callback.onSuccess(fillResponse)
                } else {
                    Log.e(TAG, "Failed to create a FillResponse from the CredentialResponse.")
                    callback.onFailure("No dataset was created from the CredentialResponse")
                }
            }

            override fun onError(error: GetCandidateCredentialsException) {
                Log.i(TAG, "getCandidateCredentials onError")
                callback.onFailure("error received from credential manager ${error.message}")
            }
        }

        credentialManager.getCandidateCredentials(
                getCredRequest,
                callingPackage,
                CancellationSignal(),
                Executors.newSingleThreadExecutor(),
                outcome,
                autofillCallback.asBinder()
        )
    }

    // TODO(b/318118018): Use from Jetpack
    private fun convertResponseToJson(response: GetCredentialResponse): String? {
        try {
            val jsonObject = JSONObject()
            jsonObject.put("type", "get")
            val jsonCred = JSONObject()
            jsonCred.put("type", response.credential.type)
            jsonCred.put("data", credentialToJSON(
                    response.credential))
            jsonObject.put("credential", jsonCred)
            return jsonObject.toString()
        } catch (e: JSONException) {
            Log.i(
                    TAG, "Exception while constructing response JSON: " +
                    e.message
            )
        }
        return null
    }

    // TODO(b/318118018): Replace with calls to Jetpack
    private fun credentialToJSON(credential: Credential): JSONObject? {
        Log.i(TAG, "credentialToJSON")
        try {
            if (credential.type == "android.credentials.TYPE_PASSWORD_CREDENTIAL") {
                Log.i(TAG, "toJSON PasswordCredential")

                val json = JSONObject()
                val id = credential.data.getString("androidx.credentials.BUNDLE_KEY_ID")
                val pass = credential.data.getString("androidx.credentials.BUNDLE_KEY_PASSWORD")
                json.put("androidx.credentials.BUNDLE_KEY_ID", id)
                json.put("androidx.credentials.BUNDLE_KEY_PASSWORD", pass)
                return json
            } else if (credential.type == "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL") {
                Log.i(TAG, "toJSON PublicKeyCredential")

                val json = JSONObject()
                val responseJson = credential
                        .data
                        .getString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON")
                json.put("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON",
                        responseJson)
                return json
            }
        } catch (e: JSONException) {
            Log.i(TAG, "issue while converting credential response to JSON")
        }
        Log.i(TAG, "Unsupported credential type")
        return null
    }

    private fun getEntryToIconMap(
            candidateProviderDataList: List<GetCredentialProviderData>
    ): Map<String, Icon> {
        val entryIconMap: MutableMap<String, Icon> = mutableMapOf()
        candidateProviderDataList.forEach { provider ->
            provider.credentialEntries.forEach { entry ->
                when (val credentialEntry = entry.slice.credentialEntry) {
                    is PasswordCredentialEntry -> {
                        entryIconMap[entry.key + entry.subkey] = credentialEntry.icon
                    }

                    is PublicKeyCredentialEntry -> {
                        entryIconMap[entry.key + entry.subkey] = credentialEntry.icon
                    }

                    is CustomCredentialEntry -> {
                        entryIconMap[entry.key + entry.subkey] = credentialEntry.icon
                    }
                }
            }
        }
        return entryIconMap
    }

    private fun getDefaultIcon(): Icon {
        return Icon.createWithResource(
                this, com.android.credentialmanager.R.drawable.ic_other_sign_in_24)
    }

    private fun convertToFillResponse(
            getCredResponse: GetCandidateCredentialsResponse,
            filLRequest: FillRequest
    ): FillResponse? {
        val candidateProviders = getCredResponse.candidateProviderDataList
        if (candidateProviders.isEmpty()) {
            return null
        }

        val entryIconMap: Map<String, Icon> = getEntryToIconMap(candidateProviders)
        val autofillIdToProvidersMap: Map<AutofillId, ArrayList<GetCredentialProviderData>> =
                mapAutofillIdToProviders(candidateProviders)
        val fillResponseBuilder = FillResponse.Builder()
        var validFillResponse = false
        autofillIdToProvidersMap.forEach { (autofillId, providers) ->
            validFillResponse = processProvidersForAutofillId(
                    filLRequest, autofillId, providers, entryIconMap, fillResponseBuilder,
                    getCredResponse.pendingIntent)
                    .or(validFillResponse)
        }
        if (!validFillResponse) {
            return null
        }
        return fillResponseBuilder.build()
    }

    private fun processProvidersForAutofillId(
            filLRequest: FillRequest,
            autofillId: AutofillId,
            providerDataList: ArrayList<GetCredentialProviderData>,
            entryIconMap: Map<String, Icon>,
            fillResponseBuilder: FillResponse.Builder,
            bottomSheetPendingIntent: PendingIntent?
    ): Boolean {
        val providerList = GetFlowUtils.toProviderList(
            providerDataList,
            this@CredentialAutofillService)
        if (providerList.isEmpty()) {
            return false
        }
        var totalEntryCount = 0
        providerList.forEach { provider ->
            totalEntryCount += provider.credentialEntryList.size
        }
        val providerDisplayInfo: ProviderDisplayInfo = toProviderDisplayInfo(providerList)
        val inlineSuggestionsRequest = filLRequest.inlineSuggestionsRequest
        val inlineMaxSuggestedCount = inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        val inlinePresentationSpecs = inlineSuggestionsRequest?.inlinePresentationSpecs
        val inlinePresentationSpecsCount = inlinePresentationSpecs?.size ?: 0
        val maxDropdownDisplayLimit = this.resources.getInteger(
                com.android.credentialmanager.R.integer.autofill_max_visible_datasets)
        var maxInlineItemCount = totalEntryCount
        maxInlineItemCount = maxInlineItemCount.coerceAtMost(inlineMaxSuggestedCount)
        val lastDropdownDatasetIndex = Settings.Global.getInt(this.contentResolver,
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS,
                (maxDropdownDisplayLimit - 1).coerceAtMost(totalEntryCount - 1))

        var i = 0
        var datasetAdded = false

        val duplicateDisplayNamesForPasskeys: MutableMap<String, Boolean> = mutableMapOf()
        providerDisplayInfo.sortedUserNameToCredentialEntryList.forEach {
            val credentialEntry = it.sortedCredentialEntryList.first()
            if (credentialEntry.credentialType == CredentialType.PASSKEY) {
                credentialEntry.displayName?.let { displayName ->
                    val duplicateEntry = duplicateDisplayNamesForPasskeys.contains(displayName)
                    duplicateDisplayNamesForPasskeys[displayName] = duplicateEntry
                }
            }
        }
        providerDisplayInfo.sortedUserNameToCredentialEntryList.forEach usernameLoop@{
            val primaryEntry = it.sortedCredentialEntryList.first()
            val pendingIntent = primaryEntry.pendingIntent
            val fillInIntent = primaryEntry.fillInIntent
            if (pendingIntent == null || fillInIntent == null) {
                // FillInIntent will not be null because autofillId was retrieved from it.
                Log.e(TAG, "PendingIntent was missing from the entry.")
                return@usernameLoop
            }
            if (i >= maxInlineItemCount && i >= lastDropdownDatasetIndex) {
                return@usernameLoop
            }
            val icon: Icon = if (primaryEntry.icon == null) {
                // The empty entry icon has non-null icon reference but null drawable reference.
                // If the drawable reference is null, then use the default icon.
                getDefaultIcon()
            } else {
                entryIconMap[primaryEntry.entryKey + primaryEntry.entrySubkey]
                        ?: getDefaultIcon()
            }
            // Create inline presentation
            var inlinePresentation: InlinePresentation? = null
            if (inlinePresentationSpecs != null && i < maxInlineItemCount) {
                val spec: InlinePresentationSpec? = if (i < inlinePresentationSpecsCount) {
                    inlinePresentationSpecs[i]
                } else {
                    inlinePresentationSpecs[inlinePresentationSpecsCount - 1]
                }
                inlinePresentation = createInlinePresentation(primaryEntry, pendingIntent, icon,
                        spec!!, duplicateDisplayNamesForPasskeys)
            }
            var dropdownPresentation: RemoteViews? = null
            if (i < lastDropdownDatasetIndex) {
                dropdownPresentation = RemoteViewsFactory
                        .createDropdownPresentation(this, icon, primaryEntry)
            }

            val dataSetBuilder = Dataset.Builder()
            val presentationBuilder = Presentations.Builder()
            if (dropdownPresentation != null) {
                presentationBuilder.setMenuPresentation(dropdownPresentation)
            }
            if (inlinePresentation != null) {
                presentationBuilder.setInlinePresentation(inlinePresentation)
            }

            fillResponseBuilder.addDataset(
                    dataSetBuilder
                            .setField(
                                    autofillId,
                                    Field.Builder().setPresentations(
                                            presentationBuilder.build())
                                            .build())
                            .setAuthentication(pendingIntent.intentSender)
                            .setAuthenticationExtras(fillInIntent.extras)
                            .build())
            datasetAdded = true
            i++

            if (i == lastDropdownDatasetIndex && bottomSheetPendingIntent != null) {
                addDropdownMoreOptionsPresentation(bottomSheetPendingIntent, autofillId,
                        fillResponseBuilder)
            }
        }
        val pinnedSpec = getLastInlinePresentationSpec(inlinePresentationSpecs,
                inlinePresentationSpecsCount)
        if (datasetAdded && bottomSheetPendingIntent != null && pinnedSpec != null) {
            addPinnedInlineSuggestion(bottomSheetPendingIntent, pinnedSpec, autofillId,
                    fillResponseBuilder, providerDataList)
        }
        return datasetAdded
    }

    private fun createInlinePresentation(
        primaryEntry: CredentialEntryInfo,
        pendingIntent: PendingIntent,
        icon: Icon,
        spec: InlinePresentationSpec,
        duplicateDisplayNameForPasskeys: MutableMap<String, Boolean>
    ): InlinePresentation {
        val displayName: String = if (primaryEntry.credentialType == CredentialType.PASSKEY &&
            primaryEntry.displayName != null) {
            primaryEntry.displayName!!
        } else {
            primaryEntry.userName
        }
        val sliceBuilder = InlineSuggestionUi
                .newContentBuilder(pendingIntent)
                .setTitle(displayName)
        sliceBuilder.setStartIcon(icon)
        if (primaryEntry.credentialType ==
                CredentialType.PASSKEY && duplicateDisplayNameForPasskeys[displayName] == true) {
            sliceBuilder.setSubtitle(primaryEntry.userName)
        }
        return InlinePresentation(
                sliceBuilder.build().slice, spec, /* pinned= */ false)
    }

    private fun addDropdownMoreOptionsPresentation(
            bottomSheetPendingIntent: PendingIntent,
            autofillId: AutofillId,
            fillResponseBuilder: FillResponse.Builder
    ) {
        val presentationBuilder = Presentations.Builder()
                .setMenuPresentation(RemoteViewsFactory.createMoreSignInOptionsPresentation(this))

        fillResponseBuilder.addDataset(
                Dataset.Builder()
                        .setField(
                                autofillId,
                                Field.Builder().setPresentations(
                                        presentationBuilder.build())
                                        .build())
                        .setAuthentication(bottomSheetPendingIntent.intentSender)
                        .build()
        )
    }

    private fun getLastInlinePresentationSpec(
            inlinePresentationSpecs: List<InlinePresentationSpec>?,
            inlinePresentationSpecsCount: Int
    ): InlinePresentationSpec? {
        if (inlinePresentationSpecs != null) {
            return inlinePresentationSpecs[inlinePresentationSpecsCount - 1]
        }
        return null
    }

    private fun addPinnedInlineSuggestion(
            bottomSheetPendingIntent: PendingIntent,
            spec: InlinePresentationSpec,
            autofillId: AutofillId,
            fillResponseBuilder: FillResponse.Builder,
            providerDataList: ArrayList<GetCredentialProviderData>
    ) {
        val dataSetBuilder = Dataset.Builder()
        val sliceBuilder = InlineSuggestionUi
                .newContentBuilder(bottomSheetPendingIntent)
                .setStartIcon(Icon.createWithResource(this,
                        com.android.credentialmanager.R.drawable.more_horiz_24px))
        val presentationBuilder = Presentations.Builder()
                .setInlinePresentation(InlinePresentation(
                        sliceBuilder.build().slice, spec, /* pinned= */ true))

        val extraBundle = Bundle()
        extraBundle.putParcelableArrayList(
                        ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST, providerDataList)

        fillResponseBuilder.addDataset(
                dataSetBuilder
                        .setField(
                                autofillId,
                                Field.Builder().setPresentations(
                                        presentationBuilder.build())
                                        .build())
                        .setAuthentication(bottomSheetPendingIntent.intentSender)
                        .setAuthenticationExtras(extraBundle)
                        .build()
        )
    }

    /**
     *  Maps Autofill Id to provider list. For example, passing in a provider info
     *
     *     ProviderInfo {
     *       id1,
     *       displayName1
     *       [entry1(autofillId1), entry2(autofillId2), entry3(autofillId3)],
     *       ...
     *     }
     *
     *     will result in
     *
     *     { autofillId1: ProviderInfo {
     *         id1,
     *         displayName1,
     *         [entry1(autofillId1)],
     *         ...
     *       }, autofillId2: ProviderInfo {
     *         id1,
     *         displayName1,
     *         [entry2(autofillId2)],
     *         ...
     *       }, autofillId3: ProviderInfo {
     *         id1,
     *         displayName1,
     *         [entry3(autofillId3)],
     *         ...
     *       }
     *     }
     */
    private fun mapAutofillIdToProviders(
        providerList: List<GetCredentialProviderData>
    ): Map<AutofillId, ArrayList<GetCredentialProviderData>> {
        val autofillIdToProviders: MutableMap<AutofillId, ArrayList<GetCredentialProviderData>> =
            mutableMapOf()
        providerList.forEach { provider ->
            val autofillIdToCredentialEntries:
                    MutableMap<AutofillId, ArrayList<Entry>> =
                mapAutofillIdToCredentialEntries(provider.credentialEntries)
            autofillIdToCredentialEntries.forEach { (autofillId, entries) ->
                autofillIdToProviders.getOrPut(autofillId) { ArrayList() }
                        .add(copyProviderInfo(provider, entries))
            }
        }
        return autofillIdToProviders
    }

    private fun mapAutofillIdToCredentialEntries(
            credentialEntryList: List<Entry>
    ): MutableMap<AutofillId, ArrayList<Entry>> {
        val autofillIdToCredentialEntries:
                MutableMap<AutofillId, ArrayList<Entry>> = mutableMapOf()
        credentialEntryList.forEach entryLoop@{ credentialEntry ->
            val autofillId: AutofillId? = credentialEntry
                    .frameworkExtrasIntent
                    ?.getParcelableExtra(
                            CredentialProviderService.EXTRA_AUTOFILL_ID,
                            AutofillId::class.java)
            if (autofillId == null) {
                Log.e(TAG, "AutofillId is missing from credential entry. Credential" +
                        " Integration might be disabled.")
                return@entryLoop
            }
            autofillIdToCredentialEntries.getOrPut(autofillId) { ArrayList() }
                    .add(credentialEntry)
        }
        return autofillIdToCredentialEntries
    }

    private fun copyProviderInfo(
            providerInfo: GetCredentialProviderData,
            credentialList: List<Entry>
    ): GetCredentialProviderData {
        return GetCredentialProviderData(
            providerInfo.providerFlattenedComponentName,
            credentialList,
            providerInfo.actionChips,
            providerInfo.authenticationEntries,
            providerInfo.remoteEntry
        )
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        TODO("Not yet implemented")
    }

    private fun getCredManRequest(
            structure: AssistStructure,
            sessionId: Int,
            requestId: Int
    ): GetCredentialRequest? {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        traverseStructure(structure, credentialOptions)

        if (credentialOptions.isNotEmpty()) {
            val dataBundle = Bundle()
            dataBundle.putInt(SESSION_ID_KEY, sessionId)
            dataBundle.putInt(REQUEST_ID_KEY, requestId)
            return GetCredentialRequest.Builder(dataBundle)
                    .setCredentialOptions(credentialOptions)
                    .build()
        }
        return null
    }

    private fun traverseStructure(
            structure: AssistStructure,
            cmRequests: MutableList<CredentialOption>
    ) {
        val windowNodes: List<AssistStructure.WindowNode> =
                structure.run {
                    (0 until windowNodeCount).map { getWindowNodeAt(it) }
                }

        windowNodes.forEach { windowNode: AssistStructure.WindowNode ->
            traverseNode(windowNode.rootViewNode, cmRequests)
        }
    }

    private fun traverseNode(
            viewNode: AssistStructure.ViewNode,
            cmRequests: MutableList<CredentialOption>
    ) {
        viewNode.autofillId?.let {
            val options = getCredentialOptionsFromViewNode(viewNode, it)
            cmRequests.addAll(options)
        }

        val children: List<AssistStructure.ViewNode> =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children.forEach { childNode: AssistStructure.ViewNode ->
            traverseNode(childNode, cmRequests)
        }
    }

    private fun getCredentialOptionsFromViewNode(
            viewNode: AssistStructure.ViewNode,
            autofillId: AutofillId
    ): List<CredentialOption> {
        // TODO(b/293945193) Replace with isCredential check from viewNode
        val credentialHints: MutableList<String> = mutableListOf()
        if (viewNode.autofillHints != null) {
            for (hint in viewNode.autofillHints!!) {
                if (hint.startsWith(CRED_HINT_PREFIX)) {
                    credentialHints.add(hint.substringAfter(CRED_HINT_PREFIX))
                }
            }
        }

        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        for (credentialHint in credentialHints) {
            try {
                convertJsonToCredentialOption(credentialHint, autofillId)
                        .let { credentialOptions.addAll(it) }
            } catch (e: JSONException) {
                Log.i(TAG, "Exception while parsing response: " + e.message)
            }
        }
        return credentialOptions
    }

    private fun convertJsonToCredentialOption(jsonString: String, autofillId: AutofillId):
            List<CredentialOption> {
        // TODO(b/302000646) Move this logic to jetpack so that is consistent
        //  with building the json
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()

        val json = JSONObject(jsonString)
        val jsonGet = json.getJSONObject(REQ_TYPE_KEY)
        val options = jsonGet.getJSONArray(CRED_OPTIONS_KEY)
        for (i in 0 until options.length()) {
            val option = options.getJSONObject(i)
            val candidateBundle = convertJsonToBundle(option.getJSONObject(CANDIDATE_DATA_KEY))
            candidateBundle.putParcelable(
                    CredentialProviderService.EXTRA_AUTOFILL_ID,
                    autofillId)
            credentialOptions.add(CredentialOption(
                    option.getString(TYPE_KEY),
                    convertJsonToBundle(option.getJSONObject(REQUEST_DATA_KEY)),
                    candidateBundle,
                    option.getBoolean(SYS_PROVIDER_REQ_KEY),
            ))
        }
        return credentialOptions
    }

    private fun convertJsonToBundle(json: JSONObject): Bundle {
        val result = Bundle()
        json.keys().forEach {
            val v = json.get(it)
            when (v) {
                is String -> result.putString(it, v)
                is Boolean -> result.putBoolean(it, v)
            }
        }
        return result
    }
}