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

package com.android.server.biometrics.log;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.AuthenticateReason;
import android.hardware.biometrics.common.DisplayState;
import android.hardware.biometrics.common.FoldState;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.biometrics.common.WakeReason;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.os.PowerManager;
import android.view.Surface;

/**
 * Wrapper around {@link OperationContext} to include properties that are not
 * shared with the HAL.
 *
 * When useful, these properties should move to the wrapped object for use by HAL in
 * future releases.
 */
public class OperationContextExt {

    @NonNull private final OperationContext mAidlContext;
    @Nullable private BiometricContextSessionInfo mSessionInfo;
    private boolean mIsDisplayOn = false;
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    @Surface.Rotation private int mOrientation = Surface.ROTATION_0;
    private int mFoldState = IBiometricContextListener.FoldState.UNKNOWN;
    private final boolean mIsBP;

    /** Create a context. */
    public OperationContextExt(boolean isBP) {
        this(new OperationContext(), isBP);
    }

    /** Create a wrapped context. */
    public OperationContextExt(@NonNull OperationContext context, boolean isBP) {
        mAidlContext = context;
        mIsBP = isBP;
    }

    /**
     * Gets the subset of the context that can be shared with the HAL.
     *
     * When starting a new operation use methods like to update & fetch the context:
     * <ul>
     *     <li>{@link #toAidlContext(FaceAuthenticateOptions)}
     *     <li>{@link #toAidlContext(FingerprintAuthenticateOptions)}
     * </ul>
     *
     * Use this method for any subsequent calls to the HAL or for operations that do
     * not accept any options.
     *
     * @return the underlying AIDL context
     */
    @NonNull
    public OperationContext toAidlContext() {
        return mAidlContext;
    }

    /**
     * Gets the subset of the context that can be shared with the HAL and updates
     * it with the given options.
     *
     * @param options authenticate options
     * @return the underlying AIDL context
     */
    @NonNull
    public OperationContext toAidlContext(@NonNull AuthenticateOptions options) {
        if (options instanceof FaceAuthenticateOptions) {
            return toAidlContext((FaceAuthenticateOptions) options);
        }
        if (options instanceof FingerprintAuthenticateOptions) {
            return toAidlContext((FingerprintAuthenticateOptions) options);
        }
        throw new IllegalStateException("Authenticate options are invalid.");
    }

    /**
     * Gets the subset of the context that can be shared with the HAL and updates
     * it with the given options.
     *
     * @param options authenticate options
     * @return the underlying AIDL context
     */
    @NonNull
    public OperationContext toAidlContext(@NonNull FaceAuthenticateOptions options) {
        mAidlContext.authenticateReason = AuthenticateReason
                .faceAuthenticateReason(getAuthReason(options));
        mAidlContext.wakeReason = getWakeReason(options);

        return mAidlContext;
    }

    /**
     * Gets the subset of the context that can be shared with the HAL and updates
     * it with the given options.
     *
     * @param options authenticate options
     * @return the underlying AIDL context
     */
    @NonNull
    public OperationContext toAidlContext(@NonNull FingerprintAuthenticateOptions options) {
        if (options.getVendorReason() != null) {
            mAidlContext.authenticateReason = AuthenticateReason
                    .vendorAuthenticateReason(options.getVendorReason());

        } else {
            mAidlContext.authenticateReason = AuthenticateReason
                    .fingerprintAuthenticateReason(getAuthReason(options));
        }
        mAidlContext.wakeReason = getWakeReason(options);

        return mAidlContext;
    }

    @AuthenticateReason.Face
    private int getAuthReason(@NonNull FaceAuthenticateOptions options) {
        switch (options.getAuthenticateReason()) {
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_STARTED_WAKING_UP:
                return AuthenticateReason.Face.STARTED_WAKING_UP;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_PRIMARY_BOUNCER_SHOWN:
                return AuthenticateReason.Face.PRIMARY_BOUNCER_SHOWN;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_ASSISTANT_VISIBLE:
                return AuthenticateReason.Face.ASSISTANT_VISIBLE;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN:
                return AuthenticateReason.Face.ALTERNATE_BIOMETRIC_BOUNCER_SHOWN;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_NOTIFICATION_PANEL_CLICKED:
                return AuthenticateReason.Face.NOTIFICATION_PANEL_CLICKED;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_OCCLUDING_APP_REQUESTED:
                return AuthenticateReason.Face.OCCLUDING_APP_REQUESTED;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_PICK_UP_GESTURE_TRIGGERED:
                return AuthenticateReason.Face.PICK_UP_GESTURE_TRIGGERED;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_QS_EXPANDED:
                return AuthenticateReason.Face.QS_EXPANDED;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_SWIPE_UP_ON_BOUNCER:
                return AuthenticateReason.Face.SWIPE_UP_ON_BOUNCER;
            case FaceAuthenticateOptions.AUTHENTICATE_REASON_UDFPS_POINTER_DOWN:
                return AuthenticateReason.Face.UDFPS_POINTER_DOWN;
            default:
                return AuthenticateReason.Face.UNKNOWN;
        }
    }

    @WakeReason
    private int getWakeReason(@NonNull FaceAuthenticateOptions options) {
        switch (options.getWakeReason()) {
            case PowerManager.WAKE_REASON_POWER_BUTTON:
                return WakeReason.POWER_BUTTON;
            case PowerManager.WAKE_REASON_GESTURE:
                return WakeReason.GESTURE;
            case PowerManager.WAKE_REASON_WAKE_KEY:
                return WakeReason.WAKE_KEY;
            case PowerManager.WAKE_REASON_WAKE_MOTION:
                return WakeReason.WAKE_MOTION;
            case PowerManager.WAKE_REASON_DISPLAY_GROUP_ADDED:
                return WakeReason.DISPLAY_GROUP_ADDED;
            case PowerManager.WAKE_REASON_TAP:
                return WakeReason.TAP;
            case PowerManager.WAKE_REASON_LIFT:
                return WakeReason.LIFT;
            case PowerManager.WAKE_REASON_BIOMETRIC:
                return WakeReason.BIOMETRIC;
            case PowerManager.WAKE_REASON_CAMERA_LAUNCH:
            case PowerManager.WAKE_REASON_HDMI:
            case PowerManager.WAKE_REASON_DISPLAY_GROUP_TURNED_ON:
            case PowerManager.WAKE_REASON_UNFOLD_DEVICE:
            case PowerManager.WAKE_REASON_DREAM_FINISHED:
            case PowerManager.WAKE_REASON_TILT:
            case PowerManager.WAKE_REASON_APPLICATION:
            case PowerManager.WAKE_REASON_PLUGGED_IN:
            default:
                return WakeReason.UNKNOWN;
        }
    }

    @AuthenticateReason.Fingerprint
    private int getAuthReason(@NonNull FingerprintAuthenticateOptions options) {
        return AuthenticateReason.Fingerprint.UNKNOWN;
    }

    @WakeReason
    private int getWakeReason(@NonNull FingerprintAuthenticateOptions options) {
        return WakeReason.UNKNOWN;
    }

    /** {@link OperationContext#id}. */
    public int getId() {
        return mAidlContext.id;
    }

    /** Gets the current order counter for the session and increment the counter. */
    public int getOrderAndIncrement() {
        final BiometricContextSessionInfo info = mSessionInfo;
        return info != null ? info.getOrderAndIncrement() : -1;
    }

    /** {@link OperationContext#reason}. */
    @OperationReason
    public byte getReason() {
        return mAidlContext.reason;
    }

    /** {@link OperationContext#wakeReason}. */
    @WakeReason
    public int getWakeReason() {
        return mAidlContext.wakeReason;
    }

    /** If the screen is currently on. */
    public boolean isDisplayOn() {
        return mIsDisplayOn;
    }

    /** @deprecated prefer {@link #getDisplayState()} to {@link OperationContext#isAod}. */
    public boolean isAod() {
        return mAidlContext.isAod;
    }

    /** {@link OperationContext#displayState}. */
    @DisplayState
    public int getDisplayState() {
        return mAidlContext.displayState;
    }

    /** {@link OperationContext#isCrypto}. */
    public boolean isCrypto() {
        return mAidlContext.isCrypto;
    }

    /** The dock state when this event occurred {@see Intent.EXTRA_DOCK_STATE_UNDOCKED}. */
    public int getDockState() {
        return mDockState;
    }

    /** The fold state of the device when this event occurred. */
    public int getFoldState() {
        return mFoldState;
    }

    /** The orientation of the device when this event occurred. */
    @Surface.Rotation
    public int getOrientation() {
        return mOrientation;
    }

    /** Update this object with the latest values from the given context. */
    OperationContextExt update(@NonNull BiometricContext biometricContext, boolean isCrypto) {
        mAidlContext.isAod = biometricContext.isAod();
        mAidlContext.displayState = toAidlDisplayState(biometricContext.getDisplayState());
        mAidlContext.foldState = toAidlFoldState(biometricContext.getFoldState());
        mAidlContext.isCrypto = isCrypto;
        setFirstSessionId(biometricContext);

        mIsDisplayOn = biometricContext.isDisplayOn();
        mDockState = biometricContext.getDockedState();
        mFoldState = biometricContext.getFoldState();
        mOrientation = biometricContext.getCurrentRotation();

        return this;
    }

    @DisplayState
    private static int toAidlDisplayState(@AuthenticateOptions.DisplayState int state) {
        switch (state) {
            case AuthenticateOptions.DISPLAY_STATE_AOD:
                return DisplayState.AOD;
            case AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN:
                return DisplayState.LOCKSCREEN;
            case AuthenticateOptions.DISPLAY_STATE_NO_UI:
                return DisplayState.NO_UI;
            case AuthenticateOptions.DISPLAY_STATE_SCREENSAVER:
                return DisplayState.SCREENSAVER;
        }
        return DisplayState.UNKNOWN;
    }

    @FoldState
    private static int toAidlFoldState(@IBiometricContextListener.FoldState int state) {
        switch (state) {
            case IBiometricContextListener.FoldState.FULLY_CLOSED:
                return FoldState.FULLY_CLOSED;
            case IBiometricContextListener.FoldState.FULLY_OPENED:
                return FoldState.FULLY_OPENED;
            case IBiometricContextListener.FoldState.HALF_OPENED:
                return FoldState.HALF_OPENED;
        }
        return FoldState.UNKNOWN;
    }

    private void setFirstSessionId(@NonNull BiometricContext biometricContext) {
        if (mIsBP) {
            mSessionInfo = biometricContext.getBiometricPromptSessionInfo();
            if (mSessionInfo != null) {
                mAidlContext.id = mSessionInfo.getId();
                mAidlContext.reason = OperationReason.BIOMETRIC_PROMPT;
                return;
            }
        } else {
            mSessionInfo = biometricContext.getKeyguardEntrySessionInfo();
            if (mSessionInfo != null) {
                mAidlContext.id = mSessionInfo.getId();
                mAidlContext.reason = OperationReason.KEYGUARD;
                return;
            }
        }

        // no session
        mAidlContext.id = 0;
        mAidlContext.reason = OperationReason.UNKNOWN;
    }
}
