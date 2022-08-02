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
    private ShellInit mShellInit;
    @Mock
    private ShellExecutor mExecutor;

    private ShellController mController;
    private TestConfigurationChangeListener mConfigChangeListener;
    private TestKeyguardChangeListener mKeyguardChangeListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mKeyguardChangeListener = new TestKeyguardChangeListener();
        mConfigChangeListener = new TestConfigurationChangeListener();
        mController = new ShellController(mShellInit, mExecutor);
        mController.onConfigurationChanged(getConfigurationCopy());
    }

    @After
    public void tearDown() {
        // Do nothing
    }

    @Test
    public void testAddKeyguardChangeListener_ensureCallback() {
        mController.addKeyguardChangeListener(mKeyguardChangeListener);

        mController.onKeyguardVisibilityChanged(true, false, false);
        assertTrue(mKeyguardChangeListener.visibilityChanged == 1);
        assertTrue(mKeyguardChangeListener.dismissAnimationFinished == 0);
    }

    @Test
    public void testDoubleAddKeyguardChangeListener_ensureSingleCallback() {
        mController.addKeyguardChangeListener(mKeyguardChangeListener);
        mController.addKeyguardChangeListener(mKeyguardChangeListener);

        mController.onKeyguardVisibilityChanged(true, false, false);
        assertTrue(mKeyguardChangeListener.visibilityChanged == 1);
        assertTrue(mKeyguardChangeListener.dismissAnimationFinished == 0);
    }

    @Test
    public void testAddRemoveKeyguardChangeListener_ensureNoCallback() {
        mController.addKeyguardChangeListener(mKeyguardChangeListener);
        mController.removeKeyguardChangeListener(mKeyguardChangeListener);

        mController.onKeyguardVisibilityChanged(true, false, false);
        assertTrue(mKeyguardChangeListener.visibilityChanged == 0);
        assertTrue(mKeyguardChangeListener.dismissAnimationFinished == 0);
    }

    @Test
    public void testKeyguardVisibilityChanged() {
        mController.addKeyguardChangeListener(mKeyguardChangeListener);

        mController.onKeyguardVisibilityChanged(true, true, true);
        assertTrue(mKeyguardChangeListener.visibilityChanged == 1);
        assertTrue(mKeyguardChangeListener.lastAnimatingDismiss);
        assertTrue(mKeyguardChangeListener.lastOccluded);
        assertTrue(mKeyguardChangeListener.lastAnimatingDismiss);
        assertTrue(mKeyguardChangeListener.dismissAnimationFinished == 0);
    }

    @Test
    public void testKeyguardDismissAnimationFinished() {
        mController.addKeyguardChangeListener(mKeyguardChangeListener);

        mController.onKeyguardDismissAnimationFinished();
        assertTrue(mKeyguardChangeListener.visibilityChanged == 0);
        assertTrue(mKeyguardChangeListener.dismissAnimationFinished == 1);
    }

    @Test
    public void testAddConfigurationChangeListener_ensureCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
    }

    @Test
    public void testDoubleAddConfigurationChangeListener_ensureSingleCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
    }

    @Test
    public void testAddRemoveConfigurationChangeListener_ensureNoCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);
        mController.removeConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 0);
    }

    @Test
    public void testMultipleConfigurationChangeListeners() {
        TestConfigurationChangeListener listener2 = new TestConfigurationChangeListener();
        mController.addConfigurationChangeListener(mConfigChangeListener);
        mController.addConfigurationChangeListener(listener2);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
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
        mController.addConfigurationChangeListener(mConfigChangeListener);

        // Ensure we don't fail just because a listener was removed mid-callback
        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
    }

    @Test
    public void testDensityChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.densityDpi = 200;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 1);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 0);
        assertTrue(mConfigChangeListener.themeChanges == 0);
        assertTrue(mConfigChangeListener.localeChanges == 0);
    }

    @Test
    public void testFontScaleChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.fontScale = 2;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 1);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 0);
        assertTrue(mConfigChangeListener.themeChanges == 0);
        assertTrue(mConfigChangeListener.localeChanges == 0);
    }

    @Test
    public void testSmallestWidthChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.smallestScreenWidthDp = 100;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 0);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 1);
        assertTrue(mConfigChangeListener.themeChanges == 0);
        assertTrue(mConfigChangeListener.localeChanges == 0);
    }

    @Test
    public void testThemeChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.assetsSeq++;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 0);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 0);
        assertTrue(mConfigChangeListener.themeChanges == 1);
        assertTrue(mConfigChangeListener.localeChanges == 0);
    }

    @Test
    public void testNightModeChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        newConfig.uiMode = Configuration.UI_MODE_NIGHT_YES;
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 0);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 0);
        assertTrue(mConfigChangeListener.themeChanges == 1);
        assertTrue(mConfigChangeListener.localeChanges == 0);
    }

    @Test
    public void testLocaleChangeCallback() {
        mController.addConfigurationChangeListener(mConfigChangeListener);

        Configuration newConfig = getConfigurationCopy();
        // Just change the locales to be different
        if (newConfig.locale == Locale.CANADA) {
            newConfig.locale = Locale.US;
        } else {
            newConfig.locale = Locale.CANADA;
        }
        mController.onConfigurationChanged(newConfig);
        assertTrue(mConfigChangeListener.configChanges == 1);
        assertTrue(mConfigChangeListener.densityChanges == 0);
        assertTrue(mConfigChangeListener.smallestWidthChanges == 0);
        assertTrue(mConfigChangeListener.themeChanges == 0);
        assertTrue(mConfigChangeListener.localeChanges == 1);
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

    private class TestKeyguardChangeListener implements KeyguardChangeListener {
        // Counts of number of times each of the callbacks are called
        public int visibilityChanged;
        public boolean lastVisibility;
        public boolean lastOccluded;
        public boolean lastAnimatingDismiss;
        public int dismissAnimationFinished;

        @Override
        public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
                boolean animatingDismiss) {
            lastVisibility = visible;
            lastOccluded = occluded;
            lastAnimatingDismiss = animatingDismiss;
            visibilityChanged++;
        }

        @Override
        public void onKeyguardDismissAnimationFinished() {
            dismissAnimationFinished++;
        }
    }
}
