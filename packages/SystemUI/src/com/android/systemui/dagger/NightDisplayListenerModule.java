/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.content.Context;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.UserHandle;

import com.android.systemui.dagger.qualifiers.Background;

import dagger.Module;
import dagger.Provides;

import javax.inject.Inject;

/**
 * Module for providing a {@link NightDisplayListener}.
 */
@Module
public class NightDisplayListenerModule {

    /**
     * Provides a {@link NightDisplayListener}.
     *
     * The provided listener is associated with the user as returned by
     * {@link android.app.ActivityManager#getCurrentUser}, making an IPC call on its creation.
     * If the current user is known, prefer using a {@link Builder}.
     */
    @Provides
    public NightDisplayListener provideNightDisplayListener(Context context,
            @Background Handler bgHandler) {
        return new NightDisplayListener(context, bgHandler);
    }

    /**
     * Builder to create instances of {@link NightDisplayListener}.
     *
     * It uses {@link UserHandle#USER_SYSTEM} as the default user.
     */
    public static class Builder {
        private final Context mContext;
        private final Handler mBgHandler;
        private int mUserId = UserHandle.USER_SYSTEM;

        @Inject
        public Builder(Context context, @Background Handler bgHandler) {
            mContext = context;
            mBgHandler = bgHandler;
        }

        /**
         * Set the userId for this builder
         */
        public Builder setUser(int userId) {
            mUserId = userId;
            return this;
        }

        /**
         * Build a {@link NightDisplayListener} for the set user.
         */
        public NightDisplayListener build() {
            return new NightDisplayListener(mContext, mUserId, mBgHandler);
        }
    }
}
