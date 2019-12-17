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

import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport;

import static com.android.testutils.ParcelUtilsKt.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.PersistableBundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConnectivityDiagnosticsManagerTest {
    private static final int NET_ID = 1;
    private static final int DETECTION_METHOD = 2;
    private static final long TIMESTAMP = 10L;
    private static final String INTERFACE_NAME = "interface";
    private static final String BUNDLE_KEY = "key";
    private static final String BUNDLE_VALUE = "value";

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
        assertEquals(createSampleConnectivityReport(), createSampleConnectivityReport());
        assertEquals(createDefaultConnectivityReport(), createDefaultConnectivityReport());

        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(INTERFACE_NAME);

        final NetworkCapabilities networkCapabilities = new NetworkCapabilities();
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);

        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(BUNDLE_KEY, BUNDLE_VALUE);

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
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(BUNDLE_KEY, BUNDLE_VALUE);
        return new DataStallReport(new Network(NET_ID), TIMESTAMP, DETECTION_METHOD, bundle);
    }

    private DataStallReport createDefaultDataStallReport() {
        return new DataStallReport(new Network(0), 0L, 0, PersistableBundle.EMPTY);
    }

    @Test
    public void testDataStallReportEquals() {
        assertEquals(createSampleDataStallReport(), createSampleDataStallReport());
        assertEquals(createDefaultDataStallReport(), createDefaultDataStallReport());

        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(BUNDLE_KEY, BUNDLE_VALUE);

        assertNotEquals(
                createDefaultDataStallReport(),
                new DataStallReport(new Network(NET_ID), 0L, 0, PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultDataStallReport(),
                new DataStallReport(new Network(0), TIMESTAMP, 0, PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultDataStallReport(),
                new DataStallReport(new Network(0), 0L, DETECTION_METHOD, PersistableBundle.EMPTY));
        assertNotEquals(
                createDefaultDataStallReport(), new DataStallReport(new Network(0), 0L, 0, bundle));
    }

    @Test
    public void testDataStallReportParcelUnparcel() {
        assertParcelSane(createSampleDataStallReport(), 4);
    }
}
