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

package com.android.server.media.projection;

import static android.Manifest.permission.RECORD_SENSITIVE_CONTENT;
import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.companion.AssociationRequest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;

import java.util.function.Consumer;

/**
 * Tracks events that should cause MediaProjection to stop
 */
public class MediaProjectionStopController {

    private static final String TAG = "MediaProjectionStopController";
    @VisibleForTesting
    static final int STOP_REASON_UNKNOWN = 0;
    @VisibleForTesting
    static final int STOP_REASON_KEYGUARD = 1;
    @VisibleForTesting
    static final int STOP_REASON_CALL_END = 2;

    private final TelephonyCallback mTelephonyCallback = new ProjectionTelephonyCallback();
    private final Consumer<Integer> mStopReasonConsumer;
    private final KeyguardManager mKeyguardManager;
    private final TelecomManager mTelecomManager;
    private final TelephonyManager mTelephonyManager;
    private final AppOpsManager mAppOpsManager;
    private final PackageManager mPackageManager;
    private final RoleManager mRoleManager;
    private final ContentResolver mContentResolver;

    private boolean mIsInCall;
    private long mLastCallStartTimeMillis;

    public MediaProjectionStopController(Context context, Consumer<Integer> stopReasonConsumer) {
        mStopReasonConsumer = stopReasonConsumer;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mTelecomManager = context.getSystemService(TelecomManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
        mRoleManager = context.getSystemService(RoleManager.class);
        mContentResolver = context.getContentResolver();
    }

    /**
     * Start tracking stop reasons that may interrupt a MediaProjection session.
     */
    public void startTrackingStopReasons(Context context) {
        final long token = Binder.clearCallingIdentity();
        try {
            mKeyguardManager.addKeyguardLockedStateListener(context.getMainExecutor(),
                    this::onKeyguardLockedStateChanged);
            if (com.android.media.projection.flags.Flags.stopMediaProjectionOnCallEnd()) {
                callStateChanged();
                mTelephonyManager.registerTelephonyCallback(context.getMainExecutor(),
                        mTelephonyCallback);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Checks whether the given projection grant is exempt from stopping restrictions.
     */
    public boolean isExemptFromStopping(
            MediaProjectionManagerService.MediaProjection projectionGrant, int stopReason) {
        return isExempt(projectionGrant, stopReason, false);
    }

    /**
     * Apps may disregard recording restrictions via MediaProjection for any stop reason if:
     * - the "Disable Screenshare protections" developer option is enabled
     * - the app is a holder of RECORD_SENSITIVE_CONTENT permission
     * - the app holds the OP_PROJECT_MEDIA AppOp
     * - the app holds the COMPANION_DEVICE_APP_STREAMING role
     * - the app is one of the bugreport allowlisted packages
     * - the current projection does not have an active VirtualDisplay associated with the
     * MediaProjection session
     */
    private boolean isExempt(
            MediaProjectionManagerService.MediaProjection projectionGrant, int stopReason,
            boolean forStart) {
        if (projectionGrant == null || projectionGrant.packageName == null) {
            return true;
        }
        boolean disableScreenShareProtections = Settings.Global.getInt(mContentResolver,
                DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 0) != 0;
        if (disableScreenShareProtections) {
            Slog.v(TAG, "Continuing MediaProjection as screenshare protections are disabled.");
            return true;
        }

        if (mPackageManager.checkPermission(RECORD_SENSITIVE_CONTENT, projectionGrant.packageName)
                == PackageManager.PERMISSION_GRANTED) {
            Slog.v(TAG,
                    "Continuing MediaProjection for package with RECORD_SENSITIVE_CONTENT "
                            + "permission");
            return true;
        }
        if (AppOpsManager.MODE_ALLOWED == mAppOpsManager.noteOpNoThrow(
                AppOpsManager.OP_PROJECT_MEDIA, projectionGrant.uid,
                projectionGrant.packageName, /* attributionTag= */ null, "recording lockscreen")) {
            // Some tools use media projection by granting the OP_PROJECT_MEDIA app
            // op via a shell command.
            Slog.v(TAG, "Continuing MediaProjection for package with OP_PROJECT_MEDIA AppOp ");
            return true;
        }
        if (mRoleManager.getRoleHoldersAsUser(AssociationRequest.DEVICE_PROFILE_APP_STREAMING,
                projectionGrant.userHandle).contains(projectionGrant.packageName)) {
            Slog.v(TAG, "Continuing MediaProjection for package holding app streaming role.");
            return true;
        }
        if (SystemConfig.getInstance().getBugreportWhitelistedPackages().contains(
                projectionGrant.packageName)) {
            Slog.v(TAG, "Continuing MediaProjection for package allowlisted for bugreporting.");
            return true;
        }
        if (!forStart && projectionGrant.getVirtualDisplayId() == Display.INVALID_DISPLAY) {
            Slog.v(TAG, "Continuing MediaProjection as current projection has no VirtualDisplay.");
            return true;
        }

        if (stopReason == STOP_REASON_CALL_END
                && projectionGrant.getCreateTimeMillis() < mLastCallStartTimeMillis) {
            Slog.v(TAG,
                    "Continuing MediaProjection as (phone) call started after MediaProjection was"
                            + " created.");
            return true;
        }

        return false;
    }

    /**
     * @return {@code true} if a MediaProjection session is currently in a restricted state.
     */
    public boolean isStartForbidden(
            MediaProjectionManagerService.MediaProjection projectionGrant) {
        if (!android.companion.virtualdevice.flags.Flags.mediaProjectionKeyguardRestrictions()) {
            return false;
        }

        if (!mKeyguardManager.isKeyguardLocked()) {
            return false;
        }

        if (isExempt(projectionGrant, STOP_REASON_UNKNOWN, true)) {
            return false;
        }
        return true;
    }

    @VisibleForTesting
    void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
        if (!isKeyguardLocked) return;
        if (!android.companion.virtualdevice.flags.Flags.mediaProjectionKeyguardRestrictions()) {
            return;
        }
        mStopReasonConsumer.accept(STOP_REASON_KEYGUARD);
    }

    @VisibleForTesting
    void callStateChanged() {
        if (!com.android.media.projection.flags.Flags.stopMediaProjectionOnCallEnd()) {
            return;
        }
        boolean isInCall = mTelecomManager.isInCall();
        if (isInCall) {
            mLastCallStartTimeMillis = SystemClock.uptimeMillis();
        }
        if (isInCall == mIsInCall) {
            return;
        }

        if (mIsInCall && !isInCall) {
            mStopReasonConsumer.accept(STOP_REASON_CALL_END);
        }
        mIsInCall = isInCall;
    }

    /**
     * @return a String representation of the stop reason interrupting MediaProjection.
     */
    public static String stopReasonToString(int stopReason) {
        switch (stopReason) {
            case STOP_REASON_KEYGUARD -> {
                return "STOP_REASON_KEYGUARD";
            }
            case STOP_REASON_CALL_END -> {
                return "STOP_REASON_CALL_END";
            }
        }
        return "";
    }

    private final class ProjectionTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            callStateChanged();
        }
    }
}
