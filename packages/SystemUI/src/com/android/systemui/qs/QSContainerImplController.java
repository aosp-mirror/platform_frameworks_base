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

import static com.android.systemui.classifier.Classifier.QS_SWIPE_NESTED;

import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** */
@QSScope
public class QSContainerImplController extends ViewController<QSContainerImpl> {
    private final QSPanelController mQsPanelController;
    private final QuickStatusBarHeaderController mQuickStatusBarHeaderController;
    private final ConfigurationController mConfigurationController;
    private final FalsingManager mFalsingManager;
    private final NonInterceptingScrollView mQSPanelContainer;
    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.updateResources(mQsPanelController, mQuickStatusBarHeaderController);
        }
    };

    private final View.OnTouchListener mContainerTouchHandler = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (mQSPanelContainer.isPreventingIntercept()) {
                    // There's really no action here to take, but we need to tell the FalsingManager
                    mFalsingManager.isFalseTouch(QS_SWIPE_NESTED);
                }
            }
            return false;
        }
    };

    @Inject
    QSContainerImplController(
            QSContainerImpl view,
            QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController,
            ConfigurationController configurationController,
            FalsingManager falsingManager,
            FeatureFlags featureFlags) {
        super(view);
        mQsPanelController = qsPanelController;
        mQuickStatusBarHeaderController = quickStatusBarHeaderController;
        mConfigurationController = configurationController;
        mFalsingManager = falsingManager;
        mQSPanelContainer = mView.getQSPanelContainer();
        view.setUseCombinedHeaders(featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS));
    }

    @Override
    public void onInit() {
        mQuickStatusBarHeaderController.init();
    }

    public void setListening(boolean listening) {
        mQuickStatusBarHeaderController.setListening(listening);
    }

    @Override
    protected void onViewAttached() {
        mView.updateResources(mQsPanelController, mQuickStatusBarHeaderController);
        mConfigurationController.addCallback(mConfigurationListener);
        mQSPanelContainer.setOnTouchListener(mContainerTouchHandler);
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mQSPanelContainer.setOnTouchListener(null);
    }

    public QSContainerImpl getView() {
        return mView;
    }
}
