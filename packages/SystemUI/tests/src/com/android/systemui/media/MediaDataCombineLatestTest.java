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
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.graphics.Color;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MediaDataCombineLatestTest extends SysuiTestCase {

    private static final String KEY = "TEST_KEY";
    private static final String APP = "APP";
    private static final String PACKAGE = "PKG";
    private static final int BG_COLOR = Color.RED;
    private static final String ARTIST = "ARTIST";
    private static final String TITLE = "TITLE";
    private static final String DEVICE_NAME = "DEVICE_NAME";

    private MediaDataCombineLatest mManager;

    @Mock private MediaDataManager mDataSource;
    @Mock private MediaDeviceManager mDeviceSource;
    @Mock private MediaDataManager.Listener mListener;

    private MediaDataManager.Listener mDataListener;
    private MediaDeviceManager.Listener mDeviceListener;

    private MediaData mMediaData;
    private MediaDeviceData mDeviceData;

    @Before
    public void setUp() {
        mDataSource = mock(MediaDataManager.class);
        mDeviceSource = mock(MediaDeviceManager.class);
        mListener = mock(MediaDataManager.Listener.class);

        mManager = new MediaDataCombineLatest(mDataSource, mDeviceSource);

        mDataListener = captureDataListener();
        mDeviceListener = captureDeviceListener();

        mManager.addListener(mListener);

        mMediaData = new MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null,
                new ArrayList<>(), new ArrayList<>(), PACKAGE, null, null, null);
        mDeviceData = new MediaDeviceData(true, null, DEVICE_NAME);
    }

    @Test
    public void eventNotEmittedWithoutDevice() {
        // WHEN data source emits an event without device data
        mDataListener.onMediaDataLoaded(KEY, mMediaData);
        // THEN an event isn't emitted
        verify(mListener, never()).onMediaDataLoaded(eq(KEY), any());
    }

    @Test
    public void eventNotEmittedWithoutMedia() {
        // WHEN device source emits an event without media data
        mDeviceListener.onMediaDeviceChanged(KEY, mDeviceData);
        // THEN an event isn't emitted
        verify(mListener, never()).onMediaDataLoaded(eq(KEY), any());
    }

    @Test
    public void emitEventAfterDeviceFirst() {
        // GIVEN that a device event has already been received
        mDeviceListener.onMediaDeviceChanged(KEY, mDeviceData);
        // WHEN media event is received
        mDataListener.onMediaDataLoaded(KEY, mMediaData);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), captor.capture());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void emitEventAfterMediaFirst() {
        // GIVEN that media event has already been received
        mDataListener.onMediaDataLoaded(KEY, mMediaData);
        // WHEN device event is received
        mDeviceListener.onMediaDeviceChanged(KEY, mDeviceData);
        // THEN the listener receives a combined event
        ArgumentCaptor<MediaData> captor = ArgumentCaptor.forClass(MediaData.class);
        verify(mListener).onMediaDataLoaded(eq(KEY), captor.capture());
        assertThat(captor.getValue().getDevice()).isNotNull();
    }

    @Test
    public void mediaDataRemoved() {
        // WHEN media data is removed without first receiving device or data
        mDataListener.onMediaDataRemoved(KEY);
        // THEN a removed event isn't emitted
        verify(mListener, never()).onMediaDataRemoved(eq(KEY));
    }

    @Test
    public void mediaDataRemovedAfterMediaEvent() {
        mDataListener.onMediaDataLoaded(KEY, mMediaData);
        mDataListener.onMediaDataRemoved(KEY);
        verify(mListener).onMediaDataRemoved(eq(KEY));
    }

    @Test
    public void mediaDataRemovedAfterDeviceEvent() {
        mDeviceListener.onMediaDeviceChanged(KEY, mDeviceData);
        mDataListener.onMediaDataRemoved(KEY);
        verify(mListener).onMediaDataRemoved(eq(KEY));
    }

    private MediaDataManager.Listener captureDataListener() {
        ArgumentCaptor<MediaDataManager.Listener> captor = ArgumentCaptor.forClass(
                MediaDataManager.Listener.class);
        verify(mDataSource).addListener(captor.capture());
        return captor.getValue();
    }

    private MediaDeviceManager.Listener captureDeviceListener() {
        ArgumentCaptor<MediaDeviceManager.Listener> captor = ArgumentCaptor.forClass(
                MediaDeviceManager.Listener.class);
        verify(mDeviceSource).addListener(captor.capture());
        return captor.getValue();
    }
}
