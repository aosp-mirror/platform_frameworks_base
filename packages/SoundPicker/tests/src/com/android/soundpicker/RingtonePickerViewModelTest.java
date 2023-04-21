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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class RingtonePickerViewModelTest {

    private static final Uri DEFAULT_URI = Uri.parse("https://www.google.com/login.html");
    private static final int POS_UNKNOWN = -1;
    private static final int NO_ATTRIBUTES_FLAGS = 0;
    private static final int SILENT_RINGTONE_POSITION = 0;
    private static final int DEFAULT_RINGTONE_POSITION = 1;
    private static final int RINGTONE_POSITION = 2;

    @Mock
    private RingtoneManagerFactory mMockRingtoneManagerFactory;
    @Mock
    private RingtoneFactory mMockRingtoneFactory;
    @Mock
    private RingtoneManager mMockRingtoneManager;
    @Mock
    private Cursor mMockCursor;

    private Ringtone mMockDefaultRingtone;
    private Ringtone mMockRingtone;
    private RingtonePickerViewModel mViewModel;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockRingtoneManagerFactory.create()).thenReturn(mMockRingtoneManager);
        mMockDefaultRingtone = createMockRingtone();
        mMockRingtone = createMockRingtone();
        when(mMockRingtoneFactory.create(DEFAULT_URI)).thenReturn(mMockDefaultRingtone);
        when(mMockRingtoneManager.getRingtone(anyInt())).thenReturn(mMockRingtone);

        mViewModel = new RingtonePickerViewModel(mMockRingtoneManagerFactory, mMockRingtoneFactory);

        mViewModel.setSilentItemPosition(SILENT_RINGTONE_POSITION);
        mViewModel.setDefaultItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);
    }

    @Test
    public void testGetStreamType_returnsTheCorrectStreamType() {
        when(mMockRingtoneManager.inferStreamType()).thenReturn(AudioManager.STREAM_ALARM);
        assertEquals(mViewModel.getRingtoneStreamType(), AudioManager.STREAM_ALARM);
    }

    @Test
    public void testGetRingtoneCursor_returnsTheCorrectRingtoneCursor() {
        when(mMockRingtoneManager.getCursor()).thenReturn(mMockCursor);
        assertEquals(mViewModel.getRingtoneCursor(), mMockCursor);
    }

    @Test
    public void testGetRingtoneUri_returnsTheCorrectRingtoneUri() {
        Uri expectedUri = DEFAULT_URI;
        when(mMockRingtoneManager.getRingtoneUri(anyInt())).thenReturn(expectedUri);
        Uri actualUri = mViewModel.getRingtoneUri(DEFAULT_RINGTONE_POSITION);
        assertEquals(actualUri, expectedUri);
    }

    @Test
    public void testOnPause_withChangingConfigurationTrue_doNotStopPlayingRingtone() {
        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockRingtone);
        mViewModel.onPause(/* isChangingConfigurations= */ true);
        verify(mMockRingtone, never()).stop();
    }

    @Test
    public void testOnPause_withChangingConfigurationFalse_stopPlayingRingtone() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);
        mViewModel.onPause(/* isChangingConfigurations= */ false);
        verify(mMockDefaultRingtone).stop();
    }

    @Test
    public void testOnViewModelRecreated_previousRingtoneCanStillBeStopped() {
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);
        Ringtone mockRingtone1 = createMockRingtone();
        Ringtone mockRingtone2 = createMockRingtone();
        when(mMockRingtoneManager.getRingtone(anyInt())).thenReturn(mockRingtone1, mockRingtone2);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mockRingtone1);
        // Fake a scenario where the activity is destroyed and recreated due to a config change.
        // This will result in a new view model getting created.
        mViewModel.onStop(/* isChangingConfigurations= */ true);
        verify(mockRingtone1, never()).stop();
        mViewModel = new RingtonePickerViewModel(mMockRingtoneManagerFactory, mMockRingtoneFactory);
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mockRingtone2);
        verify(mockRingtone1).stop();
        verify(mockRingtone2, never()).stop();
    }

    @Test
    public void testOnStop_withChangingConfigurationTrueAndDefaultRingtonePlaying_saveRingtone() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);
        mViewModel.onStop(/* isChangingConfigurations= */ true);
        assertEquals(RingtonePickerViewModel.sPlayingRingtone, mMockDefaultRingtone);
    }

    @Test
    public void testOnStop_withChangingConfigurationTrueAndCurrentRingtonePlaying_saveRingtone() {
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);
        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockRingtone);
        mViewModel.onStop(/* isChangingConfigurations= */ true);
        assertEquals(RingtonePickerViewModel.sPlayingRingtone, mMockRingtone);
    }

    @Test
    public void testOnStop_withChangingConfigurationTrueAndNoPlayingRingtone_saveNothing() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.onStop(/* isChangingConfigurations= */ true);
        assertNull(RingtonePickerViewModel.sPlayingRingtone);
    }

    @Test
    public void testOnStop_withChangingConfigurationFalse_stopPlayingRingtone() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);
        mViewModel.onStop(/* isChangingConfigurations= */ false);
        verify(mMockDefaultRingtone).stop();
    }

    @Test
    public void testGetCurrentlySelectedRingtoneUri_checkedItemIsUnknown_returnsNull() {
        Uri uri = mViewModel.getCurrentlySelectedRingtoneUri(POS_UNKNOWN, DEFAULT_URI);
        assertNull(uri);
    }

    @Test
    public void testGetCurrentlySelectedRingtoneUri_checkedItemIsDefaultPos_returnsDefaultUri() {
        Uri expectedUri = DEFAULT_URI;
        Uri actualUri = mViewModel.getCurrentlySelectedRingtoneUri(DEFAULT_RINGTONE_POSITION,
                expectedUri);
        assertEquals(actualUri, expectedUri);
    }

    @Test
    public void testGetCurrentlySelectedRingtoneUri_checkedItemIsSilentPos_returnsNull() {
        Uri uri = mViewModel.getCurrentlySelectedRingtoneUri(SILENT_RINGTONE_POSITION, DEFAULT_URI);
        assertNull(uri);
    }

    @Test
    public void testAddRingtone_returnsTheCorrectUri() throws IOException {
        Uri expectedUri = DEFAULT_URI;
        when(mMockRingtoneManager.addCustomExternalRingtone(any(), anyInt())).thenReturn(
                expectedUri);
        Uri actualUri = mViewModel.addRingtone(DEFAULT_URI, RingtoneManager.TYPE_NOTIFICATION);
        verify(mMockRingtoneManager).addCustomExternalRingtone(DEFAULT_URI,
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(actualUri, expectedUri);
    }

    @Test
    public void testGetCurrentlySelectedRingtoneUri_checkedItemRingtonePos_returnsTheCorrectUri() {
        Uri expectedUri = DEFAULT_URI;
        when(mMockRingtoneManager.getRingtoneUri(RINGTONE_POSITION)).thenReturn(expectedUri);
        Uri actualUri = mViewModel.getCurrentlySelectedRingtoneUri(RINGTONE_POSITION, DEFAULT_URI);

        verify(mMockRingtoneManager).getRingtoneUri(RINGTONE_POSITION);
        assertEquals(actualUri, expectedUri);
    }

    @Test
    public void testPlayRingtone_stopsPreviouslyRunningRingtone() {
        // Start playing the first ringtone
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);
        // Start playing the second ringtone
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);
        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockRingtone);

        verify(mMockDefaultRingtone).stop();
    }

    @Test
    public void testPlayRingtone_samplePosEqualToSilentPos_onlyStopPlayingRingtone() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);
        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);

        mViewModel.setSampleItemPosition(SILENT_RINGTONE_POSITION);
        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verify(mMockDefaultRingtone).stop();
        // This will be invoked on the first ringtone we play, but not on the second one.
        verify(mMockRingtoneFactory).create(any());
        verify(mMockRingtoneManager, never()).getRingtone(anyInt());
        verify(mMockRingtone, never()).play();

    }

    @Test
    public void testPlayRingtone_samplePosEqualToDefaultPos_playDefaultRingtone() {
        mViewModel.setSampleItemPosition(DEFAULT_RINGTONE_POSITION);

        when(mMockRingtoneManager.inferStreamType()).thenReturn(AudioManager.STREAM_ALARM);

        mViewModel.playRingtone(DEFAULT_RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockDefaultRingtone);

        verify(mMockDefaultRingtone).setStreamType(AudioManager.STREAM_ALARM);
        verify(mMockDefaultRingtone).play();
    }

    @Test
    public void testPlayRingtone_samplePosNotEqualToDefaultPos_playRingtone() {
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);

        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
        verifyRingtonePlayCalledAndMockPlayingState(mMockRingtone);
        verify(mMockRingtone).setAudioAttributes(
                audioAttributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                        AudioAttributes.FLAG_AUDIBILITY_ENFORCED));
        verify(mMockRingtone).play();
    }

    @Test
    public void testPlayRingtone_withNoAttributeFlags_doNotUpdateRingtoneAttributesFlags() {
        mViewModel.setSampleItemPosition(RINGTONE_POSITION);

        mViewModel.playRingtone(RINGTONE_POSITION, DEFAULT_URI,
                NO_ATTRIBUTES_FLAGS);
        verifyRingtonePlayCalledAndMockPlayingState(mMockRingtone);
        verify(mMockRingtone, never()).setAudioAttributes(any());
        verify(mMockRingtone).play();
    }

    @Test
    public void testGetRingtonePosition_returnsTheCorrectRingtonePosition() {
        int expectedPosition = 1;
        when(mMockRingtoneManager.getRingtonePosition(any())).thenReturn(expectedPosition);

        int actualPosition = mViewModel.getRingtonePosition(DEFAULT_URI);

        assertEquals(actualPosition, expectedPosition);
    }

    @Test
    public void testDefaultItemUri_withNotificationIntent_returnDefaultNotificationUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withAlarmIntent_returnDefaultAlarmUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(RingtoneManager.TYPE_ALARM);
        assertEquals(Settings.System.DEFAULT_ALARM_ALERT_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withRingtoneIntent_returnDefaultRingtoneUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(RingtoneManager.TYPE_RINGTONE);
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withInvalidRingtoneType_returnDefaultRingtoneUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(-1);
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, uri);
    }

    @Test
    public void testTitle_withNotificationRingtoneType_returnRingtoneNotificationTitle() {
        int title = RingtonePickerViewModel.getTitleByType(RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(com.android.internal.R.string.ringtone_picker_title_notification, title);
    }

    @Test
    public void testTitle_withAlarmRingtoneType_returnRingtoneAlarmTitle() {
        int title = RingtonePickerViewModel.getTitleByType(RingtoneManager.TYPE_ALARM);
        assertEquals(com.android.internal.R.string.ringtone_picker_title_alarm, title);
    }

    @Test
    public void testTitle_withInvalidRingtoneType_returnDefaultRingtoneTitle() {
        int title = RingtonePickerViewModel.getTitleByType(/*ringtoneType= */ -1);
        assertEquals(com.android.internal.R.string.ringtone_picker_title, title);
    }

    @Test
    public void testAddNewItemText_withAlarmType_returnAlarmAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_ALARM);
        assertEquals(R.string.add_alarm_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withNotificationType_returnNotificationAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.add_notification_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withRingtoneType_returnRingtoneAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_RINGTONE);
        assertEquals(R.string.add_ringtone_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withInvalidType_returnRingtoneAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(-1);
        assertEquals(R.string.add_ringtone_text, addNewItemTextResId);
    }

    @Test
    public void testDefaultItemText_withNotificationType_returnNotificationDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.notification_sound_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withAlarmType_returnAlarmDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.notification_sound_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withRingtoneType_returnRingtoneDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_RINGTONE);
        assertEquals(R.string.ringtone_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withInvalidType_returnRingtoneDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(-1);
        assertEquals(R.string.ringtone_default, defaultRingtoneItemText);
    }

    private Ringtone createMockRingtone() {
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockRingtone.getAudioAttributes()).thenReturn(
                audioAttributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, 0));

        return mockRingtone;
    }

    private void verifyRingtonePlayCalledAndMockPlayingState(Ringtone ringtone) {
        verify(ringtone).play();
        when(ringtone.isPlaying()).thenReturn(true);
    }

    private static AudioAttributes audioAttributes(int audioUsage, int flags) {
        return new AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setFlags(flags)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }
}
