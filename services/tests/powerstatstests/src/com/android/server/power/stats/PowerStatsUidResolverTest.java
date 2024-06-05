/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class PowerStatsUidResolverTest {

    private final PowerStatsUidResolver mResolver = new PowerStatsUidResolver();
    @Mock
    PowerStatsUidResolver.Listener mListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void addAndRemoveIsolatedUid() {
        mResolver.addListener(mListener);
        mResolver.noteIsolatedUidAdded(42, 314);
        verify(mListener).onIsolatedUidAdded(42, 314);
        assertThat(mResolver.mapUid(42)).isEqualTo(314);

        mResolver.noteIsolatedUidRemoved(42, 314);
        verify(mListener).onBeforeIsolatedUidRemoved(42, 314);
        verify(mListener).onAfterIsolatedUidRemoved(42, 314);
        assertThat(mResolver.mapUid(42)).isEqualTo(42);

        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void retainAndRemoveIsolatedUid() {
        mResolver.addListener(mListener);
        mResolver.noteIsolatedUidAdded(42, 314);
        verify(mListener).onIsolatedUidAdded(42, 314);
        assertThat(mResolver.mapUid(42)).isEqualTo(314);

        mResolver.retainIsolatedUid(42);

        mResolver.noteIsolatedUidRemoved(42, 314);
        verify(mListener).onBeforeIsolatedUidRemoved(42, 314);
        assertThat(mResolver.mapUid(42)).isEqualTo(314);
        verifyNoMoreInteractions(mListener);

        mResolver.releaseIsolatedUid(42);
        verify(mListener).onAfterIsolatedUidRemoved(42, 314);
        assertThat(mResolver.mapUid(42)).isEqualTo(42);

        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void removeUidsInRange() {
        mResolver.noteIsolatedUidAdded(1, 314);
        mResolver.noteIsolatedUidAdded(2, 314);
        mResolver.noteIsolatedUidAdded(3, 314);
        mResolver.noteIsolatedUidAdded(4, 314);
        mResolver.noteIsolatedUidAdded(6, 314);
        mResolver.noteIsolatedUidAdded(8, 314);
        mResolver.noteIsolatedUidAdded(10, 314);

        mResolver.addListener(mListener);

        mResolver.releaseUidsInRange(4, 4);     // Single
        verify(mListener).onAfterIsolatedUidRemoved(4, 314);
        verifyNoMoreInteractions(mListener);

        // Now: [1, 2, 3, 6, 8, 10]

        mResolver.releaseUidsInRange(2, 3);     // Inclusive
        verify(mListener).onAfterIsolatedUidRemoved(2, 314);
        verify(mListener).onAfterIsolatedUidRemoved(3, 314);
        verifyNoMoreInteractions(mListener);

        // Now: [1, 6, 8, 10]

        mResolver.releaseUidsInRange(5, 9);     // Exclusive
        verify(mListener).onAfterIsolatedUidRemoved(6, 314);
        verify(mListener).onAfterIsolatedUidRemoved(8, 314);
        verifyNoMoreInteractions(mListener);

        // Now: [1, 10]

        mResolver.releaseUidsInRange(5, 9);     // Empty
        verifyNoMoreInteractions(mListener);
    }
}
