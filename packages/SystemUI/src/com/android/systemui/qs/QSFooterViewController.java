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

package com.android.systemui.qs;

import static com.android.systemui.qs.dagger.QSFlagsModule.PM_LITE_ENABLED;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.globalactions.GlobalActionsDialogLite;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.MultiUserSwitchController;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link QSFooterView}.
 */
@QSScope
public class QSFooterViewController extends ViewController<QSFooterView> implements QSFooter {

    private final UserManager mUserManager;
    private final UserInfoController mUserInfoController;
    private final ActivityStarter mActivityStarter;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final UserTracker mUserTracker;
    private final QSPanelController mQsPanelController;
    private final QuickQSPanelController mQuickQSPanelController;
    private final TunerService mTunerService;
    private final MetricsLogger mMetricsLogger;
    private final FalsingManager mFalsingManager;
    private final MultiUserSwitchController mMultiUserSwitchController;
    private final SettingsButton mSettingsButton;
    private final View mSettingsButtonContainer;
    private final TextView mBuildText;
    private final View mEdit;
    private final PageIndicator mPageIndicator;
    private final View mPowerMenuLite;
    private final boolean mShowPMLiteButton;
    private final GlobalActionsDialogLite mGlobalActionsDialog;
    private final UiEventLogger mUiEventLogger;

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            new UserInfoController.OnUserInfoChangedListener() {
        @Override
        public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
            boolean isGuestUser = mUserManager.isGuestUser(KeyguardUpdateMonitor.getCurrentUser());
            mView.onUserInfoChanged(picture, isGuestUser);
        }
    };

    private final View.OnClickListener mSettingsOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            // Don't do anything until views are unhidden. Don't do anything if the tap looks
            // suspicious.
            if (!mExpanded || mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }

            if (v == mSettingsButton) {
                if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                    // If user isn't setup just unlock the device and dump them back at SUW.
                    mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                    });
                    return;
                }
                mMetricsLogger.action(
                        mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                                : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
                if (mSettingsButton.isTunerClick()) {
                    mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                        if (isTunerEnabled()) {
                            mTunerService.showResetRequest(
                                    () -> {
                                        // Relaunch settings so that the tuner disappears.
                                        startSettingsActivity();
                                    });
                        } else {
                            Toast.makeText(getContext(), R.string.tuner_toast,
                                    Toast.LENGTH_LONG).show();
                            mTunerService.setTunerEnabled(true);
                        }
                        startSettingsActivity();

                    });
                } else {
                    startSettingsActivity();
                }
            } else if (v == mPowerMenuLite) {
                mUiEventLogger.log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS);
                mGlobalActionsDialog.showOrHideDialog(false, true);
            }
        }
    };

    private boolean mListening;
    private boolean mExpanded;

    @Inject
    QSFooterViewController(QSFooterView view, UserManager userManager,
            UserInfoController userInfoController, ActivityStarter activityStarter,
            DeviceProvisionedController deviceProvisionedController, UserTracker userTracker,
            QSPanelController qsPanelController,
            MultiUserSwitchController multiUserSwitchController,
            QuickQSPanelController quickQSPanelController,
            TunerService tunerService, MetricsLogger metricsLogger, FalsingManager falsingManager,
            @Named(PM_LITE_ENABLED) boolean showPMLiteButton,
            GlobalActionsDialogLite globalActionsDialog, UiEventLogger uiEventLogger) {
        super(view);
        mUserManager = userManager;
        mUserInfoController = userInfoController;
        mActivityStarter = activityStarter;
        mDeviceProvisionedController = deviceProvisionedController;
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mQuickQSPanelController = quickQSPanelController;
        mTunerService = tunerService;
        mMetricsLogger = metricsLogger;
        mFalsingManager = falsingManager;
        mMultiUserSwitchController = multiUserSwitchController;

        mSettingsButton = mView.findViewById(R.id.settings_button);
        mSettingsButtonContainer = mView.findViewById(R.id.settings_button_container);
        mBuildText = mView.findViewById(R.id.build);
        mEdit = mView.findViewById(android.R.id.edit);
        mPageIndicator = mView.findViewById(R.id.footer_page_indicator);
        mPowerMenuLite = mView.findViewById(R.id.pm_lite);
        mShowPMLiteButton = showPMLiteButton;
        mGlobalActionsDialog = globalActionsDialog;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mMultiUserSwitchController.init();
    }

    @Override
    protected void onViewAttached() {
        if (mShowPMLiteButton) {
            mPowerMenuLite.setVisibility(View.VISIBLE);
            mPowerMenuLite.setOnClickListener(mSettingsOnClickListener);
        } else {
            mPowerMenuLite.setVisibility(View.GONE);
        }
        mView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        mView.updateAnimator(
                                right - left, mQuickQSPanelController.getNumQuickTiles()));
        mSettingsButton.setOnClickListener(mSettingsOnClickListener);
        mBuildText.setOnLongClickListener(view -> {
            CharSequence buildText = mBuildText.getText();
            if (!TextUtils.isEmpty(buildText)) {
                ClipboardManager service =
                        mUserTracker.getUserContext().getSystemService(ClipboardManager.class);
                String label = getResources().getString(R.string.build_number_clip_data_label);
                service.setPrimaryClip(ClipData.newPlainText(label, buildText));
                Toast.makeText(getContext(), R.string.build_number_copy_toast, Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            return false;
        });

        mEdit.setOnClickListener(view -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }
            mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                    mQsPanelController.showEdit(view));
        });

        mQsPanelController.setFooterPageIndicator(mPageIndicator);
        mView.updateEverything(isTunerEnabled(), mMultiUserSwitchController.isMultiUserEnabled());
    }

    @Override
    protected void onViewDetached() {
        setListening(false);
    }

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        mView.setExpanded(
                expanded, isTunerEnabled(), mMultiUserSwitchController.isMultiUserEnabled());
    }

    @Override
    public int getHeight() {
        return mView.getHeight();
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) {
            return;
        }

        mListening = listening;
        if (mListening) {
            mUserInfoController.addCallback(mOnUserInfoChangedListener);
        } else {
            mUserInfoController.removeCallback(mOnUserInfoChangedListener);
        }
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing();
    }

    /** */
    @Override
    public void setExpandClickListener(View.OnClickListener onClickListener) {
        mView.setExpandClickListener(onClickListener);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2, isTunerEnabled(), mMultiUserSwitchController.isMultiUserEnabled());
    }

    private void startSettingsActivity() {
        ActivityLaunchAnimator.Controller animationController =
                mSettingsButtonContainer != null ? ActivityLaunchAnimator.Controller.fromView(
                        mSettingsButtonContainer,
                        InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON) : null;
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */, animationController);
    }

    private boolean isTunerEnabled() {
        return mTunerService.isTunerEnabled();
    }
}
