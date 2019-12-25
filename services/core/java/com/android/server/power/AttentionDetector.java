/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.power;

import static android.provider.Settings.System.ADAPTIVE_SLEEP;

import android.Manifest;
import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.attention.AttentionManagerInternal;
import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.attention.AttentionService;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class responsible for checking if the user is currently paying attention to the phone and
 * notifying {@link PowerManagerService} that user activity should be renewed.
 *
 * This class also implements a limit of how long the extension should be, to avoid security
 * issues where the device would never be locked.
 */
public class AttentionDetector {

    private static final String TAG = "AttentionDetector";
    private static final boolean DEBUG = false;

    private Context mContext;

    private boolean mIsSettingEnabled;

    /**
     * Invoked whenever user attention is detected.
     */
    private final Runnable mOnUserAttention;

    /**
     * The maximum time, in millis, that the phone can stay unlocked because of attention events,
     * triggered by any user.
     */
    @VisibleForTesting
    protected long mMaximumExtensionMillis;

    private final Object mLock;

    /**
     * If we're currently waiting for an attention callback
     */
    private final AtomicBoolean mRequested;

    private long mLastActedOnNextScreenDimming;

    /**
     * Monotonously increasing ID for the requests sent.
     */
    @VisibleForTesting
    protected int mRequestId;

    /**
     * {@link android.service.attention.AttentionService} API timeout.
     */
    private long mMaxAttentionApiTimeoutMillis;

    /**
     * Last known user activity.
     */
    private long mLastUserActivityTime;

    @VisibleForTesting
    protected AttentionManagerInternal mAttentionManager;

    @VisibleForTesting
    protected WindowManagerInternal mWindowManager;

    @VisibleForTesting
    protected PackageManager mPackageManager;

    @VisibleForTesting
    protected ContentResolver mContentResolver;

    /**
     * Current wakefulness of the device. {@see PowerManagerInternal}
     */
    private int mWakefulness;

    /**
     * Describes how many times in a row was the timeout extended.
     */
    private AtomicLong mConsecutiveTimeoutExtendedCount = new AtomicLong(0);

    @VisibleForTesting
    AttentionCallbackInternalImpl mCallback;

    public AttentionDetector(Runnable onUserAttention, Object lock) {
        mOnUserAttention = onUserAttention;
        mLock = lock;
        mRequested = new AtomicBoolean(false);
        mRequestId = 0;

        // Device starts with an awake state upon boot.
        mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
    }

    @VisibleForTesting
    void updateEnabledFromSettings(Context context) {
        mIsSettingEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT) == 1;
    }

    public void systemReady(Context context) {
        mContext = context;
        updateEnabledFromSettings(context);
        mPackageManager = context.getPackageManager();
        mContentResolver = context.getContentResolver();
        mAttentionManager = LocalServices.getService(AttentionManagerInternal.class);
        mWindowManager = LocalServices.getService(WindowManagerInternal.class);
        mMaximumExtensionMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_attentionMaximumExtension);
        mMaxAttentionApiTimeoutMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_attentionApiTimeout);

        try {
            final UserSwitchObserver observer = new UserSwitchObserver();
            ActivityManager.getService().registerUserSwitchObserver(observer, TAG);
        } catch (RemoteException e) {
             // Shouldn't happen since in-process.
        }

        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.ADAPTIVE_SLEEP),
                false, new ContentObserver(new Handler(context.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateEnabledFromSettings(context);
                    }
                }, UserHandle.USER_ALL);
    }

    public long updateUserActivity(long nextScreenDimming) {
        if (nextScreenDimming == mLastActedOnNextScreenDimming
                || !mIsSettingEnabled
                || !isAttentionServiceSupported()
                || mWindowManager.isKeyguardShowingAndNotOccluded()) {
            return nextScreenDimming;
        }

        if (!serviceHasSufficientPermissions()) {
            Settings.System.putInt(mContentResolver, ADAPTIVE_SLEEP, 0);
            return nextScreenDimming;
        }

        final long now = SystemClock.uptimeMillis();
        final long whenToCheck = nextScreenDimming - getAttentionTimeout();
        final long whenToStopExtending = mLastUserActivityTime + mMaximumExtensionMillis;
        if (now < whenToCheck) {
            if (DEBUG) {
                Slog.d(TAG, "Do not check for attention yet, wait " + (whenToCheck - now));
            }
            return whenToCheck;
        } else if (whenToStopExtending < whenToCheck) {
            if (DEBUG) {
                Slog.d(TAG, "Let device sleep to avoid false results and improve security "
                        + (whenToCheck - whenToStopExtending));
            }
            return nextScreenDimming;
        } else if (mRequested.get()) {
            if (DEBUG) {
                Slog.d(TAG, "Pending attention callback with ID=" + mCallback.mId + ", wait.");
            }
            return whenToCheck;
        }

        // Ideally we should attribute mRequested to the result of #checkAttention, but the
        // callback might arrive before #checkAttention returns (if there are cached results.)
        // This means that we must assume that the request was successful, and then cancel it
        // afterwards if AttentionManager couldn't deliver it.
        mRequested.set(true);
        mRequestId++;
        mLastActedOnNextScreenDimming = nextScreenDimming;
        mCallback = new AttentionCallbackInternalImpl(mRequestId);
        Slog.v(TAG, "Checking user attention, ID: " + mRequestId);
        final boolean sent = mAttentionManager.checkAttention(getAttentionTimeout(), mCallback);
        if (!sent) {
            mRequested.set(false);
        }

        return whenToCheck;
    }

    /**
     * Handles user activity by cancelling any pending attention requests and keeping track of when
     * the activity happened.
     *
     * @param eventTime Activity time, in uptime millis.
     * @param event     Activity type as defined in {@link PowerManager}.
     * @return 0 when activity was ignored, 1 when handled, -1 when invalid.
     */
    public int onUserActivity(long eventTime, int event) {
        switch (event) {
            case PowerManager.USER_ACTIVITY_EVENT_ATTENTION:
                mConsecutiveTimeoutExtendedCount.incrementAndGet();
                return 0;
            case PowerManager.USER_ACTIVITY_EVENT_OTHER:
            case PowerManager.USER_ACTIVITY_EVENT_BUTTON:
            case PowerManager.USER_ACTIVITY_EVENT_TOUCH:
            case PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY:
                cancelCurrentRequestIfAny();
                mLastUserActivityTime = eventTime;
                resetConsecutiveExtensionCount();
                return 1;
            default:
                if (DEBUG) {
                    Slog.d(TAG, "Attention not reset. Unknown activity event: " + event);
                }
                return -1;
        }
    }

    public void onWakefulnessChangeStarted(int wakefulness) {
        mWakefulness = wakefulness;
        if (wakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE) {
            cancelCurrentRequestIfAny();
            resetConsecutiveExtensionCount();
        }
    }

    private void cancelCurrentRequestIfAny() {
        if (mRequested.get()) {
            mAttentionManager.cancelAttentionCheck(mCallback);
            mRequested.set(false);
        }
    }

    private void resetConsecutiveExtensionCount() {
        final long previousCount = mConsecutiveTimeoutExtendedCount.getAndSet(0);
        if (previousCount > 0) {
            StatsLog.write(StatsLog.SCREEN_TIMEOUT_EXTENSION_REPORTED, previousCount);
        }
    }

    @VisibleForTesting
    long getAttentionTimeout() {
        return mMaxAttentionApiTimeoutMillis;
    }

    /**
     * {@see AttentionManagerInternal#isAttentionServiceSupported}
     */
    @VisibleForTesting
    boolean isAttentionServiceSupported() {
        return mAttentionManager != null && mAttentionManager.isAttentionServiceSupported();
    }

    /**
     * Returns {@code true} if the attention service has sufficient permissions, disables the
     * depending features otherwise.
     */
    @VisibleForTesting
    boolean serviceHasSufficientPermissions() {
        final String attentionPackage = mPackageManager.getAttentionServicePackageName();
        return attentionPackage != null && mPackageManager.checkPermission(
                Manifest.permission.CAMERA, attentionPackage)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void dump(PrintWriter pw) {
        pw.println("AttentionDetector:");
        pw.println(" mIsSettingEnabled=" + mIsSettingEnabled);
        pw.println(" mMaximumExtensionMillis=" + mMaximumExtensionMillis);
        pw.println(" mMaxAttentionApiTimeoutMillis=" + mMaxAttentionApiTimeoutMillis);
        pw.println(" mLastUserActivityTime(excludingAttention)=" + mLastUserActivityTime);
        pw.println(" mAttentionServiceSupported=" + isAttentionServiceSupported());
        pw.println(" mRequested=" + mRequested);
    }

    @VisibleForTesting
    final class AttentionCallbackInternalImpl extends AttentionCallbackInternal {
        private final int mId;

        AttentionCallbackInternalImpl(int id) {
            this.mId = id;
        }

        @Override
        public void onSuccess(int result, long timestamp) {
            Slog.v(TAG, "onSuccess: " + result + ", ID: " + mId);
            // If we don't check for request ID it's possible to get into a loop: success leads
            // to the onUserAttention(), which in turn triggers updateUserActivity(), which will
            // call back onSuccess() instantaneously if there is a cached value, and circle repeats.
            if (mId == mRequestId && mRequested.getAndSet(false)) {
                synchronized (mLock) {
                    if (mWakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE) {
                        if (DEBUG) Slog.d(TAG, "Device slept before receiving callback.");
                        return;
                    }
                    if (result == AttentionService.ATTENTION_SUCCESS_PRESENT) {
                        mOnUserAttention.run();
                    } else {
                        resetConsecutiveExtensionCount();
                    }
                }
            }
        }

        @Override
        public void onFailure(int error) {
            Slog.i(TAG, "Failed to check attention: " + error + ", ID: " + mId);
            mRequested.set(false);
        }
    }

    private final class UserSwitchObserver extends SynchronousUserSwitchObserver {
        @Override
        public void onUserSwitching(int newUserId) throws RemoteException {
            updateEnabledFromSettings(mContext);
        }
    }
}
