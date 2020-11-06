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
package com.android.server.location.countrydetector;

import android.location.Country;
import android.location.CountryListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.test.AndroidTestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;

public class LocationBasedCountryDetectorTest extends AndroidTestCase {
    private static final List<String> sEnabledProviders = Arrays.asList(
            LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER);
    private class TestCountryDetector extends LocationBasedCountryDetector {
        public static final int TOTAL_PROVIDERS = 2;
        protected Object countryFoundLocker = new Object();
        protected boolean notifyCountry = false;
        private final Location mLocation;
        private final String mCountry;
        private final long mQueryLocationTimeout;
        private Map<String, LocationListener> mListeners;

        public TestCountryDetector(String country, String provider) {
            this(country, provider, 1000 * 60 * 5);
        }

        public TestCountryDetector(String country, String provider, long queryLocationTimeout) {
            super(getContext());
            mCountry = country;
            mLocation = new Location(provider);
            mQueryLocationTimeout = queryLocationTimeout;
            mListeners = new HashMap<String, LocationListener>();
        }

        @Override
        protected String getCountryFromLocation(Location location) {
            synchronized (countryFoundLocker) {
                if (!notifyCountry) {
                    try {
                        countryFoundLocker.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            if (mLocation.getProvider().endsWith(location.getProvider())) {
                return mCountry;
            } else {
                return null;
            }
        }

        @Override
        protected Location getLastKnownLocation() {
            return mLocation;
        }

        private Set<String> mAcceptableProviders;

        public void setAcceptableProvider(Set<String> acceptableProviders) {
            mAcceptableProviders = acceptableProviders;
        }

        @Override
        protected boolean isAcceptableProvider(String provider) {
            if (mAcceptableProviders != null) {
                return mAcceptableProviders.contains(provider);
            } else {
                return true;
            }
        }

        @Override
        protected void registerListener(String provider, LocationListener listener) {
            assertNotNull(provider);
            mListeners.put(provider, listener);
        }

        @Override
        protected void unregisterListener(LocationListener listener) {
            for (Entry<String, LocationListener> entry : mListeners.entrySet()) {
                if (entry.getValue().equals(listener)) {
                    mListeners.remove(entry.getKey());
                    return;
                }
            }
            fail("Not registered");
        }

        public Map<String, LocationListener> getListeners() {
            return mListeners;
        }

        @Override
        protected long getQueryLocationTimeout() {
            return mQueryLocationTimeout;
        }

        @Override
        protected List<String> getEnabledProviders() {
            return sEnabledProviders;
        }

        public void notifyLocationFound() {
            // Listener could be removed in the notification.
            LocationListener[] listeners = new LocationListener[mListeners.size()];
            mLocationListeners.toArray(listeners);
            for (LocationListener listener :listeners) {
                listener.onLocationChanged(mLocation);
            }
        }

        public int getListenersCount() {
            return mListeners.size();
        }

        public void notifyCountryFound() {
            synchronized (countryFoundLocker) {
                notifyCountry = true;
                countryFoundLocker.notify();
            }
        }

        public Timer getTimer() {
            return mTimer;
        }

        public Thread getQueryThread() {
            return mQueryThread;
        }
    }

    private class CountryListenerImpl implements CountryListener {
        private boolean mNotified;
        private String mCountryCode;
        public void onCountryDetected(Country country) {
            mNotified = true;
            if (country != null) {
                mCountryCode = country.getCountryIso();
            }
        }

        public boolean notified() {
            return mNotified;
        }

        public String getCountry() {
            return mCountryCode;
        }
    }

    public void testFindingCountry() {
        testFindingCountryCommon(null);
    }

    public void testFindingCountryWithAcceptableProvider() {
        testFindingCountryCommon(new HashSet<String>(Arrays.asList("passive")));
    }

    private void testFindingCountryCommon(Set<String> acceptableProviders) {
        final String country = "us";
        final String provider = "Good";
        CountryListenerImpl countryListener = new CountryListenerImpl();
        TestCountryDetector detector = new TestCountryDetector(country, provider);

        if (acceptableProviders != null) {
            detector.setAcceptableProvider(acceptableProviders);
        }

        detector.setCountryListener(countryListener);
        detector.detectCountry();

        if (acceptableProviders != null) {
            assertEquals(acceptableProviders.size(), detector.getListenersCount());
            Map<String, LocationListener> listeners = detector.getListeners();
            for (String acceptableProvider : acceptableProviders) {
                assertTrue(listeners.containsKey(acceptableProvider));
            }
        } else {
            assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        }

        detector.notifyLocationFound();
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        assertNull(detector.getTimer());
        Thread queryThread = waitForQueryThreadLaunched(detector);
        detector.notifyCountryFound();
        // Wait for query thread ending
        waitForThreadEnding(queryThread);
        // QueryThread should be set to NULL
        assertNull(detector.getQueryThread());
        assertTrue(countryListener.notified());
        assertEquals("us", countryListener.getCountry().toLowerCase());
    }

    public void testFindingCountryCancelled() {
        final String country = "us";
        final String provider = "Good";
        CountryListenerImpl countryListener = new CountryListenerImpl();
        TestCountryDetector detector = new TestCountryDetector(country, provider);
        detector.setCountryListener(countryListener);
        detector.detectCountry();
        assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        detector.notifyLocationFound();
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        // The time should be stopped
        assertNull(detector.getTimer());
        Thread queryThread = waitForQueryThreadLaunched(detector);
        detector.stop();
        // There is no way to stop the thread, let's test it could be stopped, after get country
        detector.notifyCountryFound();
        // Wait for query thread ending
        waitForThreadEnding(queryThread);
        // QueryThread should be set to NULL
        assertNull(detector.getQueryThread());
        assertTrue(countryListener.notified());
        assertEquals("us", countryListener.getCountry().toLowerCase());
    }

    public void testFindingLocationCancelled() {
        final String country = "us";
        final String provider = "Good";
        CountryListenerImpl countryListener = new CountryListenerImpl();
        TestCountryDetector detector = new TestCountryDetector(country, provider);
        detector.setCountryListener(countryListener);
        detector.detectCountry();
        assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        detector.stop();
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        // The time should be stopped
        assertNull(detector.getTimer());
        // QueryThread should still be NULL
        assertNull(detector.getQueryThread());
        assertFalse(countryListener.notified());
    }

    public void testFindingLocationFailed() {
        final String country = "us";
        final String provider = "Good";
        long timeout = 1000;
        TestCountryDetector detector = new TestCountryDetector(country, provider, timeout) {
            @Override
            protected Location getLastKnownLocation() {
                return null;
            }
        };
        CountryListenerImpl countryListener = new CountryListenerImpl();
        detector.setCountryListener(countryListener);
        detector.detectCountry();
        assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        waitForTimerReset(detector);
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        // QueryThread should still be NULL
        assertNull(detector.getQueryThread());
        assertTrue(countryListener.notified());
        assertNull(countryListener.getCountry());
    }

    public void testFindingCountryFailed() {
        final String country = "us";
        final String provider = "Good";
        TestCountryDetector detector = new TestCountryDetector(country, provider) {
            @Override
            protected String getCountryFromLocation(Location location) {
                synchronized (countryFoundLocker) {
                    if (! notifyCountry) {
                        try {
                            countryFoundLocker.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                }
                // We didn't find country.
                return null;
            }
        };
        CountryListenerImpl countryListener = new CountryListenerImpl();
        detector.setCountryListener(countryListener);
        detector.detectCountry();
        assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        detector.notifyLocationFound();
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        assertNull(detector.getTimer());
        Thread queryThread = waitForQueryThreadLaunched(detector);
        detector.notifyCountryFound();
        // Wait for query thread ending
        waitForThreadEnding(queryThread);
        // QueryThread should be set to NULL
        assertNull(detector.getQueryThread());
        // CountryListener should be notified
        assertTrue(countryListener.notified());
        assertNull(countryListener.getCountry());
    }

    public void testFindingCountryWithLastKnownLocation() {
        final String country = "us";
        final String provider = "Good";
        long timeout = 1000;
        TestCountryDetector detector = new TestCountryDetector(country, provider, timeout);
        CountryListenerImpl countryListener = new CountryListenerImpl();
        detector.setCountryListener(countryListener);
        detector.detectCountry();
        assertEquals(TestCountryDetector.TOTAL_PROVIDERS, detector.getListenersCount());
        waitForTimerReset(detector);
        // All listeners should be unregistered
        assertEquals(0, detector.getListenersCount());
        Thread queryThread = waitForQueryThreadLaunched(detector);
        detector.notifyCountryFound();
        // Wait for query thread ending
        waitForThreadEnding(queryThread);
        // QueryThread should be set to NULL
        assertNull(detector.getQueryThread());
        // CountryListener should be notified
        assertTrue(countryListener.notified());
        assertEquals("us", countryListener.getCountry().toLowerCase());
    }

    private void waitForTimerReset(TestCountryDetector detector) {
        int count = 5;
        long interval = 1000;
        try {
            while (count-- > 0 && detector.getTimer() != null) {
                Thread.sleep(interval);
            }
        } catch (InterruptedException e) {
        }
        Timer timer = detector.getTimer();
        assertTrue(timer == null);
    }

    private void waitForThreadEnding(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private Thread waitForQueryThreadLaunched(TestCountryDetector detector) {
        int count = 5;
        long interval = 1000;
        try {
            while (count-- > 0 && detector.getQueryThread() == null) {
                Thread.sleep(interval);
            }
        } catch (InterruptedException e) {
        }
        Thread thread = detector.getQueryThread();
        assertTrue(thread != null);
        return thread;
    }
}
