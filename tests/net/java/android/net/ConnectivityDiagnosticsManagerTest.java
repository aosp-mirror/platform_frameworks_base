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

package android.net;

import static android.net.ConnectivityDiagnosticsManager.ConnectivityDiagnosticsBinder;
import static android.net.ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback;
import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;
import android.os.PersistableBundle;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class ConnectivityDiagnosticsManagerTest {
    private static final int NET_ID = 1;
    private static final int DETECTION_METHOD = 2;
    private static final long TIMESTAMP = 10L;
    private static final String INTERFACE_NAME = "interface";
    private static final String BUNDLE_KEY = "key";
    private static final String BUNDLE_VALUE = "value";

    private static final Executor INLINE_EXECUTOR = x -> x.run();

    @Mock private IConnectivityManager mService;
    @Mock private ConnectivityDiagnosticsCallback mCb;

    private Context mContext;
    private ConnectivityDiagnosticsBinder mBinder;
    private ConnectivityDiagnosticsManager mManager;

    private String mPackageName;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();

        mService = mock(IConnectivityManager.class);
        mCb = mock(ConnectivityDiagnosticsCallback.class);

        mBinder = new ConnectivityDiagnosticsBinder(mCb, INLINE_EXECUTOR);
        mManager = new ConnectivityDiagnosticsManager(mContext, mService);

        mPackageName = mContext.getOpPackageName();
    }

    @After
    public void tearDown() {
        // clear ConnectivityDiagnosticsManager callbacks map
        ConnectivityDiagnosticsManager.sCallbacks.clear();
    }

    private ConnectivityReport createSampleConnectivityReport() {
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(INTERFACE_NAME);

        final NetworkCapabilities networkCapabilities = new NetworkCapabilities();
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);

        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(BUNDLE_KEY, BUNDLE_VALUE);

        return new ConnectivityReport(
                new Network(NET_ID), TIMESTAMP, linkProperties, networkCapabilities, bundle);
    }

    private ConnectivityReport createDefaultConnectivityReport() {
        return new ConnectivityReport(
                new Network(0),
                0L,
                new LinkProperties(),
                new NetworkCapabilities(),
                PersistableBundle.EMPTY);
    }

    @Test
    public void testPersistableBundleEquals() {
        assertFalse(
                ConnectivityDiagnosticsManager.persistableBundleEquals(
                        null, PersistableBundle.EMPTY));
        assertFalse(
                ConnectivityDiagnosticsManager.persistableBundleEquals(
                        PersistableBundle.EMPTY, null));
        assertTrue(
                ConnectivityDiagnosticsManager.persistableBundleEquals(
                        PersistableBundle.EMPTY, PersistableBundle.EMPTY));

        final PersistableBundle a = new PersistableBundle();
        a.putString(BUNDLE_KEY, BUNDLE_VALUE);

        final PersistableBundle b = new PersistableBundle();
        b.putString(BUNDLE_KEY, BUNDLE_VALUE);

        final PersistableBundle c = new PersistableBundle();
        c.putString(BUNDLE_KEY, null);

        assertFalse(
                ConnectivityDiagnosticsManager.persistableBundleEquals(PersistableBundle.EMPTY, a));
        assertFalse(
                ConnectivityDiagnosticsManager.persistableBundleEquals(a, PersistableBundle.EMPTY));

        assertTrue(ConnectivityDiagnosticsManager.persistableBundleEquals(a, b));
        assertTrue(ConnectivityDiagnosticsManager.persistableBundleEquals(b, a));

        assertFalse(ConnectivityDiagnosticsManager.persistableBundleEquals(a, c));
        assertFalse(ConnectivityDiagnosticsManager.persistableBundleEquals(c, a));
    }

    @Test
    public void testConnectivityReportEquals() {
        final ConnectivityReport defaultReport = createDefaultConnectivityReport();
        final ConnectivityReport sampleReport = createSampleConnectivityReport();
        assertEquals(sampleReport, createSampleConnectivityReport());
        assertEquals(defaultReport, createDefaultConnectivityReport());

        final LinkProperties linkProperties = sampleReport.getLinkProperties();
        final NetworkCapabilities networkCapabilities = sampleReport.getNetworkCapabilities();
        final PersistableBundle bundle = sampleReport.getAdditionalInfo();

        assertNotEquals(
                createDefaultConnectivityReport(),
                new ConnectivityReport(
                        new Network(NET_ID),
                        0L,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultConnectivityReport(),
                new ConnectivityReport(
                        new Network(0),
                        TIMESTAMP,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultConnectivityReport(),
                new ConnectivityReport(
                        new Network(0),
                        0L,
                        linkProperties,
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultConnectivityReport(),
                new ConnectivityReport(
                        new Network(0),
                        TIMESTAMP,
                        new LinkProperties(),
                        networkCapabilities,
                        PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultConnectivityReport(),
                new ConnectivityReport(
                        new Network(0),
                        TIMESTAMP,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        bundle));
    }

    @Test
    public void testConnectivityReportParcelUnparcel() {
        assertParcelSane(createSampleConnectivityReport(), 5);
    }

    private DataStallReport createSampleDataStallReport() {
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(INTERFACE_NAME);

        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(BUNDLE_KEY, BUNDLE_VALUE);

        final NetworkCapabilities networkCapabilities = new NetworkCapabilities();
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);

        return new DataStallReport(
                new Network(NET_ID),
                TIMESTAMP,
                DETECTION_METHOD,
                linkProperties,
                networkCapabilities,
                bundle);
    }

    private DataStallReport createDefaultDataStallReport() {
        return new DataStallReport(
                new Network(0),
                0L,
                0,
                new LinkProperties(),
                new NetworkCapabilities(),
                PersistableBundle.EMPTY);
    }

    @Test
    public void testDataStallReportEquals() {
        final DataStallReport defaultReport = createDefaultDataStallReport();
        final DataStallReport sampleReport = createSampleDataStallReport();
        assertEquals(sampleReport, createSampleDataStallReport());
        assertEquals(defaultReport, createDefaultDataStallReport());

        final LinkProperties linkProperties = sampleReport.getLinkProperties();
        final NetworkCapabilities networkCapabilities = sampleReport.getNetworkCapabilities();
        final PersistableBundle bundle = sampleReport.getStallDetails();

        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(NET_ID),
                        0L,
                        0,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(0),
                        TIMESTAMP,
                        0,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(0),
                        0L,
                        DETECTION_METHOD,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(0),
                        0L,
                        0,
                        linkProperties,
                        new NetworkCapabilities(),
                        PersistableBundle.EMPTY));
        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(0),
                        0L,
                        0,
                        new LinkProperties(),
                        networkCapabilities,
                        PersistableBundle.EMPTY));
        assertNotEquals(
                defaultReport,
                new DataStallReport(
                        new Network(0),
                        0L,
                        0,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        bundle));
    }

    @Test
    public void testDataStallReportParcelUnparcel() {
        assertParcelSane(createSampleDataStallReport(), 6);
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnConnectivityReportAvailable() {
        mBinder.onConnectivityReportAvailable(createSampleConnectivityReport());

        // The callback will be invoked synchronously by inline executor. Immediately check the
        // latch without waiting.
        verify(mCb).onConnectivityReportAvailable(eq(createSampleConnectivityReport()));
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnDataStallSuspected() {
        mBinder.onDataStallSuspected(createSampleDataStallReport());

        // The callback will be invoked synchronously by inline executor. Immediately check the
        // latch without waiting.
        verify(mCb).onDataStallSuspected(eq(createSampleDataStallReport()));
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnNetworkConnectivityReported() {
        final Network n = new Network(NET_ID);
        final boolean connectivity = true;

        mBinder.onNetworkConnectivityReported(n, connectivity);

        // The callback will be invoked synchronously by inline executor. Immediately check the
        // latch without waiting.
        verify(mCb).onNetworkConnectivityReported(eq(n), eq(connectivity));
    }

    @Test
    public void testRegisterConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();

        mManager.registerConnectivityDiagnosticsCallback(request, INLINE_EXECUTOR, mCb);

        verify(mService).registerConnectivityDiagnosticsCallback(
                any(ConnectivityDiagnosticsBinder.class), eq(request), eq(mPackageName));
        assertTrue(ConnectivityDiagnosticsManager.sCallbacks.containsKey(mCb));
    }

    @Test
    public void testRegisterDuplicateConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();

        mManager.registerConnectivityDiagnosticsCallback(request, INLINE_EXECUTOR, mCb);

        try {
            mManager.registerConnectivityDiagnosticsCallback(request, INLINE_EXECUTOR, mCb);
            fail("Duplicate callback registration should fail");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testUnregisterConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();
        mManager.registerConnectivityDiagnosticsCallback(request, INLINE_EXECUTOR, mCb);

        mManager.unregisterConnectivityDiagnosticsCallback(mCb);

        verify(mService).unregisterConnectivityDiagnosticsCallback(
                any(ConnectivityDiagnosticsBinder.class));
        assertFalse(ConnectivityDiagnosticsManager.sCallbacks.containsKey(mCb));

        // verify that re-registering is successful
        mManager.registerConnectivityDiagnosticsCallback(request, INLINE_EXECUTOR, mCb);
        verify(mService, times(2)).registerConnectivityDiagnosticsCallback(
                any(ConnectivityDiagnosticsBinder.class), eq(request), eq(mPackageName));
        assertTrue(ConnectivityDiagnosticsManager.sCallbacks.containsKey(mCb));
    }

    @Test
    public void testUnregisterUnknownConnectivityDiagnosticsCallback() throws Exception {
        mManager.unregisterConnectivityDiagnosticsCallback(mCb);

        verifyNoMoreInteractions(mService);
    }
}
