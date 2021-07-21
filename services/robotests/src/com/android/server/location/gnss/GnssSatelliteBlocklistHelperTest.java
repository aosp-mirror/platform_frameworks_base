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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.util.Collection;
import java.util.List;

/**
 * Unit tests for {@link GnssSatelliteBlocklistHelper}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class GnssSatelliteBlocklistHelperTest {

    private ContentResolver mContentResolver;
    @Mock
    private GnssSatelliteBlocklistHelper.GnssSatelliteBlocklistCallback mCallback;

    /**
     * Initialize mocks and create GnssSatelliteBlocklistHelper with callback.
     */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = RuntimeEnvironment.application;
        mContentResolver = context.getContentResolver();
        new GnssSatelliteBlocklistHelper(context, Looper.myLooper(), mCallback);
    }

    /**
     * Blocklist two satellites and verify callback is called.
     */
    @Test
    public void blocklistOf2Satellites_callbackIsCalled() {
        String blocklist = "3,0,5,24";
        updateBlocklistAndVerifyCallbackIsCalled(blocklist);
    }

    /**
     * Blocklist one satellite with spaces in string and verify callback is called.
     */
    @Test
    public void blocklistWithSpaces_callbackIsCalled() {
        String blocklist = "3, 11";
        updateBlocklistAndVerifyCallbackIsCalled(blocklist);
    }

    /**
     * Pass empty blocklist and verify callback is called.
     */
    @Test
    public void emptyBlocklist_callbackIsCalled() {
        String blocklist = "";
        updateBlocklistAndVerifyCallbackIsCalled(blocklist);
    }

    /**
     * Pass blocklist string with odd number of values and verify callback is not called.
     */
    @Test
    public void blocklistWithOddNumberOfValues_callbackIsNotCalled() {
        String blocklist = "3,0,5";
        updateBlocklistAndNotifyContentObserver(blocklist);
        verify(mCallback, never()).onUpdateSatelliteBlocklist(any(int[].class), any(int[].class));
    }

    /**
     * Pass blocklist string with negative value and verify callback is not called.
     */
    @Test
    public void blocklistWithNegativeValue_callbackIsNotCalled() {
        String blocklist = "3,-11";
        updateBlocklistAndNotifyContentObserver(blocklist);
        verify(mCallback, never()).onUpdateSatelliteBlocklist(any(int[].class), any(int[].class));
    }

    /**
     * Pass blocklist string with non-digit characters and verify callback is not called.
     */
    @Test
    public void blocklistWithNonDigitCharacter_callbackIsNotCalled() {
        String blocklist = "3,1a,5,11";
        updateBlocklistAndNotifyContentObserver(blocklist);
        verify(mCallback, never()).onUpdateSatelliteBlocklist(any(int[].class), any(int[].class));
    }

    private void updateBlocklistAndNotifyContentObserver(String blocklist) {
        Settings.Global.putString(mContentResolver,
                Settings.Global.GNSS_SATELLITE_BLOCKLIST, blocklist);
        notifyContentObserverFor(Settings.Global.GNSS_SATELLITE_BLOCKLIST);
    }

    private void updateBlocklistAndVerifyCallbackIsCalled(String blocklist) {
        updateBlocklistAndNotifyContentObserver(blocklist);

        ArgumentCaptor<int[]> constellationsCaptor = ArgumentCaptor.forClass(int[].class);
        ArgumentCaptor<int[]> svIdsCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mCallback).onUpdateSatelliteBlocklist(constellationsCaptor.capture(),
                svIdsCaptor.capture());

        int[] constellations = constellationsCaptor.getValue();
        int[] svIds = svIdsCaptor.getValue();
        List<Integer> values = GnssSatelliteBlocklistHelper.parseSatelliteBlocklist(blocklist);
        assertThat(values.size()).isEqualTo(constellations.length * 2);
        assertThat(svIds.length).isEqualTo(constellations.length);
        for (int i = 0; i < constellations.length; i++) {
            assertThat(constellations[i]).isEqualTo(values.get(i * 2));
            assertThat(svIds[i]).isEqualTo(values.get(i * 2 + 1));
        }
    }

    private static void notifyContentObserverFor(String globalSetting) {
        Collection<ContentObserver> contentObservers =
                Shadows.shadowOf(RuntimeEnvironment.application.getContentResolver())
                        .getContentObservers(Settings.Global.getUriFor(globalSetting));
        assertThat(contentObservers).isNotEmpty();
        contentObservers.iterator().next().onChange(false /* selfChange */);
    }
}
