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

package com.android.systemui.media.dialog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.settingslib.flags.Flags;
import com.android.settingslib.media.MediaOutputConstants;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MediaOutputDialogReceiverTest extends SysuiTestCase {

    private MediaOutputDialogReceiver mMediaOutputDialogReceiver;

    private final MediaOutputDialogFactory mMockMediaOutputDialogFactory =
            mock(MediaOutputDialogFactory.class);

    private final MediaOutputBroadcastDialogFactory mMockMediaOutputBroadcastDialogFactory =
            mock(MediaOutputBroadcastDialogFactory.class);

    @Before
    public void setup() {
        mMediaOutputDialogReceiver = new MediaOutputDialogReceiver(mMockMediaOutputDialogFactory,
                mMockMediaOutputBroadcastDialogFactory);
    }

    @Test
    public void launchMediaOutputDialog_ExtraPackageName_DialogFactoryCalled() {
        Intent intent = new Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG);
        intent.putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, times(1))
                .create(getContext().getPackageName(), false, null);
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void launchMediaOutputDialog_WrongExtraKey_DialogFactoryNotCalled() {
        Intent intent = new Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG);
        intent.putExtra("Wrong Package Name Key", getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void launchMediaOutputDialog_NoExtra_DialogFactoryNotCalled() {
        Intent intent = new Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG);
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void launchMediaOutputBroadcastDialog_flagOff_broadcastDialogFactoryNotCalled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_LEGACY_LE_AUDIO_SHARING);
        Intent intent = new Intent(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG);
        intent.putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void launchMediaOutputBroadcastDialog_ExtraPackageName_BroadcastDialogFactoryCalled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEGACY_LE_AUDIO_SHARING);
        Intent intent = new Intent(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG);
        intent.putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, times(1))
                .create(getContext().getPackageName(), true, null);
    }

    @Test
    public void launchMediaOutputBroadcastDialog_WrongExtraKey_DialogBroadcastFactoryNotCalled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEGACY_LE_AUDIO_SHARING);
        Intent intent = new Intent(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG);
        intent.putExtra("Wrong Package Name Key", getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void launchMediaOutputBroadcastDialog_NoExtra_BroadcastDialogFactoryNotCalled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEGACY_LE_AUDIO_SHARING);
        Intent intent = new Intent(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG);
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void unKnownAction_ExtraPackageName_FactoriesNotCalled() {
        Intent intent = new Intent("UnKnown Action");
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, getContext().getPackageName());
        intent.putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, getContext().getPackageName());
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }

    @Test
    public void unKnownActionAnd_NoExtra_FactoriesNotCalled() {
        Intent intent = new Intent("UnKnown Action");
        mMediaOutputDialogReceiver.onReceive(getContext(), intent);

        verify(mMockMediaOutputDialogFactory, never()).create(any(), anyBoolean(), any());
        verify(mMockMediaOutputBroadcastDialogFactory, never()).create(any(), anyBoolean(), any());
    }
}
