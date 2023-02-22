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

package com.android.keyguard

import android.annotation.StringDef
import android.os.PowerManager
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.FaceAuthApiRequestReason.Companion.NOTIFICATION_PANEL_CLICKED
import com.android.keyguard.FaceAuthApiRequestReason.Companion.PICK_UP_GESTURE_TRIGGERED
import com.android.keyguard.FaceAuthApiRequestReason.Companion.QS_EXPANDED
import com.android.keyguard.FaceAuthApiRequestReason.Companion.SWIPE_UP_ON_BOUNCER
import com.android.keyguard.FaceAuthApiRequestReason.Companion.UDFPS_POINTER_DOWN
import com.android.keyguard.InternalFaceAuthReasons.ALL_AUTHENTICATORS_REGISTERED
import com.android.keyguard.InternalFaceAuthReasons.ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import com.android.keyguard.InternalFaceAuthReasons.ASSISTANT_VISIBILITY_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.AUTH_REQUEST_DURING_CANCELLATION
import com.android.keyguard.InternalFaceAuthReasons.BIOMETRIC_ENABLED
import com.android.keyguard.InternalFaceAuthReasons.CAMERA_LAUNCHED
import com.android.keyguard.InternalFaceAuthReasons.DEVICE_WOKEN_UP_ON_REACH_GESTURE
import com.android.keyguard.InternalFaceAuthReasons.DREAM_STARTED
import com.android.keyguard.InternalFaceAuthReasons.DREAM_STOPPED
import com.android.keyguard.InternalFaceAuthReasons.ENROLLMENTS_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.FACE_AUTHENTICATED
import com.android.keyguard.InternalFaceAuthReasons.FACE_AUTH_STOPPED_ON_USER_INPUT
import com.android.keyguard.InternalFaceAuthReasons.FACE_CANCEL_NOT_RECEIVED
import com.android.keyguard.InternalFaceAuthReasons.FACE_LOCKOUT_RESET
import com.android.keyguard.InternalFaceAuthReasons.FINISHED_GOING_TO_SLEEP
import com.android.keyguard.InternalFaceAuthReasons.FP_AUTHENTICATED
import com.android.keyguard.InternalFaceAuthReasons.FP_LOCKED_OUT
import com.android.keyguard.InternalFaceAuthReasons.GOING_TO_SLEEP
import com.android.keyguard.InternalFaceAuthReasons.KEYGUARD_GOING_AWAY
import com.android.keyguard.InternalFaceAuthReasons.KEYGUARD_INIT
import com.android.keyguard.InternalFaceAuthReasons.KEYGUARD_OCCLUSION_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.KEYGUARD_RESET
import com.android.keyguard.InternalFaceAuthReasons.KEYGUARD_VISIBILITY_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.NON_STRONG_BIOMETRIC_ALLOWED_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.OCCLUDING_APP_REQUESTED
import com.android.keyguard.InternalFaceAuthReasons.PRIMARY_BOUNCER_SHOWN
import com.android.keyguard.InternalFaceAuthReasons.PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN
import com.android.keyguard.InternalFaceAuthReasons.RETRY_AFTER_HW_UNAVAILABLE
import com.android.keyguard.InternalFaceAuthReasons.STARTED_WAKING_UP
import com.android.keyguard.InternalFaceAuthReasons.STRONG_AUTH_ALLOWED_CHANGED
import com.android.keyguard.InternalFaceAuthReasons.TRUST_DISABLED
import com.android.keyguard.InternalFaceAuthReasons.TRUST_ENABLED
import com.android.keyguard.InternalFaceAuthReasons.USER_SWITCHING

/**
 * List of reasons why face auth is requested by clients through
 * [KeyguardUpdateMonitor.requestFaceAuth].
 */
@Retention(AnnotationRetention.SOURCE)
@StringDef(
    SWIPE_UP_ON_BOUNCER,
    UDFPS_POINTER_DOWN,
    NOTIFICATION_PANEL_CLICKED,
    QS_EXPANDED,
    PICK_UP_GESTURE_TRIGGERED,
)
annotation class FaceAuthApiRequestReason {
    companion object {
        const val SWIPE_UP_ON_BOUNCER = "Face auth due to swipe up on bouncer"
        const val UDFPS_POINTER_DOWN = "Face auth triggered due to finger down on UDFPS"
        const val NOTIFICATION_PANEL_CLICKED = "Face auth due to notification panel click."
        const val QS_EXPANDED = "Face auth due to QS expansion."
        const val PICK_UP_GESTURE_TRIGGERED =
            "Face auth due to pickup gesture triggered when the device is awake and not from AOD."
    }
}

/** List of events why face auth could be triggered by [KeyguardUpdateMonitor]. */
private object InternalFaceAuthReasons {
    const val OCCLUDING_APP_REQUESTED = "Face auth due to request from occluding app."
    const val RETRY_AFTER_HW_UNAVAILABLE = "Face auth due to retry after hardware unavailable."
    const val FACE_LOCKOUT_RESET = "Face auth due to face lockout reset."
    const val DEVICE_WOKEN_UP_ON_REACH_GESTURE =
        "Face auth requested when user reaches for the device on AoD."
    const val ALTERNATE_BIOMETRIC_BOUNCER_SHOWN = "Face auth due to alternate bouncer shown."
    const val PRIMARY_BOUNCER_SHOWN = "Face auth started/stopped due to primary bouncer shown."
    const val PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN =
        "Face auth started/stopped due to bouncer being shown or will be shown."
    const val TRUST_DISABLED = "Face auth started due to trust disabled."
    const val TRUST_ENABLED = "Face auth stopped due to trust enabled."
    const val KEYGUARD_OCCLUSION_CHANGED =
        "Face auth started/stopped due to keyguard occlusion change."
    const val ASSISTANT_VISIBILITY_CHANGED =
        "Face auth started/stopped due to assistant visibility change."
    const val STARTED_WAKING_UP = "Face auth started/stopped due to device starting to wake up."
    const val DREAM_STOPPED = "Face auth due to dream stopped."
    const val ALL_AUTHENTICATORS_REGISTERED = "Face auth due to all authenticators registered."
    const val ENROLLMENTS_CHANGED = "Face auth due to enrolments changed."
    const val KEYGUARD_VISIBILITY_CHANGED =
        "Face auth stopped or started due to keyguard visibility changed."
    const val FACE_CANCEL_NOT_RECEIVED = "Face auth stopped due to face cancel signal not received."
    const val AUTH_REQUEST_DURING_CANCELLATION =
        "Another request to start face auth received while cancelling face auth"
    const val DREAM_STARTED = "Face auth stopped because dreaming started"
    const val FP_LOCKED_OUT = "Face auth stopped because fp locked out"
    const val FACE_AUTH_STOPPED_ON_USER_INPUT =
        "Face auth stopped because user started typing password/pin"
    const val KEYGUARD_GOING_AWAY = "Face auth stopped because keyguard going away"
    const val CAMERA_LAUNCHED = "Face auth started/stopped because camera launched"
    const val FP_AUTHENTICATED = "Face auth started/stopped because fingerprint launched"
    const val GOING_TO_SLEEP = "Face auth started/stopped because going to sleep"
    const val FINISHED_GOING_TO_SLEEP = "Face auth stopped because finished going to sleep"
    const val KEYGUARD_INIT = "Face auth started/stopped because Keyguard is initialized"
    const val KEYGUARD_RESET = "Face auth started/stopped because Keyguard is reset"
    const val USER_SWITCHING = "Face auth started/stopped because user is switching"
    const val FACE_AUTHENTICATED = "Face auth started/stopped because face is authenticated"
    const val BIOMETRIC_ENABLED =
        "Face auth started/stopped because biometric is enabled on keyguard"
    const val STRONG_AUTH_ALLOWED_CHANGED = "Face auth stopped because strong auth allowed changed"
    const val NON_STRONG_BIOMETRIC_ALLOWED_CHANGED =
        "Face auth stopped because non strong biometric allowed changed"
}

/**
 * UiEvents that are logged to identify why face auth is being triggered.
 * @param extraInfo is logged as the position. See [UiEventLogger#logWithInstanceIdAndPosition]
 */
enum class FaceAuthUiEvent
constructor(private val id: Int, val reason: String, var extraInfo: Int = 0) :
    UiEventLogger.UiEventEnum {
    @UiEvent(doc = OCCLUDING_APP_REQUESTED)
    FACE_AUTH_TRIGGERED_OCCLUDING_APP_REQUESTED(1146, OCCLUDING_APP_REQUESTED),
    @UiEvent(doc = UDFPS_POINTER_DOWN)
    FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN(1147, UDFPS_POINTER_DOWN),
    @UiEvent(doc = SWIPE_UP_ON_BOUNCER)
    FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER(1148, SWIPE_UP_ON_BOUNCER),
    @UiEvent(doc = DEVICE_WOKEN_UP_ON_REACH_GESTURE)
    FACE_AUTH_TRIGGERED_ON_REACH_GESTURE_ON_AOD(1149, DEVICE_WOKEN_UP_ON_REACH_GESTURE),
    @UiEvent(doc = FACE_LOCKOUT_RESET)
    FACE_AUTH_TRIGGERED_FACE_LOCKOUT_RESET(1150, FACE_LOCKOUT_RESET),
    @UiEvent(doc = QS_EXPANDED) FACE_AUTH_TRIGGERED_QS_EXPANDED(1151, QS_EXPANDED),
    @UiEvent(doc = NOTIFICATION_PANEL_CLICKED)
    FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED(1152, NOTIFICATION_PANEL_CLICKED),
    @UiEvent(doc = PICK_UP_GESTURE_TRIGGERED)
    FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED(1153, PICK_UP_GESTURE_TRIGGERED),
    @UiEvent(doc = ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
    FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN(1154, ALTERNATE_BIOMETRIC_BOUNCER_SHOWN),
    @UiEvent(doc = PRIMARY_BOUNCER_SHOWN)
    FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN(1155, PRIMARY_BOUNCER_SHOWN),
    @UiEvent(doc = PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN)
    FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN(
        1197,
        PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN
    ),
    @UiEvent(doc = RETRY_AFTER_HW_UNAVAILABLE)
    FACE_AUTH_TRIGGERED_RETRY_AFTER_HW_UNAVAILABLE(1156, RETRY_AFTER_HW_UNAVAILABLE),
    @UiEvent(doc = TRUST_DISABLED) FACE_AUTH_TRIGGERED_TRUST_DISABLED(1158, TRUST_DISABLED),
    @UiEvent(doc = TRUST_ENABLED) FACE_AUTH_STOPPED_TRUST_ENABLED(1173, TRUST_ENABLED),
    @UiEvent(doc = KEYGUARD_OCCLUSION_CHANGED)
    FACE_AUTH_UPDATED_KEYGUARD_OCCLUSION_CHANGED(1159, KEYGUARD_OCCLUSION_CHANGED),
    @UiEvent(doc = ASSISTANT_VISIBILITY_CHANGED)
    FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED(1160, ASSISTANT_VISIBILITY_CHANGED),
    @UiEvent(doc = STARTED_WAKING_UP)
    FACE_AUTH_UPDATED_STARTED_WAKING_UP(1161, STARTED_WAKING_UP) {
        override fun extraInfoToString(): String {
            return PowerManager.wakeReasonToString(extraInfo)
        }
    },
    @Deprecated(
        "Not a face auth trigger.",
        ReplaceWith(
            "FACE_AUTH_UPDATED_STARTED_WAKING_UP, " +
                "extraInfo=PowerManager.WAKE_REASON_DREAM_FINISHED"
        )
    )
    @UiEvent(doc = DREAM_STOPPED)
    FACE_AUTH_TRIGGERED_DREAM_STOPPED(1162, DREAM_STOPPED),
    @UiEvent(doc = ALL_AUTHENTICATORS_REGISTERED)
    FACE_AUTH_TRIGGERED_ALL_AUTHENTICATORS_REGISTERED(1163, ALL_AUTHENTICATORS_REGISTERED),
    @UiEvent(doc = ENROLLMENTS_CHANGED)
    FACE_AUTH_TRIGGERED_ENROLLMENTS_CHANGED(1164, ENROLLMENTS_CHANGED),
    @UiEvent(doc = KEYGUARD_VISIBILITY_CHANGED)
    FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED(1165, KEYGUARD_VISIBILITY_CHANGED),
    @UiEvent(doc = FACE_CANCEL_NOT_RECEIVED)
    FACE_AUTH_STOPPED_FACE_CANCEL_NOT_RECEIVED(1174, FACE_CANCEL_NOT_RECEIVED),
    @UiEvent(doc = AUTH_REQUEST_DURING_CANCELLATION)
    FACE_AUTH_TRIGGERED_DURING_CANCELLATION(1175, AUTH_REQUEST_DURING_CANCELLATION),
    @UiEvent(doc = DREAM_STARTED) FACE_AUTH_STOPPED_DREAM_STARTED(1176, DREAM_STARTED),
    @UiEvent(doc = FP_LOCKED_OUT) FACE_AUTH_STOPPED_FP_LOCKED_OUT(1177, FP_LOCKED_OUT),
    @UiEvent(doc = FACE_AUTH_STOPPED_ON_USER_INPUT)
    FACE_AUTH_STOPPED_USER_INPUT_ON_BOUNCER(1178, FACE_AUTH_STOPPED_ON_USER_INPUT),
    @UiEvent(doc = KEYGUARD_GOING_AWAY)
    FACE_AUTH_STOPPED_KEYGUARD_GOING_AWAY(1179, KEYGUARD_GOING_AWAY),
    @UiEvent(doc = CAMERA_LAUNCHED) FACE_AUTH_UPDATED_CAMERA_LAUNCHED(1180, CAMERA_LAUNCHED),
    @UiEvent(doc = FP_AUTHENTICATED) FACE_AUTH_UPDATED_FP_AUTHENTICATED(1181, FP_AUTHENTICATED),
    @UiEvent(doc = GOING_TO_SLEEP) FACE_AUTH_UPDATED_GOING_TO_SLEEP(1182, GOING_TO_SLEEP),
    @UiEvent(doc = FINISHED_GOING_TO_SLEEP)
    FACE_AUTH_STOPPED_FINISHED_GOING_TO_SLEEP(1183, FINISHED_GOING_TO_SLEEP),
    @UiEvent(doc = KEYGUARD_INIT) FACE_AUTH_UPDATED_ON_KEYGUARD_INIT(1189, KEYGUARD_INIT),
    @UiEvent(doc = KEYGUARD_RESET) FACE_AUTH_UPDATED_KEYGUARD_RESET(1185, KEYGUARD_RESET),
    @UiEvent(doc = USER_SWITCHING) FACE_AUTH_UPDATED_USER_SWITCHING(1186, USER_SWITCHING),
    @UiEvent(doc = FACE_AUTHENTICATED)
    FACE_AUTH_UPDATED_ON_FACE_AUTHENTICATED(1187, FACE_AUTHENTICATED),
    @UiEvent(doc = BIOMETRIC_ENABLED)
    FACE_AUTH_UPDATED_BIOMETRIC_ENABLED_ON_KEYGUARD(1188, BIOMETRIC_ENABLED),
    @UiEvent(doc = STRONG_AUTH_ALLOWED_CHANGED)
    FACE_AUTH_UPDATED_STRONG_AUTH_CHANGED(1255, STRONG_AUTH_ALLOWED_CHANGED),
    @UiEvent(doc = NON_STRONG_BIOMETRIC_ALLOWED_CHANGED)
    FACE_AUTH_NON_STRONG_BIOMETRIC_ALLOWED_CHANGED(1256, NON_STRONG_BIOMETRIC_ALLOWED_CHANGED);

    override fun getId(): Int = this.id

    /** Convert [extraInfo] to a human-readable string. By default, this is empty. */
    open fun extraInfoToString(): String = ""
}

private val apiRequestReasonToUiEvent =
    mapOf(
        SWIPE_UP_ON_BOUNCER to FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
        UDFPS_POINTER_DOWN to FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN,
        NOTIFICATION_PANEL_CLICKED to
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED,
        QS_EXPANDED to FaceAuthUiEvent.FACE_AUTH_TRIGGERED_QS_EXPANDED,
        PICK_UP_GESTURE_TRIGGERED to FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED,
    )

/** Converts the [reason] to the corresponding [FaceAuthUiEvent]. */
fun apiRequestReasonToUiEvent(@FaceAuthApiRequestReason reason: String): FaceAuthUiEvent =
    apiRequestReasonToUiEvent[reason]!!
