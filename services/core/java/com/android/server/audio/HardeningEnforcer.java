/*
 * Copyright 2023 The Android Open Source Project
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
package com.android.server.audio;

import static android.media.audio.Flags.autoPublicVolumeApiHardening;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.modules.expresslog.Counter;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;

/**
 * Class to encapsulate all audio API hardening operations
 */
public class HardeningEnforcer {

    private static final String TAG = "AS.HardeningEnforcer";
    private static final boolean DEBUG = false;
    private static final int LOG_NB_EVENTS = 20;

    final Context mContext;
    final AppOpsManager mAppOps;
    final boolean mIsAutomotive;

    final ActivityManager mActivityManager;
    final PackageManager mPackageManager;

    final EventLogger mEventLogger = new EventLogger(LOG_NB_EVENTS,
            "Hardening enforcement");

    // capacity = 4 for each of the focus request types
    static final SparseArray<String> METRIC_COUNTERS_FOCUS_DENIAL = new SparseArray<>(4);
    static final SparseArray<String> METRIC_COUNTERS_FOCUS_GRANT = new SparseArray<>(4);

    static {
        METRIC_COUNTERS_FOCUS_GRANT.put(AudioManager.AUDIOFOCUS_GAIN,
                "media_audio.value_audio_focus_gain_granted");
        METRIC_COUNTERS_FOCUS_GRANT.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                "media_audio.value_audio_focus_gain_transient_granted");
        METRIC_COUNTERS_FOCUS_GRANT.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                "media_audio.value_audio_focus_gain_transient_duck_granted");
        METRIC_COUNTERS_FOCUS_GRANT.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                "media_audio.value_audio_focus_gain_transient_excl_granted");

        METRIC_COUNTERS_FOCUS_DENIAL.put(AudioManager.AUDIOFOCUS_GAIN,
                "media_audio.value_audio_focus_gain_appops_denial");
        METRIC_COUNTERS_FOCUS_DENIAL.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                "media_audio.value_audio_focus_gain_transient_appops_denial");
        METRIC_COUNTERS_FOCUS_DENIAL.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                "media_audio.value_audio_focus_gain_transient_duck_appops_denial");
        METRIC_COUNTERS_FOCUS_DENIAL.put(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                "media_audio.value_audio_focus_gain_transient_excl_appops_denial");
    }

    /**
     * Matches calls from {@link AudioManager#setStreamVolume(int, int, int)}
     */
    public static final int METHOD_AUDIO_MANAGER_SET_STREAM_VOLUME = 100;
    /**
     * Matches calls from {@link AudioManager#adjustVolume(int, int)}
     */
    public static final int METHOD_AUDIO_MANAGER_ADJUST_VOLUME = 101;
    /**
     * Matches calls from {@link AudioManager#adjustSuggestedStreamVolume(int, int, int)}
     */
    public static final int METHOD_AUDIO_MANAGER_ADJUST_SUGGESTED_STREAM_VOLUME = 102;
    /**
     * Matches calls from {@link AudioManager#adjustStreamVolume(int, int, int)}
     */
    public static final int METHOD_AUDIO_MANAGER_ADJUST_STREAM_VOLUME = 103;
    /**
     * Matches calls from {@link AudioManager#setRingerMode(int)}
     */
    public static final int METHOD_AUDIO_MANAGER_SET_RINGER_MODE = 200;
    /**
     * Matches calls from {@link AudioManager#requestAudioFocus(AudioFocusRequest)}
     * and legacy variants
     */
    public static final int METHOD_AUDIO_MANAGER_REQUEST_AUDIO_FOCUS = 300;

    public HardeningEnforcer(Context ctxt, boolean isAutomotive, AppOpsManager appOps,
            PackageManager pm) {
        mContext = ctxt;
        mIsAutomotive = isAutomotive;
        mAppOps = appOps;
        mActivityManager = ctxt.getSystemService(ActivityManager.class);
        mPackageManager = pm;
    }

    protected void dump(PrintWriter pw) {
        // log
        mEventLogger.dump(pw);
    }

    /**
     * Checks whether the call in the current thread should be allowed or blocked
     * @param volumeMethod name of the method to check, for logging purposes
     * @return false if the method call is allowed, true if it should be a no-op
     */
    protected boolean blockVolumeMethod(int volumeMethod) {
        // for Auto, volume methods require MODIFY_AUDIO_SETTINGS_PRIVILEGED
        if (mIsAutomotive) {
            if (!autoPublicVolumeApiHardening()) {
                // automotive hardening flag disabled, no blocking on auto
                return false;
            }
            if (mContext.checkCallingOrSelfPermission(
                    Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
                    == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (Binder.getCallingUid() < UserHandle.AID_APP_START) {
                return false;
            }
            // TODO metrics?
            // TODO log for audio dumpsys?
            Slog.e(TAG, "Preventing volume method " + volumeMethod + " for "
                    + getPackNameForUid(Binder.getCallingUid()));
            return true;
        }
        // not blocking
        return false;
    }

    /**
     * Checks whether the call in the current thread should be allowed or blocked
     * @param focusMethod name of the method to check, for logging purposes
     * @param clientId id of the requester
     * @param focusReqType focus type being requested
     * @param attributionTag attribution of the caller
     * @param targetSdk target SDK of the caller
     * @return false if the method call is allowed, true if it should be a no-op
     */
    @SuppressWarnings("AndroidFrameworkCompatChange")
    protected boolean blockFocusMethod(int callingUid, int focusMethod, @NonNull String clientId,
            int focusReqType, @NonNull String packageName, String attributionTag, int targetSdk) {
        if (packageName.isEmpty()) {
            packageName = getPackNameForUid(callingUid);
        }

        boolean blocked = true;
        // indicates the focus request was not blocked because of the SDK version
        boolean unblockedBySdk = false;
        if (noteOp(AppOpsManager.OP_TAKE_AUDIO_FOCUS, callingUid, packageName, attributionTag)) {
            if (DEBUG) {
                Slog.i(TAG, "blockFocusMethod pack:" + packageName + " NOT blocking");
            }
            blocked = false;
        } else if (targetSdk < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (DEBUG) {
                Slog.i(TAG, "blockFocusMethod pack:" + packageName + " NOT blocking due to sdk="
                        + targetSdk);
            }
            blocked = false;
            unblockedBySdk = true;
        }

        metricsLogFocusReq(blocked, focusReqType, callingUid, unblockedBySdk);

        if (!blocked) {
            return false;
        }

        String errorMssg = "Focus request DENIED for uid:" + callingUid
                + " clientId:" + clientId + " req:" + focusReqType
                + " procState:" + mActivityManager.getUidProcessState(callingUid);
        mEventLogger.enqueueAndSlog(errorMssg, EventLogger.Event.ALOGI, TAG);

        return true;
    }

    /**
     * Log metrics for the focus request
     * @param blocked true if the call blocked
     * @param focusReq the type of focus request
     * @param callingUid the UID of the caller
     * @param unblockedBySdk if blocked is false,
     *                       true indicates it was unblocked thanks to an older SDK
     */
    /*package*/ void metricsLogFocusReq(boolean blocked, int focusReq, int callingUid,
            boolean unblockedBySdk) {
        final String metricId = blocked ? METRIC_COUNTERS_FOCUS_DENIAL.get(focusReq)
                : METRIC_COUNTERS_FOCUS_GRANT.get(focusReq);
        if (TextUtils.isEmpty(metricId)) {
            Slog.e(TAG, "Bad string for focus metrics gain:" + focusReq + " blocked:" + blocked);
            return;
        }
        try {
            Counter.logIncrementWithUid(metricId, callingUid);
            if (!blocked && unblockedBySdk) {
                // additional metric to capture focus requests that are currently granted
                // because the app is on an older SDK, but would have been blocked otherwise
                Counter.logIncrementWithUid(
                        "media_audio.value_audio_focus_grant_hardening_waived_by_sdk", callingUid);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Counter error metricId:" + metricId + " for focus req:" + focusReq
                    + " from uid:" + callingUid, e);
        }
    }

    private String getPackNameForUid(int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            final String[] names = mPackageManager.getPackagesForUid(uid);
            if (names == null
                    || names.length == 0
                    || TextUtils.isEmpty(names[0])) {
                return "[" + uid + "]";
            }
            return names[0];
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Notes the given op without throwing
     * @param op the appOp code
     * @param uid the calling uid
     * @param packageName the package name of the caller
     * @param attributionTag attribution of the caller
     * @return return false if the operation is not allowed
     */
    private boolean noteOp(int op, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        if (mAppOps.noteOpNoThrow(op, uid, packageName, attributionTag, null)
                != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        return true;
    }
}
