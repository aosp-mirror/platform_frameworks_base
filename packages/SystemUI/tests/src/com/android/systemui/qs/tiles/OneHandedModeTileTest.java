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

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class OneHandedModeTileTest extends SysuiTestCase {

    private final String mOneHandedTitle = "One-handed mode";

    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private SecureSettings mSecureSettings;

    private TestableLooper mTestableLooper;
    private OneHandedModeTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);

        mTile = spy(new OneHandedModeTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mUserTracker,
                mSecureSettings));

        mTestableLooper.processAllMessages();
        mTile.initialize();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testIsAvailable_unsupportOneHandedProperty_shouldReturnsFalse() {
        when(mTile.isSupportOneHandedMode()).thenReturn(false);

        assertThat(mTile.isAvailable()).isFalse();
    }

    @Test
    public void testIsAvailable_supportOneHandedProperty_shouldReturnsTrue() {
        when(mTile.isSupportOneHandedMode()).thenReturn(true);

        assertThat(mTile.isAvailable()).isTrue();
    }

    @Test
    public void testGetTileLabel_shouldReturnOneHandedModeTitle() {
        assertThat(mTile.getTileLabel()).isEqualTo(mOneHandedTitle);
    }
}
