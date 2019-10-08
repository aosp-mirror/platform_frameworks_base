/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;

import android.Manifest.permission;
import android.content.Context;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for the {@link NetworkRecommendationProvider}.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkRecommendationProviderTest {
    @Mock private Context mContext;
    private NetworkRecProvider mRecProvider;
    private INetworkRecommendationProvider mStub;
    private CountDownLatch mScoreRequestLatch;
    private NetworkKey[] mTestNetworkKeys;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Executor executor = Executors.newSingleThreadExecutor();
        mScoreRequestLatch = new CountDownLatch(1);
        mRecProvider = new NetworkRecProvider(mContext, executor, mScoreRequestLatch);
        mStub = INetworkRecommendationProvider.Stub.asInterface(mRecProvider.getBinder());
        mTestNetworkKeys = new NetworkKey[2];
        mTestNetworkKeys[0] = new NetworkKey(new WifiKey("\"ssid_01\"", "00:00:00:00:00:11"));
        mTestNetworkKeys[1] = new NetworkKey(new WifiKey("\"ssid_02\"", "00:00:00:00:00:22"));
    }

    @Test
    public void testScoreRequestReceived() throws Exception {
        mStub.requestScores(mTestNetworkKeys);

        // wait for onRequestScores() to be called in our impl below.
        mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS);

        assertSame(mTestNetworkKeys, mRecProvider.mCapturedNetworks);
    }

    @Test
    public void testScoreRequest_nullInput() throws Exception {
        mStub.requestScores(null);

        // onRequestScores() should never be called
        assertFalse(mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testScoreRequest_emptyInput() throws Exception {
        mStub.requestScores(new NetworkKey[0]);

        // onRequestScores() should never be called
        assertFalse(mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testScoreRequest_permissionsEnforced() throws Exception {
        Mockito.doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mStub.requestScores(mTestNetworkKeys);
            fail("SecurityException expected.");
        } catch (SecurityException e) {
            // expected
        }
    }

    private static class NetworkRecProvider extends NetworkRecommendationProvider {
        private final CountDownLatch mScoreRequestLatch;
        NetworkKey[] mCapturedNetworks;

        NetworkRecProvider(Context context, Executor executor, CountDownLatch networkRequestLatch) {
            super(context, executor);
            mScoreRequestLatch = networkRequestLatch;
        }

        @Override
        public void onRequestScores(NetworkKey[] networks) {
            mCapturedNetworks = networks;
            mScoreRequestLatch.countDown();
        }
    }
}
