/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.hardware.biometrics.BiometricSourceType.FACE;

import static com.android.systemui.flags.Flags.LOCKSCREEN_ENABLE_LANDSCAPE;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Build;
import android.os.SystemProperties;
import android.os.Trace;

import androidx.annotation.VisibleForTesting;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.logging.KeyguardUpdateMonitorLogger;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 *
 */
@SysUISingleton
public class KeyguardStateControllerImpl implements KeyguardStateController {

    private static final boolean DEBUG_AUTH_WITH_ADB = false;
    private static final String AUTH_BROADCAST_KEY = "debug_trigger_auth";

    private final ConcurrentHashMap.KeySetView<Callback, Boolean> mCallbacks =
            ConcurrentHashMap.<Callback>newKeySet();
    private final Context mContext;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final SelectedUserInteractor mUserInteractor;
    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new UpdateMonitorCallback();
    private final Lazy<KeyguardUnlockAnimationController> mUnlockAnimationControllerLazy;
    private final KeyguardUpdateMonitorLogger mLogger;

    private boolean mCanDismissLockScreen;
    private boolean mShowing;
    private boolean mPrimaryBouncerShowing;
    private boolean mSecure;
    private boolean mOccluded;

    private boolean mKeyguardFadingAway;
    private long mKeyguardFadingAwayDelay;
    private long mKeyguardFadingAwayDuration;
    private boolean mKeyguardGoingAway;
    private boolean mLaunchTransitionFadingAway;
    private boolean mTrustManaged;
    private boolean mTrusted;
    private boolean mDebugUnlocked = false;
    private boolean mFaceEnrolledAndEnabled;

    private float mDismissAmount = 0f;
    private boolean mDismissingFromTouch = false;

    /**
     * Whether the panel is currently flinging to a collapsed state, which means we're dismissing
     * the keyguard.
     */
    private boolean mFlingingToDismissKeyguard = false;

    /**
     * Whether the panel is currently flinging to a collapsed state, which means we're dismissing
     * the keyguard, and the fling started during a swipe gesture. This means that we need to take
     * over the gesture and animate the rest of the way dismissed.
     */
    private boolean mFlingingToDismissKeyguardDuringSwipeGesture = false;

    /**
     * Whether the panel is currently flinging to an expanded state, which means we cancelled the
     * dismiss gesture and are snapping back to the keyguard state.
     */
    private boolean mSnappingKeyguardBackAfterSwipe = false;

    private FeatureFlags mFeatureFlags;

    /**
     *
     */
    @Inject
    public KeyguardStateControllerImpl(
            Context context,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            LockPatternUtils lockPatternUtils,
            Lazy<KeyguardUnlockAnimationController> keyguardUnlockAnimationController,
            KeyguardUpdateMonitorLogger logger,
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            SelectedUserInteractor userInteractor) {
        mContext = context;
        mLogger = logger;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mUserInteractor = userInteractor;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mUnlockAnimationControllerLazy = keyguardUnlockAnimationController;
        mFeatureFlags = featureFlags;

        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        update(true /* updateAlways */);
        if (Build.IS_DEBUGGABLE && DEBUG_AUTH_WITH_ADB) {
            // Watch for interesting updates
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AUTH_BROADCAST_KEY);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (DEBUG_AUTH_WITH_ADB && AUTH_BROADCAST_KEY.equals(intent.getAction())) {
                        mDebugUnlocked = !mDebugUnlocked;
                        update(true /* updateAlways */);
                    }
                }
            }, filter, null, null, Context.RECEIVER_EXPORTED_UNAUDITED);
        }
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.remove(callback);
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    public boolean isPrimaryBouncerShowing() {
        return mPrimaryBouncerShowing;
    }

    @Override
    public boolean isMethodSecure() {
        return mSecure;
    }

    @Override
    public boolean isOccluded() {
        return mOccluded;
    }

    @Override
    public boolean isTrusted() {
        return mTrusted;
    }

    @Override
    public void notifyKeyguardState(boolean showing, boolean occluded) {
        if (mShowing == showing && mOccluded == occluded) return;
        mShowing = showing;
        mOccluded = occluded;
        mKeyguardUpdateMonitor.setKeyguardShowing(showing, occluded);
        Trace.instantForTrack(Trace.TRACE_TAG_APP, "UI Events",
                "Keyguard showing: " + showing + " occluded: " + occluded);
        notifyKeyguardChanged();

        // Update the dismiss amount to the full 0f/1f if we explicitly show or hide the keyguard.
        // Otherwise, the dismiss amount could be left at a random value if we show/hide during a
        // dismiss gesture, canceling the gesture.
        notifyKeyguardDismissAmountChanged(showing ? 0f : 1f, false);
    }

    private void notifyKeyguardChanged() {
        Trace.beginSection("KeyguardStateController#notifyKeyguardChanged");
        // Copy the list to allow removal during callback.
        invokeForEachCallback(Callback::onKeyguardShowingChanged);
        Trace.endSection();
    }

    private void notifyKeyguardFaceAuthEnabledChanged() {
        invokeForEachCallback(Callback::onFaceEnrolledChanged);
    }

    private void invokeForEachCallback(Consumer<Callback> consumer) {
        mCallbacks.forEach(consumer);
    }

    private void notifyUnlockedChanged() {
        Trace.beginSection("KeyguardStateController#notifyUnlockedChanged");
        // Copy the list to allow removal during callback.
        invokeForEachCallback(Callback::onUnlockedChanged);
        Trace.endSection();
    }

    @Override
    public void notifyKeyguardFadingAway(long delay, long fadeoutDuration) {
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
        setKeyguardFadingAway(true);
    }

    private void setKeyguardFadingAway(boolean keyguardFadingAway) {
        if (mKeyguardFadingAway != keyguardFadingAway) {
            Trace.traceCounter(Trace.TRACE_TAG_APP, "keyguardFadingAway",
                    keyguardFadingAway ? 1 : 0);
            mKeyguardFadingAway = keyguardFadingAway;
            invokeForEachCallback(Callback::onKeyguardFadingAwayChanged);
        }
    }

    @Override
    public void notifyKeyguardDoneFading() {
        notifyKeyguardGoingAway(false);
        setKeyguardFadingAway(false);
    }

    @VisibleForTesting
    void update(boolean updateAlways) {
        Trace.beginSection("KeyguardStateController#update");
        int user = mUserInteractor.getSelectedUserId();
        boolean secure = mLockPatternUtils.isSecure(user);
        boolean canDismissLockScreen = !secure || mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)
                || (Build.IS_DEBUGGABLE && DEBUG_AUTH_WITH_ADB && mDebugUnlocked);
        boolean trustManaged = mKeyguardUpdateMonitor.getUserTrustIsManaged(user);
        boolean trusted = mKeyguardUpdateMonitor.getUserHasTrust(user);
        boolean faceEnabledAndEnrolled = mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled();
        boolean changed = secure != mSecure || canDismissLockScreen != mCanDismissLockScreen
                || trustManaged != mTrustManaged || mTrusted != trusted
                || mFaceEnrolledAndEnabled != faceEnabledAndEnrolled;
        if (changed || updateAlways) {
            mSecure = secure;
            mCanDismissLockScreen = canDismissLockScreen;
            mTrusted = trusted;
            mTrustManaged = trustManaged;
            mFaceEnrolledAndEnabled = faceEnabledAndEnrolled;
            mLogger.logKeyguardStateUpdate(
                    mSecure, mCanDismissLockScreen, mTrusted, mTrustManaged);
            notifyUnlockedChanged();
        }
        Trace.endSection();
    }

    @Override
    public boolean canDismissLockScreen() {
        return mCanDismissLockScreen;
    }

    @Override
    public boolean isKeyguardScreenRotationAllowed() {
        return SystemProperties.getBoolean("lockscreen.rot_override", false)
                || mContext.getResources().getBoolean(R.bool.config_enableLockScreenRotation)
                || mFeatureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE);
    }

    @Override
    public boolean isFaceEnrolledAndEnabled() {
        return mFaceEnrolledAndEnabled;
    }

    @Override
    public boolean isKeyguardFadingAway() {
        return mKeyguardFadingAway;
    }

    @Override
    public boolean isKeyguardGoingAway() {
        return mKeyguardGoingAway;
    }

    @Override
    public boolean isAnimatingBetweenKeyguardAndSurfaceBehind() {
        return mUnlockAnimationControllerLazy.get().isAnimatingBetweenKeyguardAndSurfaceBehind();
    }

    @Override
    public long getKeyguardFadingAwayDelay() {
        return mKeyguardFadingAwayDelay;
    }

    @Override
    public long getKeyguardFadingAwayDuration() {
        return mKeyguardFadingAwayDuration;
    }

    @Override
    public long calculateGoingToFullShadeDelay() {
        return mKeyguardFadingAwayDelay + mKeyguardFadingAwayDuration;
    }

    @Override
    public boolean isFlingingToDismissKeyguard() {
        return mFlingingToDismissKeyguard;
    }

    @Override
    public boolean isFlingingToDismissKeyguardDuringSwipeGesture() {
        return mFlingingToDismissKeyguardDuringSwipeGesture;
    }

    @Override
    public boolean isSnappingKeyguardBackAfterSwipe() {
        return mSnappingKeyguardBackAfterSwipe;
    }

    @Override
    public float getDismissAmount() {
        return mDismissAmount;
    }

    @Override
    public boolean isDismissingFromSwipe() {
        return mDismissingFromTouch;
    }

    @Override
    public void notifyKeyguardGoingAway(boolean keyguardGoingAway) {
        if (mKeyguardGoingAway != keyguardGoingAway) {
            Trace.traceCounter(Trace.TRACE_TAG_APP, "keyguardGoingAway",
                    keyguardGoingAway ? 1 : 0);
            mKeyguardGoingAway = keyguardGoingAway;
            invokeForEachCallback(Callback::onKeyguardGoingAwayChanged);
        }
    }

    @Override
    public void notifyPrimaryBouncerShowing(boolean showing) {
        if (mPrimaryBouncerShowing != showing) {
            mPrimaryBouncerShowing = showing;

            invokeForEachCallback(Callback::onPrimaryBouncerShowingChanged);
        }
    }

    @Override
    public void notifyPanelFlingEnd() {
        mFlingingToDismissKeyguard = false;
        mFlingingToDismissKeyguardDuringSwipeGesture = false;
        mSnappingKeyguardBackAfterSwipe = false;
    }

    @Override
    public void notifyPanelFlingStart(boolean flingToDismiss) {
        mFlingingToDismissKeyguard = flingToDismiss;
        mFlingingToDismissKeyguardDuringSwipeGesture =
                flingToDismiss && mDismissingFromTouch;
        mSnappingKeyguardBackAfterSwipe = !flingToDismiss;
    }

    @Override
    public void notifyKeyguardDismissAmountChanged(float dismissAmount,
            boolean dismissingFromTouch) {
        mDismissAmount = dismissAmount;
        mDismissingFromTouch = dismissingFromTouch;
        invokeForEachCallback(Callback::onKeyguardDismissAmountChanged);
    }

    @Override
    public void setLaunchTransitionFadingAway(boolean fadingAway) {
        mLaunchTransitionFadingAway = fadingAway;
        invokeForEachCallback(Callback::onLaunchTransitionFadingAwayChanged);
    }

    @Override
    public boolean isLaunchTransitionFadingAway() {
        return mLaunchTransitionFadingAway;
    }

    /**
     * Dumps internal state for debugging.
     * @param pw Where to dump.
     */
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardStateController:");
        pw.println("  mShowing: " + mShowing);
        pw.println("  mOccluded: " + mOccluded);
        pw.println("  mSecure: " + mSecure);
        pw.println("  mCanDismissLockScreen: " + mCanDismissLockScreen);
        pw.println("  mTrustManaged: " + mTrustManaged);
        pw.println("  mTrusted: " + mTrusted);
        pw.println("  mDebugUnlocked: " + mDebugUnlocked);
        pw.println("  mFaceEnrolled: " + mFaceEnrolledAndEnabled);
        pw.println("  isKeyguardFadingAway: " + isKeyguardFadingAway());
        pw.println("  isKeyguardGoingAway: " + isKeyguardGoingAway());
        pw.println("  isLaunchTransitionFadingAway: " + isLaunchTransitionFadingAway());
    }

    private class UpdateMonitorCallback extends KeyguardUpdateMonitorCallback {
        @Override
        public void onUserSwitchComplete(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onTrustChanged(int userId) {
            update(false /* updateAlways */);
            notifyKeyguardChanged();
        }

        @Override
        public void onTrustManagedChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onBiometricEnrollmentStateChanged(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                // We only care about enrollment state here. Keyguard face auth enabled is just
                // same as face auth enrolled
                update(false);
                notifyKeyguardFaceAuthEnabledChanged();
            }
        }

        @Override
        public void onStartedWakingUp() {
            update(false /* updateAlways */);
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            Trace.beginSection("KeyguardUpdateMonitorCallback#onBiometricAuthenticated");
            if (mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric)) {
                update(false /* updateAlways */);
            }
            Trace.endSection();
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onLockedOutStateChanged(BiometricSourceType biometricSourceType) {
            update(false /* updateAlways */);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible) {
            update(false /* updateAlways */);
        }

        @Override
        public void onFingerprintsCleared() {
            update(false /* alwaysUpdate */);
        }

        @Override
        public void onFacesCleared() {
            update(false /* alwaysUpdate */);
        }

        @Override
        public void onEnabledTrustAgentsChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onForceIsDismissibleChanged(boolean keepUnlockedOnFold) {
            update(false /* updateAlways */);
        }
    }
}
