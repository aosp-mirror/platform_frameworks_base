/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for {@link NoOpInfoMediaManager} to avoid exceptions in {@link InfoMediaManager}.
 *
 * <p>While {@link NoOpInfoMediaManager} should not perform any actions, it should still return
 * placeholder information in certain cases to not change the behaviour of {@link InfoMediaManager}
 * and prevent crashes.
 */
@RunWith(RobolectricTestRunner.class)
public class NoOpInfoMediaManagerTest {
    private InfoMediaManager mInfoMediaManager;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mInfoMediaManager =
                new NoOpInfoMediaManager(
                        mContext,
                        /* packageName */ "FAKE_PACKAGE_NAME",
                        mContext.getUser(),
                        /* localBluetoothManager */ null,
                        /* mediaController */ null);
    }

    @Test
    public void getSessionVolumeMax_returnsNotFound() {
        assertThat(mInfoMediaManager.getSessionVolumeMax()).isEqualTo(-1);
    }

    @Test
    public void getSessionVolume_returnsNotFound() {
        assertThat(mInfoMediaManager.getSessionVolume()).isEqualTo(-1);
    }

    @Test
    public void getSessionName_returnsNull() {
        assertThat(mInfoMediaManager.getSessionName()).isNull();
    }

    @Test
    public void getRoutingSessionForPackage_returnsPlaceholderSession() {
        // Make sure we return a placeholder routing session so that we avoid OOB exceptions.
        assertThat(mInfoMediaManager.getRoutingSessionsForPackage()).hasSize(1);
    }

    @Test
    public void getSelectedMediaDevices_returnsEmptyList() {
        assertThat(mInfoMediaManager.getSelectedMediaDevices()).isEmpty();
    }
}
