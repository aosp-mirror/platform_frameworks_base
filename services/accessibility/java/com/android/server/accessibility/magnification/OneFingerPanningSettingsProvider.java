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
package com.android.server.accessibility.magnification;


import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider for secure settings {@link Settings.Secure.ACCESSIBILITY_SINGLE_FINGER_PANNING_ENABLED}.
 */
public class OneFingerPanningSettingsProvider {

    @VisibleForTesting
    static final String KEY = Settings.Secure.ACCESSIBILITY_SINGLE_FINGER_PANNING_ENABLED;
    private static final Uri URI = Settings.Secure.getUriFor(KEY);
    private AtomicBoolean mCached = new AtomicBoolean();
    @VisibleForTesting
    ContentObserver mObserver;
    @VisibleForTesting
    ContentResolver mContentResolver;

    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
        int OFF = 0;
        int ON = 1;
    }

    public OneFingerPanningSettingsProvider(
            Context context,
            boolean featureFlagEnabled
    ) {
        var defaultValue = isOneFingerPanningEnabledDefault(context);
        if (featureFlagEnabled) {
            mContentResolver = context.getContentResolver();
            mObserver = new ContentObserver(context.getMainThreadHandler()) {
                @Override
                public void onChange(boolean selfChange) {
                    mCached.set(isOneFingerPanningEnabledInSetting(context, defaultValue));
                }
            };
            mCached.set(isOneFingerPanningEnabledInSetting(context, defaultValue));
            mContentResolver.registerContentObserver(URI, false, mObserver);
        } else {
            mCached.set(defaultValue);
        }
    }

    /** Returns whether one finger panning is enabled.. */
    public boolean isOneFingerPanningEnabled() {
        return mCached.get();
    }

    /** Unregister content observer for listening to secure settings. */
    public void unregister() {
        if (mContentResolver != null) {
            mContentResolver.unregisterContentObserver(mObserver);
        }
        mContentResolver = null;
    }

    private boolean isOneFingerPanningEnabledInSetting(Context context, boolean defaultValue) {
        return State.ON == Settings.Secure.getIntForUser(
                mContentResolver,
                KEY,
                (defaultValue ? State.ON : State.OFF),
                context.getUserId());
    }

    @VisibleForTesting
    static boolean isOneFingerPanningEnabledDefault(Context context) {
        boolean oneFingerPanningDefaultValue;
        try {
            oneFingerPanningDefaultValue = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_enable_a11y_magnification_single_panning);
        } catch (Resources.NotFoundException e) {
            oneFingerPanningDefaultValue = false;
        }
        return oneFingerPanningDefaultValue;
    }
}
