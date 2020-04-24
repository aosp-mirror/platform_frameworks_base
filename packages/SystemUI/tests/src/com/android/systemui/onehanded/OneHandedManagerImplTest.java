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

package com.android.systemui.onehanded;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.model.SysUiState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedManagerImplTest extends OneHandedTestCase {

    OneHandedManagerImpl mOneHandedManagerImpl;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    SysUiState mMockSysUiState;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOneHandedManagerImpl = new OneHandedManagerImpl(getContext(),
                mMockDisplayAreaOrganizer,
                mMockSysUiState);

        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
    }

    @Test
    public void testRegisterOrganizer() {
        verify(mMockDisplayAreaOrganizer, times(1)).registerOrganizer(anyInt());
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedManagerImpl.startOneHanded();
    }

    @Test
    public void testStopOneHanded() {
        mOneHandedManagerImpl.stopOneHanded();
    }

}
