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

package com.android.keyguard;

import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for a {@link KeyguardMessageAreaController}. */
public class KeyguardMessageAreaController extends ViewController {
    private final KeyguardMessageArea mView;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;

    private KeyguardMessageAreaController(KeyguardMessageArea view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController) {
        super(view);

        mView = view;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
    }

    @Override
    protected void onViewAttached() {
        //mConfigurationController.addCallback();
        //mKeyguardUpdateMonitor.registerCallback();
    }

    @Override
    protected void onViewDetached() {
        //mConfigurationController.removeCallback();
        //mKeyguardUpdateMonitor.removeCallback();
    }

    /** Factory for createing {@link com.android.keyguard.KeyguardMessageAreaController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final ConfigurationController mConfigurationController;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                ConfigurationController configurationController) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mConfigurationController = configurationController;
        }

        /** Build a new {@link KeyguardMessageAreaController}. */
        public KeyguardMessageAreaController create(KeyguardMessageArea view) {
            return new KeyguardMessageAreaController(
                    view, mKeyguardUpdateMonitor, mConfigurationController);
        }
    }
}
