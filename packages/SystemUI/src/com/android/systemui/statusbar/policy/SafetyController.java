/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.safetycenter.SafetyCenterManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Background;

import java.util.ArrayList;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Controller which calls listeners when a PACKAGE_CHANGED broadcast is sent for the
 * PermissionController. These broadcasts may be because the PermissionController enabled or
 * disabled its TileService, and the tile should be added if the component was enabled, or removed
 * if disabled.
 */
public class SafetyController implements
        CallbackController<SafetyController.Listener> {
    private boolean mSafetyCenterEnabled;
    private final Handler mBgHandler;
    private final ArrayList<Listener> mListeners = new ArrayList<>();
    private static final IntentFilter PKG_CHANGE_INTENT_FILTER;
    private final Context mContext;
    private final SafetyCenterManager mSafetyCenterManager;
    private final PackageManager mPackageManager;

    static {
        PKG_CHANGE_INTENT_FILTER = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
        PKG_CHANGE_INTENT_FILTER.addDataScheme("package");
    }

    @Inject
    public SafetyController(Context context, PackageManager pm, SafetyCenterManager scm,
            @Background Handler bgHandler) {
        mContext = context;
        mSafetyCenterManager = scm;
        mPackageManager = pm;
        mBgHandler = bgHandler;
        mSafetyCenterEnabled = mSafetyCenterManager.isSafetyCenterEnabled();
    }

    public boolean isSafetyCenterEnabled() {
        return mSafetyCenterEnabled;
    }

    /**
     * Adds a listener, registering the broadcast receiver if need be. Immediately calls the
     * provided listener on the calling thread.
     */
    @Override
    public void addCallback(@NonNull Listener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
            if (mListeners.size() == 1) {
                mContext.registerReceiver(mPermControllerChangeReceiver, PKG_CHANGE_INTENT_FILTER);
                mBgHandler.post(() -> {
                    mSafetyCenterEnabled = mSafetyCenterManager.isSafetyCenterEnabled();
                    listener.onSafetyCenterEnableChanged(isSafetyCenterEnabled());
                });
            } else {
                listener.onSafetyCenterEnableChanged(isSafetyCenterEnabled());
            }
        }
    }

    @Override
    public void removeCallback(@NonNull Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
            if (mListeners.isEmpty()) {
                mContext.unregisterReceiver(mPermControllerChangeReceiver);
            }
        }
    }

    private void handleSafetyCenterEnableChange() {
        synchronized (mListeners) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onSafetyCenterEnableChanged(mSafetyCenterEnabled);
            }
        }
    }

    /**
     * Upon receiving a package changed broadcast for the PermissionController, checks if the
     * safety center is enabled or disabled, and sends an update on the main thread if the state
     * changed.
     */
    @VisibleForTesting
    final BroadcastReceiver mPermControllerChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData() != null ? intent.getData().getSchemeSpecificPart()
                    : null;
            if (!Objects.equals(packageName,
                    mPackageManager.getPermissionControllerPackageName())) {
                return;
            }

            boolean wasSafetyCenterEnabled = mSafetyCenterEnabled;
            mSafetyCenterEnabled = mSafetyCenterManager.isSafetyCenterEnabled();
            if (wasSafetyCenterEnabled == mSafetyCenterEnabled) {
                return;
            }

            mBgHandler.post(() -> handleSafetyCenterEnableChange());
        }
    };

    /**
     * Listener for safety center enabled changes
     */
    public interface Listener {
        /**
         * Callback to be notified when the safety center is enabled or disabled
         * @param isSafetyCenterEnabled If the safety center is enabled
         */
        void onSafetyCenterEnableChanged(boolean isSafetyCenterEnabled);
    }
}
