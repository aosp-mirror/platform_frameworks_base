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
package com.android.systemui.battery;

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.View;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link BatteryMeterView}. **/
public class BatteryMeterViewController extends ViewController<BatteryMeterView> {
    private final ConfigurationController mConfigurationController;
    private final TunerService mTunerService;
    private final ContentResolver mContentResolver;

    private final String mSlotBattery;
    private final SettingObserver mSettingObserver;
    private final CurrentUserTracker mCurrentUserTracker;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.scaleBatteryMeterViews();
                }
            };

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
                ArraySet<String> icons = StatusBarIconController.getIconHideList(
                        getContext(), newValue);
                mView.setVisibility(icons.contains(mSlotBattery) ? View.GONE : View.VISIBLE);
            }
        }
    };

    // Some places may need to show the battery conditionally, and not obey the tuner
    private boolean mIgnoreTunerUpdates;
    private boolean mIsSubscribedForTunerUpdates;

    @Inject
    public BatteryMeterViewController(
            BatteryMeterView view,
            ConfigurationController configurationController,
            TunerService tunerService,
            BroadcastDispatcher broadcastDispatcher,
            @Main Handler mainHandler,
            ContentResolver contentResolver) {
        super(view);
        mConfigurationController = configurationController;
        mTunerService = tunerService;
        mContentResolver = contentResolver;

        mSlotBattery = getResources().getString(com.android.internal.R.string.status_bar_battery);
        mSettingObserver = new SettingObserver(mainHandler);
        mCurrentUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                contentResolver.unregisterContentObserver(mSettingObserver);
                registerShowBatteryPercentObserver(newUserId);
                mView.updateShowPercent();
            }
        };
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        subscribeForTunerUpdates();

        registerShowBatteryPercentObserver(ActivityManager.getCurrentUser());
        registerGlobalBatteryUpdateObserver();
        mCurrentUserTracker.startTracking();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        unsubscribeFromTunerUpdates();
        mCurrentUserTracker.stopTracking();
        mContentResolver.unregisterContentObserver(mSettingObserver);
    }

    /**
     * Turn off {@link BatteryMeterView}'s subscribing to the tuner for updates, and thus avoid it
     * controlling its own visibility.
     */
    public void ignoreTunerUpdates() {
        mIgnoreTunerUpdates = true;
        unsubscribeFromTunerUpdates();
    }

    private void subscribeForTunerUpdates() {
        if (mIsSubscribedForTunerUpdates || mIgnoreTunerUpdates) {
            return;
        }

        mTunerService.addTunable(mTunable, StatusBarIconController.ICON_HIDE_LIST);
        mIsSubscribedForTunerUpdates = true;
    }

    private void unsubscribeFromTunerUpdates() {
        if (!mIsSubscribedForTunerUpdates) {
            return;
        }

        mTunerService.removeTunable(mTunable);
        mIsSubscribedForTunerUpdates = false;
    }

    private void registerShowBatteryPercentObserver(int user) {
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT),
                false,
                mSettingObserver,
                user);
    }

    private void registerGlobalBatteryUpdateObserver() {
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME),
                false,
                mSettingObserver);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            mView.updateShowPercent();
            if (TextUtils.equals(uri.getLastPathSegment(),
                    Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME)) {
                // update the text for sure if the estimate in the cache was updated
                mView.updatePercentText();
            }
        }
    }
}
