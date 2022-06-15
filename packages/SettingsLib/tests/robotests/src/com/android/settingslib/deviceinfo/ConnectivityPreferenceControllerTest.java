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
 * limitations under the License.
 */

package com.android.settingslib.deviceinfo;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;

import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ConnectivityPreferenceControllerTest {
    @Mock
    private Context mContext;

    @Mock
    private Lifecycle mLifecycle;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testBroadcastReceiver() {
        final AbstractConnectivityPreferenceController preferenceController =
                spy(new ConcreteConnectivityPreferenceController(mContext, mLifecycle));

        final ArgumentCaptor<BroadcastReceiver> receiverArgumentCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<IntentFilter> filterArgumentCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        doReturn(new String[] {"Filter1", "Filter2"})
                .when(preferenceController).getConnectivityIntents();

        preferenceController.onStart();

        verify(mContext, times(1))
                .registerReceiver(receiverArgumentCaptor.capture(),
                        filterArgumentCaptor.capture(),
                        anyString(), nullable(Handler.class));

        final BroadcastReceiver receiver = receiverArgumentCaptor.getValue();
        final IntentFilter filter = filterArgumentCaptor.getValue();

        assertWithMessage("intent filter should match 'Filter1'")
                .that(filter.matchAction("Filter1"))
                .isTrue();
        assertWithMessage("intent filter should match 'Filter2'")
                .that(filter.matchAction("Filter2"))
                .isTrue();

        preferenceController.onStop();

        verify(mContext, times(1)).unregisterReceiver(receiver);
    }

    private static class ConcreteConnectivityPreferenceController
            extends AbstractConnectivityPreferenceController {

        private ConcreteConnectivityPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String getPreferenceKey() {
            return null;
        }

        @Override
        protected String[] getConnectivityIntents() {
            return new String[0];
        }

        @Override
        protected void updateConnectivity() {

        }
    }
}
