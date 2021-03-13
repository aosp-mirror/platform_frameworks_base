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

package com.android.systemui.qs;

import android.app.AlarmManager.AlarmClockInfo;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyChipEvent;
import com.android.systemui.privacy.PrivacyDialogController;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.logging.PrivacyLogger;
import com.android.systemui.qs.carrier.QSCarrierGroupController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeController.Callback;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller for {@link QuickStatusBarHeader}.
 */
@QSScope
class QuickStatusBarHeaderController extends ViewController<QuickStatusBarHeader> {
    private static final String TAG = "QuickStatusBarHeader";

    private final ZenModeController mZenModeController;
    private final NextAlarmController mNextAlarmController;
    private final PrivacyItemController mPrivacyItemController;
    private final RingerModeTracker mRingerModeTracker;
    private final ActivityStarter mActivityStarter;
    private final UiEventLogger mUiEventLogger;
    private final QSCarrierGroupController mQSCarrierGroupController;
    private final QuickQSPanelController mHeaderQsPanelController;
    private final LifecycleRegistry mLifecycle;
    private final OngoingPrivacyChip mPrivacyChip;
    private final Clock mClockView;
    private final View mNextAlarmContainer;
    private final View mRingerContainer;
    private final QSTileHost mQSTileHost;
    private final StatusBarIconController mStatusBarIconController;
    private final CommandQueue mCommandQueue;
    private final DemoModeController mDemoModeController;
    private final UserTracker mUserTracker;
    private final StatusIconContainer mIconContainer;
    private final StatusBarIconController.TintedIconManager mIconManager;
    private final DemoMode mDemoModeReceiver;
    private final PrivacyLogger mPrivacyLogger;
    private final PrivacyDialogController mPrivacyDialogController;

    private boolean mListening;
    private AlarmClockInfo mNextAlarm;
    private boolean mMicCameraIndicatorsEnabled;
    private boolean mLocationIndicatorsEnabled;
    private boolean mPrivacyChipLogged;

    private SysuiColorExtractor mColorExtractor;
    private ColorExtractor.OnColorsChangedListener mOnColorsChangedListener;
    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;

    private final ZenModeController.Callback mZenModeControllerCallback = new Callback() {
        @Override
        public void onZenChanged(int zen) {
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger(),
                    use24HourFormat());
        }

        @Override
        public void onConfigChanged(ZenModeConfig config) {
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger(),
                    use24HourFormat());
        }
    };

    private boolean use24HourFormat() {
        return android.text.format.DateFormat.is24HourFormat(
                mView.getContext(), mUserTracker.getUserId());

    }

    private final NextAlarmChangeCallback mNextAlarmChangeCallback = new NextAlarmChangeCallback() {
        @Override
        public void onNextAlarmChanged(AlarmClockInfo nextAlarm) {
            mNextAlarm = nextAlarm;
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger(),
                    use24HourFormat());
        }
    };

    private final LifecycleOwner mLifecycleOwner = new LifecycleOwner() {
        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mLifecycle;
        }
    };

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void onPrivacyItemsChanged(@NonNull List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }

        @Override
        public void onFlagMicCameraChanged(boolean flag) {
            if (mMicCameraIndicatorsEnabled != flag) {
                mMicCameraIndicatorsEnabled = flag;
                update();
            }
        }

        @Override
        public void onFlagLocationChanged(boolean flag) {
            if (mLocationIndicatorsEnabled != flag) {
                mLocationIndicatorsEnabled = flag;
                update();
            }
        }

        private void update() {
            StatusIconContainer iconContainer = mView.requireViewById(R.id.statusIcons);
            iconContainer.setIgnoredSlots(getIgnoredIconSlots());
            setChipVisibility(!mPrivacyChip.getPrivacyList().isEmpty());
        }
    };

    private View.OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v == mClockView) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
                if (mNextAlarm.getShowIntent() != null) {
                    mActivityStarter.postStartActivityDismissingKeyguard(
                            mNextAlarm.getShowIntent());
                } else {
                    Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                    mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                            AlarmClock.ACTION_SHOW_ALARMS), 0);
                }
            } else if (v == mPrivacyChip) {
                // If the privacy chip is visible, it means there were some indicators
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_CLICK);
                mPrivacyDialogController.showDialog(getContext());
            } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        Settings.ACTION_SOUND_SETTINGS), 0);
            }
        }
    };

    @Inject
    QuickStatusBarHeaderController(QuickStatusBarHeader view,
            ZenModeController zenModeController, NextAlarmController nextAlarmController,
            PrivacyItemController privacyItemController, RingerModeTracker ringerModeTracker,
            ActivityStarter activityStarter, UiEventLogger uiEventLogger,
            QSTileHost qsTileHost, StatusBarIconController statusBarIconController,
            CommandQueue commandQueue, DemoModeController demoModeController,
            UserTracker userTracker, QuickQSPanelController quickQSPanelController,
            QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder,
            PrivacyLogger privacyLogger,
            SysuiColorExtractor colorExtractor,
            PrivacyDialogController privacyDialogController) {
        super(view);
        mZenModeController = zenModeController;
        mNextAlarmController = nextAlarmController;
        mPrivacyItemController = privacyItemController;
        mRingerModeTracker = ringerModeTracker;
        mActivityStarter = activityStarter;
        mUiEventLogger = uiEventLogger;
        mQSTileHost = qsTileHost;
        mStatusBarIconController = statusBarIconController;
        mCommandQueue = commandQueue;
        mDemoModeController = demoModeController;
        mUserTracker = userTracker;
        mLifecycle = new LifecycleRegistry(mLifecycleOwner);
        mHeaderQsPanelController = quickQSPanelController;
        mPrivacyLogger = privacyLogger;
        mPrivacyDialogController = privacyDialogController;

        mQSCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(mView.findViewById(R.id.carrier_group))
                .build();


        mPrivacyChip = mView.findViewById(R.id.privacy_chip);
        mNextAlarmContainer = mView.findViewById(R.id.alarm_container);
        mClockView = mView.findViewById(R.id.clock);
        mRingerContainer = mView.findViewById(R.id.ringer_container);
        mIconContainer = mView.findViewById(R.id.statusIcons);

        mIconManager = new StatusBarIconController.TintedIconManager(mIconContainer, mCommandQueue);
        mDemoModeReceiver = new ClockDemoModeReceiver(mClockView);
        mColorExtractor = colorExtractor;
        mOnColorsChangedListener = (extractor, which) -> {
            final boolean lightTheme = mColorExtractor.getNeutralColors().supportsDarkText();
            mClockView.onColorsChanged(lightTheme);
        };
        mColorExtractor.addOnColorsChangedListener(mOnColorsChangedListener);
    }

    @Override
    protected void onViewAttached() {
        mRingerModeTracker.getRingerModeInternal().observe(mLifecycleOwner, ringer -> {
            mRingerMode = ringer;
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger(),
                    use24HourFormat());
        });

        mClockView.setOnClickListener(mOnClickListener);
        mNextAlarmContainer.setOnClickListener(mOnClickListener);
        mRingerContainer.setOnClickListener(mOnClickListener);
        mPrivacyChip.setOnClickListener(mOnClickListener);

        mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
        mLocationIndicatorsEnabled = mPrivacyItemController.getLocationAvailable();

        // Ignore privacy icons because they show in the space above QQS
        mIconContainer.setIgnoredSlots(getIgnoredIconSlots());
        mIconContainer.setShouldRestrictIcons(false);
        mStatusBarIconController.addIconGroup(mIconManager);

        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);

        mView.onAttach(mIconManager);

        mDemoModeController.addCallback(mDemoModeReceiver);
    }

    @Override
    protected void onViewDetached() {
        mRingerModeTracker.getRingerModeInternal().removeObservers(mLifecycleOwner);
        mClockView.setOnClickListener(null);
        mColorExtractor.removeOnColorsChangedListener(mOnColorsChangedListener);
        mNextAlarmContainer.setOnClickListener(null);
        mRingerContainer.setOnClickListener(null);
        mPrivacyChip.setOnClickListener(null);
        mStatusBarIconController.removeIconGroup(mIconManager);
        mDemoModeController.removeCallback(mDemoModeReceiver);
        setListening(false);
    }

    public void setListening(boolean listening) {
        mQSCarrierGroupController.setListening(listening);

        if (listening == mListening) {
            return;
        }
        mListening = listening;

        mHeaderQsPanelController.setListening(listening);
        if (mHeaderQsPanelController.isListening()) {
            mHeaderQsPanelController.refreshAllTiles();
        }

        if (mHeaderQsPanelController.switchTileLayout(false)) {
            mView.updateResources();
        }

        if (listening) {
            mZenModeController.addCallback(mZenModeControllerCallback);
            mNextAlarmController.addCallback(mNextAlarmChangeCallback);
            mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
            // Get the most up to date info
            mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
            mLocationIndicatorsEnabled = mPrivacyItemController.getLocationAvailable();
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mZenModeController.removeCallback(mZenModeControllerCallback);
            mNextAlarmController.removeCallback(mNextAlarmChangeCallback);
            mLifecycle.setCurrentState(Lifecycle.State.CREATED);
            mPrivacyItemController.removeCallback(mPICCallback);
            mPrivacyChipLogged = false;
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && getChipEnabled()) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            mPrivacyLogger.logChipVisible(true);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_VIEW);
            }
        } else {
            mPrivacyLogger.logChipVisible(false);
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        if (getChipEnabled()) {
            if (mMicCameraIndicatorsEnabled) {
                ignored.add(mView.getResources().getString(
                        com.android.internal.R.string.status_bar_camera));
                ignored.add(mView.getResources().getString(
                        com.android.internal.R.string.status_bar_microphone));
            }
            if (mLocationIndicatorsEnabled) {
                ignored.add(mView.getResources().getString(
                        com.android.internal.R.string.status_bar_location));
            }
        }
        return ignored;
    }

    private boolean getChipEnabled() {
        return mMicCameraIndicatorsEnabled || mLocationIndicatorsEnabled;
    }

    private boolean isZenOverridingRinger() {
        return ZenModeConfig.isZenOverridingRinger(mZenModeController.getZen(),
                mZenModeController.getConsolidatedPolicy());
    }

    public void setContentMargins(int contentPaddingStart, int contentPaddingEnd) {
        mView.setContentMargins(contentPaddingStart, contentPaddingEnd, mHeaderQsPanelController);
    }


    private static class ClockDemoModeReceiver implements DemoMode {
        private Clock mClockView;

        @Override
        public List<String> demoCommands() {
            return List.of(COMMAND_CLOCK);
        }

        ClockDemoModeReceiver(Clock clockView) {
            mClockView = clockView;
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) {
            mClockView.dispatchDemoCommand(command, args);
        }

        @Override
        public void onDemoModeStarted() {
            mClockView.onDemoModeStarted();
        }

        @Override
        public void onDemoModeFinished() {
            mClockView.onDemoModeFinished();
        }
    }
}
