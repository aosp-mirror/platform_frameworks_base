/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class DeviceProvisionedControllerImpl extends CurrentUserTracker implements
        DeviceProvisionedController {

    protected static final String TAG = DeviceProvisionedControllerImpl.class.getSimpleName();
    protected final ArrayList<DeviceProvisionedListener> mListeners = new ArrayList<>();
    private final GlobalSettings mGlobalSettings;
    private final SecureSettings mSecureSettings;
    private final Uri mDeviceProvisionedUri;
    private final Uri mUserSetupUri;
    protected final ContentObserver mSettingsObserver;

    /**
     */
    @Inject
    public DeviceProvisionedControllerImpl(@Main Handler mainHandler,
            BroadcastDispatcher broadcastDispatcher, GlobalSettings globalSettings,
            SecureSettings secureSettings) {
        super(broadcastDispatcher);
        mGlobalSettings = globalSettings;
        mSecureSettings = secureSettings;
        mDeviceProvisionedUri = mGlobalSettings.getUriFor(Global.DEVICE_PROVISIONED);
        mUserSetupUri = mSecureSettings.getUriFor(Secure.USER_SETUP_COMPLETE);
        mSettingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int flags) {
                Log.d(TAG, "Setting change: " + uri);
                if (mUserSetupUri.equals(uri)) {
                    notifySetupChanged();
                } else {
                    notifyProvisionedChanged();
                }
            }
        };
    }

    @Override
    public boolean isDeviceProvisioned() {
        return mGlobalSettings.getInt(Global.DEVICE_PROVISIONED, 0) != 0;
    }

    @Override
    public boolean isUserSetup(int currentUser) {
        return mSecureSettings.getIntForUser(Secure.USER_SETUP_COMPLETE, 0, currentUser) != 0;
    }

    @Override
    public int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    @Override
    public void addCallback(@NonNull DeviceProvisionedListener listener) {
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            startListening(getCurrentUser());
        }
        listener.onUserSetupChanged();
        listener.onDeviceProvisionedChanged();
    }

    @Override
    public void removeCallback(@NonNull DeviceProvisionedListener listener) {
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            stopListening();
        }
    }

    protected void startListening(int user) {
        mGlobalSettings.registerContentObserverForUser(mDeviceProvisionedUri, true,
                mSettingsObserver, 0);
        mSecureSettings.registerContentObserverForUser(mUserSetupUri, true,
                mSettingsObserver, user);
        startTracking();
    }

    protected void stopListening() {
        stopTracking();
        mGlobalSettings.unregisterContentObserver(mSettingsObserver);
    }

    @Override
    public void onUserSwitched(int newUserId) {
        mGlobalSettings.unregisterContentObserver(mSettingsObserver);
        mGlobalSettings.registerContentObserverForUser(mDeviceProvisionedUri, true,
                mSettingsObserver, 0);
        mSecureSettings.registerContentObserverForUser(mUserSetupUri, true,
                mSettingsObserver, newUserId);
        notifyUserChanged();
    }

    private void notifyUserChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onUserSwitched();
        }
    }

    private void notifySetupChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onUserSetupChanged();
        }
    }

    private void notifyProvisionedChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onDeviceProvisionedChanged();
        }
    }
}
