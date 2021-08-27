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

package com.android.systemui.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.graphics.Color;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MediaDataCombineLatestTest extends SysuiTestCase {

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private static final String KEY = "TEST_KEY";
    private static final String OLD_KEY = "TEST_KEY_OLD";
    private static final String APP = "APP";
    private static final String PACKAGE = "PKG";
    private static final int BG_COLOR = Color.RED;
    private static final String ARTIST = "ARTIST";
    private static final String TITLE = "TITLE";
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final int USER_ID = 0;

    private MediaDataCombineLatest mManager;

    @Mock private MediaDataManager.Listener mListener;

    private MediaData mMediaData;
    private MediaDeviceData mDeviceData;

    @Before
    public void setUp() {
        mManager = new MediaDataCombineLatest();
        mManager.addListener(mListener);

        mMediaData = new MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null,
                new ArrayList<>(), new ArrayList<>(), PACKAGE, null, null, null, true, null, true,
                false, KEY, false, false, false, 0L);
        mDeviceData = new MediaDeviceData(true, null, DEVICE_NAME);
    }

    @Test
    public void eventNotEmittedWithoutDevice() {
        // WHEN data source emits an event without device data
        mManager.onMediaDataLoaded(KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // THEN an event isn't emitted
        verify(mListener, never()).onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(),
                anyBoolean());
    }

    @Test
    public void eventNotEmittedWithoutMedia() {
        // WHEN device source emits an event without media data
        mManager.onMediaDeviceChanged(KEY, null, mDeviceData);
        // THEN an event isn't emitted
        verify(mListener, never()).onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(),
                anyBoolean());
    }

    @Test
    public void emitEventAfterDeviceFirst() {
        // GIVEN that a device event has already been received
        mManager.onMediaDeviceChanged(KEY, null, mDeviceData);
        // WHEN media event is received
        mManager.onMediaDataLoaded(KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), any(), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void emitEventAfterMediaFirst() {
        // GIVEN that media event has already been received
        mManager.onMediaDataLoaded(KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // WHEN device event is received
        mManager.onMediaDeviceChanged(KEY, null, mDeviceData);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), any(), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void migrateKeyMediaFirst() {
        // GIVEN that media and device info has already been received
        mManager.onMediaDataLoaded(OLD_KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDeviceChanged(OLD_KEY, null, mDeviceData);
        reset(mListener);
        // WHEN a key migration event is received
        mManager.onMediaDataLoaded(KEY, OLD_KEY, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), eq(OLD_KEY), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void migrateKeyDeviceFirst() {
        // GIVEN that media and device info has already been received
        mManager.onMediaDataLoaded(OLD_KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDeviceChanged(OLD_KEY, null, mDeviceData);
        reset(mListener);
        // WHEN a key migration event is received
        mManager.onMediaDeviceChanged(KEY, OLD_KEY, mDeviceData);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), eq(OLD_KEY), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void migrateKeyMediaAfter() {
        // GIVEN that media and device info has already been received
        mManager.onMediaDataLoaded(OLD_KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDeviceChanged(OLD_KEY, null, mDeviceData);
        mManager.onMediaDeviceChanged(KEY, OLD_KEY, mDeviceData);
        reset(mListener);
        // WHEN a second key migration event is received for media
        mManager.onMediaDataLoaded(KEY, OLD_KEY, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // THEN the key has already been migrated
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), eq(KEY), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void migrateKeyDeviceAfter() {
        // GIVEN that media and device info has already been received
        mManager.onMediaDataLoaded(OLD_KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDeviceChanged(OLD_KEY, null, mDeviceData);
        mManager.onMediaDataLoaded(KEY, OLD_KEY, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        reset(mListener);
        // WHEN a second key migration event is received for the device
        mManager.onMediaDeviceChanged(KEY, OLD_KEY, mDeviceData);
        // THEN the key has already be migrated
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), eq(KEY), captor.capture(), anyBoolean(),
                anyBoolean());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void mediaDataRemoved() {
        // WHEN media data is removed without first receiving device or data
        mManager.onMediaDataRemoved(KEY);
        // THEN a removed event isn't emitted
        verify(mListener, never()).onMediaDataRemoved(eq(KEY));
    }

    @Test
    public void mediaDataRemovedAfterMediaEvent() {
        mManager.onMediaDataLoaded(KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDataRemoved(KEY);
        verify(mListener).onMediaDataRemoved(eq(KEY));
    }

    @Test
    public void mediaDataRemovedAfterDeviceEvent() {
        mManager.onMediaDeviceChanged(KEY, null, mDeviceData);
        mManager.onMediaDataRemoved(KEY);
        verify(mListener).onMediaDataRemoved(eq(KEY));
    }

    @Test
    public void mediaDataKeyUpdated() {
        // GIVEN that device and media events have already been received
        mManager.onMediaDataLoaded(KEY, null, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        mManager.onMediaDeviceChanged(KEY, null, mDeviceData);
        // WHEN the key is changed
        mManager.onMediaDataLoaded("NEW_KEY", KEY, mMediaData, true /* immediately */,
                false /* isSsReactivated */);
        // THEN the listener gets a load event with the correct keys
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(
                eq("NEW_KEY"), any(), captor.capture(), anyBoolean(), anyBoolean());
    }
}
