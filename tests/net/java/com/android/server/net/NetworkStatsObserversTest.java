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

package com.android.server.net;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.internal.util.TestUtils.waitForIdleHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;

import android.app.usage.NetworkStatsManager;
import android.net.DataUsageRequest;
import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.net.NetworkStatsServiceTest.LatchedHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Objects;

/**
 * Tests for {@link NetworkStatsObservers}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsObserversTest {
    private static final String TEST_IFACE = "test0";
    private static final String TEST_IFACE2 = "test1";
    private static final long TEST_START = 1194220800000L;

    private static final String IMSI_1 = "310004";
    private static final String IMSI_2 = "310260";
    private static final String TEST_SSID = "AndroidAP";

    private static NetworkTemplate sTemplateWifi = buildTemplateWifiWildcard();
    private static NetworkTemplate sTemplateImsi1 = buildTemplateMobileAll(IMSI_1);
    private static NetworkTemplate sTemplateImsi2 = buildTemplateMobileAll(IMSI_2);

    private static final int UID_RED = UserHandle.PER_USER_RANGE + 1;
    private static final int UID_BLUE = UserHandle.PER_USER_RANGE + 2;
    private static final int UID_GREEN = UserHandle.PER_USER_RANGE + 3;
    private static final int UID_ANOTHER_USER = 2 * UserHandle.PER_USER_RANGE + 4;

    private static final long WAIT_TIMEOUT_MS = 500;
    private static final long THRESHOLD_BYTES = 2 * MB_IN_BYTES;
    private static final long BASE_BYTES = 7 * MB_IN_BYTES;
    private static final int INVALID_TYPE = -1;

    private long mElapsedRealtime;

    private HandlerThread mObserverHandlerThread;
    private Handler mObserverNoopHandler;

    private LatchedHandler mHandler;

    private NetworkStatsObservers mStatsObservers;
    private Messenger mMessenger;
    private ArrayMap<String, NetworkIdentitySet> mActiveIfaces;
    private ArrayMap<String, NetworkIdentitySet> mActiveUidIfaces;

    @Mock private IBinder mockBinder;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mObserverHandlerThread = new HandlerThread("HandlerThread");
        mObserverHandlerThread.start();
        final Looper observerLooper = mObserverHandlerThread.getLooper();
        mStatsObservers = new NetworkStatsObservers() {
            @Override
            protected Looper getHandlerLooperLocked() {
                return observerLooper;
            }
        };

        mHandler = new LatchedHandler(Looper.getMainLooper(), new ConditionVariable());
        mMessenger = new Messenger(mHandler);

        mActiveIfaces = new ArrayMap<>();
        mActiveUidIfaces = new ArrayMap<>();
    }

    @Test
    public void testRegister_thresholdTooLow_setsDefaultThreshold() throws Exception {
        long thresholdTooLowBytes = 1L;
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateWifi, thresholdTooLowBytes);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateWifi, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);
    }

    @Test
    public void testRegister_highThreshold_accepted() throws Exception {
        long highThresholdBytes = 2 * THRESHOLD_BYTES;
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateWifi, highThresholdBytes);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateWifi, request.template));
        assertEquals(highThresholdBytes, request.thresholdInBytes);
    }

    @Test
    public void testRegister_twoRequests_twoIds() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateWifi, THRESHOLD_BYTES);

        DataUsageRequest request1 = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request1.requestId > 0);
        assertTrue(Objects.equals(sTemplateWifi, request1.template));
        assertEquals(THRESHOLD_BYTES, request1.thresholdInBytes);

        DataUsageRequest request2 = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request2.requestId > request1.requestId);
        assertTrue(Objects.equals(sTemplateWifi, request2.template));
        assertEquals(THRESHOLD_BYTES, request2.thresholdInBytes);
    }

    @Test
    public void testUnregister_unknownRequest_noop() throws Exception {
        DataUsageRequest unknownRequest = new DataUsageRequest(
                123456 /* id */, sTemplateWifi, THRESHOLD_BYTES);

        mStatsObservers.unregister(unknownRequest, UID_RED);
    }

    @Test
    public void testUnregister_knownRequest_releasesCaller() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);
        Mockito.verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        mStatsObservers.unregister(request, Process.SYSTEM_UID);
        waitForObserverToIdle();

        Mockito.verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    @Test
    public void testUnregister_knownRequest_invalidUid_doesNotUnregister() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                UID_RED, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);
        Mockito.verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        mStatsObservers.unregister(request, UID_BLUE);
        waitForObserverToIdle();

        Mockito.verifyZeroInteractions(mockBinder);
    }

    private NetworkIdentitySet makeTestIdentSet() {
        NetworkIdentitySet identSet = new NetworkIdentitySet();
        identSet.add(new NetworkIdentity(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                IMSI_1, null /* networkId */, false /* roaming */, true /* metered */,
                true /* defaultNetwork */));
        return identSet;
    }

    @Test
    public void testUpdateStats_initialSample_doesNotNotify() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = new NetworkStats(TEST_START, 1 /* initialSize */)
                .addIfaceValues(TEST_IFACE, BASE_BYTES, 8L, BASE_BYTES, 16L);
        NetworkStats uidSnapshot = null;

        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
    }

    @Test
    public void testUpdateStats_belowThreshold_doesNotNotify() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = new NetworkStats(TEST_START, 1 /* initialSize */)
                .addIfaceValues(TEST_IFACE, BASE_BYTES, 8L, BASE_BYTES, 16L);
        NetworkStats uidSnapshot = null;
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        xtSnapshot = new NetworkStats(TEST_START, 1 /* initialSize */)
                .addIfaceValues(TEST_IFACE, BASE_BYTES + 1024L, 10L, BASE_BYTES + 2048L, 20L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
    }


    @Test
    public void testUpdateStats_deviceAccess_notifies() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                Process.SYSTEM_UID, NetworkStatsAccess.Level.DEVICE);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = new NetworkStats(TEST_START, 1 /* initialSize */)
                .addIfaceValues(TEST_IFACE, BASE_BYTES, 8L, BASE_BYTES, 16L);
        NetworkStats uidSnapshot = null;
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        xtSnapshot = new NetworkStats(TEST_START + MINUTE_IN_MILLIS, 1 /* initialSize */)
                .addIfaceValues(TEST_IFACE, BASE_BYTES + THRESHOLD_BYTES, 12L,
                        BASE_BYTES + THRESHOLD_BYTES, 22L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
        assertEquals(NetworkStatsManager.CALLBACK_LIMIT_REACHED, mHandler.lastMessageType);
    }

    @Test
    public void testUpdateStats_defaultAccess_notifiesSameUid() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                UID_RED, NetworkStatsAccess.Level.DEFAULT);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveUidIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = null;
        NetworkStats uidSnapshot = new NetworkStats(TEST_START, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, BASE_BYTES, 2L, BASE_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        uidSnapshot = new NetworkStats(TEST_START + 2 * MINUTE_IN_MILLIS, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, BASE_BYTES + THRESHOLD_BYTES, 2L,
                        BASE_BYTES + THRESHOLD_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
        assertEquals(NetworkStatsManager.CALLBACK_LIMIT_REACHED, mHandler.lastMessageType);
    }

    @Test
    public void testUpdateStats_defaultAccess_usageOtherUid_doesNotNotify() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                UID_BLUE, NetworkStatsAccess.Level.DEFAULT);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveUidIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = null;
        NetworkStats uidSnapshot = new NetworkStats(TEST_START, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, BASE_BYTES, 2L, BASE_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        uidSnapshot = new NetworkStats(TEST_START + 2 * MINUTE_IN_MILLIS, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, BASE_BYTES + THRESHOLD_BYTES, 2L,
                        BASE_BYTES + THRESHOLD_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
    }

    @Test
    public void testUpdateStats_userAccess_usageSameUser_notifies() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                UID_BLUE, NetworkStatsAccess.Level.USER);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveUidIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = null;
        NetworkStats uidSnapshot = new NetworkStats(TEST_START, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, BASE_BYTES, 2L, BASE_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        uidSnapshot = new NetworkStats(TEST_START + 2 * MINUTE_IN_MILLIS, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, BASE_BYTES + THRESHOLD_BYTES, 2L,
                        BASE_BYTES + THRESHOLD_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
        assertEquals(NetworkStatsManager.CALLBACK_LIMIT_REACHED, mHandler.lastMessageType);
    }

    @Test
    public void testUpdateStats_userAccess_usageAnotherUser_doesNotNotify() throws Exception {
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateImsi1, THRESHOLD_BYTES);

        DataUsageRequest request = mStatsObservers.register(inputRequest, mMessenger, mockBinder,
                UID_RED, NetworkStatsAccess.Level.USER);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateImsi1, request.template));
        assertEquals(THRESHOLD_BYTES, request.thresholdInBytes);

        NetworkIdentitySet identSet = makeTestIdentSet();
        mActiveUidIfaces.put(TEST_IFACE, identSet);

        // Baseline
        NetworkStats xtSnapshot = null;
        NetworkStats uidSnapshot = new NetworkStats(TEST_START, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_ANOTHER_USER, SET_DEFAULT, TAG_NONE, METERED_NO,
                        ROAMING_NO, DEFAULT_NETWORK_YES, BASE_BYTES, 2L, BASE_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);

        // Delta
        uidSnapshot = new NetworkStats(TEST_START + 2 * MINUTE_IN_MILLIS, 2 /* initialSize */)
                .addValues(TEST_IFACE, UID_ANOTHER_USER, SET_DEFAULT, TAG_NONE, METERED_NO,
                        ROAMING_NO, DEFAULT_NETWORK_NO, BASE_BYTES + THRESHOLD_BYTES, 2L,
                        BASE_BYTES + THRESHOLD_BYTES, 2L, 0L);
        mStatsObservers.updateStats(
                xtSnapshot, uidSnapshot, mActiveIfaces, mActiveUidIfaces, TEST_START);
        waitForObserverToIdle();
    }

    private void waitForObserverToIdle() {
        waitForIdleHandler(mObserverHandlerThread, WAIT_TIMEOUT_MS);
        waitForIdleHandler(mHandler, WAIT_TIMEOUT_MS);
    }
}
