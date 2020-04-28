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

package com.android.systemui.car;

import android.app.ActivityManager;
import android.car.settings.CarSettings;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import com.android.systemui.Dependency;

import java.util.ArrayList;

/**
 * A controller that monitors the status of SUW progress for each user.
 */
public class SUWProgressController {
    private static final Uri USER_SETUP_IN_PROGRESS_URI = Settings.Secure.getUriFor(
            CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS);
    private final ArrayList<SUWProgressListener> mListeners = new ArrayList<>();
    private final ContentObserver mCarSettingsObserver = new ContentObserver(
            Dependency.get(Dependency.MAIN_HANDLER)) {
        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (USER_SETUP_IN_PROGRESS_URI.equals(uri)) {
                notifyUserSetupInProgressChanged();
            }
        }
    };
    private final ContentResolver mContentResolver;

    public SUWProgressController(Context context) {
        mContentResolver = context.getContentResolver();
    }

    /**
     * Returns {@code true} then SUW is in progress for the given user.
     */
    public boolean isUserSetupInProgress(int user) {
        return Settings.Secure.getIntForUser(mContentResolver,
                CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS, /* def= */ 0, user) != 0;
    }

    /**
     * Returns {@code true} then SUW is in progress for the current user.
     */
    public boolean isCurrentUserSetupInProgress() {
        return isUserSetupInProgress(ActivityManager.getCurrentUser());
    }

    /**
     * Adds a {@link SUWProgressListener} callback.
     */
    public void addCallback(SUWProgressListener listener) {
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            startListening(ActivityManager.getCurrentUser());
        }
        listener.onUserSetupInProgressChanged();
    }

    /**
     * Removes a {@link SUWProgressListener} callback.
     */
    public void removeCallback(SUWProgressListener listener) {
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            stopListening();
        }
    }

    private void startListening(int user) {
        mContentResolver.registerContentObserver(
                USER_SETUP_IN_PROGRESS_URI, /* notifyForDescendants= */ true, mCarSettingsObserver,
                user);
    }

    private void stopListening() {
        mContentResolver.unregisterContentObserver(mCarSettingsObserver);
    }

    /**
     * Allows SUWProgressController to switch its listeners to observe SUW progress for new user.
     */
    public void onUserSwitched() {
        if (mListeners.size() == 0) {
            return;
        }

        mContentResolver.unregisterContentObserver(mCarSettingsObserver);
        mContentResolver.registerContentObserver(
                USER_SETUP_IN_PROGRESS_URI, /* notifyForDescendants= */ true, mCarSettingsObserver,
                ActivityManager.getCurrentUser());
    }

    private void notifyUserSetupInProgressChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onUserSetupInProgressChanged();
        }
    }

    /**
     * A listener that listens for changes in SUW progress for a user.
     */
    public interface SUWProgressListener {
        /**
         * A callback for when a change occurs in SUW progress for a user.
         */
        default void onUserSetupInProgressChanged() {
        }
    }
}
