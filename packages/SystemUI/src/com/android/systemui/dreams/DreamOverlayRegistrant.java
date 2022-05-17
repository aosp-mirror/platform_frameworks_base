/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;

/**
 * {@link DreamOverlayRegistrant} is responsible for telling system server that SystemUI should be
 * the designated dream overlay component.
 */
public class DreamOverlayRegistrant extends CoreStartable {
    private static final String TAG = "DreamOverlayRegistrant";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private final IDreamManager mDreamManager;
    private final ComponentName mOverlayServiceComponent;
    private final Resources mResources;
    private boolean mCurrentRegisteredState = false;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "package changed receiver - onReceive");
            }

            registerOverlayService();
        }
    };

    private void registerOverlayService() {
        // Check to see if the service has been disabled by the user. In this case, we should not
        // proceed modifying the enabled setting.
        final PackageManager packageManager = mContext.getPackageManager();
        final int enabledState =
                packageManager.getComponentEnabledSetting(mOverlayServiceComponent);


        // TODO(b/204626521): We should not have to set the component enabled setting if the
        // enabled config flag is properly applied based on the RRO.
        if (enabledState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            final int overlayState = mResources.getBoolean(R.bool.config_dreamOverlayServiceEnabled)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

            if (overlayState != enabledState) {
                packageManager
                        .setComponentEnabledSetting(mOverlayServiceComponent, overlayState, 0);
            }
        }

        // The overlay service is only registered when its component setting is enabled.
        boolean register = packageManager.getComponentEnabledSetting(mOverlayServiceComponent)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        if (mCurrentRegisteredState == register) {
            return;
        }

        mCurrentRegisteredState = register;

        try {
            if (DEBUG) {
                Log.d(TAG, mCurrentRegisteredState
                        ? "registering dream overlay service:" + mOverlayServiceComponent
                        : "clearing dream overlay service");
            }

            mDreamManager.registerDreamOverlayService(
                    mCurrentRegisteredState ? mOverlayServiceComponent : null);
        } catch (RemoteException e) {
            Log.e(TAG, "could not register dream overlay service:" + e);
        }
    }

    @Inject
    public DreamOverlayRegistrant(Context context, @Main Resources resources) {
        super(context);
        mResources = resources;
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));
        mOverlayServiceComponent = new ComponentName(mContext, DreamOverlayService.class);
    }

    @Override
    public void start() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mOverlayServiceComponent.getPackageName(),
                PatternMatcher.PATTERN_LITERAL);
        // Note that we directly register the receiver here as data schemes are not supported by
        // BroadcastDispatcher.
        mContext.registerReceiver(mReceiver, filter);

        registerOverlayService();
    }
}
