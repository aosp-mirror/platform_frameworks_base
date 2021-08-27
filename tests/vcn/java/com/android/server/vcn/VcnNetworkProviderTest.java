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

package com.android.server.vcn;

import static android.net.NetworkProvider.NetworkOfferCallback;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.VcnNetworkProvider.NetworkRequestListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnNetworkProviderTest {
    private static final int TEST_SCORE_UNSATISFIED = 0;
    private static final int TEST_PROVIDER_ID = 1;

    @NonNull private final Context mContext;
    @NonNull private final TestLooper mTestLooper;

    @NonNull private VcnNetworkProvider.Dependencies mDeps;
    @NonNull private ConnectivityManager mConnMgr;
    @NonNull private VcnNetworkProvider mVcnNetworkProvider;
    @NonNull private NetworkRequestListener mListener;

    public VcnNetworkProviderTest() {
        mContext = mock(Context.class);
        mTestLooper = new TestLooper();
    }

    @Before
    public void setUp() throws Exception {
        mDeps = mock(VcnNetworkProvider.Dependencies.class);
        mConnMgr = mock(ConnectivityManager.class);
        VcnTestUtils.setupSystemService(
                mContext, mConnMgr, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);

        mVcnNetworkProvider = new VcnNetworkProvider(mContext, mTestLooper.getLooper(), mDeps);
        mListener = mock(NetworkRequestListener.class);
    }

    private NetworkOfferCallback verifyRegisterAndGetOfferCallback() throws Exception {
        mVcnNetworkProvider.register();

        final ArgumentCaptor<NetworkOfferCallback> cbCaptor =
                ArgumentCaptor.forClass(NetworkOfferCallback.class);

        verify(mConnMgr).registerNetworkProvider(eq(mVcnNetworkProvider));
        verify(mDeps)
                .registerNetworkOffer(
                        eq(mVcnNetworkProvider),
                        argThat(
                                score ->
                                        score.getLegacyInt() == Vcn.getNetworkScore().getLegacyInt()
                                                && score.isTransportPrimary()),
                        any(),
                        any(),
                        cbCaptor.capture());

        return cbCaptor.getValue();
    }

    @Test
    public void testRegister() throws Exception {
        verifyRegisterAndGetOfferCallback();
    }

    @Test
    public void testRequestsPassedToRegisteredListeners() throws Exception {
        mVcnNetworkProvider.registerListener(mListener);

        final NetworkRequest request = mock(NetworkRequest.class);
        verifyRegisterAndGetOfferCallback().onNetworkNeeded(request);
        verify(mListener).onNetworkRequested(request);
    }

    @Test
    public void testUnregisterListener() throws Exception {
        mVcnNetworkProvider.registerListener(mListener);
        mVcnNetworkProvider.unregisterListener(mListener);

        final NetworkRequest request = mock(NetworkRequest.class);
        verifyRegisterAndGetOfferCallback().onNetworkNeeded(request);
        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void testCachedRequestsPassedOnRegister() throws Exception {
        final List<NetworkRequest> requests = new ArrayList<>();
        final NetworkOfferCallback offerCb = verifyRegisterAndGetOfferCallback();

        for (int i = 0; i < 10; i++) {
            // Build unique network requests; in this case, iterate down the capabilities as a way
            // to unique-ify requests.
            final NetworkRequest request =
                    new NetworkRequest.Builder().clearCapabilities().addCapability(i).build();

            requests.add(request);
            offerCb.onNetworkNeeded(request);
        }

        // Remove one, and verify that it is never sent to the listeners.
        final NetworkRequest removed = requests.remove(0);
        offerCb.onNetworkUnneeded(removed);

        mVcnNetworkProvider.registerListener(mListener);
        for (NetworkRequest request : requests) {
            verify(mListener).onNetworkRequested(request);
        }
        verifyNoMoreInteractions(mListener);
    }
}
