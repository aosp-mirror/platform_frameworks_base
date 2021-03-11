/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.inputmethodservice;

import static android.content.res.Configuration.KEYBOARD_12KEY;
import static android.content.res.Configuration.NAVIGATION_NONAV;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;


import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeoutException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodServiceTest {
    private InputMethodService mService;
    private Context mContext;
    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Before
    public void setUp() throws TimeoutException {
        mContext = getInstrumentation().getContext();
        mService = new InputMethodService();
    }

    @Test
    public void testShouldImeRestartForConfig() throws Exception {
        // Make sure we preserve Pre-S behavior i.e. Service restarts.
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.R;
        Configuration config = mContext.getResources().getConfiguration();
        mService.setLastKnownConfig(config);
        assertTrue("IME should restart for Pre-S",
                mService.shouldImeRestartForConfig(config));

        // IME shouldn't restart on targetSdk S+ (with no config changes).
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.S;
        assertFalse("IME shouldn't restart for S+",
                mService.shouldImeRestartForConfig(config));

        // Screen density changed but IME doesn't handle congfigChanges
        config.densityDpi = 99;
        assertTrue("IME should restart for unhandled configChanges",
                mService.shouldImeRestartForConfig(config));

        // opt-in IME to handle config changes.
        mService.setHandledConfigChanges(ActivityInfo.CONFIG_DENSITY);
        assertFalse("IME shouldn't restart for S+ since it handles configChanges",
                mService.shouldImeRestartForConfig(config));
    }
}
