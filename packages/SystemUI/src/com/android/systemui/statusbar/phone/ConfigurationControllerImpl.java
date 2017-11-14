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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.systemui.ConfigurationChangedReceiver;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ConfigurationControllerImpl implements ConfigurationController,
        ConfigurationChangedReceiver {

    private final ArrayList<ConfigurationListener> mListeners = new ArrayList<>();
    private final Configuration mLastConfig = new Configuration();
    private int mDensity;
    private float mFontScale;
    private boolean mInCarMode;
    private int mUiMode;
    private LocaleList mLocaleList;

    public ConfigurationControllerImpl(Context context) {
        Configuration currentConfig = context.getResources().getConfiguration();
        mFontScale = currentConfig.fontScale;
        mDensity = currentConfig.densityDpi;
        mInCarMode = (currentConfig.uiMode  & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_CAR;
        mUiMode = currentConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mLocaleList = currentConfig.getLocales();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Avoid concurrent modification exception
        ArrayList<ConfigurationListener> listeners = new ArrayList<>(mListeners);

        listeners.forEach(l -> {
            if (mListeners.contains(l)) {
                l.onConfigChanged(newConfig);
            }
        });
        final float fontScale = newConfig.fontScale;
        final int density = newConfig.densityDpi;
        int uiMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (density != mDensity || fontScale != mFontScale
                || (mInCarMode && uiMode != mUiMode)) {
            listeners.forEach(l -> {
                if (mListeners.contains(l)) {
                    l.onDensityOrFontScaleChanged();
                }
            });
            mDensity = density;
            mFontScale = fontScale;
            mUiMode = uiMode;
        }

        final LocaleList localeList = newConfig.getLocales();
        if (!localeList.equals(mLocaleList)) {
            mLocaleList = localeList;
            listeners.forEach(l -> {
                if (mListeners.contains(l)) {
                    l.onLocaleListChanged();
                }
            });
        }

        if ((mLastConfig.updateFrom(newConfig) & ActivityInfo.CONFIG_ASSETS_PATHS) != 0) {
                listeners.forEach(l -> {
                    if (mListeners.contains(l)) {
                        l.onOverlayChanged();
                    }
                });
        }
    }

    @Override
    public void addCallback(ConfigurationListener listener) {
        mListeners.add(listener);
        listener.onDensityOrFontScaleChanged();
    }

    @Override
    public void removeCallback(ConfigurationListener listener) {
        mListeners.remove(listener);
    }
}
