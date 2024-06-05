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

package com.android.server.flags;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class FlagOverrideStoreTest {
    private static final String NS = "ns";
    private static final String NAME = "name";
    private static final String PROP_NAME = FlagOverrideStore.getPropName(NS, NAME);

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private SettingsProxy mSettingsProxy;
    @Mock
    private FlagOverrideStore.FlagChangeCallback mCallback;

    private FlagOverrideStore mFlagStore;

    @Before
    public void setup() {
        mFlagStore = new FlagOverrideStore(mSettingsProxy);
        mFlagStore.setChangeCallback(mCallback);
    }

    @Test
    public void testSet_unset() {
        mFlagStore.set(NS, NAME, "value");
        verify(mSettingsProxy).putString(PROP_NAME, "value");
    }

    @Test
    public void testSet_setTwice() {
        mFlagStore.set(NS, NAME, "value");
        mFlagStore.set(NS, NAME, "newvalue");
        verify(mSettingsProxy).putString(PROP_NAME, "value");
        verify(mSettingsProxy).putString(PROP_NAME, "newvalue");
    }

    @Test
    public void testGet_unset() {
        assertThat(mFlagStore.get(NS, NAME)).isNull();
    }

    @Test
    public void testGet_set() {
        when(mSettingsProxy.getString(PROP_NAME)).thenReturn("value");
        assertThat(mFlagStore.get(NS, NAME)).isEqualTo("value");
    }

    @Test
    public void testErase() {
        mFlagStore.erase(NS, NAME);
        verify(mSettingsProxy).putString(PROP_NAME, null);
    }

    @Test
    public void testContains_unset() {
        assertThat(mFlagStore.contains(NS, NAME)).isFalse();
    }

    @Test
    public void testContains_set() {
        when(mSettingsProxy.getString(PROP_NAME)).thenReturn("value");
        assertThat(mFlagStore.contains(NS, NAME)).isTrue();
    }

    @Test
    public void testCallback_onSet() {
        mFlagStore.set(NS, NAME, "value");
        verify(mCallback).onFlagChanged(NS, NAME, "value");
    }

    @Test
    public void testCallback_onErase() {
        mFlagStore.erase(NS, NAME);
        verify(mCallback).onFlagChanged(NS, NAME, null);
    }
}
