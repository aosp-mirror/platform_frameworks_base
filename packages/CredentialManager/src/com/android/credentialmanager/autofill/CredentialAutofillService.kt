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

import android.app.assist.AssistStructure
import android.content.Context
import android.credentials.CredentialManager
import android.credentials.CredentialOption
import android.credentials.GetCandidateCredentialsException
import android.credentials.GetCandidateCredentialsResponse
import android.credentials.GetCredentialRequest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
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
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.android.credentialmanager.GetFlowUtils
import org.json.JSONObject
import java.util.concurrent.Executors

class CredentialAutofillService : AutofillService() {

    companion object {
        private const val TAG = "CredAutofill"

        private const val CRED_HINT_PREFIX = "credential="
        private const val REQUEST_DATA_KEY = "requestData"
        private const val CANDIDATE_DATA_KEY = "candidateQueryData"
        private const val SYS_PROVIDER_REQ_KEY = "isSystemProviderRequired"
        private const val CRED_OPTIONS_KEY = "credentialOptions"
        private const val TYPE_KEY = "type"
    }

    private val credentialManager: CredentialManager =
            getSystemService(Context.CREDENTIAL_SERVICE) as CredentialManager

    override fun onFillRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback
    ) {
        val context = request.fillContexts
        val structure = context[context.size - 1].structure
        val callingPackage = structure.activityComponent.packageName
        Log.i(TAG, "onFillRequest called for $callingPackage")

        val getCredRequest: GetCredentialRequest? = getCredManRequest(structure)
        if (getCredRequest == null) {
            callback.onFailure("No credential manager request found")
            return
        }

        val outcome = object : OutcomeReceiver<GetCandidateCredentialsResponse,
                GetCandidateCredentialsException> {
            override fun onResult(result: GetCandidateCredentialsResponse) {
                Log.i(TAG, "getCandidateCredentials onResponse")
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
                outcome
        )
    }

    private fun convertToFillResponse(
            getCredResponse: GetCandidateCredentialsResponse,
            filLRequest: FillRequest
    ): FillResponse? {
        val providerList = GetFlowUtils.toProviderList(
                getCredResponse.candidateProviderDataList,
                this@CredentialAutofillService)
        var totalEntryCount = 0
        providerList.forEach { provider ->
            totalEntryCount += provider.credentialEntryList.size
        }
        val inlineSuggestionsRequest = filLRequest.inlineSuggestionsRequest
        val inlineMaxSuggestedCount = inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        val inlinePresentationSpecs = inlineSuggestionsRequest?.inlinePresentationSpecs
        val inlinePresentationSpecsCount = inlinePresentationSpecs?.size ?: 0
        var maxItemCount = totalEntryCount
        if (inlineMaxSuggestedCount > 0) {
            maxItemCount = maxItemCount.coerceAtMost(inlineMaxSuggestedCount)
        }
        var i = 0
        val fillResponseBuilder = FillResponse.Builder()
        var emptyFillResponse = true
        providerList.forEach {provider ->
            // TODO(b/299321128): Before iterating the list, sort the list so that
            //  the relevant entries don't get truncated
            provider.credentialEntryList.forEach entryLoop@ {entry ->
                val autofillId: AutofillId? = entry.fillInIntent?.getParcelableExtra(
                        CredentialProviderService.EXTRA_AUTOFILL_ID,
                        AutofillId::class.java)
                val pendingIntent = entry.pendingIntent
                if (autofillId == null || pendingIntent == null) {
                    return@entryLoop
                }
                var inlinePresentation: InlinePresentation? = null
                // Create inline presentation
                if (inlinePresentationSpecs != null && i < maxItemCount) {
                    val spec: InlinePresentationSpec
                    if (i < inlinePresentationSpecsCount) {
                        spec = inlinePresentationSpecs[i]
                    } else {
                        spec = inlinePresentationSpecs[inlinePresentationSpecsCount - 1]
                    }
                    val sliceBuilder = InlineSuggestionUi
                            .newContentBuilder(pendingIntent)
                            .setTitle(entry.userName)
                    inlinePresentation = InlinePresentation(
                            sliceBuilder.build().slice, spec, /* pinned= */ false)
                }
                i++

                val dataSetBuilder = Dataset.Builder()
                val presentationBuilder = Presentations.Builder()
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
                                .setAuthentication(entry.pendingIntent.intentSender)
                                .setAuthenticationExtras(entry.fillInIntent.extras)
                                .build())
                emptyFillResponse = false
            }
        }
        if (emptyFillResponse) {
            return null
        }
        return fillResponseBuilder.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        TODO("Not yet implemented")
    }

    private fun getCredManRequest(structure: AssistStructure): GetCredentialRequest? {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        traverseStructure(structure, credentialOptions)

        if (credentialOptions.isNotEmpty()) {
            return GetCredentialRequest.Builder(Bundle.EMPTY)
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
            convertJsonToCredentialOption(credentialHint, autofillId)
                    .let { credentialOptions.addAll(it) }
        }
        return credentialOptions
    }

    private fun convertJsonToCredentialOption(jsonString: String, autofillId: AutofillId):
            List<CredentialOption> {
        // TODO(b/302000646) Move this logic to jetpack so that is consistent
        //  with building the json
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()

        val json = JSONObject(jsonString)
        val options = json.getJSONArray(CRED_OPTIONS_KEY)
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