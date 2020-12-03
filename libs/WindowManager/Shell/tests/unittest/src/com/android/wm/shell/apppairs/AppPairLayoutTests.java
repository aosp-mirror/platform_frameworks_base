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

package com.android.wm.shell.apppairs;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.Display;
import android.view.SurfaceControl;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link AppPairLayout} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppPairLayoutTests extends ShellTestCase {
    @Mock SurfaceControl mSurfaceControl;
    private Display mDisplay;
    private Configuration mConfiguration;
    private AppPairLayout mAppPairLayout;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mConfiguration = getConfiguration(false);
        mDisplay = mContext.getDisplay();
        mAppPairLayout = new AppPairLayout(mContext, mDisplay, mConfiguration, mSurfaceControl);
    }

    @After
    @UiThreadTest
    public void tearDown() {
        mAppPairLayout.release();
    }

    @Test
    @UiThreadTest
    public void testUpdateConfiguration() {
        assertThat(mAppPairLayout.updateConfiguration(getConfiguration(false))).isFalse();
        assertThat(mAppPairLayout.updateConfiguration(getConfiguration(true))).isTrue();
    }

    @Test
    @UiThreadTest
    public void testInitRelease() {
        mAppPairLayout.init();
        assertThat(mAppPairLayout.getDividerLeash()).isNotNull();
        mAppPairLayout.release();
        assertThat(mAppPairLayout.getDividerLeash()).isNull();
    }

    private static Configuration getConfiguration(boolean isLandscape) {
        final Configuration configuration = new Configuration();
        configuration.unset();
        configuration.orientation = isLandscape ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
        configuration.windowConfiguration.setBounds(
                new Rect(0, 0, isLandscape ? 2160 : 1080, isLandscape ? 1080 : 2160));
        return configuration;
    }
}
