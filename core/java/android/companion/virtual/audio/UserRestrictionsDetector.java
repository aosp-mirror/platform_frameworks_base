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

package android.companion.virtual.audio;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserManager;

import com.android.internal.annotations.GuardedBy;

/**
 * Class to detect the user restrictions change for microphone usage.
 */
final class UserRestrictionsDetector extends BroadcastReceiver {
    private static final String TAG = "UserRestrictionsDetector";

    /** Interface for listening user restrictions change. */
    interface UserRestrictionsCallback {

        /** Notifies when value of {@link UserManager#DISALLOW_UNMUTE_MICROPHONE} is changed. */
        void onMicrophoneRestrictionChanged(boolean isUnmuteMicDisallowed);
    }

    private final Context mContext;
    private final UserManager mUserManager;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mIsUnmuteMicDisallowed;
    private UserRestrictionsCallback mUserRestrictionsCallback;

    UserRestrictionsDetector(Context context) {
        mContext = context;
        mUserManager = context.getSystemService(UserManager.class);
    }

    /** Returns value of {@link UserManager#DISALLOW_UNMUTE_MICROPHONE}. */
    boolean isUnmuteMicrophoneDisallowed() {
        Bundle bundle = mUserManager.getUserRestrictions();
        return bundle.getBoolean(UserManager.DISALLOW_UNMUTE_MICROPHONE);
    }

    /** Registers user restrictions change. */
    void register(@NonNull UserRestrictionsCallback callback) {
        mUserRestrictionsCallback = callback;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        mContext.registerReceiver(/* receiver= */ this, filter);

        synchronized (mLock) {
            // Gets initial value.
            mIsUnmuteMicDisallowed = isUnmuteMicrophoneDisallowed();
        }
    }

    /** Unregisters user restrictions change. */
    void unregister() {
        if (mUserRestrictionsCallback != null) {
            mUserRestrictionsCallback = null;
            mContext.unregisterReceiver(/* receiver= */ this);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (UserManager.ACTION_USER_RESTRICTIONS_CHANGED.equals(action)) {
            boolean isUnmuteMicDisallowed = isUnmuteMicrophoneDisallowed();
            synchronized (mLock) {
                if (isUnmuteMicDisallowed == mIsUnmuteMicDisallowed) {
                    return;
                }
                mIsUnmuteMicDisallowed = isUnmuteMicDisallowed;
            }
            if (mUserRestrictionsCallback != null) {
                mUserRestrictionsCallback.onMicrophoneRestrictionChanged(isUnmuteMicDisallowed);
            }
        }
    }
}
