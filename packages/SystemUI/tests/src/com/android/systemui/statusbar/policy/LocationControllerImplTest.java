/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.location.LocationManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.BootCompleteCache;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class LocationControllerImplTest extends SysuiTestCase {

    private LocationControllerImpl mLocationController;
    private TestableLooper mTestableLooper;

    @Before
    public void setup() {
        mTestableLooper = TestableLooper.get(this);
        mLocationController = spy(new LocationControllerImpl(mContext,
                mTestableLooper.getLooper(),
                mTestableLooper.getLooper(),
                mock(BroadcastDispatcher.class),
                mock(BootCompleteCache.class)));
    }

    @Test
    public void testRemoveSelfActive_DoesNotCrash() {
        LocationController.LocationChangeCallback callback = new LocationChangeCallback() {
            @Override
            public void onLocationActiveChanged(boolean active) {
                mLocationController.removeCallback(this);
            }
        };
        mLocationController.addCallback(callback);
        mLocationController.addCallback(mock(LocationChangeCallback.class));

        when(mLocationController.areActiveHighPowerLocationRequests()).thenReturn(false);
        mLocationController.onReceive(mContext, new Intent(
                LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION));
        when(mLocationController.areActiveHighPowerLocationRequests()).thenReturn(true);
        mLocationController.onReceive(mContext, new Intent(
                LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION));

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testRemoveSelfSettings_DoesNotCrash() {
        LocationController.LocationChangeCallback callback = new LocationChangeCallback() {
            @Override
            public void onLocationSettingsChanged(boolean isEnabled) {
                mLocationController.removeCallback(this);
            }
        };
        mLocationController.addCallback(callback);
        mLocationController.addCallback(mock(LocationChangeCallback.class));

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testAddCallback_notifiedImmediately() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);

        mTestableLooper.processAllMessages();

        verify(callback).onLocationSettingsChanged(anyBoolean());
    }

    @Test
    public void testCallbackNotified() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);
        mLocationController.onReceive(mContext, new Intent(LocationManager.MODE_CHANGED_ACTION));

        mTestableLooper.processAllMessages();

        verify(callback, times(2)).onLocationSettingsChanged(anyBoolean());
    }

    @Test
    public void testCallbackRemoved() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);
        mTestableLooper.processAllMessages();

        verify(callback).onLocationSettingsChanged(anyBoolean());
        mLocationController.removeCallback(callback);

        mLocationController.onReceive(mContext, new Intent(LocationManager.MODE_CHANGED_ACTION));

        mTestableLooper.processAllMessages();

        // No new callbacks
        verify(callback).onLocationSettingsChanged(anyBoolean());
    }
}
