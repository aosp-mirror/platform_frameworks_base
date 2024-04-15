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
import android.hardware.biometrics.CryptoObject
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
 * // TODO(b/333445112) : Finalize once all the strings and create flow is iterated to completion
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
    val biometricResult: BiometricResult? = null,
    val biometricError: BiometricError? = null,
    val biometricStatus: BiometricPromptState = BiometricPromptState.INACTIVE
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
    val errorMessage: CharSequence? = null
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
 * This is the entry point to start the integrated biometric prompt for 'get' flows. It captures
 * information specific to the get flow, along with required shared callbacks and more general
 * info across both flows, such as the tapped [EntryInfo] or [sendDataToProvider].
 */
fun runBiometricFlowForGet(
    biometricEntry: EntryInfo,
    context: Context,
    openMoreOptionsPage: () -> Unit,
    sendDataToProvider: (EntryInfo, BiometricPrompt.AuthenticationResult?, BiometricError?) -> Unit,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
    getBiometricPromptState: () -> BiometricPromptState,
    onBiometricPromptStateChange: (BiometricPromptState) -> Unit,
    onBiometricFailureFallback: (BiometricFlowType) -> Unit,
    getRequestDisplayInfo: RequestDisplayInfo? = null,
    getProviderInfoList: List<ProviderInfo>? = null,
    getProviderDisplayInfo: ProviderDisplayInfo? = null,
) {
    if (getBiometricPromptState() != BiometricPromptState.INACTIVE) {
        // Screen is already up, do not re-launch
        return
    }
    onBiometricPromptStateChange(BiometricPromptState.PENDING)
    val biometricDisplayInfo = validateAndRetrieveBiometricGetDisplayInfo(
        getRequestDisplayInfo,
        getProviderInfoList,
        getProviderDisplayInfo,
        context, biometricEntry
    )

    if (biometricDisplayInfo == null) {
        onBiometricFailureFallback(BiometricFlowType.GET)
        return
    }

    val callback: BiometricPrompt.AuthenticationCallback =
        setupBiometricAuthenticationCallback(sendDataToProvider, biometricEntry,
            onCancelFlowAndFinish, onIllegalStateAndFinish, onBiometricPromptStateChange)

    Log.d(TAG, "The BiometricPrompt API call begins.")
    runBiometricFlow(context, biometricDisplayInfo, callback, openMoreOptionsPage,
        onBiometricFailureFallback, BiometricFlowType.GET)
}

/**
 * This is the entry point to start the integrated biometric prompt for 'create' flows. It captures
 * information specific to the create flow, along with required shared callbacks and more general
 * info across both flows, such as the tapped [EntryInfo] or [sendDataToProvider].
 */
fun runBiometricFlowForCreate(
    biometricEntry: EntryInfo,
    context: Context,
    openMoreOptionsPage: () -> Unit,
    sendDataToProvider: (EntryInfo, BiometricPrompt.AuthenticationResult?, BiometricError?) -> Unit,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
    getBiometricPromptState: () -> BiometricPromptState,
    onBiometricPromptStateChange: (BiometricPromptState) -> Unit,
    onBiometricFailureFallback: (BiometricFlowType) -> Unit,
    createRequestDisplayInfo: com.android.credentialmanager.createflow
    .RequestDisplayInfo? = null,
    createProviderInfo: EnabledProviderInfo? = null,
) {
    if (getBiometricPromptState() != BiometricPromptState.INACTIVE) {
        // Screen is already up, do not re-launch
        return
    }
    onBiometricPromptStateChange(BiometricPromptState.PENDING)
    val biometricDisplayInfo = validateAndRetrieveBiometricCreateDisplayInfo(
        createRequestDisplayInfo,
        createProviderInfo,
        context, biometricEntry
    )

    if (biometricDisplayInfo == null) {
        onBiometricFailureFallback(BiometricFlowType.CREATE)
        return
    }

    val callback: BiometricPrompt.AuthenticationCallback =
        setupBiometricAuthenticationCallback(sendDataToProvider, biometricEntry,
            onCancelFlowAndFinish, onIllegalStateAndFinish, onBiometricPromptStateChange)

    Log.d(TAG, "The BiometricPrompt API call begins.")
    runBiometricFlow(context, biometricDisplayInfo, callback, openMoreOptionsPage,
        onBiometricFailureFallback, BiometricFlowType.CREATE)
}

/**
 * This will handle the logic for integrating credential manager with the biometric prompt for the
 * single account biometric experience. This simultaneously handles both the get and create flows,
 * by retrieving all the data from credential manager, and properly parsing that data into the
 * biometric prompt.
 */
private fun runBiometricFlow(
    context: Context,
    biometricDisplayInfo: BiometricDisplayInfo,
    callback: BiometricPrompt.AuthenticationCallback,
    openMoreOptionsPage: () -> Unit,
    onBiometricFailureFallback: (BiometricFlowType) -> Unit,
    biometricFlowType: BiometricFlowType
) {
    val biometricPrompt = setupBiometricPrompt(context, biometricDisplayInfo, openMoreOptionsPage,
        biometricDisplayInfo.biometricRequestInfo, biometricFlowType)

    val cancellationSignal = CancellationSignal()
    cancellationSignal.setOnCancelListener {
        Log.d(TAG, "Your cancellation signal was called.")
        // TODO(b/333445112) : Migrate towards passing along the developer cancellation signal
        // or validate the necessity for this
    }

    val executor = getMainExecutor(context)

    try {
        val cryptoOpId = getCryptoOpId(biometricDisplayInfo)
        if (cryptoOpId != null) {
            biometricPrompt.authenticate(
                    BiometricPrompt.CryptoObject(cryptoOpId.toLong()),
                    cancellationSignal, executor, callback)
        } else {
            biometricPrompt.authenticate(cancellationSignal, executor, callback)
        }
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Calling the biometric prompt API failed with: /n${e.localizedMessage}\n")
        onBiometricFailureFallback(biometricFlowType)
    }
}

private fun getCryptoOpId(biometricDisplayInfo: BiometricDisplayInfo): Int? {
    return biometricDisplayInfo.biometricRequestInfo.opId
}

/**
 * Sets up the biometric prompt with the UI specific bits.
 * // TODO(b/333445112) : Pass in opId once dependency is confirmed via CryptoObject
 */
private fun setupBiometricPrompt(
    context: Context,
    biometricDisplayInfo: BiometricDisplayInfo,
    openMoreOptionsPage: () -> Unit,
    biometricRequestInfo: BiometricRequestInfo,
    biometricFlowType: BiometricFlowType,
): BiometricPrompt {
    val finalAuthenticators = removeDeviceCredential(biometricRequestInfo.allowedAuthenticators)

    val biometricPrompt = BiometricPrompt.Builder(context)
        .setTitle(biometricDisplayInfo.displayTitleText)
        // TODO(b/333445112) : Migrate to using new methods and strings recently aligned upon
        .setNegativeButton(context.getString(if (biometricFlowType == BiometricFlowType.GET)
            R.string
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

// TODO(b/333445112) : Remove after larger level alignments made on fallback negative button
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
    sendDataToProvider: (EntryInfo, BiometricPrompt.AuthenticationResult?, BiometricError?) -> Unit,
    selectedEntry: EntryInfo,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
    onBiometricPromptStateChange: (BiometricPromptState) -> Unit
): BiometricPrompt.AuthenticationCallback {
    val callback: BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {
            // TODO(b/333445772) : Validate remaining callbacks
            override fun onAuthenticationSucceeded(
                authResult: BiometricPrompt.AuthenticationResult?
            ) {
                super.onAuthenticationSucceeded(authResult)
                try {
                    if (authResult != null) {
                        onBiometricPromptStateChange(BiometricPromptState.COMPLETE)
                        sendDataToProvider(selectedEntry, authResult, /*authError=*/null)
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
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                Log.d(TAG, "Authentication error-ed out: $errorCode and $errString")
                onBiometricPromptStateChange(BiometricPromptState.COMPLETE)
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    // Note that because the biometric prompt is imbued directly
                    // into the selector, parity applies to the selector's cancellation instead
                    // of the provider's biometric prompt cancellation.
                    onCancelFlowAndFinish()
                } else {
                    sendDataToProvider(selectedEntry, /*authResult=*/null, /*authError=*/
                        BiometricError(errorCode, errString))
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.d(TAG, "Authentication failed.")
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
        return retrieveBiometricGetDisplayValues(getProviderInfoList,
            context, getRequestDisplayInfo, selectedEntry)
    }
    return null
}

/**
 * Creates the [BiometricDisplayInfo] for create flows, and early handles conditional
 * checking between the two. The reason for this method matches the logic for the
 * [validateAndRetrieveBiometricGetDisplayInfo] with the only difference being that this is for
 * the create flow.
 */
private fun validateAndRetrieveBiometricCreateDisplayInfo(
    createRequestDisplayInfo: com.android.credentialmanager.createflow.RequestDisplayInfo?,
    createProviderInfo: EnabledProviderInfo?,
    context: Context,
    selectedEntry: EntryInfo,
): BiometricDisplayInfo? {
    if (createRequestDisplayInfo != null && createProviderInfo != null) {
        if (selectedEntry !is CreateOptionInfo) { return null }
        return retrieveBiometricCreateDisplayValues(createRequestDisplayInfo, createProviderInfo,
            context, selectedEntry)
    }
    return null
}

/**
 * Handles the biometric sign in via the 'get credentials' flow.
 * If any expected value is not present, the flow is considered unreachable and we will fallback
 * to the original selector. Note that these redundant checks are just failsafe; the original
 * flow should never reach here with invalid params.
 */
private fun retrieveBiometricGetDisplayValues(
    getProviderInfoList: List<ProviderInfo>,
    context: Context,
    getRequestDisplayInfo: RequestDisplayInfo,
    selectedEntry: CredentialEntryInfo,
): BiometricDisplayInfo? {
    val icon: Bitmap?
    val providerName: String?
    val displayTitleText: String?
    val descriptionText: String?
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
private fun retrieveBiometricCreateDisplayValues(
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
    // TODO(b/333445112) : Add a subtitle and any other recently aligned ideas
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
