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

import android.content.res.Configuration;

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

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.updateResources(mQsPanelController, mQuickStatusBarHeaderController);
        }
    };

    @Inject
    QSContainerImplController(QSContainerImpl view, QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController,
            ConfigurationController configurationController) {
        super(view);
        mQsPanelController = qsPanelController;
        mQuickStatusBarHeaderController = quickStatusBarHeaderController;
        mConfigurationController = configurationController;
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
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    public QSContainerImpl getView() {
        return mView;
    }
}
