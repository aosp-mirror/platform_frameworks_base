/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.ITimeDetectorListener;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ITimeDetectorService;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.NtpTrustedTime;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.location.gnss.TimeDetectorNetworkTimeHelper;
import com.android.server.timezonedetector.CallerIdentityInjector;
import com.android.server.timezonedetector.CurrentUserIdentityInjector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.time.DateTimeException;
import java.util.Objects;

/**
 * The implementation of ITimeDetectorService.aidl.
 *
 * <p>This service is implemented as a wrapper around {@link TimeDetectorStrategy}. It handles
 * interaction with Android framework classes, enforcing caller permissions, capturing user identity
 * and making calls async, leaving the (consequently more testable) {@link TimeDetectorStrategy}
 * implementation to deal with the logic around time detection.
 */
public final class TimeDetectorService extends ITimeDetectorService.Stub
        implements IBinder.DeathRecipient {
    static final String TAG = "time_detector";

    public static class Lifecycle extends SystemService {

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            Context context = getContext();
            Handler handler = FgThread.getHandler();

            ServiceConfigAccessor serviceConfigAccessor =
                    ServiceConfigAccessorImpl.getInstance(context);
            TimeDetectorStrategy timeDetectorStrategy =
                    TimeDetectorStrategyImpl.create(context, handler, serviceConfigAccessor);

            // Create and publish the local service for use by internal callers.
            CurrentUserIdentityInjector currentUserIdentityInjector =
                    CurrentUserIdentityInjector.REAL;
            TimeDetectorInternal internal = new TimeDetectorInternalImpl(
                    context, handler, currentUserIdentityInjector, serviceConfigAccessor,
                    timeDetectorStrategy);
            publishLocalService(TimeDetectorInternal.class, internal);

            CallerIdentityInjector callerIdentityInjector = CallerIdentityInjector.REAL;
            TimeDetectorService service = new TimeDetectorService(
                    context, handler, callerIdentityInjector, timeDetectorStrategy,
                    NtpTrustedTime.getInstance(context));

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            publishBinderService(Context.TIME_DETECTOR_SERVICE, service);
        }
    }

    @NonNull private final Handler mHandler;
    @NonNull private final Context mContext;
    @NonNull private final CallerIdentityInjector mCallerIdentityInjector;
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;
    @NonNull private final NtpTrustedTime mNtpTrustedTime;

    /**
     * Holds the listeners. The key is the {@link IBinder} associated with the listener, the value
     * is the listener itself.
     */
    @GuardedBy("mListeners")
    @NonNull
    private final ArrayMap<IBinder, ITimeDetectorListener> mListeners = new ArrayMap<>();

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull CallerIdentityInjector callerIdentityInjector,
            @NonNull TimeDetectorStrategy timeDetectorStrategy,
            @NonNull NtpTrustedTime ntpTrustedTime) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCallerIdentityInjector = Objects.requireNonNull(callerIdentityInjector);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
        mNtpTrustedTime = Objects.requireNonNull(ntpTrustedTime);

        // Wire up a change listener so that ITimeDetectorListeners can be notified when the
        // detector state changes for any reason.
        mTimeDetectorStrategy.addChangeListener(
                () -> mHandler.post(this::handleChangeOnHandlerThread));
    }

    @Override
    @NonNull
    public TimeCapabilitiesAndConfig getCapabilitiesAndConfig() {
        int userId = mCallerIdentityInjector.getCallingUserId();
        return getTimeCapabilitiesAndConfig(userId);
    }

    private TimeCapabilitiesAndConfig getTimeCapabilitiesAndConfig(@UserIdInt int userId) {
        enforceManageTimeDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeDetectorStrategy.getCapabilitiesAndConfig(userId, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean updateConfiguration(@NonNull TimeConfiguration configuration) {
        int callingUserId = mCallerIdentityInjector.getCallingUserId();
        return updateConfiguration(callingUserId, configuration);
    }

    /**
     * Updates the user's configuration. Exposed for use by {@link TimeDetectorShellCommand}.
     */
    boolean updateConfiguration(@UserIdInt int userId, @NonNull TimeConfiguration configuration) {
        // Resolve constants like USER_CURRENT to the true user ID as needed.
        int resolvedUserId = mCallerIdentityInjector.resolveUserId(userId, "updateConfiguration");

        enforceManageTimeDetectorPermission();

        Objects.requireNonNull(configuration);

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeDetectorStrategy.updateConfiguration(
                    resolvedUserId, configuration, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addListener(@NonNull ITimeDetectorListener listener) {
        enforceManageTimeDetectorPermission();
        Objects.requireNonNull(listener);

        synchronized (mListeners) {
            IBinder listenerBinder = listener.asBinder();
            if (mListeners.containsKey(listenerBinder)) {
                return;
            }
            try {
                // Ensure the reference to the listener will be removed if the client process dies.
                listenerBinder.linkToDeath(this, 0 /* flags */);

                // Only add the listener if we can linkToDeath().
                mListeners.put(listenerBinder, listener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to linkToDeath() for listener=" + listener, e);
            }
        }
    }

    @Override
    public void removeListener(@NonNull ITimeDetectorListener listener) {
        enforceManageTimeDetectorPermission();
        Objects.requireNonNull(listener);

        synchronized (mListeners) {
            IBinder listenerBinder = listener.asBinder();
            boolean removedListener = false;
            if (mListeners.remove(listenerBinder) != null) {
                // Stop listening for the client process to die.
                listenerBinder.unlinkToDeath(this, 0 /* flags */);
                removedListener = true;
            }
            if (!removedListener) {
                Slog.w(TAG, "Client asked to remove listener=" + listener
                        + ", but no listeners were removed."
                        + " mListeners=" + mListeners);
            }
        }
    }

    @Override
    public void binderDied() {
        // Should not be used as binderDied(IBinder who) is overridden.
        Slog.wtf(TAG, "binderDied() called unexpectedly.");
    }

    /**
     * Called when one of the ITimeDetectorListener processes dies before calling
     * {@link #removeListener(ITimeDetectorListener)}.
     */
    @Override
    public void binderDied(IBinder who) {
        synchronized (mListeners) {
            boolean removedListener = false;
            final int listenerCount = mListeners.size();
            for (int listenerIndex = listenerCount - 1; listenerIndex >= 0; listenerIndex--) {
                IBinder listenerBinder = mListeners.keyAt(listenerIndex);
                if (listenerBinder.equals(who)) {
                    mListeners.removeAt(listenerIndex);
                    removedListener = true;
                    break;
                }
            }
            if (!removedListener) {
                Slog.w(TAG, "Notified of binder death for who=" + who
                        + ", but did not remove any listeners."
                        + " mListeners=" + mListeners);
            }
        }
    }

    private void handleChangeOnHandlerThread() {
        // Configuration has changed, but each user may have a different view of the configuration.
        // It's possible that this will cause unnecessary notifications but that shouldn't be a
        // problem.
        synchronized (mListeners) {
            final int listenerCount = mListeners.size();
            for (int listenerIndex = 0; listenerIndex < listenerCount; listenerIndex++) {
                ITimeDetectorListener listener = mListeners.valueAt(listenerIndex);
                try {
                    // No need to surrender the mListeners lock while doing this:
                    // ITimeDetectorListener is declared "oneway".
                    listener.onChange();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to notify listener=" + listener, e);
                }
            }
        }
    }

    @Override
    public TimeState getTimeState() {
        enforceManageTimeDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeDetectorStrategy.getTimeState();
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    /**
     * Sets the system time state. See {@link TimeState} for details. For use by {@link
     * TimeDetectorShellCommand}.
     */
    void setTimeState(@NonNull TimeState timeState) {
        enforceManageTimeDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            mTimeDetectorStrategy.setTimeState(timeState);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean confirmTime(@NonNull UnixEpochTime time) {
        enforceManageTimeDetectorPermission();
        Objects.requireNonNull(time);

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeDetectorStrategy.confirmTime(time);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean setManualTime(@NonNull ManualTimeSuggestion suggestion) {
        enforceManageTimeDetectorPermission();
        Objects.requireNonNull(suggestion);

        // This calls suggestManualTime() as the logic is identical, it only differs in the
        // permission required, which is handled on the line above.
        int userId = mCallerIdentityInjector.getCallingUserId();
        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeDetectorStrategy.suggestManualTime(
                    userId, suggestion, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSignal) {
        enforceSuggestTelephonyTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestTelephonyTime(timeSignal));
    }

    @Override
    public boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSignal) {
        enforceSuggestManualTimePermission();
        Objects.requireNonNull(timeSignal);

        int userId = mCallerIdentityInjector.getCallingUserId();
        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeDetectorStrategy.suggestManualTime(
                    userId, timeSignal, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    /**
     * Suggests network time with permission checks. For use by {@link TimeDetectorShellCommand}.
     */
    void suggestNetworkTime(@NonNull NetworkTimeSuggestion suggestion) {
        enforceSuggestNetworkTimePermission();
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> mTimeDetectorStrategy.suggestNetworkTime(suggestion));
    }

    /**
     * Clears the cached network time information. For use during tests to simulate when no network
     * time has been made available. For use by {@link TimeDetectorShellCommand}.
     *
     * <p>This operation takes place in the calling thread.
     */
    void clearLatestNetworkTime() {
        enforceSuggestNetworkTimePermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            mTimeDetectorStrategy.clearLatestNetworkSuggestion();
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public UnixEpochTime latestNetworkTime() {
        NetworkTimeSuggestion latestNetworkTime;
        // TODO(b/222295093): Remove this condition once we can be sure that all uses of
        //  NtpTrustedTime result in a suggestion being made to the time detector.
        //  mNtpTrustedTime can be removed once this happens.
        if (TimeDetectorNetworkTimeHelper.isInUse()) {
            // The new implementation.
            latestNetworkTime = mTimeDetectorStrategy.getLatestNetworkSuggestion();
        } else {
            // The old implementation.
            NtpTrustedTime.TimeResult ntpResult = mNtpTrustedTime.getCachedTimeResult();
            if (ntpResult != null) {
                latestNetworkTime = new NetworkTimeSuggestion(
                        new UnixEpochTime(
                                ntpResult.getElapsedRealtimeMillis(), ntpResult.getTimeMillis()),
                        ntpResult.getUncertaintyMillis());
            } else {
                latestNetworkTime = null;
            }
        }
        if (latestNetworkTime == null) {
            throw new ParcelableException(new DateTimeException("Missing network time fix"));
        }
        return latestNetworkTime.getUnixEpochTime();
    }

    /**
     * Returns the latest network suggestion accepted. For use by {@link TimeDetectorShellCommand}.
     */
    @Nullable
    NetworkTimeSuggestion getLatestNetworkSuggestion() {
        return mTimeDetectorStrategy.getLatestNetworkSuggestion();
    }

    /**
     * Suggests GNSS time with permission checks. For use by {@link TimeDetectorShellCommand}.
     */
    void suggestGnssTime(@NonNull GnssTimeSuggestion timeSignal) {
        enforceSuggestGnssTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestGnssTime(timeSignal));
    }

    @Override
    public void suggestExternalTime(@NonNull ExternalTimeSuggestion timeSignal) {
        enforceSuggestExternalTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestExternalTime(timeSignal));
    }

    /**
     * Sets the network time for testing {@link SystemClock#currentNetworkTimeClock()}.
     *
     * <p>This operation takes place in the calling thread.
     */
    void setNetworkTimeForSystemClockForTests(
            @NonNull UnixEpochTime unixEpochTime, int uncertaintyMillis) {
        enforceSuggestNetworkTimePermission();

        // TODO(b/222295093): Remove this condition once we can be sure that all uses of
        //  NtpTrustedTime result in a suggestion being made to the time detector.
        //  mNtpTrustedTime can be removed once this happens.
        if (TimeDetectorNetworkTimeHelper.isInUse()) {
            NetworkTimeSuggestion suggestion =
                    new NetworkTimeSuggestion(unixEpochTime, uncertaintyMillis);
            suggestion.addDebugInfo("Injected for tests");
            mTimeDetectorStrategy.suggestNetworkTime(suggestion);
        } else {
            NtpTrustedTime.TimeResult timeResult = new NtpTrustedTime.TimeResult(
                    unixEpochTime.getUnixEpochTimeMillis(),
                    unixEpochTime.getElapsedRealtimeMillis(),
                    uncertaintyMillis,
                    InetSocketAddress.createUnresolved("time.set.for.tests", 123));
            mNtpTrustedTime.setCachedTimeResult(timeResult);
        }
    }

    /**
     * Clears the network time for testing {@link SystemClock#currentNetworkTimeClock()}.
     *
     * <p>This operation takes place in the calling thread.
     */
    void clearNetworkTimeForSystemClockForTests() {
        enforceSuggestNetworkTimePermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            // TODO(b/222295093): Remove this condition once we can be sure that all uses of
            //  NtpTrustedTime result in a suggestion being made to the time detector.
            //  mNtpTrustedTime can be removed once this happens.
            if (TimeDetectorNetworkTimeHelper.isInUse()) {
                // Clear the latest network suggestion. Done in all c
                mTimeDetectorStrategy.clearLatestNetworkSuggestion();
            } else {
                mNtpTrustedTime.clearCachedTimeResult();
            }
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        mTimeDetectorStrategy.dump(ipw, args);
        ipw.flush();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new TimeDetectorShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    private void enforceSuggestTelephonyTimePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE,
                "suggest telephony time and time zone");
    }

    private void enforceSuggestManualTimePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE,
                "suggest manual time and time zone");
    }

    private void enforceSuggestNetworkTimePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME,
                "suggest network time");
    }

    private void enforceSuggestGnssTimePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME,
                "suggest gnss time");
    }

    private void enforceSuggestExternalTimePermission() {
        // We don't expect a call from system server, so simply enforce calling permission.
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_EXTERNAL_TIME,
                "suggest time from external source");
    }

    private void enforceManageTimeDetectorPermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION,
                "manage time and time zone detection");
    }
}
