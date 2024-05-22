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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import kotlinx.coroutines.flow.Flow

/**
 * Interactor that exposes API to get the face authentication status and handle any events that can
 * cause face authentication to run for device entry.
 */
interface DeviceEntryFaceAuthInteractor {

    /** Current authentication status */
    val authenticationStatus: Flow<FaceAuthenticationStatus>

    /** Current detection status */
    val detectionStatus: Flow<FaceDetectionStatus>

    val lockedOut: Flow<Boolean>

    val authenticated: Flow<Boolean>

    /** Whether bypass is enabled. If enabled, face unlock dismisses the lock screen. */
    val isBypassEnabled: Flow<Boolean>

    /** Can face auth be run right now */
    fun canFaceAuthRun(): Boolean

    /** Whether face auth is currently running or not. */
    fun isRunning(): Boolean

    /** Whether face auth is in lock out state. */
    fun isLockedOut(): Boolean

    /** Whether face auth is enrolled and enabled for the current user */
    fun isFaceAuthEnabledAndEnrolled(): Boolean

    /** Whether the current user is authenticated successfully with face auth */
    fun isAuthenticated(): Boolean
    /**
     * Register listener for use from code that cannot use [authenticationStatus] or
     * [detectionStatus]
     */
    fun registerListener(listener: FaceAuthenticationListener)

    /** Unregister previously registered listener */
    fun unregisterListener(listener: FaceAuthenticationListener)

    fun onUdfpsSensorTouched()
    fun onAssistantTriggeredOnLockScreen()
    fun onDeviceLifted()
    fun onQsExpansionStared()
    fun onNotificationPanelClicked()
    fun onSwipeUpOnBouncer()
    fun onPrimaryBouncerUserInput()
    fun onAccessibilityAction()
    fun onWalletLaunched()
    fun onDeviceUnfolded()

    /** Whether face auth is considered class 3 */
    fun isFaceAuthStrong(): Boolean
}

/**
 * Listener that can be registered with the [DeviceEntryFaceAuthInteractor] to receive updates about
 * face authentication & detection updates.
 *
 * This is present to make it easier for use the new face auth API for code that cannot use
 * [DeviceEntryFaceAuthInteractor.authenticationStatus] or
 * [DeviceEntryFaceAuthInteractor.detectionStatus] flows.
 */
interface FaceAuthenticationListener {
    /** Receive face isAuthenticated updates */
    fun onAuthenticatedChanged(isAuthenticated: Boolean)

    /** Receive face authentication status updates */
    fun onAuthenticationStatusChanged(status: FaceAuthenticationStatus)

    /** Receive status updates whenever face detection runs */
    fun onDetectionStatusChanged(status: FaceDetectionStatus)

    fun onLockoutStateChanged(isLockedOut: Boolean)

    fun onRunningStateChanged(isRunning: Boolean)

    fun onAuthEnrollmentStateChanged(enrolled: Boolean)
}
