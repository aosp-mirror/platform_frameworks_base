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

package com.android.systemui;

import android.util.Log;

import com.android.systemui.statusbar.phone.StatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Ensure a single status bar service implementation is running at all times, using the in-process
 * implementation according to the product config.
 */
public class SystemBars extends SystemUI {
    private static final String TAG = "SystemBars";
    private static final boolean DEBUG = false;
    private static final int WAIT_FOR_BARS_TO_DIE = 500;

    // in-process fallback implementation, per the product config
    private SystemUI mStatusBar;

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "start");
        createStatusBarFromConfig();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mStatusBar != null) {
            mStatusBar.dump(fd, pw, args);
        }
    }

    private void createStatusBarFromConfig() {
        if (DEBUG) Log.d(TAG, "createStatusBarFromConfig");
        final String clsName = mContext.getString(R.string.config_statusBarComponent);
        if (clsName == null || clsName.length() == 0) {
            throw andLog("No status bar component configured", null);
        }
        Class<?> cls = null;
        try {
            cls = mContext.getClassLoader().loadClass(clsName);
        } catch (Throwable t) {
            throw andLog("Error loading status bar component: " + clsName, t);
        }
        try {
            mStatusBar = (SystemUI) cls.newInstance();
        } catch (Throwable t) {
            throw andLog("Error creating status bar component: " + clsName, t);
        }
        mStatusBar.mContext = mContext;
        mStatusBar.mComponents = mComponents;
        if (mStatusBar instanceof StatusBar) {
            SystemUIFactory.getInstance().getRootComponent()
                    .getStatusBarInjector()
                    .createStatusBar((StatusBar) mStatusBar);
        }
        mStatusBar.start();
        if (DEBUG) Log.d(TAG, "started " + mStatusBar.getClass().getSimpleName());
    }

    private RuntimeException andLog(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        throw new RuntimeException(msg, t);
    }
}
