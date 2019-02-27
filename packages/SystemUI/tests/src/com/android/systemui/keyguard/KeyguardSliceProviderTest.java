/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.media.MediaMetadata;
import android.net.Uri;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.slice.Slice;
import androidx.slice.SliceItem;
import androidx.slice.SliceProvider;
import androidx.slice.SliceSpecs;
import androidx.slice.builders.ListBuilder;
import androidx.slice.core.SliceQuery;
import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationMediaManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class KeyguardSliceProviderTest extends SysuiTestCase {

    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private NotificationMediaManager mNotificationMediaManager;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    private TestableKeyguardSliceProvider mProvider;
    private boolean mIsZenMode;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mIsZenMode = false;
        mProvider = new TestableKeyguardSliceProvider();
        mProvider.attachInfo(getContext(), null);
        mProvider.initDependencies(mNotificationMediaManager, mStatusBarStateController);
        SliceProvider.setSpecs(new HashSet<>(Arrays.asList(SliceSpecs.LIST)));
    }

    @Test
    public void registersClockUpdate() {
        Assert.assertTrue("registerClockUpdate should have been called during initialization.",
                mProvider.isRegistered());
    }

    @Test
    public void returnsValidSlice() {
        Slice slice = mProvider.onBindSlice(mProvider.getUri());
        SliceItem text = SliceQuery.find(slice, android.app.slice.SliceItem.FORMAT_TEXT,
                android.app.slice.Slice.HINT_TITLE,
                null /* nonHints */);
        Assert.assertNotNull("Slice must provide a title.", text);
    }

    @Test
    public void onBindSlice_readsMedia() {
        MediaMetadata metadata = mock(MediaMetadata.class);
        mProvider.onDozingChanged(true);
        mProvider.onMetadataChanged(metadata);
        mProvider.onBindSlice(mProvider.getUri());
        verify(metadata).getText(eq(MediaMetadata.METADATA_KEY_TITLE));
        verify(metadata).getText(eq(MediaMetadata.METADATA_KEY_ARTIST));
        verify(mNotificationMediaManager).getMediaIcon();
    }

    @Test
    public void cleansDateFormat() {
        mProvider.mKeyguardUpdateMonitorCallback.onTimeZoneChanged(null);
        TestableLooper.get(this).processAllMessages();
        Assert.assertEquals("Date format should have been cleaned.", 1 /* expected */,
                mProvider.mCleanDateFormatInvokations);
    }

    @Test
    public void updatesClock() {
        mProvider.mKeyguardUpdateMonitorCallback.onTimeChanged();
        TestableLooper.get(this).processAllMessages();
        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));
    }

    @Test
    public void schedulesAlarm12hBefore() {
        long in16Hours = System.currentTimeMillis() + TimeUnit.HOURS.toHours(16);
        AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(in16Hours, null);
        mProvider.onNextAlarmChanged(alarmClockInfo);

        long twelveHours = TimeUnit.HOURS.toMillis(KeyguardSliceProvider.ALARM_VISIBILITY_HOURS);
        long triggerAt = in16Hours - twelveHours;
        verify(mAlarmManager).setExact(eq(AlarmManager.RTC), eq(triggerAt), anyString(), any(),
                any());
    }

    @Test
    public void updatingNextAlarmInvalidatesSlice() {
        long in16Hours = System.currentTimeMillis() + TimeUnit.HOURS.toHours(8);
        AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(in16Hours, null);
        mProvider.onNextAlarmChanged(alarmClockInfo);

        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));
    }

    @Test
    public void onZenChanged_updatesSlice() {
        mProvider.onZenChanged(Settings.Global.ZEN_MODE_ALARMS);
        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));
    }

    @Test
    public void addZenMode_addedToSlice() {
        ListBuilder listBuilder = spy(new ListBuilder(getContext(), mProvider.getUri(),
            ListBuilder.INFINITY));
        mProvider.addZenModeLocked(listBuilder);
        verify(listBuilder, never()).addRow(any(ListBuilder.RowBuilder.class));

        mIsZenMode = true;
        mProvider.addZenModeLocked(listBuilder);
        verify(listBuilder).addRow(any(ListBuilder.RowBuilder.class));
    }

    @Test
    public void onMetadataChanged_updatesSlice() {
        mProvider.onDozingChanged(true);
        reset(mContentResolver);
        mProvider.onMetadataChanged(mock(MediaMetadata.class));
        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));

        // Hides after waking up
        reset(mContentResolver);
        mProvider.onDozingChanged(false);
        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));
    }

    @Test
    public void onDozingChanged_updatesSliceIfMedia() {
        mProvider.onMetadataChanged(mock(MediaMetadata.class));
        reset(mContentResolver);
        // Show media when dozing
        mProvider.onDozingChanged(true);
        verify(mContentResolver).notifyChange(eq(mProvider.getUri()), eq(null));

        // Do not notify again if nothing changed
        reset(mContentResolver);
        mProvider.onDozingChanged(true);
        verify(mContentResolver, never()).notifyChange(eq(mProvider.getUri()), eq(null));
    }

    private class TestableKeyguardSliceProvider extends KeyguardSliceProvider {
        int mCleanDateFormatInvokations;
        private int mCounter;

        Uri getUri() {
            return mSliceUri;
        }

        @Override
        public boolean onCreateSliceProvider() {
            super.onCreateSliceProvider();
            mAlarmManager = KeyguardSliceProviderTest.this.mAlarmManager;
            mContentResolver = KeyguardSliceProviderTest.this.mContentResolver;
            return true;
        }

        @Override
        protected boolean isDndOn() {
            return mIsZenMode;
        }

        @Override
        void cleanDateFormatLocked() {
            super.cleanDateFormatLocked();
            mCleanDateFormatInvokations++;
        }

        @Override
        public KeyguardUpdateMonitor getKeyguardUpdateMonitor() {
            return mock(KeyguardUpdateMonitor.class);
        }

        @Override
        protected String getFormattedDateLocked() {
            return super.getFormattedDateLocked() + mCounter++;
        }
    }

}
