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

public final class KeyguardMonitor extends KeyguardUpdateMonitorCallback {

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private final Context mContext;
    private final CurrentUserTracker mUserTracker;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private int mCurrentUser;
    private boolean mShowing;
    private boolean mSecure;
    private boolean mTrusted;

    private boolean mListening;

    public KeyguardMonitor(Context context) {
        mContext = context;
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                updateTrustedState();
            }
        };
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
        if (mCallbacks.size() != 0 && !mListening) {
            mListening = true;
            mCurrentUser = ActivityManager.getCurrentUser();
            updateTrustedState();
            mKeyguardUpdateMonitor.registerCallback(this);
            mUserTracker.startTracking();
        }
    }

    public void removeCallback(Callback callback) {
        if (mCallbacks.remove(callback) && mCallbacks.size() == 0 && mListening) {
            mListening = false;
            mKeyguardUpdateMonitor.removeCallback(this);
            mUserTracker.stopTracking();
        }
    }

    public boolean isShowing() {
        return mShowing;
    }

    public boolean isSecure() {
        return mSecure;
    }

    public boolean isTrusted() {
        return mTrusted;
    }

    public void notifyKeyguardState(boolean showing, boolean secure) {
        if (mShowing == showing && mSecure == secure) return;
        mShowing = showing;
        mSecure = secure;
        notifyKeyguardChanged();
    }

    @Override
    public void onTrustChanged(int userId) {
        updateTrustedState();
        notifyKeyguardChanged();
    }

    private void updateTrustedState() {
        mTrusted = mKeyguardUpdateMonitor.getUserHasTrust(mCurrentUser);
    }

    private void notifyKeyguardChanged() {
        for (Callback callback : mCallbacks) {
            callback.onKeyguardChanged();
        }
    }

    public interface Callback {
        void onKeyguardChanged();
    }
}