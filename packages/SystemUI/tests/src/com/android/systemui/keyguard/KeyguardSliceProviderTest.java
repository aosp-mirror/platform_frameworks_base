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

import androidx.app.slice.Slice;
import android.content.Intent;
import android.net.Uri;
import android.os.Debug;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import androidx.app.slice.SliceItem;
import androidx.app.slice.SliceProvider;
import androidx.app.slice.SliceSpecs;
import androidx.app.slice.core.SliceQuery;
import androidx.app.slice.widget.SliceLiveData;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class KeyguardSliceProviderTest extends SysuiTestCase {

    private TestableKeyguardSliceProvider mProvider;

    @Before
    public void setup() {
        mProvider = new TestableKeyguardSliceProvider();
        mProvider.attachInfo(getContext(), null);
        SliceProvider.setSpecs(Arrays.asList(SliceSpecs.LIST));
    }

    @Test
    public void registersClockUpdate() {
        Assert.assertTrue("registerClockUpdate should have been called during initialization.",
                mProvider.isRegistered());
    }

    @Test
    public void unregisterClockUpdate() {
        mProvider.unregisterClockUpdate();
        Assert.assertFalse("Clock updates should have been unregistered.",
                mProvider.isRegistered());
    }

    @Test
    public void returnsValidSlice() {
        Slice slice = mProvider.onBindSlice(Uri.parse(KeyguardSliceProvider.KEYGUARD_SLICE_URI));
        SliceItem text = SliceQuery.find(slice, android.app.slice.SliceItem.FORMAT_TEXT,
                android.app.slice.Slice.HINT_TITLE,
                null /* nonHints */);
        Assert.assertNotNull("Slice must provide a title.", text);
    }

    @Test
    public void cleansDateFormat() {
        mProvider.mIntentReceiver.onReceive(getContext(), new Intent(Intent.ACTION_TIMEZONE_CHANGED));
        TestableLooper.get(this).processAllMessages();
        Assert.assertEquals("Date format should have been cleaned.", 1 /* expected */,
                mProvider.mCleanDateFormatInvokations);
    }

    @Test
    public void updatesClock() {
        mProvider.mUpdateClockInvokations = 0;
        mProvider.mIntentReceiver.onReceive(getContext(), new Intent(Intent.ACTION_TIME_TICK));
        TestableLooper.get(this).processAllMessages();
        Assert.assertEquals("Clock should have been updated.", 1 /* expected */,
                mProvider.mUpdateClockInvokations);
    }

    private class TestableKeyguardSliceProvider extends KeyguardSliceProvider {
        int mCleanDateFormatInvokations;
        int mUpdateClockInvokations;

        TestableKeyguardSliceProvider() {
            super(new Handler(TestableLooper.get(KeyguardSliceProviderTest.this).getLooper()));
        }

        @Override
        void cleanDateFormat() {
            super.cleanDateFormat();
            mCleanDateFormatInvokations++;
        }

        @Override
        protected void updateClock() {
            super.updateClock();
            mUpdateClockInvokations++;
        }
    }

}
