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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImsConfigurationTrackerTest {
    private ImsConfigurationTracker mImsConfigTracker;
    private Context mContext;

    @Before
    public void setUp() throws TimeoutException {
        mContext = getInstrumentation().getContext();
        mImsConfigTracker = new ImsConfigurationTracker();
    }

    @Test
    public void testShouldImeRestart() throws Exception {
        Configuration config = mContext.getResources().getConfiguration();
        mImsConfigTracker.onInitialize(0 /* handledConfigChanges */);
        mImsConfigTracker.onBindInput(mContext.getResources());
        Configuration newConfig = new Configuration(config);

        final AtomicBoolean didReset = new AtomicBoolean();
        Runnable resetStateRunner = () -> didReset.set(true);

        mImsConfigTracker.onConfigurationChanged(newConfig, resetStateRunner);
        assertFalse("IME shouldn't restart if config hasn't changed",
                didReset.get());

        // Screen density changed but IME doesn't handle configChanges
        newConfig.densityDpi = 99;
        mImsConfigTracker.onConfigurationChanged(newConfig, resetStateRunner);
        assertTrue("IME should restart for unhandled configChanges",
                didReset.get());

        didReset.set(false);
        // opt-in IME to handle density config changes.
        mImsConfigTracker.setHandledConfigChanges(ActivityInfo.CONFIG_DENSITY);
        mImsConfigTracker.onConfigurationChanged(newConfig, resetStateRunner);
        assertFalse("IME shouldn't restart since it handles configChanges",
                didReset.get());
    }
}
