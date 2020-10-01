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

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.EasyConnectStatusCallbackTest}.
 */
@SmallTest
public class EasyConnectStatusCallbackTest {
    private EasyConnectStatusCallback mEasyConnectStatusCallback = new EasyConnectStatusCallback() {
        @Override
        public void onEnrolleeSuccess(int newNetworkId) {

        }

        @Override
        public void onConfiguratorSuccess(int code) {

        }

        @Override
        public void onProgress(int code) {

        }

        @Override
        public void onFailure(int code) {
            mOnFailureR1EventReceived = true;
            mLastCode = code;
        }
    };
    private boolean mOnFailureR1EventReceived;
    private int mLastCode;

    @Before
    public void setUp() {
        mOnFailureR1EventReceived = false;
        mLastCode = 0;
    }

    /**
     * Test that the legacy R1 onFailure is called by default if the R2 onFailure is not overridden
     * by the app.
     */
    @Test
    public void testR1OnFailureCalled() {

        SparseArray<int[]> channelList = new SparseArray<>();
        int[] channelArray = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

        channelList.append(81, channelArray);
        mEasyConnectStatusCallback.onFailure(
                EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_CANNOT_FIND_NETWORK,
                "SomeSSID", channelList, new int[] {81});

        assertTrue(mOnFailureR1EventReceived);
        assertEquals(mLastCode,
                EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_CANNOT_FIND_NETWORK);
    }
}
