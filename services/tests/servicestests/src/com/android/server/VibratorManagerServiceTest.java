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

package com.android.server;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibratorManagerService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorManagerServiceTest
 */
@Presubmit
public class VibratorManagerServiceTest {

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private VibratorManagerService.NativeWrapper mNativeWrapperMock;

    @Before
    public void setUp() throws Exception {
    }

    private VibratorManagerService createService() {
        return new VibratorManagerService(InstrumentationRegistry.getContext(),
                new VibratorManagerService.Injector() {
                    @Override
                    VibratorManagerService.NativeWrapper getNativeWrapper() {
                        return mNativeWrapperMock;
                    }
                });
    }

    @Test
    public void createService_initializesNativeService() {
        createService();
        verify(mNativeWrapperMock).init();
    }

    @Test
    public void getVibratorIds_withNullResultFromNative_returnsEmptyArray() {
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(null);
        assertArrayEquals(new int[0], createService().getVibratorIds());
    }

    @Test
    public void getVibratorIds_withNonEmptyResultFromNative_returnsSameArray() {
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(new int[]{ 1, 2 });
        assertArrayEquals(new int[]{ 1, 2 }, createService().getVibratorIds());
    }
}
