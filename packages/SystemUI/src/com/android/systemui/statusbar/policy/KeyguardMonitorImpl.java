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

import android.app.ActivityManager;
import android.content.Context;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.settings.CurrentUserTracker;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 */
@Singleton
public class KeyguardMonitorImpl extends KeyguardUpdateMonitorCallback
        implements KeyguardMonitor {

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();

    private final Context mContext;
    private final CurrentUserTracker mUserTracker;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private int mCurrentUser;
    private boolean mShowing;
    private boolean mSecure;
    private boolean mOccluded;
    private boolean mCanSkipBouncer;

    private boolean mListening;
    private boolean mKeyguardFadingAway;
    private long mKeyguardFadingAwayDelay;
    private long mKeyguardFadingAwayDuration;
    private boolean mKeyguardGoingAway;
    private boolean mLaunchTransitionFadingAway;

    /**
     */
    @Inject
    public KeyguardMonitorImpl(Context context) {
        mContext = context;
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                updateCanSkipBouncerState();
            }
        };
    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
        if (mCallbacks.size() != 0 && !mListening) {
            mListening = true;
            mCurrentUser = ActivityManager.getCurrentUser();
            updateCanSkipBouncerState();
            mKeyguardUpdateMonitor.registerCallback(this);
            mUserTracker.startTracking();
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        if (mCallbacks.remove(callback) && mCallbacks.size() == 0 && mListening) {
            mListening = false;
            mKeyguardUpdateMonitor.removeCallback(this);
            mUserTracker.stopTracking();
        }
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    public boolean isSecure() {
        return mSecure;
    }

    @Override
    public boolean isOccluded() {
        return mOccluded;
    }

    @Override
    public boolean canSkipBouncer() {
        return mCanSkipBouncer;
    }

    public void notifyKeyguardState(boolean showing, boolean secure, boolean occluded) {
        if (mShowing == showing && mSecure == secure && mOccluded == occluded) return;
        mShowing = showing;
        mSecure = secure;
        mOccluded = occluded;
        notifyKeyguardChanged();
    }

    @Override
    public void onTrustChanged(int userId) {
        updateCanSkipBouncerState();
        notifyKeyguardChanged();
    }

    public boolean isDeviceInteractive() {
        return mKeyguardUpdateMonitor.isDeviceInteractive();
    }

    private void updateCanSkipBouncerState() {
        mCanSkipBouncer = mKeyguardUpdateMonitor.getUserCanSkipBouncer(mCurrentUser);
    }

    private void notifyKeyguardChanged() {
        // Copy the list to allow removal during callback.
        new ArrayList<>(mCallbacks).forEach(Callback::onKeyguardShowingChanged);
    }

    public void notifyKeyguardFadingAway(long delay, long fadeoutDuration) {
        mKeyguardFadingAway = true;
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
    }

    public void notifyKeyguardDoneFading() {
        mKeyguardFadingAway = false;
        mKeyguardGoingAway = false;
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

    public void notifyKeyguardGoingAway(boolean keyguardGoingAway) {
        mKeyguardGoingAway = keyguardGoingAway;
    }

    public void setLaunchTransitionFadingAway(boolean fadingAway) {
        mLaunchTransitionFadingAway = fadingAway;
    }

    @Override
    public boolean isLaunchTransitionFadingAway() {
        return mLaunchTransitionFadingAway;
    }
}