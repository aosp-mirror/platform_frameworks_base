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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.car.settings.CarSettings;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/**
 * A controller that monitors the status of SUW progress for each user in addition to the
 * functionality provided by {@link DeviceProvisionedControllerImpl}.
 */
@SysUISingleton
public class CarDeviceProvisionedControllerImpl extends DeviceProvisionedControllerImpl implements
        CarDeviceProvisionedController {
    private final Uri mUserSetupInProgressUri;
    private final ContentObserver mCarSettingsObserver;
    private final Handler mMainHandler;
    private final SecureSettings mSecureSettings;

    @Inject
    public CarDeviceProvisionedControllerImpl(@Main Handler mainHandler,
            BroadcastDispatcher broadcastDispatcher, GlobalSettings globalSetting,
            SecureSettings secureSettings) {
        super(mainHandler, broadcastDispatcher, globalSetting, secureSettings);
        mMainHandler = mainHandler;
        mSecureSettings = secureSettings;
        mUserSetupInProgressUri = mSecureSettings.getUriFor(
                CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS);
        mCarSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int flags) {
                if (mUserSetupInProgressUri.equals(uri)) {
                    notifyUserSetupInProgressChanged();
                }
            }
        };
    }

    @Override
    public boolean isUserSetupInProgress(int user) {
        return mSecureSettings.getIntForUser(
                CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS, /* def= */ 0, user) != 0;
    }

    @Override
    public boolean isCurrentUserSetupInProgress() {
        return isUserSetupInProgress(ActivityManager.getCurrentUser());
    }

    @Override
    public void addCallback(@NonNull DeviceProvisionedListener listener) {
        super.addCallback(listener);
        if (listener instanceof CarDeviceProvisionedListener) {
            ((CarDeviceProvisionedListener) listener).onUserSetupInProgressChanged();
        }
    }

    @Override
    protected void startListening(int user) {
        mSecureSettings.registerContentObserverForUser(
                mUserSetupInProgressUri, /* notifyForDescendants= */ true,
                mCarSettingsObserver, user);
        // The SUW Flag observer is registered before super.startListening() so that the observer is
        // in place before DeviceProvisionedController starts to track user switches which avoids
        // an edge case where our observer gets registered twice.
        super.startListening(user);
    }

    @Override
    protected void stopListening() {
        super.stopListening();
        mSecureSettings.unregisterContentObserver(mCarSettingsObserver);
    }

    @Override
    public void onUserSwitched(int newUserId) {
        super.onUserSwitched(newUserId);
        mSecureSettings.unregisterContentObserver(mCarSettingsObserver);
        mSecureSettings.registerContentObserverForUser(
                mUserSetupInProgressUri, /* notifyForDescendants= */ true,
                mCarSettingsObserver, newUserId);
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
