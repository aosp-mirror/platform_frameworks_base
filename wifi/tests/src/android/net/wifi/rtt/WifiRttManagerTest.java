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

package android.net.wifi.rtt;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.aware.PeerHandle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Unit test harness for WifiRttManager class.
 */
@SmallTest
public class WifiRttManagerTest {
    private WifiRttManager mDut;
    private TestLooper mMockLooper;
    private Executor mMockLooperExecutor;

    private final String packageName = "some.package.name.for.rtt.app";
    private final String featureId = "some.feature.id.in.rtt.app";

    @Mock
    public Context mockContext;

    @Mock
    public IWifiRttManager mockRttService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiRttManager(mockContext, mockRttService);
        mMockLooper = new TestLooper();
        mMockLooperExecutor = mMockLooper.getNewExecutor();

        when(mockContext.getOpPackageName()).thenReturn(packageName);
        when(mockContext.getAttributionTag()).thenReturn(featureId);
    }

    /**
     * Validate ranging call flow with successful results.
     */
    @Test
    public void testRangeSuccess() throws Exception {
        RangingRequest request = new RangingRequest.Builder().build();
        List<RangingResult> results = new ArrayList<>();
        results.add(
                new RangingResult(RangingResult.STATUS_SUCCESS, MacAddress.BROADCAST_ADDRESS, 15, 5,
                        10, 8, 5, null, null, null, 666));
        RangingResultCallback callbackMock = mock(RangingResultCallback.class);
        ArgumentCaptor<IRttCallback> callbackCaptor = ArgumentCaptor.forClass(IRttCallback.class);

        // verify ranging request passed to service
        mDut.startRanging(request, mMockLooperExecutor, callbackMock);
        verify(mockRttService).startRanging(any(IBinder.class), eq(packageName), eq(featureId),
                eq(null),  eq(request), callbackCaptor.capture());

        // service calls back with success
        callbackCaptor.getValue().onRangingResults(results);
        mMockLooper.dispatchAll();
        verify(callbackMock).onRangingResults(results);

        verifyNoMoreInteractions(mockRttService, callbackMock);
    }

    /**
     * Validate ranging call flow which failed.
     */
    @Test
    public void testRangeFail() throws Exception {
        int failureCode = RangingResultCallback.STATUS_CODE_FAIL;

        RangingRequest request = new RangingRequest.Builder().build();
        RangingResultCallback callbackMock = mock(RangingResultCallback.class);
        ArgumentCaptor<IRttCallback> callbackCaptor = ArgumentCaptor.forClass(IRttCallback.class);

        // verify ranging request passed to service
        mDut.startRanging(request, mMockLooperExecutor, callbackMock);
        verify(mockRttService).startRanging(any(IBinder.class), eq(packageName), eq(featureId),
                eq(null), eq(request), callbackCaptor.capture());

        // service calls back with failure code
        callbackCaptor.getValue().onRangingFailure(failureCode);
        mMockLooper.dispatchAll();
        verify(callbackMock).onRangingFailure(failureCode);

        verifyNoMoreInteractions(mockRttService, callbackMock);
    }

    /**
     * Validate that RangingRequest parcel works (produces same object on write/read).
     */
    @Test
    public void testRangingRequestParcel() {
        // Note: not validating parcel code of ScanResult (assumed to work)
        ScanResult scanResult1 = new ScanResult();
        scanResult1.BSSID = "00:01:02:03:04:05";
        ScanResult scanResult2 = new ScanResult();
        scanResult2.BSSID = "06:07:08:09:0A:0B";
        ScanResult scanResult3 = new ScanResult();
        scanResult3.BSSID = "AA:BB:CC:DD:EE:FF";
        List<ScanResult> scanResults2and3 = new ArrayList<>(2);
        scanResults2and3.add(scanResult2);
        scanResults2and3.add(scanResult3);
        MacAddress mac1 = MacAddress.fromString("00:01:02:03:04:05");
        PeerHandle peerHandle1 = new PeerHandle(12);

        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAccessPoint(scanResult1);
        builder.addAccessPoints(scanResults2and3);
        builder.addWifiAwarePeer(mac1);
        builder.addWifiAwarePeer(peerHandle1);
        RangingRequest request = builder.build();

        Parcel parcelW = Parcel.obtain();
        request.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        RangingRequest rereadRequest = RangingRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(request, rereadRequest);
    }

    /**
     * Validate that can request as many range operation as the upper limit on number of requests.
     */
    @Test
    public void testRangingRequestAtLimit() {
        ScanResult scanResult = new ScanResult();
        scanResult.BSSID = "AA:BB:CC:DD:EE:FF";
        List<ScanResult> scanResultList = new ArrayList<>();
        for (int i = 0; i < RangingRequest.getMaxPeers() - 3; ++i) {
            scanResultList.add(scanResult);
        }
        MacAddress mac1 = MacAddress.fromString("00:01:02:03:04:05");

        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAccessPoint(scanResult);
        builder.addAccessPoints(scanResultList);
        builder.addAccessPoint(scanResult);
        builder.addWifiAwarePeer(mac1);
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(true);
    }

    /**
     * Validate that limit on number of requests is applied.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRangingRequestPastLimit() {
        ScanResult scanResult = new ScanResult();
        scanResult.BSSID = "00:01:02:03:04:05";
        List<ScanResult> scanResultList = new ArrayList<>();
        for (int i = 0; i < RangingRequest.getMaxPeers() - 2; ++i) {
            scanResultList.add(scanResult);
        }
        MacAddress mac1 = MacAddress.fromString("00:01:02:03:04:05");

        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAccessPoint(scanResult);
        builder.addAccessPoints(scanResultList);
        builder.addAccessPoint(scanResult);
        builder.addWifiAwarePeer(mac1);
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(true);
    }

    /**
     * Validate that Aware requests are invalid on devices which do not support Aware
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRangingRequestWithAwareWithNoAwareSupport() {
        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addWifiAwarePeer(new PeerHandle(10));
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(false);
    }

    /**
     * Validate that RangingResults parcel works (produces same object on write/read).
     */
    @Test
    public void testRangingResultsParcel() {
        int status = RangingResult.STATUS_SUCCESS;
        final MacAddress mac = MacAddress.fromString("00:01:02:03:04:05");
        PeerHandle peerHandle = new PeerHandle(10);
        int distanceCm = 105;
        int distanceStdDevCm = 10;
        int rssi = 5;
        int numAttemptedMeasurements = 8;
        int numSuccessfulMeasurements = 3;
        long timestamp = System.currentTimeMillis();
        byte[] lci = { 0x5, 0x6, 0x7 };
        byte[] lcr = { 0x1, 0x2, 0x3, 0xA, 0xB, 0xC };

        // RangingResults constructed with a MAC address
        RangingResult result = new RangingResult(status, mac, distanceCm, distanceStdDevCm, rssi,
                numAttemptedMeasurements, numSuccessfulMeasurements, lci, lcr, null, timestamp);

        Parcel parcelW = Parcel.obtain();
        result.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        RangingResult rereadResult = RangingResult.CREATOR.createFromParcel(parcelR);

        assertEquals(result, rereadResult);

        // RangingResults constructed with a PeerHandle
        result = new RangingResult(status, peerHandle, distanceCm, distanceStdDevCm, rssi,
                numAttemptedMeasurements, numSuccessfulMeasurements, null, null, null, timestamp);

        parcelW = Parcel.obtain();
        result.writeToParcel(parcelW, 0);
        bytes = parcelW.marshall();
        parcelW.recycle();

        parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        rereadResult = RangingResult.CREATOR.createFromParcel(parcelR);

        assertEquals(result, rereadResult);
    }

    /**
     * Validate that RangingResults tests equal even if LCI/LCR is empty (length == 0) and null.
     */
    @Test
    public void testRangingResultsEqualityLciLcr() {
        int status = RangingResult.STATUS_SUCCESS;
        final MacAddress mac = MacAddress.fromString("00:01:02:03:04:05");
        PeerHandle peerHandle = new PeerHandle(10);
        int distanceCm = 105;
        int distanceStdDevCm = 10;
        int rssi = 5;
        int numAttemptedMeasurements = 10;
        int numSuccessfulMeasurements = 3;
        long timestamp = System.currentTimeMillis();
        byte[] lci = { };
        byte[] lcr = { };

        RangingResult rr1 = new RangingResult(status, mac, distanceCm, distanceStdDevCm, rssi,
                numAttemptedMeasurements, numSuccessfulMeasurements, lci, lcr, null, timestamp);
        RangingResult rr2 = new RangingResult(status, mac, distanceCm, distanceStdDevCm, rssi,
                numAttemptedMeasurements, numSuccessfulMeasurements, null, null, null, timestamp);

        assertEquals(rr1, rr2);
    }

    /**
     * Validate that ResponderConfig parcel works (produces same object on write/read).
     */
    @Test
    public void testResponderConfigParcel() {
        // ResponderConfig constructed with a MAC address
        ResponderConfig config = new ResponderConfig(MacAddress.fromString("00:01:02:03:04:05"),
                ResponderConfig.RESPONDER_AP, true, ResponderConfig.CHANNEL_WIDTH_80MHZ, 2134, 2345,
                2555, ResponderConfig.PREAMBLE_LEGACY);

        Parcel parcelW = Parcel.obtain();
        config.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ResponderConfig rereadConfig = ResponderConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(config, rereadConfig);

        // ResponderConfig constructed with a PeerHandle
        config = new ResponderConfig(new PeerHandle(10), ResponderConfig.RESPONDER_AWARE, false,
                ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ, 5555, 6666, 7777,
                ResponderConfig.PREAMBLE_VHT);

        parcelW = Parcel.obtain();
        config.writeToParcel(parcelW, 0);
        bytes = parcelW.marshall();
        parcelW.recycle();

        parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        rereadConfig = ResponderConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(config, rereadConfig);
    }

    /**
     * Validate preamble selection from ScanResults.
     */
    @Test
    public void testResponderPreambleSelection() {
        ScanResult.InformationElement htCap = new ScanResult.InformationElement();
        htCap.id = ScanResult.InformationElement.EID_HT_CAPABILITIES;

        ScanResult.InformationElement vhtCap = new ScanResult.InformationElement();
        vhtCap.id = ScanResult.InformationElement.EID_VHT_CAPABILITIES;

        ScanResult.InformationElement vsa = new ScanResult.InformationElement();
        vsa.id = ScanResult.InformationElement.EID_VSA;

        // no IE
        ScanResult scan = new ScanResult();
        scan.BSSID = "00:01:02:03:04:05";
        scan.informationElements = null;
        scan.channelWidth = ResponderConfig.CHANNEL_WIDTH_80MHZ;

        ResponderConfig config = ResponderConfig.fromScanResult(scan);

        assertEquals(ResponderConfig.PREAMBLE_VHT, config.preamble);

        // IE with HT & VHT
        scan.channelWidth = ResponderConfig.CHANNEL_WIDTH_40MHZ;

        scan.informationElements = new ScanResult.InformationElement[2];
        scan.informationElements[0] = htCap;
        scan.informationElements[1] = vhtCap;

        config = ResponderConfig.fromScanResult(scan);

        assertEquals(ResponderConfig.PREAMBLE_VHT, config.preamble);

        // IE with some entries but no HT or VHT
        scan.informationElements[0] = vsa;
        scan.informationElements[1] = vsa;

        config = ResponderConfig.fromScanResult(scan);

        assertEquals(ResponderConfig.PREAMBLE_LEGACY, config.preamble);

        // IE with HT
        scan.informationElements[0] = vsa;
        scan.informationElements[1] = htCap;

        config = ResponderConfig.fromScanResult(scan);

        assertEquals(ResponderConfig.PREAMBLE_HT, config.preamble);
    }
}
