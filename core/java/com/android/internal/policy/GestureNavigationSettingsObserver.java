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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.android.internal.util.xtended.XtendedUtils;

/**
 * @hide
 */
public class GestureNavigationSettingsObserver extends ContentObserver {
    private Context mContext;
    private Runnable mOnChangeRunnable;
    private Handler mMainHandler;
    private IntentFilter mIntentFilter;

    public GestureNavigationSettingsObserver(Handler handler, Context context,
            Runnable onChangeRunnable) {
        super(handler);
        mMainHandler = handler;
        mContext = context;
        mOnChangeRunnable = onChangeRunnable;
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mIntentFilter.addDataScheme("package");
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

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                // Get packageName from Uri
                String packageName = intent.getData().getSchemeSpecificPart();
                // If the package is still installed
                if (XtendedUtils.isPackageInstalled(context, packageName)) {
                    // it's an application update, we can skip the rest.
                    return;
                }
                // Get package names currently set as default
                String leftPackageName = Settings.System.getStringForUser(context.getContentResolver(),
                        Settings.System.LEFT_LONG_BACK_SWIPE_APP_ACTION,
                        UserHandle.USER_CURRENT);
                String rightPackageName = Settings.System.getStringForUser(context.getContentResolver(),
                        Settings.System.RIGHT_LONG_BACK_SWIPE_APP_ACTION,
                        UserHandle.USER_CURRENT);
                String verticalLeftPackageName = Settings.System.getStringForUser(context.getContentResolver(),
                        Settings.System.LEFT_VERTICAL_BACK_SWIPE_APP_ACTION,
                        UserHandle.USER_CURRENT);
                String verticalRightPackageName = Settings.System.getStringForUser(context.getContentResolver(),
                        Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_ACTION,
                        UserHandle.USER_CURRENT);
                // if the package name equals to some set value
                if(packageName.equals(leftPackageName)) {
                    // The short application action has to be reset
                    resetApplicationAction(true, false);
                }
                if (packageName.equals(rightPackageName)) {
                    // The long application action has to be reset
                    resetApplicationAction(false, false);
                }
                if(packageName.equals(verticalLeftPackageName)) {
                    // The short application action has to be reset
                    resetApplicationAction(true, true);
                }
                if (packageName.equals(verticalRightPackageName)) {
                    // The long application action has to be reset
                    resetApplicationAction(false, true);
                }
            }
        }
    };

    private void resetApplicationAction(boolean isLeftAction, boolean isVertical) {
        if (isLeftAction) {
            // Remove stored values
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    isVertical ? Settings.System.LEFT_VERTICAL_BACK_SWIPE_ACTION : Settings.System.LEFT_LONG_BACK_SWIPE_ACTION,
                    /* no action */ 0,
                    UserHandle.USER_CURRENT);
            Settings.System.putStringForUser(mContext.getContentResolver(),
                    isVertical ? Settings.System.LEFT_VERTICAL_BACK_SWIPE_APP_FR_ACTION : Settings.System.LEFT_LONG_BACK_SWIPE_APP_FR_ACTION,
                    /* none */ "",
                    UserHandle.USER_CURRENT);
        } else {
            // Remove stored values
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    isVertical ? Settings.System.RIGHT_VERTICAL_BACK_SWIPE_ACTION : Settings.System.RIGHT_LONG_BACK_SWIPE_ACTION,
                    /* no action */ 0,
                    UserHandle.USER_CURRENT);
            Settings.System.putStringForUser(mContext.getContentResolver(),
                    isVertical ? Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_FR_ACTION : Settings.System.RIGHT_LONG_BACK_SWIPE_APP_FR_ACTION,
                    /* none */ "",
                    UserHandle.USER_CURRENT);
        }
        // the observer will trigger EdgeBackGestureHandler.updateCurrentUserResources and update settings there too
    }

    public void register() {
        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
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
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.BACK_GESTURE_HEIGHT_LEFT),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.BACK_GESTURE_HEIGHT_RIGHT),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(Settings.System.getUriFor(Settings.System.BACK_GESTURE_HAPTIC),
                false, this, UserHandle.USER_ALL);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                runnable -> mMainHandler.post(runnable),
                mOnPropertiesChangedListener);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.LONG_BACK_SWIPE_TIMEOUT),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.LEFT_LONG_BACK_SWIPE_ACTION),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.RIGHT_LONG_BACK_SWIPE_ACTION),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.BACK_SWIPE_EXTENDED),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.LEFT_VERTICAL_BACK_SWIPE_ACTION),
                false, this, UserHandle.USER_ALL);
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.RIGHT_VERTICAL_BACK_SWIPE_ACTION),
                false, this, UserHandle.USER_ALL);
    }

    public void unregister() {
        mContext.unregisterReceiver(mBroadcastReceiver);
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

    public boolean getEdgeHaptic() {
        return Settings.System.getIntForUser(
               mContext.getContentResolver(), Settings.System.BACK_GESTURE_HAPTIC, 0,
               UserHandle.USER_CURRENT) == 1 &&
               Settings.System.getIntForUser(
                   mContext.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 0,
                   UserHandle.USER_CURRENT) == 1;
    }

    public boolean areNavigationButtonForcedVisible() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) == 0;
    }

    private int getSensitivity(Resources userRes, String side) {
        final DisplayMetrics dm = userRes.getDisplayMetrics();
        final float defaultInset = userRes.getDimension(
                com.android.internal.R.dimen.config_backGestureInset) / dm.density;
        // Only apply the back gesture config if there is an existing inset
        final float backGestureInset = defaultInset > 0
                ? DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                        BACK_GESTURE_EDGE_WIDTH, defaultInset)
                : defaultInset;
        final float inset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, backGestureInset,
                dm);
        final float scale = Settings.Secure.getFloatForUser(
                mContext.getContentResolver(), side, 1.0f, UserHandle.USER_CURRENT);
        return (int) (inset * scale);
    }

    public float getLeftHeight() {
        return Settings.Secure.getFloatForUser(
                        mContext.getContentResolver(), Settings.Secure.BACK_GESTURE_HEIGHT_LEFT,
                        1.0f, UserHandle.USER_CURRENT);
    }

    public float getRightHeight() {
        return Settings.Secure.getFloatForUser(
                        mContext.getContentResolver(), Settings.Secure.BACK_GESTURE_HEIGHT_RIGHT,
                        1.0f, UserHandle.USER_CURRENT);
    }

    public int getLongSwipeTimeOut() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.LONG_BACK_SWIPE_TIMEOUT, 2000,
            UserHandle.USER_CURRENT);
    }

    public int getLeftLongSwipeAction() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.LEFT_LONG_BACK_SWIPE_ACTION, 0,
            UserHandle.USER_CURRENT);
    }

    public int getRightLongSwipeAction() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RIGHT_LONG_BACK_SWIPE_ACTION, 0,
            UserHandle.USER_CURRENT);
    }

    public boolean getIsExtendedSwipe() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.BACK_SWIPE_EXTENDED, 0,
            UserHandle.USER_CURRENT) != 0;
    }

    public int getLeftLSwipeAction() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.LEFT_VERTICAL_BACK_SWIPE_ACTION, 0,
            UserHandle.USER_CURRENT);
    }

    public int getRightLSwipeAction() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RIGHT_VERTICAL_BACK_SWIPE_ACTION, 0,
            UserHandle.USER_CURRENT);
    }
}
