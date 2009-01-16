/*
 * Copyright (C) 2008 Google Inc.
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
package android.location;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

/**
 * Tests for LocationManager.addProximityAlert
 * 
 * TODO: add tests for more scenarios
 * 
 * To run:
 *  adb shell am instrument -e class com.google.android.mapstests.api.LocationProximityTest \
 *     -w com.google.android.mapstests/.MapInstrumentationTestRunner
 * 
 */
@MediumTest
public class LocationManagerProximityTest extends AndroidTestCase {

    private static final int UPDATE_LOCATION_WAIT_TIME = 1000;
    private static final int PROXIMITY_WAIT_TIME = 2000;

    private LocationManager mLocationManager;
    private PendingIntent mPendingIntent;
    private TestIntentReceiver mIntentReceiver;
    private String mOriginalAllowedProviders;
    private int mOriginalMocksAllowed;

    private static final String LOG_TAG = "LocationProximityTest";

    // use network provider as mock location provider, because:
    //  - proximity alert is hardcoded to listen to only network or gps
    //  - 'network' provider is not installed in emulator, so can mock it 
    //    using test provider APIs
    private static final String PROVIDER_NAME = LocationManager.NETWORK_PROVIDER;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // allow mock locations
        mOriginalMocksAllowed =
            android.provider.Settings.Secure.getInt(getContext().getContentResolver(),
                android.provider.Settings.Secure.ALLOW_MOCK_LOCATION, 0);
        
        android.provider.Settings.Secure.putInt(getContext().getContentResolver(),
                android.provider.Settings.Secure.ALLOW_MOCK_LOCATION, 1);
        
        mOriginalAllowedProviders = 
            android.provider.Settings.Secure.getString(
                    getContext().getContentResolver(), 
                    android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
        
        // ensure 'only' the mock provider is enabled
        // need to do this so the proximity listener does not ignore the mock 
        // updates in favor of gps updates
        android.provider.Settings.Secure.putString(
                getContext().getContentResolver(), 
                android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED, 
                PROVIDER_NAME);

        mLocationManager = (LocationManager) getContext().
                getSystemService(Context.LOCATION_SERVICE);
        if (mLocationManager.getProvider(PROVIDER_NAME) != null) {
            mLocationManager.removeTestProvider(PROVIDER_NAME);
        }
        
        mLocationManager.addTestProvider(PROVIDER_NAME, true, //requiresNetwork,
                false, // requiresSatellite,
                true, // requiresCell,
                false, // hasMonetaryCost,
                false, // supportsAltitude,
                false, // supportsSpeed, s
                false, // upportsBearing,
                Criteria.POWER_MEDIUM, // powerRequirement
                Criteria.ACCURACY_FINE); // accuracy
    }

    @Override
    protected void tearDown() throws Exception {
        mLocationManager.removeTestProvider(PROVIDER_NAME);

        if (mPendingIntent != null) {
            mLocationManager.removeProximityAlert(mPendingIntent);
        }
        if (mIntentReceiver != null) {
            getContext().unregisterReceiver(mIntentReceiver);
        }
        
        android.provider.Settings.Secure.putInt(getContext().getContentResolver(),
                android.provider.Settings.Secure.ALLOW_MOCK_LOCATION, mOriginalMocksAllowed);
        
        if (mOriginalAllowedProviders != null) {
            // restore original settings
            android.provider.Settings.Secure.putString(
                    getContext().getContentResolver(), 
                    android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED, 
                    mOriginalAllowedProviders);
            mLocationManager.updateProviders();
        }
    }

    /**
     * Tests basic proximity alert when entering proximity
     */
    public void testEnterProximity() throws Exception {
        doTestEnterProximity(10000);
    }

    /**
     * Tests proximity alert when entering proximity, with no expiration 
     */
    public void testEnterProximity_noexpire() throws Exception {
        doTestEnterProximity(-1);
    }

    /**
     * Helper variant for testing enter proximity scenario 
     * TODO: add additional parameters as more scenarios are added
     * 
     * @param expiration - expiry of proximity alert 
     */
    private void doTestEnterProximity(long expiration) throws Exception {
        // update location to outside proximity range
        synchronousSendLocation(30, 30);
        registerProximityListener(0, 0, 1000, expiration);
        sendLocation(0, 0);
        waitForAlert();
        assertProximityType(true);
    }

    /**
     * Tests basic proximity alert when exiting proximity
     */
    public void testExitProximity() throws Exception {
        // first do enter proximity scenario
        doTestEnterProximity(-1);

        // now update to trigger exit proximity proximity
        mIntentReceiver.clearReceivedIntents();
        sendLocation(20, 20);
        waitForAlert();
        assertProximityType(false);
    }

    /**
     * Registers the proximity intent receiver
     */
    private void registerProximityListener(double latitude, double longitude, 
            float radius, long expiration) {
        String intentKey = "testProximity";
        Intent proximityIntent = new Intent(intentKey);
        mPendingIntent = PendingIntent.getBroadcast(getContext(), 0, 
                proximityIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        mIntentReceiver = new TestIntentReceiver(intentKey);

        mLocationManager.addProximityAlert(latitude, longitude, radius, 
                expiration, mPendingIntent);

        getContext().registerReceiver(mIntentReceiver, 
                mIntentReceiver.getFilter());

    }

    /**
     * Blocks until proximity intent notification is received
     * @throws InterruptedException
     */
    private void waitForAlert() throws InterruptedException {
        Log.d(LOG_TAG, "Waiting for proximity update");
        synchronized (mIntentReceiver) {
            mIntentReceiver.wait(PROXIMITY_WAIT_TIME);
        }

        assertNotNull("Did not receive proximity alert", 
                mIntentReceiver.getLastReceivedIntent());
    }

    /**
     * Asserts that the received intent had the enter proximity property set as
     * expected
     * @param expectedEnterProximity - true if enter proximity expected, false if
     *   exit expected
     */
    private void assertProximityType(boolean expectedEnterProximity) 
            throws Exception {
        boolean proximityTest = mIntentReceiver.getLastReceivedIntent().
                getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, 
                !expectedEnterProximity);
        assertEquals("proximity alert not set to expected enter proximity value",
                expectedEnterProximity, proximityTest);
    }

    /**
     * Synchronous variant of sendLocation
     */
    private void synchronousSendLocation(final double latitude, 
            final double longitude)
            throws InterruptedException {
        sendLocation(latitude, longitude, this);
        // wait for location to be set
        synchronized (this) {
            wait(UPDATE_LOCATION_WAIT_TIME);
        }
    }

    /**
     * Asynchronously update the mock location provider without notification
     */
    private void sendLocation(final double latitude, final double longitude) {
        sendLocation(latitude, longitude, null);
    }

    /**
     * Asynchronously update the mock location provider with given latitude and
     * longitude
     * 
     * @param latitude - update location
     * @param longitude - update location
     * @param observer - optionally, object to notify when update is sent.If
     *            null, no update will be sent
     */
    private void sendLocation(final double latitude, final double longitude, 
            final Object observer) {
        Thread locationUpdater = new Thread() {
            @Override
            public void run() {
                Location loc = new Location(PROVIDER_NAME);
                loc.setLatitude(latitude);
                loc.setLongitude(longitude);

                loc.setTime(java.lang.System.currentTimeMillis());
                Log.d(LOG_TAG, "Sending update for " + PROVIDER_NAME);
                mLocationManager.setTestProviderLocation(PROVIDER_NAME, loc);
                if (observer != null) {
                    synchronized (observer) {
                        observer.notify();
                    }
                }
            }
        };
        locationUpdater.start();

    }

    /**
     * Helper class that receives a proximity intent and notifies the main class
     * when received
     */
    private static class TestIntentReceiver extends BroadcastReceiver {

        private String mExpectedAction;
        private Intent mLastReceivedIntent;

        public TestIntentReceiver(String expectedAction) {
            mExpectedAction = expectedAction;
            mLastReceivedIntent = null;
        }

        public IntentFilter getFilter() {
            IntentFilter filter = new IntentFilter(mExpectedAction);
            return filter;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && mExpectedAction.equals(intent.getAction())) {
                Log.d(LOG_TAG, "Intent Received: " + intent.toString());
                mLastReceivedIntent = intent;
                synchronized (this) {
                    notify();
                }
            }
        }

        public Intent getLastReceivedIntent() {
            return mLastReceivedIntent;
        }
        
        public void clearReceivedIntents() {
            mLastReceivedIntent = null;
        }
    }
}
