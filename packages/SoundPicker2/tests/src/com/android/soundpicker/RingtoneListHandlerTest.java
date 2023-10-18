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

package com.android.soundpicker;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class RingtoneListHandlerTest {

    private static final Uri DEFAULT_URI = Uri.parse("media://custom/ringtone/default_uri");
    private static final Uri RINGTONE_URI = Uri.parse("media://custom/ringtone/uri");
    private static final int SILENT_RINGTONE_POSITION = 0;
    private static final int DEFAULT_RINGTONE_POSITION = 1;
    private static final int RINGTONE_POSITION = 2;

    @Mock
    private RingtoneManager mMockRingtoneManager;
    @Mock
    private Cursor mMockCursor;

    private RingtoneListHandler mRingtoneListHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        RingtoneListHandler.Config mRingtoneListConfig = createRingtoneListConfig();

        mRingtoneListHandler = new RingtoneListHandler();

        // Add silent and default options to the list.
        mRingtoneListHandler.addSilentItem();
        mRingtoneListHandler.addDefaultItem();

        mRingtoneListHandler.init(mRingtoneListConfig, mMockRingtoneManager, mMockCursor);
    }

    @Test
    public void testGetRingtoneCursor_returnsTheCorrectRingtoneCursor() {
        assertThat(mRingtoneListHandler.getRingtoneCursor()).isEqualTo(mMockCursor);
    }

    @Test
    public void testGetRingtoneUri_returnsTheCorrectRingtoneUri() {
        Uri expectedUri = RINGTONE_URI;
        when(mMockRingtoneManager.getRingtoneUri(eq(0))).thenReturn(expectedUri);

        // Request 3rd item from list.
        Uri actualUri = mRingtoneListHandler.getRingtoneUri(RINGTONE_POSITION);
        assertThat(actualUri).isEqualTo(expectedUri);
    }

    @Test
    public void testGetRingtoneUri_withSelectedItemUnknown_returnsTheCorrectRingtoneUri() {
        Uri uri = mRingtoneListHandler.getRingtoneUri(RingtoneListHandler.ITEM_POSITION_UNKNOWN);
        assertThat(uri).isEqualTo(RingtoneListHandler.SILENT_URI);
    }

    @Test
    public void testGetRingtoneUri_withSelectedItemDefaultPosition_returnsTheCorrectRingtoneUri() {
        Uri actualUri = mRingtoneListHandler.getRingtoneUri(DEFAULT_RINGTONE_POSITION);
        assertThat(actualUri).isEqualTo(DEFAULT_URI);
    }

    @Test
    public void testGetRingtoneUri_withSelectedItemSilentPosition_returnsTheCorrectRingtoneUri() {
        Uri uri = mRingtoneListHandler.getRingtoneUri(SILENT_RINGTONE_POSITION);
        assertThat(uri).isEqualTo(RingtoneListHandler.SILENT_URI);
    }

    @Test
    public void testGetCurrentlySelectedRingtoneUri_returnsTheCorrectRingtoneUri() {
        mRingtoneListHandler.setSelectedItemPosition(RingtoneListHandler.ITEM_POSITION_UNKNOWN);
        Uri actualUri = mRingtoneListHandler.getSelectedRingtoneUri();
        assertThat(actualUri).isEqualTo(RingtoneListHandler.SILENT_URI);

        mRingtoneListHandler.setSelectedItemPosition(DEFAULT_RINGTONE_POSITION);
        actualUri = mRingtoneListHandler.getSelectedRingtoneUri();
        assertThat(actualUri).isEqualTo(DEFAULT_URI);

        mRingtoneListHandler.setSelectedItemPosition(SILENT_RINGTONE_POSITION);
        actualUri = mRingtoneListHandler.getSelectedRingtoneUri();
        assertThat(actualUri).isEqualTo(RingtoneListHandler.SILENT_URI);

        when(mMockRingtoneManager.getRingtoneUri(eq(0))).thenReturn(RINGTONE_URI);
        mRingtoneListHandler.setSelectedItemPosition(RINGTONE_POSITION);
        actualUri = mRingtoneListHandler.getSelectedRingtoneUri();
        assertThat(actualUri).isEqualTo(RINGTONE_URI);
    }

    @Test
    public void testGetRingtonePosition_returnsTheCorrectRingtonePosition() {
        when(mMockRingtoneManager.getRingtonePosition(RINGTONE_URI)).thenReturn(0);

        int actualPosition = mRingtoneListHandler.getRingtonePosition(RINGTONE_URI);

        assertThat(actualPosition).isEqualTo(RINGTONE_POSITION);

    }

    @Test
    public void testFixedItems_onlyAddsItemsOnceAndInOrder() {
        // Clear fixed items before testing the add methods.
        mRingtoneListHandler.resetFixedItems();

        assertThat(mRingtoneListHandler.getSilentItemPosition()).isEqualTo(
                RingtoneListHandler.ITEM_POSITION_UNKNOWN);
        assertThat(mRingtoneListHandler.getDefaultItemPosition()).isEqualTo(
                RingtoneListHandler.ITEM_POSITION_UNKNOWN);

        mRingtoneListHandler.addSilentItem();
        mRingtoneListHandler.addDefaultItem();
        mRingtoneListHandler.addSilentItem();
        mRingtoneListHandler.addDefaultItem();

        assertThat(mRingtoneListHandler.getSilentItemPosition()).isEqualTo(
                SILENT_RINGTONE_POSITION);
        assertThat(mRingtoneListHandler.getDefaultItemPosition()).isEqualTo(
                DEFAULT_RINGTONE_POSITION);
    }

    @Test
    public void testResetFixedItems_resetsSilentAndDefaultItemPositions() {
        assertThat(mRingtoneListHandler.getSilentItemPosition()).isEqualTo(
                SILENT_RINGTONE_POSITION);
        assertThat(mRingtoneListHandler.getDefaultItemPosition()).isEqualTo(
                DEFAULT_RINGTONE_POSITION);

        mRingtoneListHandler.resetFixedItems();

        assertThat(mRingtoneListHandler.getSilentItemPosition()).isEqualTo(
                RingtoneListHandler.ITEM_POSITION_UNKNOWN);
        assertThat(mRingtoneListHandler.getDefaultItemPosition()).isEqualTo(
                RingtoneListHandler.ITEM_POSITION_UNKNOWN);
    }

    private RingtoneListHandler.Config createRingtoneListConfig() {
        return new RingtoneListHandler.Config(/* hasDefaultItem= */ true,
                /* uriForDefaultItem= */ DEFAULT_URI, /* hasSilentItem= */ true,
                /* existingUri= */ DEFAULT_URI);
    }
}
