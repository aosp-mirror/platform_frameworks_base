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

package com.android.systemui.recents;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;

import com.android.systemui.CoreStartable;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.PrintWriter;

/**
 * A proxy to a Recents implementation.
 */
public class Recents implements
        CoreStartable,
        ConfigurationController.ConfigurationListener,
        CommandQueue.Callbacks {

    private final Context mContext;
    private final RecentsImplementation mImpl;
    private final CommandQueue mCommandQueue;

    public Recents(Context context, RecentsImplementation impl, CommandQueue commandQueue) {
        mContext = context;
        mImpl = impl;
        mCommandQueue = commandQueue;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mImpl.onStart(mContext);
    }

    @Override
    public void onBootCompleted() {
        mImpl.onBootCompleted();
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        mImpl.onConfigurationChanged(newConfig);
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (mContext.getDisplayId() == displayId) {
            mImpl.onAppTransitionFinished();
        }
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.showRecentApps(triggeredFromAltTab);
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey);
    }

    @Override
    public void toggleRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.toggleRecentApps();
    }

    @Override
    public void preloadRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.preloadRecentApps();
    }

    @Override
    public void cancelPreloadRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.cancelPreloadRecentApps();
    }

    /**
     * @return whether this device is provisioned and the current user is set up.
     */
    private boolean isUserSetup() {
        ContentResolver cr = mContext.getContentResolver();
        return (Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) != 0) &&
                (Settings.Secure.getInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        mImpl.dump(pw);
    }
}
