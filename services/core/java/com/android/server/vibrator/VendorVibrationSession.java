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

package com.android.server.vibrator;

import static com.android.server.vibrator.VibrationSession.DebugInfo.formatTime;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.os.CancellationSignal;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.vibrator.IVibrationSession;
import android.os.vibrator.IVibrationSessionCallback;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * A vibration session started by a vendor request that can trigger {@link CombinedVibration}.
 */
final class VendorVibrationSession extends IVibrationSession.Stub
        implements VibrationSession, CancellationSignal.OnCancelListener, IBinder.DeathRecipient {
    private static final String TAG = "VendorVibrationSession";

    /** Calls into VibratorManager functionality needed for playing an {@link ExternalVibration}. */
    interface VibratorManagerHooks {

        /** Tells the manager to end the vibration session. */
        void endSession(long sessionId, boolean shouldAbort);

        /**
         * Tells the manager that the vibration session is finished and the vibrators can now be
         * used for another vibration.
         */
        void onSessionReleased(long sessionId);

        /** Request the manager to trigger a vibration within this session. */
        void vibrate(long sessionId, CallerInfo callerInfo, CombinedVibration vibration);
    }

    private final Object mLock = new Object();
    private final long mSessionId = VibrationSession.nextSessionId();
    private final ICancellationSignal mCancellationSignal = CancellationSignal.createTransport();
    private final int[] mVibratorIds;
    private final long mCreateUptime;
    private final long mCreateTime; // for debugging
    private final IVibrationSessionCallback mCallback;
    private final CallerInfo mCallerInfo;
    private final VibratorManagerHooks mManagerHooks;
    private final DeviceAdapter mDeviceAdapter;
    private final Handler mHandler;
    private final List<DebugInfo> mVibrations = new ArrayList<>();

    @GuardedBy("mLock")
    private Status mStatus = Status.RUNNING;
    @GuardedBy("mLock")
    private Status mEndStatusRequest;
    @GuardedBy("mLock")
    private boolean mEndedByVendor;
    @GuardedBy("mLock")
    private long mStartTime; // for debugging
    @GuardedBy("mLock")
    private long mEndUptime;
    @GuardedBy("mLock")
    private long mEndTime; // for debugging
    @GuardedBy("mLock")
    private VibrationStepConductor mConductor;

    VendorVibrationSession(@NonNull CallerInfo callerInfo, @NonNull Handler handler,
            @NonNull VibratorManagerHooks managerHooks, @NonNull DeviceAdapter deviceAdapter,
            @NonNull IVibrationSessionCallback callback) {
        mCreateUptime = SystemClock.uptimeMillis();
        mCreateTime = System.currentTimeMillis();
        mVibratorIds = deviceAdapter.getAvailableVibratorIds();
        mHandler = handler;
        mCallback = callback;
        mCallerInfo = callerInfo;
        mManagerHooks = managerHooks;
        mDeviceAdapter = deviceAdapter;
        CancellationSignal.fromTransport(mCancellationSignal).setOnCancelListener(this);
    }

    @Override
    public void vibrate(CombinedVibration vibration, String reason) {
        CallerInfo vibrationCallerInfo = new CallerInfo(mCallerInfo.attrs, mCallerInfo.uid,
                mCallerInfo.deviceId, mCallerInfo.opPkg, reason);
        mManagerHooks.vibrate(mSessionId, vibrationCallerInfo, vibration);
    }

    @Override
    public void finishSession() {
        Slog.d(TAG, "Session finish requested, ending vibration session...");
        // Do not abort session in HAL, wait for ongoing vibration requests to complete.
        // This might take a while to end the session, but it can be aborted by cancelSession.
        requestEndSession(Status.FINISHED, /* shouldAbort= */ false, /* isVendorRequest= */ true);
    }

    @Override
    public void cancelSession() {
        Slog.d(TAG, "Session cancel requested, aborting vibration session...");
        // Always abort session in HAL while cancelling it.
        // This might be triggered after finishSession was already called.
        requestEndSession(Status.CANCELLED_BY_USER, /* shouldAbort= */ true,
                /* isVendorRequest= */ true);
    }

    @Override
    public long getSessionId() {
        return mSessionId;
    }

    @Override
    public long getCreateUptimeMillis() {
        return mCreateUptime;
    }

    @Override
    public boolean isRepeating() {
        return false;
    }

    @Override
    public CallerInfo getCallerInfo() {
        return mCallerInfo;
    }

    @Override
    public IBinder getCallerToken() {
        return mCallback.asBinder();
    }

    @Override
    public DebugInfo getDebugInfo() {
        synchronized (mLock) {
            return new DebugInfoImpl(mStatus, mCallerInfo, mCreateUptime, mCreateTime, mStartTime,
                    mEndUptime, mEndTime, mEndedByVendor, mVibrations);
        }
    }

    @Override
    public boolean wasEndRequested() {
        synchronized (mLock) {
            return mEndStatusRequest != null;
        }
    }

    @Override
    public void onCancel() {
        Slog.d(TAG, "Cancellation signal received, cancelling vibration session...");
        requestEndSession(Status.CANCELLED_BY_USER, /* shouldAbort= */ true,
                /* isVendorRequest= */ true);
    }

    @Override
    public void binderDied() {
        Slog.d(TAG, "Binder died, cancelling vibration session...");
        requestEndSession(Status.CANCELLED_BINDER_DIED, /* shouldAbort= */ true,
                /* isVendorRequest= */ false);
    }

    @Override
    public boolean linkToDeath() {
        try {
            mCallback.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking session to token death", e);
            return false;
        }
        return true;
    }

    @Override
    public void unlinkToDeath() {
        try {
            mCallback.asBinder().unlinkToDeath(this, 0);
        } catch (NoSuchElementException e) {
            Slog.wtf(TAG, "Failed to unlink session to token death", e);
        }
    }

    @Override
    public void requestEnd(@NonNull Status status, @Nullable CallerInfo endedBy,
            boolean immediate) {
        // All requests to end a session should abort it to stop ongoing vibrations, even if
        // immediate flag is false. Only the #finishSession API will not abort and wait for
        // session vibrations to complete, which might take a long time.
        requestEndSession(status, /* shouldAbort= */ true, /* isVendorRequest= */ false);
    }

    @Override
    public void notifyVibratorCallback(int vibratorId, long vibrationId) {
        // Ignore it, the session vibration playback doesn't depend on HAL timings
    }

    @Override
    public void notifySyncedVibratorsCallback(long vibrationId) {
        // Ignore it, the session vibration playback doesn't depend on HAL timings
    }

    @Override
    public void notifySessionCallback() {
        synchronized (mLock) {
            Slog.d(TAG, "Session callback received, ending vibration session...");
            // If end was not requested then the HAL has cancelled the session.
            maybeSetEndRequestLocked(Status.CANCELLED_BY_UNKNOWN_REASON,
                    /* isVendorRequest= */ false);
            maybeSetStatusToRequestedLocked();
            clearVibrationConductor();
            mHandler.post(() -> mManagerHooks.onSessionReleased(mSessionId));
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "createTime: " + formatTime(mCreateTime, /*includeDate=*/ true)
                    + ", startTime: " + (mStartTime == 0 ? null : formatTime(mStartTime,
                    /* includeDate= */ true))
                    + ", endTime: " + (mEndTime == 0 ? null : formatTime(mEndTime,
                    /* includeDate= */ true))
                    + ", status: " + mStatus.name().toLowerCase(Locale.ROOT)
                    + ", callerInfo: " + mCallerInfo
                    + ", vibratorIds: " + Arrays.toString(mVibratorIds)
                    + ", vibrations: " + mVibrations;
        }
    }

    public Status getStatus() {
        synchronized (mLock) {
            return mStatus;
        }
    }

    public boolean isStarted() {
        synchronized (mLock) {
            return mStartTime > 0;
        }
    }

    public boolean isEnded() {
        synchronized (mLock) {
            return mStatus != Status.RUNNING;
        }
    }

    public int[] getVibratorIds() {
        return mVibratorIds;
    }

    @VisibleForTesting
    public List<DebugInfo> getVibrations() {
        synchronized (mLock) {
            return new ArrayList<>(mVibrations);
        }
    }

    public ICancellationSignal getCancellationSignal() {
        return mCancellationSignal;
    }

    public void notifyStart() {
        boolean isAlreadyEnded = false;
        synchronized (mLock) {
            if (isEnded()) {
                // Session already ended, skip start callbacks.
                isAlreadyEnded = true;
            } else {
                mStartTime = System.currentTimeMillis();
                // Run client callback in separate thread.
                mHandler.post(() -> {
                    try {
                        mCallback.onStarted(this);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error notifying vendor session started", e);
                    }
                });
            }
        }
        if (isAlreadyEnded) {
            // Session already ended, make sure we end it in the HAL.
            mHandler.post(() -> mManagerHooks.endSession(mSessionId, /* shouldAbort= */ true));
        }
    }

    public void notifyVibrationAttempt(DebugInfo vibrationDebugInfo) {
        mVibrations.add(vibrationDebugInfo);
    }

    @Nullable
    public VibrationStepConductor clearVibrationConductor() {
        synchronized (mLock) {
            VibrationStepConductor conductor = mConductor;
            if (conductor != null) {
                mVibrations.add(conductor.getVibration().getDebugInfo());
            }
            mConductor = null;
            return conductor;
        }
    }

    public DeviceAdapter getDeviceAdapter() {
        return mDeviceAdapter;
    }

    public boolean maybeSetVibrationConductor(VibrationStepConductor conductor) {
        synchronized (mLock) {
            if (mConductor != null) {
                Slog.d(TAG, "Vibration session still dispatching previous vibration,"
                        + " new vibration ignored");
                return false;
            }
            mConductor = conductor;
            return true;
        }
    }

    private void requestEndSession(Status status, boolean shouldAbort, boolean isVendorRequest) {
        boolean shouldTriggerSessionHook = false;
        synchronized (mLock) {
            maybeSetEndRequestLocked(status, isVendorRequest);
            if (isStarted()) {
                // Always trigger session hook after it has started, in case new request aborts an
                // already finishing session. Wait for HAL callback before actually ending here.
                shouldTriggerSessionHook = true;
            } else {
                // Session did not start in the HAL, end it right away.
                maybeSetStatusToRequestedLocked();
            }
        }
        if (shouldTriggerSessionHook) {
            mHandler.post(() ->  mManagerHooks.endSession(mSessionId, shouldAbort));
        }
    }

    @GuardedBy("mLock")
    private void maybeSetEndRequestLocked(Status status, boolean isVendorRequest) {
        if (mEndStatusRequest != null) {
            // End already requested, keep first requested status and time.
            return;
        }
        mEndStatusRequest = status;
        mEndedByVendor = isVendorRequest;
        mEndTime = System.currentTimeMillis();
        mEndUptime = SystemClock.uptimeMillis();
        if (mConductor != null) {
            // Vibration is being dispatched when session end was requested, cancel it.
            mConductor.notifyCancelled(new Vibration.EndInfo(status),
                    /* immediate= */ status != Status.FINISHED);
        }
        if (isStarted()) {
            // Only trigger "finishing" callback if session started.
            // Run client callback in separate thread.
            mHandler.post(() -> {
                try {
                    mCallback.onFinishing();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error notifying vendor session is finishing", e);
                }
            });
        }
    }

    @GuardedBy("mLock")
    private void maybeSetStatusToRequestedLocked() {
        if (isEnded()) {
            // End already set, keep first requested status and time.
            return;
        }
        if (mEndStatusRequest == null) {
            // No end status was requested, nothing to set.
            return;
        }
        mStatus = mEndStatusRequest;
        // Run client callback in separate thread.
        final Status endStatus = mStatus;
        mHandler.post(() -> {
            try {
                mCallback.onFinished(toSessionStatus(endStatus));
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying vendor session is finishing", e);
            }
        });
    }

    @android.os.vibrator.VendorVibrationSession.Status
    private static int toSessionStatus(Status status) {
        // Exhaustive switch to cover all possible internal status.
        return switch (status) {
            case FINISHED
                    -> android.os.vibrator.VendorVibrationSession.STATUS_SUCCESS;
            case IGNORED_UNSUPPORTED
                    -> STATUS_UNSUPPORTED;
            case CANCELLED_BINDER_DIED, CANCELLED_BY_APP_OPS, CANCELLED_BY_USER,
                 CANCELLED_SUPERSEDED, CANCELLED_BY_FOREGROUND_USER, CANCELLED_BY_SCREEN_OFF,
                 CANCELLED_BY_SETTINGS_UPDATE, CANCELLED_BY_UNKNOWN_REASON
                    -> android.os.vibrator.VendorVibrationSession.STATUS_CANCELED;
            case IGNORED_APP_OPS, IGNORED_BACKGROUND, IGNORED_FOR_EXTERNAL, IGNORED_FOR_ONGOING,
                 IGNORED_FOR_POWER, IGNORED_FOR_SETTINGS, IGNORED_FOR_HIGHER_IMPORTANCE,
                 IGNORED_FOR_RINGER_MODE, IGNORED_FROM_VIRTUAL_DEVICE, IGNORED_SUPERSEDED,
                 IGNORED_MISSING_PERMISSION, IGNORED_ON_WIRELESS_CHARGER
                    -> android.os.vibrator.VendorVibrationSession.STATUS_IGNORED;
            case UNKNOWN, IGNORED_ERROR_APP_OPS, IGNORED_ERROR_CANCELLING, IGNORED_ERROR_SCHEDULING,
                 IGNORED_ERROR_TOKEN, FORWARDED_TO_INPUT_DEVICES, FINISHED_UNEXPECTED, RUNNING
                    -> android.os.vibrator.VendorVibrationSession.STATUS_UNKNOWN_ERROR;
        };
    }

    /**
     * Holds lightweight debug information about the session that could potentially be kept in
     * memory for a long time for bugreport dumpsys operations.
     *
     * Since DebugInfo can be kept in memory for a long time, it shouldn't hold any references to
     * potentially expensive or resource-linked objects, such as {@link IBinder}.
     */
    static final class DebugInfoImpl implements VibrationSession.DebugInfo {
        private final Status mStatus;
        private final CallerInfo mCallerInfo;
        private final List<DebugInfo> mVibrations;

        private final long mCreateUptime;
        private final long mCreateTime;
        private final long mStartTime;
        private final long mEndTime;
        private final long mDurationMs;
        private final boolean mEndedByVendor;

        DebugInfoImpl(Status status, CallerInfo callerInfo, long createUptime, long createTime,
                long startTime, long endUptime, long endTime, boolean endedByVendor,
                List<DebugInfo> vibrations) {
            mStatus = status;
            mCallerInfo = callerInfo;
            mCreateUptime = createUptime;
            mCreateTime = createTime;
            mStartTime = startTime;
            mEndTime = endTime;
            mEndedByVendor = endedByVendor;
            mDurationMs = endUptime > 0 ? endUptime - createUptime : -1;
            mVibrations = vibrations == null ? new ArrayList<>() : new ArrayList<>(vibrations);
        }

        @Override
        public Status getStatus() {
            return mStatus;
        }

        @Override
        public long getCreateUptimeMillis() {
            return mCreateUptime;
        }

        @Override
        public CallerInfo getCallerInfo() {
            return mCallerInfo;
        }

        @Nullable
        @Override
        public Object getDumpAggregationKey() {
            return null; // No aggregation.
        }

        @Override
        public void logMetrics(VibratorFrameworkStatsLogger statsLogger) {
            if (mStartTime > 0) {
                // Only log sessions that have started.
                statsLogger.logVibrationVendorSessionStarted(mCallerInfo.uid);
                statsLogger.logVibrationVendorSessionVibrations(mCallerInfo.uid,
                        mVibrations.size());
                if (!mEndedByVendor) {
                    statsLogger.logVibrationVendorSessionInterrupted(mCallerInfo.uid);
                }
            }
            for (DebugInfo vibration : mVibrations) {
                vibration.logMetrics(statsLogger);
            }
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.END_TIME, mEndTime);
            proto.write(VibrationProto.DURATION_MS, mDurationMs);
            proto.write(VibrationProto.STATUS, mStatus.ordinal());

            final long attrsToken = proto.start(VibrationProto.ATTRIBUTES);
            final VibrationAttributes attrs = mCallerInfo.attrs;
            proto.write(VibrationAttributesProto.USAGE, attrs.getUsage());
            proto.write(VibrationAttributesProto.AUDIO_USAGE, attrs.getAudioUsage());
            proto.write(VibrationAttributesProto.FLAGS, attrs.getFlags());
            proto.end(attrsToken);

            proto.end(token);
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.println("VibrationSession:");
            pw.increaseIndent();
            pw.println("status = " + mStatus.name().toLowerCase(Locale.ROOT));
            pw.println("durationMs = " + mDurationMs);
            pw.println("createTime = " + formatTime(mCreateTime, /*includeDate=*/ true));
            pw.println("startTime = " + formatTime(mStartTime, /*includeDate=*/ true));
            pw.println("endTime = " + (mEndTime == 0 ? null
                    : formatTime(mEndTime, /*includeDate=*/ true)));
            pw.println("callerInfo = " + mCallerInfo);

            pw.println("vibrations:");
            pw.increaseIndent();
            for (DebugInfo vibration : mVibrations) {
                vibration.dump(pw);
            }
            pw.decreaseIndent();

            pw.decreaseIndent();
        }

        @Override
        public void dumpCompact(IndentingPrintWriter pw) {
            // Follow pattern from Vibration.DebugInfoImpl for better debugging from dumpsys.
            String timingsStr = String.format(Locale.ROOT,
                    "%s | %8s | %20s | duration: %5dms | start: %12s | end: %12s",
                    formatTime(mCreateTime, /*includeDate=*/ true),
                    "session",
                    mStatus.name().toLowerCase(Locale.ROOT),
                    mDurationMs,
                    mStartTime == 0 ? "" : formatTime(mStartTime, /*includeDate=*/ false),
                    mEndTime == 0 ? "" : formatTime(mEndTime, /*includeDate=*/ false));
            String paramStr = String.format(Locale.ROOT,
                    " | flags: %4s | usage: %s",
                    Long.toBinaryString(mCallerInfo.attrs.getFlags()),
                    mCallerInfo.attrs.usageToString());
            // Optional, most vibrations should not be defined via AudioAttributes
            // so skip them to simplify the logs
            String audioUsageStr =
                    mCallerInfo.attrs.getOriginalAudioUsage() != AudioAttributes.USAGE_UNKNOWN
                            ? " | audioUsage=" + AudioAttributes.usageToString(
                            mCallerInfo.attrs.getOriginalAudioUsage())
                            : "";
            String callerStr = String.format(Locale.ROOT,
                    " | %s (uid=%d, deviceId=%d) | reason: %s",
                    mCallerInfo.opPkg, mCallerInfo.uid, mCallerInfo.deviceId, mCallerInfo.reason);
            pw.println(timingsStr + paramStr + audioUsageStr + callerStr);

            pw.increaseIndent();
            for (DebugInfo vibration : mVibrations) {
                vibration.dumpCompact(pw);
            }
            pw.decreaseIndent();
        }

        @Override
        public String toString() {
            return "createTime: " + formatTime(mCreateTime, /* includeDate= */ true)
                    + ", startTime: " + formatTime(mStartTime, /* includeDate= */ true)
                    + ", endTime: " + (mEndTime == 0 ? null : formatTime(mEndTime,
                    /* includeDate= */ true))
                    + ", durationMs: " + mDurationMs
                    + ", status: " + mStatus.name().toLowerCase(Locale.ROOT)
                    + ", callerInfo: " + mCallerInfo
                    + ", vibrations: " + mVibrations;
        }
    }
}
