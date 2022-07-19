/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.sysui;

import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Locale;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class ShellControllerTest extends ShellTestCase {

    @Mock
    private ShellExecutor mExecutor;

    private ShellController mController;
    private TestConfigurationChangeListener mListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mListener = new TestConfigurationChangeListener();
        mController = new ShellController(mExecutor);
        mController.onConfigurationChanged(getConfigurationCopy());
    }

    @After
    public void tearDown() {
        // Do nothing
    }

    @Test
    public void testAddConfigurationChangeListener_ensureCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
    }

    @Test
    public void testDoubleAddConfigurationChangeListener_ensureSingleCallback() {
        mController.addConfigurationChangeListener(mListener);
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
    }

    @Test
    public void testAddRemoveConfigurationChangeListener_ensureNoCallback() {
        mController.addConfigurationChangeListener(mListener);
        mController.removeConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 0);
    }

    @Test
    public void testMultipleConfigurationChangeListeners() {
        TestConfigurationChangeListener listener2 = new TestConfigurationChangeListener();
        mController.addConfigurationChangeListener(mListener);
        mController.addConfigurationChangeListener(listener2);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(listener2.configChanges == 1);
    }

    @Test
    public void testRemoveListenerDuringCallback() {
        TestConfigurationChangeListener badListener = new TestConfigurationChangeListener() {
            @Override
            public void onConfigurationChanged(Configuration newConfiguration) {
                mController.removeConfigurationChangeListener(this);
            }
        };
        mController.addConfigurationChangeListener(badListener);
        mController.addConfigurationChangeListener(mListener);

        // Ensure we don't fail just because a listener was removed mid-callback
        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
    }

    @Test
    public void testDensityChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 1);
        assertTrue(mListener.smallestWidthChanges == 0);
        assertTrue(mListener.themeChanges == 0);
        assertTrue(mListener.localeChanges == 0);
    }

    @Test
    public void testFontScaleChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.fontScale = 2;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 1);
        assertTrue(mListener.smallestWidthChanges == 0);
        assertTrue(mListener.themeChanges == 0);
        assertTrue(mListener.localeChanges == 0);
    }

    @Test
    public void testSmallestWidthChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.smallestScreenWidthDp = 100;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 0);
        assertTrue(mListener.smallestWidthChanges == 1);
        assertTrue(mListener.themeChanges == 0);
        assertTrue(mListener.localeChanges == 0);
    }

    @Test
    public void testThemeChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.assetsSeq++;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 0);
        assertTrue(mListener.smallestWidthChanges == 0);
        assertTrue(mListener.themeChanges == 1);
        assertTrue(mListener.localeChanges == 0);
    }

    @Test
    public void testNightModeChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.uiMode = Configuration.UI_MODE_NIGHT_YES;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 0);
        assertTrue(mListener.smallestWidthChanges == 0);
        assertTrue(mListener.themeChanges == 1);
        assertTrue(mListener.localeChanges == 0);
    }

    @Test
    public void testLocaleChangeCallback() {
        mController.addConfigurationChangeListener(mListener);

        Configuration newConfig = getConfigurationCopy();
        // Just change the locales to be different
        if (newConfig.locale == Locale.CANADA) {
            newConfig.locale = Locale.US;
        } else {
            newConfig.locale = Locale.CANADA;
        }
        mController.onConfigurationChanged(newConfig);
        assertTrue(mListener.configChanges == 1);
        assertTrue(mListener.densityChanges == 0);
        assertTrue(mListener.smallestWidthChanges == 0);
        assertTrue(mListener.themeChanges == 0);
        assertTrue(mListener.localeChanges == 1);
    }

    private Configuration getConfigurationCopy() {
        final Configuration c = new Configuration(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getResources().getConfiguration());
        // In tests this might be undefined so make sure it's valid
        c.assetsSeq = 1;
        return c;
    }

    private class TestConfigurationChangeListener implements ConfigurationChangeListener {
        // Counts of number of times each of the callbacks are called
        public int configChanges;
        public int densityChanges;
        public int smallestWidthChanges;
        public int themeChanges;
        public int localeChanges;

        @Override
        public void onConfigurationChanged(Configuration newConfiguration) {
            configChanges++;
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            densityChanges++;
        }

        @Override
        public void onSmallestScreenWidthChanged() {
            smallestWidthChanges++;
        }

        @Override
        public void onThemeChanged() {
            themeChanges++;
        }

        @Override
        public void onLocaleOrLayoutDirectionChanged() {
            localeChanges++;
        }
    }
}
