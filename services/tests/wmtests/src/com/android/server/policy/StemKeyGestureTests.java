/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;

import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for stem key gesture.
 *
 * Build/Install/Run:
 *  atest WmTests:StemKeyGestureTests
 */
public class StemKeyGestureTests extends ShortcutKeyTestBase {

    @Before
    public void stemKeySetup() {
        mPhoneWindowManager.overrideShortPressOnStemPrimaryBehavior(
                SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS);
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
    }

    /**
     * Stem single key should not launch behavior during set up.
     */
    @Test
    public void stemSingleKey_duringSetup_doNothing() {
        mPhoneWindowManager.overrideIsUserSetupComplete(false);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
    }

    /**
     * Stem single key should launch all app after set up.
     */
    @Test
    public void stemSingleKey_AfterSetup_openAllApp() {
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
    }
}
