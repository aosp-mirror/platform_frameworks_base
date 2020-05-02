/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.policy;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BACK_GESTURE_EDGE_WIDTH;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;

/**
 * @hide
 */
public class GestureNavigationSettingsObserver extends ContentObserver {
    private Context mContext;
    private Runnable mOnChangeRunnable;
    private Handler mMainHandler;

    public GestureNavigationSettingsObserver(Handler handler, Context context,
            Runnable onChangeRunnable) {
        super(handler);
        mMainHandler = handler;
        mContext = context;
        mOnChangeRunnable = onChangeRunnable;
    }

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            if (DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())
                            && mOnChangeRunnable != null) {
                mOnChangeRunnable.run();
            }
        }
    };

    public void register() {
        ContentResolver r = mContext.getContentResolver();
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.BACK_GESTURE_INSET_SCALE_LEFT),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.BACK_GESTURE_INSET_SCALE_RIGHT),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE),
                false, this, UserHandle.USER_ALL);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                runnable -> mMainHandler.post(runnable),
                mOnPropertiesChangedListener);
    }

    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
    }

    @Override
    public void onChange(boolean selfChange) {
        super.onChange(selfChange);
        if (mOnChangeRunnable != null) {
            mOnChangeRunnable.run();
        }
    }

    public int getLeftSensitivity(Resources userRes) {
        return getSensitivity(userRes, Settings.Secure.BACK_GESTURE_INSET_SCALE_LEFT);
    }

    public int getRightSensitivity(Resources userRes) {
        return getSensitivity(userRes, Settings.Secure.BACK_GESTURE_INSET_SCALE_RIGHT);
    }

    public boolean areNavigationButtonForcedVisible() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) == 0;
    }

    private int getSensitivity(Resources userRes, String side) {
        final DisplayMetrics dm = userRes.getDisplayMetrics();
        final float defaultInset = userRes.getDimension(
                com.android.internal.R.dimen.config_backGestureInset) / dm.density;
        final float backGestureInset = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                BACK_GESTURE_EDGE_WIDTH, defaultInset);
        final float inset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, backGestureInset,
                dm);
        final float scale = Settings.Secure.getFloatForUser(
                mContext.getContentResolver(), side, 1.0f, UserHandle.USER_CURRENT);
        return (int) (inset * scale);
    }
}
