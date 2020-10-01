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

package com.android.systemui.car.statusbar;

import android.app.IActivityManager;
import android.content.Context;
import android.view.WindowManager;

import com.android.systemui.car.window.SystemUIOverlayWindowController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A dummy implementation of {@link NotificationShadeWindowController}.
 *
 * TODO(b/155711562): This should be replaced with a longer term solution (i.e. separating
 * {@link BiometricUnlockController} from the views it depends on).
 */
@Singleton
public class DummyNotificationShadeWindowController extends NotificationShadeWindowController {
    private final SystemUIOverlayWindowController mOverlayWindowController;

    @Inject
    public DummyNotificationShadeWindowController(Context context,
            WindowManager windowManager, IActivityManager activityManager,
            DozeParameters dozeParameters,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            KeyguardViewMediator keyguardViewMediator,
            KeyguardBypassController keyguardBypassController,
            SysuiColorExtractor colorExtractor,
            DumpManager dumpManager,
            SystemUIOverlayWindowController overlayWindowController) {
        super(context, windowManager, activityManager, dozeParameters, statusBarStateController,
                configurationController, keyguardViewMediator, keyguardBypassController,
                colorExtractor, dumpManager);
        mOverlayWindowController = overlayWindowController;
    }

    @Override
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        // No op.
    }

    @Override
    public void setNotificationShadeFocusable(boolean focusable) {
        // The overlay window is the car sysui equivalent of the notification shade.
        mOverlayWindowController.setWindowFocusable(focusable);
    }
}
