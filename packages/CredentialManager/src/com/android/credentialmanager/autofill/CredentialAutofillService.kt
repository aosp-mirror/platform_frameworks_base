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
import android.credentials.CredentialManager
import android.credentials.GetCredentialRequest
import android.credentials.GetCandidateCredentialsResponse
import android.credentials.GetCandidateCredentialsException
import android.credentials.CredentialOption
import android.credentials.selection.Entry
import android.credentials.selection.GetCredentialProviderData
import android.credentials.selection.ProviderData
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Flags
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.service.credentials.CredentialProviderService
import android.util.Log
import android.content.Intent
import android.view.autofill.AutofillId
import android.view.autofill.IAutoFillManagerClient
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.android.credentialmanager.GetFlowUtils
import com.android.credentialmanager.common.ui.InlinePresentationsFactory
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

        val responseClientState = Bundle()
        responseClientState.putBoolean(WEBVIEW_REQUESTED_CREDENTIAL_KEY, false)
        val getCredRequest: GetCredentialRequest? = getCredManRequest(structure, sessionId,
                requestId, responseClientState)
        // TODO(b/324635774): Use callback for validating. If the request is coming
        // directly from the view, there should be a corresponding callback, otherwise
        // we should fail fast,
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
                Log.i(TAG, "getCandidateCredentials onResult")
                val fillResponse = convertToFillResponse(result, request,
                    responseClientState, GetFlowUtils.extractTypePriorityMap(getCredRequest))
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
            filLRequest: FillRequest,
            responseClientState: Bundle,
            typePriorityMap: Map<String, Int>,
    ): FillResponse? {
        val candidateProviders = getCredResponse.candidateProviderDataList
        if (candidateProviders.isEmpty()) {
            return null
        }

        val entryIconMap: Map<String, Icon> = getEntryToIconMap(candidateProviders)
        val autofillIdToProvidersMap: Map<AutofillId, ArrayList<GetCredentialProviderData>> =
                mapAutofillIdToProviders(candidateProviders)
        val fillResponseBuilder = FillResponse.Builder()
        fillResponseBuilder.setFlags(FillResponse.FLAG_CREDENTIAL_MANAGER_RESPONSE)
        var validFillResponse = false
        autofillIdToProvidersMap.forEach { (autofillId, providers) ->
            validFillResponse = processProvidersForAutofillId(
                    filLRequest, autofillId, providers, entryIconMap, fillResponseBuilder,
                    getCredResponse.intent, typePriorityMap)
                    .or(validFillResponse)
        }
        if (!validFillResponse) {
            return null
        }
        fillResponseBuilder.setClientState(responseClientState)
        return fillResponseBuilder.build()
    }

    private fun processProvidersForAutofillId(
            filLRequest: FillRequest,
            autofillId: AutofillId,
            providerDataList: ArrayList<GetCredentialProviderData>,
            entryIconMap: Map<String, Icon>,
            fillResponseBuilder: FillResponse.Builder,
            bottomSheetIntent: Intent,
            typePriorityMap: Map<String, Int>,
    ): Boolean {
        val providerList = GetFlowUtils.toProviderList(
            providerDataList,
            this@CredentialAutofillService)
        if (providerList.isEmpty()) {
            return false
        }
        val providerDisplayInfo: ProviderDisplayInfo =
                toProviderDisplayInfo(providerList, typePriorityMap)
        var totalEntryCount = providerDisplayInfo.sortedUserNameToCredentialEntryList.size
        val inlineSuggestionsRequest = filLRequest.inlineSuggestionsRequest
        val inlinePresentationSpecs = inlineSuggestionsRequest?.inlinePresentationSpecs
        val inlinePresentationSpecsCount = inlinePresentationSpecs?.size ?: 0
        val maxDatasetDisplayLimit = this.resources.getInteger(
                com.android.credentialmanager.R.integer.autofill_max_visible_datasets)
                .coerceAtMost(totalEntryCount)
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
        bottomSheetIntent.putExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST, providerDataList)
        providerDisplayInfo.sortedUserNameToCredentialEntryList.forEach usernameLoop@{
            val primaryEntry = it.sortedCredentialEntryList.first()
            val pendingIntent = primaryEntry.pendingIntent
            val fillInIntent = primaryEntry.fillInIntent
            if (pendingIntent == null || fillInIntent == null) {
                // FillInIntent will not be null because autofillId was retrieved from it.
                Log.e(TAG, "PendingIntent was missing from the entry.")
                return@usernameLoop
            }
            if (i >= maxDatasetDisplayLimit) {
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
            if (inlinePresentationSpecs != null && i < maxDatasetDisplayLimit) {
                val spec: InlinePresentationSpec? = if (i < inlinePresentationSpecsCount) {
                    inlinePresentationSpecs[i]
                } else {
                    inlinePresentationSpecs[inlinePresentationSpecsCount - 1]
                }
                if (spec != null) {
                    inlinePresentation = createInlinePresentation(primaryEntry, pendingIntent, icon,
                            InlinePresentationsFactory.modifyInlinePresentationSpec
                            (this@CredentialAutofillService, spec),
                            duplicateDisplayNamesForPasskeys)
                }
            }
            var dropdownPresentation: RemoteViews? = null
            if (i < maxDatasetDisplayLimit) {
                dropdownPresentation = RemoteViewsFactory.createDropdownPresentation(
                    this, icon, primaryEntry, /*isFirstEntry= */ i == 0,
                    /*isLastEntry= */ (totalEntryCount - i == 1))
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
                            .setCredentialFillInIntent(fillInIntent)
                            .build())
            datasetAdded = true
            i++
        }
        val pinnedSpec = getLastInlinePresentationSpec(inlinePresentationSpecs,
                inlinePresentationSpecsCount)
        if (datasetAdded) {
            addDropdownMoreOptionsPresentation(bottomSheetIntent, autofillId, fillResponseBuilder)
            if (pinnedSpec != null) {
                addPinnedInlineSuggestion(pinnedSpec, autofillId,
                        fillResponseBuilder, bottomSheetIntent)
            }
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
        icon.setTintBlendMode(BlendMode.DST)
        sliceBuilder.setStartIcon(icon)
        if (primaryEntry.credentialType ==
                CredentialType.PASSKEY && duplicateDisplayNameForPasskeys[displayName] == true) {
            sliceBuilder.setSubtitle(primaryEntry.userName)
        }
        return InlinePresentation(
                sliceBuilder.build().slice, spec, /* pinned= */ false)
    }

    private fun addDropdownMoreOptionsPresentation(
            bottomSheetIntent: Intent,
            autofillId: AutofillId,
            fillResponseBuilder: FillResponse.Builder
    ) {
        val presentationBuilder = Presentations.Builder()
                .setMenuPresentation(
                        RemoteViewsFactory.createMoreSignInOptionsPresentation(this))
        val pendingIntent = setUpBottomSheetPendingIntent(bottomSheetIntent)

        fillResponseBuilder.addDataset(
                Dataset.Builder()
                        .setField(
                                autofillId,
                                Field.Builder().setPresentations(
                                        presentationBuilder.build())
                                        .build())
                        .setAuthentication(pendingIntent.intentSender)
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
            spec: InlinePresentationSpec,
            autofillId: AutofillId,
            fillResponseBuilder: FillResponse.Builder,
            bottomSheetIntent: Intent
    ) {
        val pendingIntent = setUpBottomSheetPendingIntent(bottomSheetIntent)

        val dataSetBuilder = Dataset.Builder()
        val sliceBuilder = InlineSuggestionUi
                .newContentBuilder(pendingIntent)
                .setStartIcon(Icon.createWithResource(this,
                        com.android.credentialmanager.R.drawable.more_horiz_24px))
        val presentationBuilder = Presentations.Builder()
                .setInlinePresentation(InlinePresentation(
                        sliceBuilder.build().slice, spec, /* pinned= */ true))

        fillResponseBuilder.addDataset(
                dataSetBuilder
                        .setField(
                                autofillId,
                                Field.Builder().setPresentations(
                                        presentationBuilder.build()
                                ).build()
                        )
                        .setAuthentication(pendingIntent.intentSender)
                        .build()
        )
    }

    private fun setUpBottomSheetPendingIntent(intent: Intent): PendingIntent {
        intent.setAction(java.util.UUID.randomUUID().toString())
        return PendingIntent.getActivity(this, /*requestCode=*/0, intent,
                PendingIntent.FLAG_MUTABLE, /*options=*/null)
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
            val intent = credentialEntry.frameworkExtrasIntent
            intent?.getParcelableExtra(
                        CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
                        android.service.credentials.GetCredentialRequest::class.java)
                    ?.credentialOptions
                    ?.forEach { credentialOption ->
                        credentialOption.candidateQueryData.getParcelableArrayList(
                            CredentialProviderService.EXTRA_AUTOFILL_ID, AutofillId::class.java)
                                ?.forEach { autofillId ->
                                    intent.putExtra(
                                        CredentialProviderService.EXTRA_AUTOFILL_ID,
                                        autofillId)
                                    val entry = Entry(
                                        credentialEntry.key,
                                        credentialEntry.subkey,
                                        credentialEntry.slice,
                                        intent)
                                    autofillIdToCredentialEntries
                                            .getOrPut(autofillId) { ArrayList() }
                                            .add(entry)
                                }
                    }
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
            requestId: Int,
            responseClientState: Bundle
    ): GetCredentialRequest? {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        traverseStructureForRequest(structure, credentialOptions, responseClientState, sessionId)

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

    private fun traverseStructureForRequest(
            structure: AssistStructure,
            cmRequests: MutableList<CredentialOption>,
            responseClientState: Bundle,
            sessionId: Int
    ) {
        val traversedViewNodes: MutableSet<AutofillId> = mutableSetOf()
        val credentialOptionsFromHints: MutableMap<String, CredentialOption> = mutableMapOf()
        val windowNodes: List<AssistStructure.WindowNode> =
                structure.run {
                    (0 until windowNodeCount).map { getWindowNodeAt(it) }
                }

        windowNodes.forEach { windowNode: AssistStructure.WindowNode ->
            traverseNodeForRequest(
                windowNode.rootViewNode, cmRequests, responseClientState, traversedViewNodes,
                credentialOptionsFromHints, sessionId)
        }
    }

    private fun traverseNodeForRequest(
            viewNode: AssistStructure.ViewNode,
            cmRequests: MutableList<CredentialOption>,
            responseClientState: Bundle,
            traversedViewNodes: MutableSet<AutofillId>,
            credentialOptionsFromHints: MutableMap<String, CredentialOption>,
            sessionId: Int
    ) {
        viewNode.autofillId?.let {
            val domain = viewNode.webDomain
            val request = viewNode.pendingCredentialRequest
            if (domain != null && request != null) {
                responseClientState.putBoolean(
                    WEBVIEW_REQUESTED_CREDENTIAL_KEY, true)
            }
            cmRequests.addAll(getCredentialOptionsFromViewNode(viewNode, it, responseClientState,
                traversedViewNodes, credentialOptionsFromHints, sessionId))
            traversedViewNodes.add(it)
        }

        val children: List<AssistStructure.ViewNode> =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children.forEach { childNode: AssistStructure.ViewNode ->
            traverseNodeForRequest(childNode, cmRequests, responseClientState, traversedViewNodes,
                credentialOptionsFromHints, sessionId)
        }
    }

    private fun getCredentialOptionsFromViewNode(
            viewNode: AssistStructure.ViewNode,
            autofillId: AutofillId,
            responseClientState: Bundle,
            traversedViewNodes: MutableSet<AutofillId>,
            credentialOptionsFromHints: MutableMap<String, CredentialOption>,
            sessionId: Int
    ): MutableList<CredentialOption> {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        if (Flags.autofillCredmanDevIntegration() && viewNode.pendingCredentialRequest != null) {
            viewNode.pendingCredentialRequest
                    ?.getCredentialOptions()
                    ?.forEach { credentialOption ->
                credentialOption.candidateQueryData
                        .getParcelableArrayList(
                            CredentialProviderService.EXTRA_AUTOFILL_ID, AutofillId::class.java)
                        ?.let { associatedAutofillIds ->
                            // Set sessionId in autofillIds. The autofillIds stored in Credential
                            // Options do not have associated session id and will result in
                            // different hashes than the ones in assistStructure.
                            associatedAutofillIds.forEach { associatedAutofillId ->
                                associatedAutofillId.sessionId = sessionId
                            }

                            // Check whether any of the associated autofill ids have already been
                            // traversed. If so, skip, to dedupe on duplicate credential options.
                            if ((traversedViewNodes intersect associatedAutofillIds.toSet())
                                        .isEmpty()) {
                                credentialOptions.add(credentialOption)
                            }

                            // Set the autofillIds with session id back to credential option.
                            credentialOption.candidateQueryData.putParcelableArrayList(
                                CredentialProviderService.EXTRA_AUTOFILL_ID,
                                associatedAutofillIds
                            )
                        }
            }
        }
        // TODO(b/325502552): clean up cred option logic in autofill hint
        val credentialHints: MutableList<String> = mutableListOf()

        if (viewNode.autofillHints != null) {
            for (hint in viewNode.autofillHints!!) {
                if (hint.startsWith(CRED_HINT_PREFIX)) {
                    credentialHints.add(hint.substringAfter(CRED_HINT_PREFIX))
                    if (viewNode.webDomain != null) {
                        responseClientState.putBoolean(WEBVIEW_REQUESTED_CREDENTIAL_KEY, true)
                    }
                }
            }
        }

        for (credentialHint in credentialHints) {
            try {
                convertJsonToCredentialOption(
                    credentialHint, autofillId, credentialOptionsFromHints)
                        .let { credentialOptions.addAll(it) }
            } catch (e: JSONException) {
                Log.i(TAG, "Exception while parsing response: " + e.message)
            }
        }
        return credentialOptions
    }

    private fun convertJsonToCredentialOption(
        jsonString: String,
        autofillId: AutofillId,
        credentialOptionsFromHints: MutableMap<String, CredentialOption>
    ): List<CredentialOption> {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()

        val json = JSONObject(jsonString)
        val jsonGet = json.getJSONObject(REQ_TYPE_KEY)
        val options = jsonGet.getJSONArray(CRED_OPTIONS_KEY)
        for (i in 0 until options.length()) {
            val option = options.getJSONObject(i)
            val optionString = option.toString()
            credentialOptionsFromHints[optionString]
                    ?.let { credentialOption ->
                        // if the current credential option was seen before, add the current
                        // viewNode to the credential option, but do not add it to the option list
                        // again. This will result in the same result as deduping based on
                        // traversed viewNode.
                        credentialOption.candidateQueryData.getParcelableArrayList(
                            CredentialProviderService.EXTRA_AUTOFILL_ID, AutofillId::class.java)
                                ?.let {
                                    it.add(autofillId)
                                    credentialOption.candidateQueryData.putParcelableArrayList(
                                        CredentialProviderService.EXTRA_AUTOFILL_ID, it)
                                }
            } ?: run {
                val candidateBundle = convertJsonToBundle(option.getJSONObject(CANDIDATE_DATA_KEY))
                candidateBundle.putParcelableArrayList(
                    CredentialProviderService.EXTRA_AUTOFILL_ID,
                    arrayListOf(autofillId))
                val credentialOption = CredentialOption(
                    option.getString(TYPE_KEY),
                    convertJsonToBundle(option.getJSONObject(REQUEST_DATA_KEY)),
                    candidateBundle,
                    option.getBoolean(SYS_PROVIDER_REQ_KEY),
                )
                credentialOptions.add(credentialOption)
                credentialOptionsFromHints[optionString] = credentialOption
            }
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