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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link QSFooterImpl}.
 */
@QSScope
public class QSFooterImplController extends ViewController<QSFooterImpl> implements QSFooter {

    private final UserManager mUserManager;
    private final UserInfoController mUserInfoController;
    private final ActivityStarter mActivityStarter;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final UserTracker mUserTracker;
    private final QSPanelController mQsPanelController;
    private final TunerService mTunerService;
    private final MetricsLogger mMetricsLogger;
    private final SettingsButton mSettingsButton;
    private final TextView mBuildText;
    private final View mEdit;

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
            // Don't do anything until view are unhidden
            if (!mExpanded) {
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
                                    mUserTracker.getUserHandle(),
                                    () -> {
                                        // Relaunch settings so that the tuner disappears.
                                        startSettingsActivity();
                                    });
                        } else {
                            Toast.makeText(getContext(), R.string.tuner_toast,
                                    Toast.LENGTH_LONG).show();
                            mTunerService.setTunerEnabled(mUserTracker.getUserHandle(), true);
                        }
                        startSettingsActivity();

                    });
                } else {
                    startSettingsActivity();
                }
            }
        }
    };

    private boolean mListening;
    private boolean mExpanded;

    @Inject
    QSFooterImplController(QSFooterImpl view, UserManager userManager,
            UserInfoController userInfoController, ActivityStarter activityStarter,
            DeviceProvisionedController deviceProvisionedController, UserTracker userTracker,
            QSPanelController qsPanelController, TunerService tunerService,
            MetricsLogger metricsLogger) {
        super(view);
        mUserManager = userManager;
        mUserInfoController = userInfoController;
        mActivityStarter = activityStarter;
        mDeviceProvisionedController = deviceProvisionedController;
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mTunerService = tunerService;
        mMetricsLogger = metricsLogger;

        mSettingsButton = mView.findViewById(R.id.settings_button);
        mBuildText = mView.findViewById(R.id.build);
        mEdit = mView.findViewById(android.R.id.edit);
    }


    @Override
    protected void onViewAttached() {
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

        mEdit.setOnClickListener(view ->
                mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                        mQsPanelController.showEdit(view)));

        mView.updateEverything(isTunerEnabled());
    }

    @Override
    protected void onViewDetached() {
        setListening(false);
    }


    @Override
    public void setQSPanel(@Nullable QSPanel panel) {
        mView.setQSPanel(panel);
    }

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        mView.setExpanded(expanded, isTunerEnabled());
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
    public void setQQSPanel(@Nullable QuickQSPanel panel) {
        mView.setQQSPanel(panel);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2, isTunerEnabled());
    }


    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    private boolean isTunerEnabled() {
        return mTunerService.isTunerEnabled(mUserTracker.getUserHandle());
    }

    /**
     * Factory for {@link QSFooterImplController}.
     *
     * TODO(b/168904199): Delete this once QSFooterImpl is no longer marked as injectable.
     */
    @QSScope
    public static class Factory {
        private final UserManager mUserManager;
        private final UserInfoController mUserInfoController;
        private final ActivityStarter mActivityStarter;
        private final DeviceProvisionedController mDeviceProvisionedController;
        private final UserTracker mUserTracker;
        private final QSPanelController mQsPanelController;
        private final TunerService mTunerService;
        private final MetricsLogger mMetricsLogger;

        @Inject
        Factory(UserManager userManager, UserInfoController userInfoController,
                ActivityStarter activityStarter,
                DeviceProvisionedController deviceProvisionedController, UserTracker userTracker,
                QSPanelController qsPanelController, TunerService tunerService,
                MetricsLogger metricsLogger) {
            mUserManager = userManager;
            mUserInfoController = userInfoController;
            mActivityStarter = activityStarter;
            mDeviceProvisionedController = deviceProvisionedController;
            mUserTracker = userTracker;
            mQsPanelController = qsPanelController;
            mTunerService = tunerService;
            mMetricsLogger = metricsLogger;
        }

        QSFooterImplController create(QSFooterImpl view) {
            QSFooterImplController controller = new QSFooterImplController(view, mUserManager,
                    mUserInfoController, mActivityStarter, mDeviceProvisionedController,
                    mUserTracker, mQsPanelController, mTunerService, mMetricsLogger);
            controller.init();
            return controller;
        }
    }
}
