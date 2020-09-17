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

package com.android.systemui.settings;

import android.content.Intent;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Testing functionality of the current user tracker
 */
@SmallTest
public class CurrentUserTrackerTest extends SysuiTestCase {

    private CurrentUserTracker mTracker;
    private CurrentUserTracker.UserReceiver mReceiver;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReceiver = new CurrentUserTracker.UserReceiver(mBroadcastDispatcher);
        mTracker = new CurrentUserTracker(mReceiver) {
            @Override
            public void onUserSwitched(int newUserId) {
                stopTracking();
            }
        };
    }

    @Test
    public void testBroadCastDoesntCrashOnConcurrentModification() {
        mTracker.startTracking();
        CurrentUserTracker secondTracker = new CurrentUserTracker(mReceiver) {
            @Override
            public void onUserSwitched(int newUserId) {
                stopTracking();
            }
        };
        secondTracker.startTracking();
        triggerUserSwitch();
    }
    /**
     * Simulates a user switch event.
     */
    private void triggerUserSwitch() {
        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 1);
        mReceiver.onReceive(getContext(), intent);
    }
}
