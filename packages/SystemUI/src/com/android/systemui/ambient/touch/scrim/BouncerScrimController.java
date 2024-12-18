/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.scrim;

import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

import javax.inject.Inject;

/**
 * Implementation for handling swipe movements on the overlay when the keyguard is present.
 */
public class BouncerScrimController implements ScrimController {
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Inject
    BouncerScrimController(StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    @Override
    public void show() {
        mStatusBarKeyguardViewManager.showPrimaryBouncer(false);
    }

    @Override
    public void expand(ShadeExpansionChangeEvent event) {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(event);
    }

    @Override
    public void reset() {
        mStatusBarKeyguardViewManager.reset(false);
    }
}
