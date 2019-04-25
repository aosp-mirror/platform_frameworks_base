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

package com.android.systemui.statusbar.phone;

import static android.content.Intent.ACTION_OVERLAY_CHANGED;
import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.os.PatternMatcher;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.Dumpable;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller for tracking the current navigation bar mode.
 */
@Singleton
public class NavigationModeController implements Dumpable {

    private static final String TAG = NavigationModeController.class.getName();
    private static final boolean DEBUG = true;

    public interface ModeChangedListener {
        void onNavigationModeChanged(int mode);
    }

    private final Context mContext;
    private final IOverlayManager mOverlayManager;

    private int mMode = NAV_BAR_MODE_3BUTTON;
    private ArrayList<ModeChangedListener> mListeners = new ArrayList<>();

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            context = getCurrentUserContext();
            int mode = getCurrentInteractionMode(context);
            mMode = mode;
            if (DEBUG) {
                Log.e(TAG, "ACTION_OVERLAY_CHANGED: mode=" + mMode
                        + " contextUser=" + context.getUserId());
                dumpAssetPaths(context);
            }

            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onNavigationModeChanged(mode);
            }
        }
    };

    @Inject
    public NavigationModeController(Context context) {
        mContext = context;
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));

        IntentFilter overlayFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        overlayFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, overlayFilter, null, null);

        IntentFilter userFilter = new IntentFilter(ACTION_USER_SWITCHED);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, userFilter, null, null);

        mMode = getCurrentInteractionMode(getCurrentUserContext());
    }

    public int addListener(ModeChangedListener listener) {
        mListeners.add(listener);
        return getCurrentInteractionMode(mContext);
    }

    public void removeListener(ModeChangedListener listener) {
        mListeners.remove(listener);
    }

    private int getCurrentInteractionMode(Context context) {
        int mode = context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        return mode;
    }

    private Context getCurrentUserContext() {
        int userId = ActivityManagerWrapper.getInstance().getCurrentUserId();
        if (DEBUG) {
            Log.d(TAG, "getCurrentUserContext: contextUser=" + mContext.getUserId()
                    + " currentUser=" + userId);
        }
        if (mContext.getUserId() == userId) {
            return mContext;
        }
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(),
                    0 /* flags */, UserHandle.of(userId));
        } catch (PackageManager.NameNotFoundException e) {
            // Never happens for the sysui package
            return null;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationModeController:");
        pw.println("  mode=" + mMode);
        dumpAssetPaths(getCurrentUserContext());
    }

    private void dumpAssetPaths(Context context) {
        Log.d(TAG, "assetPaths=");
        ApkAssets[] assets = context.getResources().getAssets().getApkAssets();
        for (ApkAssets a : assets) {
            Log.d(TAG, "    " + a.getAssetPath());
        }
    }
}
