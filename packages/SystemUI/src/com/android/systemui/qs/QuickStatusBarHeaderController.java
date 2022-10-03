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

import android.os.Bundle;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.qs.carrier.QSCarrierGroupController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.VariableDateViewController;
import com.android.systemui.util.ViewController;

import java.util.List;

import javax.inject.Inject;

/**
 * Controller for {@link QuickStatusBarHeader}.
 */
@QSScope
class QuickStatusBarHeaderController extends ViewController<QuickStatusBarHeader> implements
        ChipVisibilityListener {

    private final QSCarrierGroupController mQSCarrierGroupController;
    private final QuickQSPanelController mQuickQSPanelController;
    private final Clock mClockView;
    private final StatusBarIconController mStatusBarIconController;
    private final DemoModeController mDemoModeController;
    private final StatusIconContainer mIconContainer;
    private final StatusBarIconController.TintedIconManager mIconManager;
    private final DemoMode mDemoModeReceiver;
    private final QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    private final FeatureFlags mFeatureFlags;
    private final BatteryMeterViewController mBatteryMeterViewController;
    private final StatusBarContentInsetsProvider mInsetsProvider;

    private final VariableDateViewController mVariableDateViewControllerDateView;
    private final VariableDateViewController mVariableDateViewControllerClockDateView;
    private final HeaderPrivacyIconsController mPrivacyIconsController;

    private boolean mListening;

    private SysuiColorExtractor mColorExtractor;
    private ColorExtractor.OnColorsChangedListener mOnColorsChangedListener;

    @Inject
    QuickStatusBarHeaderController(QuickStatusBarHeader view,
            HeaderPrivacyIconsController headerPrivacyIconsController,
            StatusBarIconController statusBarIconController,
            DemoModeController demoModeController,
            QuickQSPanelController quickQSPanelController,
            QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder,
            SysuiColorExtractor colorExtractor,
            QSExpansionPathInterpolator qsExpansionPathInterpolator,
            FeatureFlags featureFlags,
            VariableDateViewController.Factory variableDateViewControllerFactory,
            BatteryMeterViewController batteryMeterViewController,
            StatusBarContentInsetsProvider statusBarContentInsetsProvider,
            StatusBarIconController.TintedIconManager.Factory tintedIconManagerFactory) {
        super(view);
        mPrivacyIconsController = headerPrivacyIconsController;
        mStatusBarIconController = statusBarIconController;
        mDemoModeController = demoModeController;
        mQuickQSPanelController = quickQSPanelController;
        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        mFeatureFlags = featureFlags;
        mBatteryMeterViewController = batteryMeterViewController;
        mInsetsProvider = statusBarContentInsetsProvider;

        mQSCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(mView.findViewById(R.id.carrier_group))
                .build();
        mClockView = mView.findViewById(R.id.clock);
        mIconContainer = mView.findViewById(R.id.statusIcons);
        mVariableDateViewControllerDateView = variableDateViewControllerFactory.create(
                mView.requireViewById(R.id.date)
        );
        mVariableDateViewControllerClockDateView = variableDateViewControllerFactory.create(
                mView.requireViewById(R.id.date_clock)
        );

        mIconManager = tintedIconManagerFactory.create(mIconContainer, StatusBarLocation.QS);
        mDemoModeReceiver = new ClockDemoModeReceiver(mClockView);
        mColorExtractor = colorExtractor;
        mOnColorsChangedListener = (extractor, which) -> {
            final boolean lightTheme = mColorExtractor.getNeutralColors().supportsDarkText();
            mClockView.onColorsChanged(lightTheme);
        };
        mColorExtractor.addOnColorsChangedListener(mOnColorsChangedListener);

        // Don't need to worry about tuner settings for this icon
        mBatteryMeterViewController.ignoreTunerUpdates();
    }

    @Override
    protected void onInit() {
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mPrivacyIconsController.onParentVisible();
        mPrivacyIconsController.setChipVisibilityListener(this);
        mIconContainer.addIgnoredSlot(
                getResources().getString(com.android.internal.R.string.status_bar_managed_profile));
        mIconContainer.addIgnoredSlot(
                getResources().getString(com.android.internal.R.string.status_bar_alarm_clock));
        mIconContainer.setShouldRestrictIcons(false);
        mStatusBarIconController.addIconGroup(mIconManager);

        mView.setIsSingleCarrier(mQSCarrierGroupController.isSingleCarrier());
        mQSCarrierGroupController
                .setOnSingleCarrierChangedListener(mView::setIsSingleCarrier);

        List<String> rssiIgnoredSlots = List.of(
                getResources().getString(com.android.internal.R.string.status_bar_mobile)
        );

        mView.onAttach(mIconManager, mQSExpansionPathInterpolator, rssiIgnoredSlots,
                mInsetsProvider, mFeatureFlags.isEnabled(Flags.COMBINED_QS_HEADERS));

        mDemoModeController.addCallback(mDemoModeReceiver);

        mVariableDateViewControllerDateView.init();
        mVariableDateViewControllerClockDateView.init();
    }

    @Override
    protected void onViewDetached() {
        mColorExtractor.removeOnColorsChangedListener(mOnColorsChangedListener);
        mPrivacyIconsController.onParentInvisible();
        mStatusBarIconController.removeIconGroup(mIconManager);
        mQSCarrierGroupController.setOnSingleCarrierChangedListener(null);
        mDemoModeController.removeCallback(mDemoModeReceiver);
        setListening(false);
    }

    public void setListening(boolean listening) {
        mQSCarrierGroupController.setListening(listening);

        if (listening == mListening) {
            return;
        }
        mListening = listening;

        mQuickQSPanelController.setListening(listening);

        if (mQuickQSPanelController.switchTileLayout(false)) {
            mView.updateResources();
        }

        if (listening) {
            mPrivacyIconsController.startListening();
        } else {
            mPrivacyIconsController.stopListening();
        }
    }

    @Override
    public void onChipVisibilityRefreshed(boolean visible) {
        mView.setChipVisibility(visible);
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mQuickQSPanelController.setContentMargins(marginStart, marginEnd);
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
