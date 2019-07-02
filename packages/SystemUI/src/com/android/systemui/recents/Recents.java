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
import android.content.res.Configuration;
import android.graphics.Rect;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A proxy to a Recents implementation.
 */
public class Recents extends SystemUI implements CommandQueue.Callbacks {

    private RecentsImplementation mImpl;

    @Override
    public void start() {
        getComponent(CommandQueue.class).addCallback(this);
        putComponent(Recents.class, this);
        mImpl = createRecentsImplementationFromConfig();
        mImpl.onStart(mContext, this);
    }

    @Override
    public void onBootCompleted() {
        mImpl.onBootCompleted();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mImpl.onConfigurationChanged(newConfig);
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (mContext.getDisplayId() == displayId) {
            mImpl.onAppTransitionFinished();
        }
    }

    public void growRecents() {
        mImpl.growRecents();
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

    public boolean splitPrimaryTask(int stackCreateMode, Rect initialBounds,
            int metricsDockAction) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return false;
        }

        return mImpl.splitPrimaryTask(stackCreateMode, initialBounds, metricsDockAction);
    }

    /**
     * @return whether this device is provisioned and the current user is set up.
     */
    private boolean isUserSetup() {
        ContentResolver cr = mContext.getContentResolver();
        return (Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) != 0) &&
                (Settings.Secure.getInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    /**
     * @return The recents implementation from the config.
     */
    private RecentsImplementation createRecentsImplementationFromConfig() {
        final String clsName = mContext.getString(R.string.config_recentsComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No recents component configured", null);
        }
        Class<?> cls = null;
        try {
            cls = mContext.getClassLoader().loadClass(clsName);
        } catch (Throwable t) {
            throw new RuntimeException("Error loading recents component: " + clsName, t);
        }
        try {
            RecentsImplementation impl = (RecentsImplementation) cls.newInstance();
            return impl;
        } catch (Throwable t) {
            throw new RuntimeException("Error creating recents component: " + clsName, t);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mImpl.dump(pw);
    }
}
