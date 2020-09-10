/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.media.tv.remoteprovider;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;
import android.media.tv.ITvRemoteProvider;
import android.media.tv.ITvRemoteServiceInput;
import android.os.Binder;
import android.os.IBinder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.ArrayList;

public class TvRemoteProviderTest extends AndroidTestCase {
    private static final String TAG = TvRemoteProviderTest.class.getSimpleName();

    @SmallTest
    public void testOpenRemoteInputBridge() throws Exception {
        Binder tokenA = new Binder();
        Binder tokenB = new Binder();
        Binder tokenC = new Binder();

        class LocalTvRemoteProvider extends TvRemoteProvider {
            private final ArrayList<IBinder> mTokens = new ArrayList<IBinder>();

            LocalTvRemoteProvider(Context context) {
                super(context);
            }

            @Override
            public void onInputBridgeConnected(IBinder token) {
                mTokens.add(token);
            }

            public boolean verifyTokens() {
                return mTokens.size() == 3
                    && mTokens.contains(tokenA)
                    && mTokens.contains(tokenB)
                    && mTokens.contains(tokenC);
            }
        }

        LocalTvRemoteProvider tvProvider = new LocalTvRemoteProvider(getContext());
        ITvRemoteProvider binder = (ITvRemoteProvider) tvProvider.getBinder();

        ITvRemoteServiceInput tvServiceInput = mock(ITvRemoteServiceInput.class);
        doAnswer((i) -> {
            binder.onInputBridgeConnected(i.getArgument(0));
            return null;
        }).when(tvServiceInput).openInputBridge(any(), any(), anyInt(), anyInt(), anyInt());

        tvProvider.openRemoteInputBridge(tokenA, "A", 1, 1, 1);
        tvProvider.openRemoteInputBridge(tokenB, "B", 1, 1, 1);
        binder.setRemoteServiceInputSink(tvServiceInput);
        tvProvider.openRemoteInputBridge(tokenC, "C", 1, 1, 1);

        verify(tvServiceInput).openInputBridge(tokenA, "A", 1, 1, 1);
        verify(tvServiceInput).openInputBridge(tokenB, "B", 1, 1, 1);
        verify(tvServiceInput).openInputBridge(tokenC, "C", 1, 1, 1);
        verifyNoMoreInteractions(tvServiceInput);

        assertTrue(tvProvider.verifyTokens());
    }

    @SmallTest
    public void testOpenGamepadRemoteInputBridge() throws Exception {
        Binder tokenA = new Binder();
        Binder tokenB = new Binder();
        Binder tokenC = new Binder();

        class LocalTvRemoteProvider extends TvRemoteProvider {
            private final ArrayList<IBinder> mTokens = new ArrayList<IBinder>();

            LocalTvRemoteProvider(Context context) {
                super(context);
            }

            @Override
            public void onInputBridgeConnected(IBinder token) {
                mTokens.add(token);
            }

            public boolean verifyTokens() {
                return mTokens.size() == 3 && mTokens.contains(tokenA) && mTokens.contains(tokenB)
                        && mTokens.contains(tokenC);
            }
        }

        LocalTvRemoteProvider tvProvider = new LocalTvRemoteProvider(getContext());
        ITvRemoteProvider binder = (ITvRemoteProvider) tvProvider.getBinder();

        ITvRemoteServiceInput tvServiceInput = mock(ITvRemoteServiceInput.class);
        doAnswer((i) -> {
            binder.onInputBridgeConnected(i.getArgument(0));
            return null;
        })
                .when(tvServiceInput)
                .openGamepadBridge(any(), any());

        tvProvider.openGamepadBridge(tokenA, "A");
        tvProvider.openGamepadBridge(tokenB, "B");
        binder.setRemoteServiceInputSink(tvServiceInput);
        tvProvider.openGamepadBridge(tokenC, "C");

        verify(tvServiceInput).openGamepadBridge(tokenA, "A");
        verify(tvServiceInput).openGamepadBridge(tokenB, "B");
        verify(tvServiceInput).openGamepadBridge(tokenC, "C");
        verifyNoMoreInteractions(tvServiceInput);

        assertTrue(tvProvider.verifyTokens());
    }
}
