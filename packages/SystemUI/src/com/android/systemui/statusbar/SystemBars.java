/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.res.Configuration;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Ensure a single status bar service implementation is running at all times.
 *
 * <p>The implementation either comes from a service component running in a remote process (defined
 * using a secure setting), else falls back to using the in-process implementation according
 * to the product config.
 */
public class SystemBars extends SystemUI implements ServiceMonitor.Callbacks {
    private static final String TAG = "SystemBars";
    private static final boolean DEBUG = false;
    private static final int WAIT_FOR_BARS_TO_DIE = 500;

    // manages the implementation coming from the remote process
    private ServiceMonitor mServiceMonitor;

    // in-process fallback implementation, per the product config
    private BaseStatusBar mStatusBar;

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "start");
        mServiceMonitor = new ServiceMonitor(TAG, DEBUG,
                mContext, Settings.Secure.BAR_SERVICE_COMPONENT, this);
        mServiceMonitor.start();  // will call onNoService if no remote service is found
    }

    @Override
    public void onNoService() {
        if (DEBUG) Log.d(TAG, "onNoService");
        createStatusBarFromConfig();  // fallback to using an in-process implementation
    }

    @Override
    public long onServiceStartAttempt() {
        if (DEBUG) Log.d(TAG, "onServiceStartAttempt mStatusBar="+mStatusBar);
        if (mStatusBar != null) {
            // tear down the in-process version, we'll recreate it again if needed
            mStatusBar.destroy();
            mStatusBar = null;
            return WAIT_FOR_BARS_TO_DIE;
        }
        return 0;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        if (mStatusBar != null) {
            mStatusBar.onConfigurationChanged(newConfig);
        }
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
            mStatusBar = (BaseStatusBar) cls.newInstance();
        } catch (Throwable t) {
            throw andLog("Error creating status bar component: " + clsName, t);
        }
        mStatusBar.mContext = mContext;
        mStatusBar.mComponents = mComponents;
        mStatusBar.start();
        if (DEBUG) Log.d(TAG, "started " + mStatusBar.getClass().getSimpleName());
    }

    private RuntimeException andLog(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        throw new RuntimeException(msg, t);
    }
}
