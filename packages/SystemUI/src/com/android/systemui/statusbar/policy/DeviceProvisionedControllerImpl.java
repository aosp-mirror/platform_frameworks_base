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

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.os.Handler;

import com.android.systemui.settings.CurrentUserTracker;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 */
@Singleton
public class DeviceProvisionedControllerImpl extends CurrentUserTracker implements
        DeviceProvisionedController {

    private final ArrayList<DeviceProvisionedListener> mListeners = new ArrayList<>();
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final Uri mDeviceProvisionedUri;
    private final Uri mUserSetupUri;
    protected final ContentObserver mSettingsObserver;

    /**
     */
    @Inject
    public DeviceProvisionedControllerImpl(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        super(context);
        mContext = context;
        mContentResolver = context.getContentResolver();
        mDeviceProvisionedUri = Global.getUriFor(Global.DEVICE_PROVISIONED);
        mUserSetupUri = Secure.getUriFor(Secure.USER_SETUP_COMPLETE);
        mSettingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
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
        return Global.getInt(mContentResolver, Global.DEVICE_PROVISIONED, 0) != 0;
    }

    @Override
    public boolean isUserSetup(int currentUser) {
        return Secure.getIntForUser(mContentResolver, Secure.USER_SETUP_COMPLETE, 0, currentUser)
                != 0;
    }

    @Override
    public int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    @Override
    public void addCallback(DeviceProvisionedListener listener) {
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            startListening(getCurrentUser());
        }
        listener.onUserSetupChanged();
        listener.onDeviceProvisionedChanged();
    }

    @Override
    public void removeCallback(DeviceProvisionedListener listener) {
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            stopListening();
        }
    }

    private void startListening(int user) {
        mContentResolver.registerContentObserver(mDeviceProvisionedUri, true,
                mSettingsObserver, 0);
        mContentResolver.registerContentObserver(mUserSetupUri, true,
                mSettingsObserver, user);
        startTracking();
    }

    private void stopListening() {
        stopTracking();
        mContentResolver.unregisterContentObserver(mSettingsObserver);
    }

    @Override
    public void onUserSwitched(int newUserId) {
        mContentResolver.unregisterContentObserver(mSettingsObserver);
        mContentResolver.registerContentObserver(mDeviceProvisionedUri, true,
                mSettingsObserver, 0);
        mContentResolver.registerContentObserver(mUserSetupUri, true,
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
