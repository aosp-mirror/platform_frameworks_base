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

package com.android.server.location.gnss;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Unit tests for {@link GnssAntennaInfoProvider}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class GnssAntennaInfoProviderTest {
    @Mock
    private GnssAntennaInfoProvider.GnssAntennaInfoProviderNative
            mMockNative;
    private GnssAntennaInfoProvider mTestProvider;

    /** Setup. */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockNative.startAntennaInfoListening()).thenReturn(true);
        when(mMockNative.stopAntennaInfoListening()).thenReturn(true);

        mTestProvider = new GnssAntennaInfoProvider(RuntimeEnvironment.application,
                new Handler(Looper.myLooper()), mMockNative) {
            @Override
            public boolean isGpsEnabled() {
                return true;
            }
        };
    }

    /**
     * Test that registerWithService calls the native startAntennaInfoListening method.
     */
    @Test
    public void register_nativeStarted() {
        mTestProvider.registerWithService();
        verify(mMockNative, times(1)).startAntennaInfoListening();
    }

    /**
     * Test that unregisterFromService calls the native stopAntennaInfoListening method.
     */
    @Test
    public void unregister_nativeStopped() {
        mTestProvider.registerWithService();
        mTestProvider.unregisterFromService();
        verify(mMockNative, times(1)).stopAntennaInfoListening();
    }

    /**
     * Test that GnssAntennaInfoProvider.isAntennaInfoSupported() returns the result of the
     * native isAntennaInfoSupported method.
     */
    @Test
    public void isSupported_nativeIsSupported() {
        when(mMockNative.isAntennaInfoSupported()).thenReturn(true);
        assertThat(mTestProvider.isAvailableInPlatform()).isTrue();

        when(mMockNative.isAntennaInfoSupported()).thenReturn(false);
        assertThat(mTestProvider.isAvailableInPlatform()).isFalse();
    }
}
