/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.credentialmanager.common

import android.content.Context
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat.getMainExecutor
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.getCreateTitleResCode
import com.android.credentialmanager.getflow.ProviderDisplayInfo
import com.android.credentialmanager.getflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.generateDisplayTitleTextResCode
import com.android.credentialmanager.model.BiometricRequestInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.creation.CreateOptionInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.get.ProviderInfo
import java.lang.Exception

/**
 * Aggregates common display information used for the Biometric Flow.
 * Namely, this adds the ability to encapsulate the [providerIcon], the providers icon, the
 * [providerName], which represents the name of the provider, the [displayTitleText] which is
 * the large text displaying the flow in progress, and the [descriptionForCredential], which
 * describes details of where the credential is being saved, and how.
 * (E.g. assume a hypothetical provider 'Any Provider' for *passkey* flows with Your@Email.com:
 *
 * 'get' flow:
 *     - [providerIcon] and [providerName] = 'Any Provider' (and it's icon)
 *     - [displayTitleText] = "Use your saved passkey for Any Provider?"
 *     - [descriptionForCredential] = "Use your screen lock to sign in to Any Provider with
 *     Your@Email.com"
 *
 * 'create' flow:
 *     - [providerIcon] and [providerName] = 'Any Provider' (and it's icon)
 *     - [displayTitleText] = "Create passkey to sign in to Any Provider?"
 *     - [descriptionForCredential] = "Use your screen lock to create a passkey for Any Provider?"
 * ).
 *
 * The above are examples; the credential type can change depending on scenario.
 * // TODO(b/326243891) : Finalize once all the strings and create flow is iterated to completion
 */
data class BiometricDisplayInfo(
    val providerIcon: Bitmap,
    val providerName: String,
    val displayTitleText: String,
    val descriptionForCredential: String,
    val biometricRequestInfo: BiometricRequestInfo,
)

/**
 * Sets up generic state used by the create and get flows to hold the holistic states for the flow.
 * These match all the present callback values from [BiometricPrompt], and may be extended to hold
 * additional states that may improve the flow.
 */
data class BiometricState(
    val biometricResult: BiometricResult? = null
)

/**
 * When a result exists, it must be retrievable. This encapsulates the result
 * so that should this object exist, the result will be retrievable.
 */
data class BiometricResult(
    val biometricAuthenticationResult: BiometricPrompt.AuthenticationResult
)

/**
 * Encapsulates the error callback results to easily manage biometric error states in the flow.
 */
data class BiometricError(
    val errorCode: Int,
    val errString: CharSequence? = null
)

/**
 * Encapsulates the help callback results to easily manage biometric help states in the flow.
 * To specify, this allows us to parse the onAuthenticationHelp method in the [BiometricPrompt].
 */
data class BiometricHelp(
    val helpCode: Int,
    var helpString: CharSequence? = null
)

/**
 * This will handle the logic for integrating credential manager with the biometric prompt for the
 * single account biometric experience. This simultaneously handles both the get and create flows,
 * by retrieving all the data from credential manager, and properly parsing that data into the
 * biometric prompt.
 */
fun runBiometricFlow(
    biometricEntry: EntryInfo,
    context: Context,
    openMoreOptionsPage: () -> Unit,
    sendDataToProvider: (EntryInfo, BiometricPrompt.AuthenticationResult) -> Unit,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
    getRequestDisplayInfo: RequestDisplayInfo? = null,
    getProviderInfoList: List<ProviderInfo>? = null,
    getProviderDisplayInfo: ProviderDisplayInfo? = null,
    onBiometricFailureFallback: () -> Unit,
    createRequestDisplayInfo: com.android.credentialmanager.createflow
    .RequestDisplayInfo? = null,
    createProviderInfo: EnabledProviderInfo? = null,
) {
    // TODO(b/330396089) : Add rotation configuration fix with state machine
    var biometricDisplayInfo: BiometricDisplayInfo? = null
    var flowType = FlowType.GET
    if (getRequestDisplayInfo != null) {
        biometricDisplayInfo = validateAndRetrieveBiometricGetDisplayInfo(getRequestDisplayInfo,
            getProviderInfoList,
            getProviderDisplayInfo,
            context, biometricEntry)
    } else if (createRequestDisplayInfo != null) {
        flowType = FlowType.CREATE
        biometricDisplayInfo = validateAndRetrieveBiometricCreateDisplayInfo(
            createRequestDisplayInfo,
            createProviderInfo,
            context, biometricEntry)
    }

    if (biometricDisplayInfo == null) {
        onBiometricFailureFallback()
        return
    }

    val biometricPrompt = setupBiometricPrompt(context, biometricDisplayInfo, openMoreOptionsPage,
        biometricDisplayInfo.biometricRequestInfo.allowedAuthenticators, flowType)

    val callback: BiometricPrompt.AuthenticationCallback =
        setupBiometricAuthenticationCallback(sendDataToProvider, biometricEntry,
            onCancelFlowAndFinish, onIllegalStateAndFinish)

    val cancellationSignal = CancellationSignal()
    cancellationSignal.setOnCancelListener {
        Log.d(TAG, "Your cancellation signal was called.")
        // TODO(b/326243754) : Migrate towards passing along the developer cancellation signal
        // or validate the necessity for this
    }

    val executor = getMainExecutor(context)

    try {
        biometricPrompt.authenticate(cancellationSignal, executor, callback)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Calling the biometric prompt API failed with: /n${e.localizedMessage}\n")
        onBiometricFailureFallback()
    }
}

/**
 * Sets up the biometric prompt with the UI specific bits.
 * // TODO(b/326243754) : Pass in opId once dependency is confirmed via CryptoObject
 */
private fun setupBiometricPrompt(
    context: Context,
    biometricDisplayInfo: BiometricDisplayInfo,
    openMoreOptionsPage: () -> Unit,
    requestAllowedAuthenticators: Int,
    flowType: FlowType,
): BiometricPrompt {
    val finalAuthenticators = removeDeviceCredential(requestAllowedAuthenticators)

    val biometricPrompt = BiometricPrompt.Builder(context)
        .setTitle(biometricDisplayInfo.displayTitleText)
        // TODO(b/326243754) : Migrate to using new methods recently aligned upon
        .setNegativeButton(context.getString(if (flowType == FlowType.GET) R.string
                .dropdown_presentation_more_sign_in_options_text else R.string.string_more_options),
            getMainExecutor(context)) { _, _ ->
            openMoreOptionsPage()
        }
        .setAllowedAuthenticators(finalAuthenticators)
        .setConfirmationRequired(true)
        .setLogoBitmap(biometricDisplayInfo.providerIcon)
        .setLogoDescription(biometricDisplayInfo.providerName)
        .setDescription(biometricDisplayInfo.descriptionForCredential)
        .build()

    return biometricPrompt
}

// TODO(b/326243754) : Remove after larger level alignments made on fallback negative button
// For the time being, we do not support the pin fallback until UX is decided.
private fun removeDeviceCredential(requestAllowedAuthenticators: Int): Int {
    var finalAuthenticators = requestAllowedAuthenticators

    if (requestAllowedAuthenticators == (BiometricManager.Authenticators.DEVICE_CREDENTIAL or
                BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
        finalAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    if (requestAllowedAuthenticators == (BiometricManager.Authenticators.DEVICE_CREDENTIAL or
                BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
        finalAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    if (requestAllowedAuthenticators == (BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
        finalAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    return finalAuthenticators
}

/**
 * Sets up the biometric authentication callback.
 */
private fun setupBiometricAuthenticationCallback(
    sendDataToProvider: (EntryInfo, BiometricPrompt.AuthenticationResult) -> Unit,
    selectedEntry: EntryInfo,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
): BiometricPrompt.AuthenticationCallback {
    val callback: BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {
            // TODO(b/326243754) : Validate remaining callbacks
            override fun onAuthenticationSucceeded(
                authResult: BiometricPrompt.AuthenticationResult?
            ) {
                super.onAuthenticationSucceeded(authResult)
                try {
                    if (authResult != null) {
                        sendDataToProvider(selectedEntry, authResult)
                    } else {
                        onIllegalStateAndFinish("The biometric flow succeeded but unexpectedly " +
                                "returned a null value.")
                    }
                } catch (e: Exception) {
                    onIllegalStateAndFinish("The biometric flow succeeded but failed on handling " +
                            "the result. See: \n$e\n")
                }
            }

            override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {
                super.onAuthenticationHelp(helpCode, helpString)
                Log.d(TAG, "Authentication help discovered: $helpCode and $helpString")
                // TODO(b/326243754) : Decide on strategy with provider (a simple log probably
                // suffices here)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                Log.d(TAG, "Authentication error-ed out: $errorCode and $errString")
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    // Note that because the biometric prompt is imbued directly
                    // into the selector, parity applies to the selector's cancellation instead
                    // of the provider's biometric prompt cancellation.
                    onCancelFlowAndFinish()
                }
                // TODO(b/326243754) : Propagate to provider
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.d(TAG, "Authentication failed.")
                // TODO(b/326243754) : Propagate to provider
            }
        }
    return callback
}

/**
 * Creates the [BiometricDisplayInfo] for get flows, and early handles conditional
 * checking between the two.  Note that while this method's main purpose is to retrieve the info
 * required to display the biometric prompt, it acts as a secondary validator to handle any null
 * checks at the beginning of the biometric flow and supports a quick fallback.
 * While it's not expected for the flow to be triggered if values are
 * missing, some values are by default nullable when they are pulled, such as entries. Thus, this
 * acts as a final validation failsafe, without requiring null checks or null forcing around the
 * codebase.
 */
private fun validateAndRetrieveBiometricGetDisplayInfo(
    getRequestDisplayInfo: RequestDisplayInfo?,
    getProviderInfoList: List<ProviderInfo>?,
    getProviderDisplayInfo: ProviderDisplayInfo?,
    context: Context,
    selectedEntry: EntryInfo
): BiometricDisplayInfo? {
    if (getRequestDisplayInfo != null && getProviderInfoList != null &&
        getProviderDisplayInfo != null) {
        if (selectedEntry !is CredentialEntryInfo) { return null }
        return getBiometricDisplayValues(getProviderInfoList,
            context, getRequestDisplayInfo, selectedEntry)
    }
    return null
}

/**
 * Creates the [BiometricDisplayInfo] for create flows, and early handles conditional
 * checking between the two. The reason for this method matches the logic for the
 * [validateBiometricGetFlow] with the only difference being that this is for the create flow.
 */
private fun validateAndRetrieveBiometricCreateDisplayInfo(
    createRequestDisplayInfo: com.android.credentialmanager.createflow.RequestDisplayInfo?,
    createProviderInfo: EnabledProviderInfo?,
    context: Context,
    selectedEntry: EntryInfo,
): BiometricDisplayInfo? {
    if (createRequestDisplayInfo != null && createProviderInfo != null) {
        if (selectedEntry !is CreateOptionInfo) { return null }
        return createBiometricDisplayValues(createRequestDisplayInfo, createProviderInfo, context,
            selectedEntry)
    }
    return null
}

/**
 * Handles the biometric sign in via the 'get credentials' flow.
 * If any expected value is not present, the flow is considered unreachable and we will fallback
 * to the original selector. Note that these redundant checks are just failsafe; the original
 * flow should never reach here with invalid params.
 */
private fun getBiometricDisplayValues(
    getProviderInfoList: List<ProviderInfo>,
    context: Context,
    getRequestDisplayInfo: RequestDisplayInfo,
    selectedEntry: CredentialEntryInfo,
): BiometricDisplayInfo? {
    var icon: Bitmap? = null
    var providerName: String? = null
    var displayTitleText: String? = null
    var descriptionText: String? = null
    val primaryAccountsProviderInfo = retrievePrimaryAccountProviderInfo(selectedEntry.providerId,
        getProviderInfoList)
    icon = primaryAccountsProviderInfo?.icon?.toBitmap()
    providerName = primaryAccountsProviderInfo?.displayName
    if (icon == null || providerName == null) {
        Log.d(TAG, "Unexpectedly found invalid provider information.")
        return null
    }
    if (selectedEntry.biometricRequest == null) {
        Log.d(TAG, "Unexpectedly in biometric flow without a biometric request.")
        return null
    }
    val singleEntryType = selectedEntry.credentialType
    val username = selectedEntry.userName
    displayTitleText = context.getString(
        generateDisplayTitleTextResCode(singleEntryType),
        getRequestDisplayInfo.appName
    )
    descriptionText = context.getString(
        R.string.get_dialog_title_single_tap_for,
        getRequestDisplayInfo.appName,
        username
    )
    return BiometricDisplayInfo(providerIcon = icon, providerName = providerName,
        displayTitleText = displayTitleText, descriptionForCredential = descriptionText,
        biometricRequestInfo = selectedEntry.biometricRequest as BiometricRequestInfo)
}

/**
 * Handles the biometric sign in via the create credentials flow. Stricter in the get flow in that
 * if this is called, a result is guaranteed. Specifically, this is guaranteed to return a non-null
 * value unlike the get counterpart.
 */
private fun createBiometricDisplayValues(
    createRequestDisplayInfo: com.android.credentialmanager.createflow.RequestDisplayInfo,
    createProviderInfo: EnabledProviderInfo,
    context: Context,
    selectedEntry: CreateOptionInfo,
): BiometricDisplayInfo {
    val icon: Bitmap?
    val providerName: String?
    val displayTitleText: String?
    icon = createProviderInfo.icon.toBitmap()
    providerName = createProviderInfo.displayName
    displayTitleText = context.getString(
        getCreateTitleResCode(createRequestDisplayInfo),
        createRequestDisplayInfo.appName
    )
    val descriptionText: String = context.getString(
        when (createRequestDisplayInfo.type) {
            CredentialType.PASSKEY ->
                R.string.choose_create_single_tap_passkey_title

            CredentialType.PASSWORD ->
                R.string.choose_create_single_tap_password_title

            CredentialType.UNKNOWN ->
                R.string.choose_create_single_tap_sign_in_title
        },
        createRequestDisplayInfo.appName,
    )
    // TODO(b/327620327) : Add a subtitle and any other recently aligned ideas
    return BiometricDisplayInfo(providerIcon = icon, providerName = providerName,
        displayTitleText = displayTitleText, descriptionForCredential = descriptionText,
        biometricRequestInfo = selectedEntry.biometricRequest as BiometricRequestInfo)
}

/**
 * During a get flow with single tap sign in enabled, this will match the credentialEntry that
 * will single tap with the correct provider info. Namely, it's the first provider info that
 * contains a matching providerId to the selected entry.
 */
private fun retrievePrimaryAccountProviderInfo(
    providerId: String,
    getProviderInfoList: List<ProviderInfo>
): ProviderInfo? {
    var discoveredProviderInfo: ProviderInfo? = null
    getProviderInfoList.forEach { provider ->
        if (provider.id == providerId) {
            discoveredProviderInfo = provider
            return@forEach
        }
    }
    return discoveredProviderInfo
}

const val TAG = "BiometricHandler"
