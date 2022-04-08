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
import android.os.Handler;
import android.provider.Settings;

import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A controller that monitors the status of SUW progress for each user in addition to the
 * functionality provided by {@link DeviceProvisionedControllerImpl}.
 */
@Singleton
public class CarDeviceProvisionedControllerImpl extends DeviceProvisionedControllerImpl implements
        CarDeviceProvisionedController {
    private static final Uri USER_SETUP_IN_PROGRESS_URI = Settings.Secure.getUriFor(
            CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS);
    private final ContentObserver mCarSettingsObserver = new ContentObserver(
            Dependency.get(Dependency.MAIN_HANDLER)) {

        @Override
        public void onChange(boolean selfChange, Uri uri, int flags) {
            if (USER_SETUP_IN_PROGRESS_URI.equals(uri)) {
                notifyUserSetupInProgressChanged();
            }
        }
    };
    private final ContentResolver mContentResolver;

    @Inject
    public CarDeviceProvisionedControllerImpl(Context context, @Main Handler mainHandler,
            BroadcastDispatcher broadcastDispatcher) {
        super(context, mainHandler, broadcastDispatcher);
        mContentResolver = context.getContentResolver();
    }

    @Override
    public boolean isUserSetupInProgress(int user) {
        return Settings.Secure.getIntForUser(mContentResolver,
                CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS, /* def= */ 0, user) != 0;
    }

    @Override
    public boolean isCurrentUserSetupInProgress() {
        return isUserSetupInProgress(ActivityManager.getCurrentUser());
    }

    @Override
    public void addCallback(DeviceProvisionedListener listener) {
        super.addCallback(listener);
        if (listener instanceof CarDeviceProvisionedListener) {
            ((CarDeviceProvisionedListener) listener).onUserSetupInProgressChanged();
        }
    }

    @Override
    protected void startListening(int user) {
        mContentResolver.registerContentObserver(
                USER_SETUP_IN_PROGRESS_URI, /* notifyForDescendants= */ true, mCarSettingsObserver,
                user);
        // The SUW Flag observer is registered before super.startListening() so that the observer is
        // in place before DeviceProvisionedController starts to track user switches which avoids
        // an edge case where our observer gets registered twice.
        super.startListening(user);
    }

    @Override
    protected void stopListening() {
        super.stopListening();
        mContentResolver.unregisterContentObserver(mCarSettingsObserver);
    }

    @Override
    public void onUserSwitched(int newUserId) {
        super.onUserSwitched(newUserId);
        mContentResolver.unregisterContentObserver(mCarSettingsObserver);
        mContentResolver.registerContentObserver(
                USER_SETUP_IN_PROGRESS_URI, /* notifyForDescendants= */ true, mCarSettingsObserver,
                newUserId);
    }

    private void notifyUserSetupInProgressChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            DeviceProvisionedListener listener = mListeners.get(i);
            if (listener instanceof CarDeviceProvisionedListener) {
                ((CarDeviceProvisionedListener) listener).onUserSetupInProgressChanged();
            }
        }
    }
}
