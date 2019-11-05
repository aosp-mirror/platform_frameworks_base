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

import android.annotation.NonNull;
import android.content.Context;

import com.android.internal.util.Preconditions;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

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
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private boolean mShowing;
    private boolean mSecure;
    private boolean mOccluded;

    private boolean mListening;
    private boolean mKeyguardFadingAway;
    private long mKeyguardFadingAwayDelay;
    private long mKeyguardFadingAwayDuration;
    private boolean mKeyguardGoingAway;
    private boolean mLaunchTransitionFadingAway;
    private boolean mBypassFadingAnimation;

    /**
     */
    @Inject
    public KeyguardMonitorImpl(Context context) {
        mContext = context;
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        Preconditions.checkNotNull(callback, "Callback must not be null. b/128895449");
        mCallbacks.add(callback);
        if (mCallbacks.size() != 0 && !mListening) {
            mListening = true;
            mKeyguardUpdateMonitor.registerCallback(this);
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Preconditions.checkNotNull(callback, "Callback must not be null. b/128895449");
        if (mCallbacks.remove(callback) && mCallbacks.size() == 0 && mListening) {
            mListening = false;
            mKeyguardUpdateMonitor.removeCallback(this);
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

    public void notifyKeyguardState(boolean showing, boolean secure, boolean occluded) {
        if (mShowing == showing && mSecure == secure && mOccluded == occluded) return;
        mShowing = showing;
        mSecure = secure;
        mOccluded = occluded;
        notifyKeyguardChanged();
    }

    @Override
    public void onTrustChanged(int userId) {
        notifyKeyguardChanged();
    }

    public boolean isDeviceInteractive() {
        return mKeyguardUpdateMonitor.isDeviceInteractive();
    }

    private void notifyKeyguardChanged() {
        // Copy the list to allow removal during callback.
        new ArrayList<>(mCallbacks).forEach(Callback::onKeyguardShowingChanged);
    }

    public void notifyKeyguardFadingAway(long delay, long fadeoutDuration, boolean isBypassFading) {
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
        mBypassFadingAnimation = isBypassFading;
        setKeyguardFadingAway(true);
    }

    private void setKeyguardFadingAway(boolean keyguardFadingAway) {
        if (mKeyguardFadingAway != keyguardFadingAway) {
            mKeyguardFadingAway = keyguardFadingAway;
            ArrayList<Callback> callbacks = new ArrayList<>(mCallbacks);
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).onKeyguardFadingAwayChanged();
            }
        }
    }

    public void notifyKeyguardDoneFading() {
        mKeyguardGoingAway = false;
        setKeyguardFadingAway(false);
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
    public boolean isBypassFadingAnimation() {
        return mBypassFadingAnimation;
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