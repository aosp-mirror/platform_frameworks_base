/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.SensorPrivacyManager;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SensorPrivacyTileTest extends SysuiTestCase {

    @Mock
    private KeyguardMonitor mKeyguard;
    @Mock
    private QSTileHost mHost;
    @Mock
    SensorPrivacyManager mSensorPrivacyManager;

    private TestableLooper mTestableLooper;

    private SensorPrivacyTile mSensorPrivacyTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mKeyguard = mDependency.injectMockDependency(KeyguardMonitor.class);

        mSensorPrivacyManager = mDependency.injectMockDependency(SensorPrivacyManager.class);

        when(mHost.getContext()).thenReturn(mContext);

        mSensorPrivacyTile = new SensorPrivacyTile(mHost, mSensorPrivacyManager, mKeyguard,
                mock(ActivityStarter.class));
    }

    @Test
    public void testSensorPrivacyListenerAdded_handleListeningTrue() {
        // To prevent access to privacy related features from apps with WRITE_SECURE_SETTINGS the
        // sensor privacy state is not stored in Settings; to receive notification apps must add
        // themselves as a listener with the SensorPrivacyManager. This test verifies when
        // setListening is called with a value of true the tile adds itself as a listener.
        mSensorPrivacyTile.handleSetListening(true);
        mTestableLooper.processAllMessages();
        verify(mSensorPrivacyManager).addSensorPrivacyListener(mSensorPrivacyTile);
    }

    @Test
    public void testSensorPrivacyListenerRemoved_handleListeningFalse() {
        // Similar to the test above verifies that the tile removes itself as a listener when
        // setListening is called with a value of false.
        mSensorPrivacyTile.handleSetListening(false);
        mTestableLooper.processAllMessages();
        verify(mSensorPrivacyManager).removeSensorPrivacyListener((mSensorPrivacyTile));
    }

    @Test
    public void testSensorPrivacyEnabled_handleClick() {
        // Verifies when the SensorPrivacy tile is clicked it invokes the SensorPrivacyManager to
        // set sensor privacy.
        mSensorPrivacyTile.getState().value = false;
        mSensorPrivacyTile.handleClick();
        mTestableLooper.processAllMessages();
        verify(mSensorPrivacyManager).setSensorPrivacy(true);

        mSensorPrivacyTile.getState().value = true;
        mSensorPrivacyTile.handleClick();
        mTestableLooper.processAllMessages();
        verify(mSensorPrivacyManager).setSensorPrivacy(false);
    }

    @Test
    public void testSensorPrivacyNotDisabled_keyguard() {
        // Verifies when the device is locked that sensor privacy cannot be disabled
        when(mKeyguard.isSecure()).thenReturn(true);
        when(mKeyguard.isShowing()).thenReturn(true);
        mSensorPrivacyTile.getState().value = true;
        mSensorPrivacyTile.handleClick();
        mTestableLooper.processAllMessages();
        verify(mSensorPrivacyManager, never()).setSensorPrivacy(false);
    }
}
