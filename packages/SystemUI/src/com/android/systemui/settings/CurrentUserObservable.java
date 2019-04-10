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

package com.android.systemui.settings;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * A class that has an observable for the current user.
 */
public class CurrentUserObservable {

    private final CurrentUserTracker mTracker;

    private final MutableLiveData<Integer> mCurrentUser = new MutableLiveData<Integer>() {
        @Override
        protected void onActive() {
            super.onActive();
            mTracker.startTracking();
        }

        @Override
        protected void onInactive() {
            super.onInactive();
            mTracker.startTracking();
        }
    };

    public CurrentUserObservable(Context context) {
        mTracker = new CurrentUserTracker(context) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser.setValue(newUserId);
            }
        };
    }

    /**
     * Returns the current user that can be observed.
     */
    public LiveData<Integer> getCurrentUser() {
        if (mCurrentUser.getValue() == null) {
            mCurrentUser.setValue(mTracker.getCurrentUserId());
        }
        return mCurrentUser;
    }
}
