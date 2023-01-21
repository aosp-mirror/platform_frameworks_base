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
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.statusbar.phone.CentralSurfaces;

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
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;

    private Handler mHandler;
    private final OverviewProxyService mOverviewProxyService;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            OverviewProxyService overviewProxyService) {
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mOverviewProxyService = overviewProxyService;
    }

    @Override
    public void onStart(Context context) {
        mHandler = new Handler();
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewShown(triggeredFromAltTab);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
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
            final Optional<CentralSurfaces> centralSurfacesOptional =
                    mCentralSurfacesOptionalLazy.get();
            if (centralSurfacesOptional.map(CentralSurfaces::isKeyguardShowing).orElse(false)) {
                centralSurfacesOptional.get().executeRunnableDismissingKeyguard(
                        () -> mHandler.post(toggleRecents), null, true /* dismissShade */,
                        false /* afterKeyguardGone */,
                        true /* deferred */);
            } else {
                toggleRecents.run();
            }
        }
    }
}
