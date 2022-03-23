/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.settingslib.SliceBroadcastRelay;
import com.android.systemui.broadcast.BroadcastDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SliceBroadcastRelayHandlerTest extends SysuiTestCase {

    private static final String TEST_ACTION = "com.android.systemui.action.TEST_ACTION";
    private SliceBroadcastRelayHandler mRelayHandler;
    private Context mSpyContext;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSpyContext = spy(mContext);

        mRelayHandler = new SliceBroadcastRelayHandler(mSpyContext, mBroadcastDispatcher);
    }

    @Test
    public void testRegister() {
        Uri testUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("something")
                .path("test")
                .build();

        Intent intent = new Intent(SliceBroadcastRelay.ACTION_REGISTER);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, ContentProvider.maybeAddUserId(testUri, 0));
        intent.putExtra(SliceBroadcastRelay.EXTRA_RECEIVER,
                new ComponentName(mContext.getPackageName(), Receiver.class.getName()));
        IntentFilter value = new IntentFilter(TEST_ACTION);
        intent.putExtra(SliceBroadcastRelay.EXTRA_FILTER, value);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, testUri);

        mRelayHandler.handleIntent(intent);
        verify(mSpyContext).registerReceiver(any(), eq(value));
    }

    @Test
    public void testUnregister() {
        Uri testUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("something")
                .path("test")
                .build();

        Intent intent = new Intent(SliceBroadcastRelay.ACTION_REGISTER);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, ContentProvider.maybeAddUserId(testUri, 0));
        intent.putExtra(SliceBroadcastRelay.EXTRA_RECEIVER,
                new ComponentName(mContext.getPackageName(), Receiver.class.getName()));
        IntentFilter value = new IntentFilter(TEST_ACTION);
        intent.putExtra(SliceBroadcastRelay.EXTRA_FILTER, value);

        mRelayHandler.handleIntent(intent);
        ArgumentCaptor<BroadcastReceiver> relay = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mSpyContext).registerReceiver(relay.capture(), eq(value));

        intent = new Intent(SliceBroadcastRelay.ACTION_UNREGISTER);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, ContentProvider.maybeAddUserId(testUri, 0));
        mRelayHandler.handleIntent(intent);
        verify(mSpyContext).unregisterReceiver(eq(relay.getValue()));
    }

    @Test
    public void testUnregisterWithoutRegister() {
        Uri testUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("something")
                .path("test")
                .build();

        Intent intent = new Intent(SliceBroadcastRelay.ACTION_UNREGISTER);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, ContentProvider.maybeAddUserId(testUri, 0));
        mRelayHandler.handleIntent(intent);
        // No crash
    }

    @Test
    public void testRelay() {
        Receiver.sReceiver = mock(BroadcastReceiver.class);
        Uri testUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("something")
                .path("test")
                .build();
        Intent intent = new Intent(SliceBroadcastRelay.ACTION_REGISTER);
        intent.putExtra(SliceBroadcastRelay.EXTRA_URI, ContentProvider.maybeAddUserId(testUri, 0));
        intent.putExtra(SliceBroadcastRelay.EXTRA_RECEIVER,
                new ComponentName(mContext.getPackageName(), Receiver.class.getName()));
        IntentFilter value = new IntentFilter(TEST_ACTION);
        intent.putExtra(SliceBroadcastRelay.EXTRA_FILTER, value);

        mRelayHandler.handleIntent(intent);
        ArgumentCaptor<BroadcastReceiver> relay = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mSpyContext).registerReceiver(relay.capture(), eq(value));
        relay.getValue().onReceive(mSpyContext, new Intent(TEST_ACTION));

        verify(Receiver.sReceiver, timeout(2000)).onReceive(any(), any());
    }

    @Test
    public void testRegisteredWithDispatcher() {
        mRelayHandler.start();

        verify(mBroadcastDispatcher)
                .registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));
        verify(mSpyContext, never())
                .registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));
    }

    public static class Receiver extends BroadcastReceiver {
        private static BroadcastReceiver sReceiver;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (sReceiver != null) sReceiver.onReceive(context, intent);
        }
    }

}