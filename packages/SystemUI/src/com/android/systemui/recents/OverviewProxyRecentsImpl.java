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

import android.annotation.Nullable;
import android.app.trust.TrustManager;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * An implementation of the Recents interface which proxies to the OverviewProxyService.
 */
@SysUISingleton
public class OverviewProxyRecentsImpl implements RecentsImplementation {

    private final static String TAG = "OverviewProxyRecentsImpl";
    @Nullable
    private final Lazy<StatusBar> mStatusBarLazy;

    private Context mContext;
    private Handler mHandler;
    private TrustManager mTrustManager;
    private OverviewProxyService mOverviewProxyService;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(Optional<Lazy<StatusBar>> statusBarLazy) {
        mStatusBarLazy = statusBarLazy.orElse(null);
    }

    @Override
    public void onStart(Context context) {
        mContext = context;
        mHandler = new Handler();
        mTrustManager = (TrustManager) context.getSystemService(Context.TRUST_SERVICE);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewShown(triggeredFromAltTab);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        } else {
            // Do nothing
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        } else {
            // Do nothing
        }
    }

    @Override
    public void toggleRecentApps() {
        // If connected to launcher service, let it handle the toggle logic
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mOverviewProxyService.getProxy() != null) {
                        mOverviewProxyService.getProxy().onOverviewToggle();
                        mOverviewProxyService.notifyToggleRecentApps();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            if (mStatusBarLazy != null && mStatusBarLazy.get().isKeyguardShowing()) {
                mStatusBarLazy.get().executeRunnableDismissingKeyguard(() -> {
                        // Flush trustmanager before checking device locked per user
                        mTrustManager.reportKeyguardShowingChanged();
                        mHandler.post(toggleRecents);
                    }, null,  true /* dismissShade */, false /* afterKeyguardGone */,
                    true /* deferred */);
            } else {
                toggleRecents.run();
            }
            return;
        } else {
            // Do nothing
        }
    }
}
