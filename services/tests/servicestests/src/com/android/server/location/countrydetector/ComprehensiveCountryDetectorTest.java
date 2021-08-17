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
import android.test.AndroidTestCase;

public class ComprehensiveCountryDetectorTest extends AndroidTestCase {
    private class TestCountryDetector extends ComprehensiveCountryDetector {
        public final static String COUNTRY_ISO = "us";
        private boolean mLocationBasedDetectorStarted;
        private boolean mLocationBasedDetectorStopped;
        protected boolean mNotified;
        private boolean listenerAdded = false;

        private Country mNotifiedCountry;
        public TestCountryDetector() {
            super(getContext());
        }

        public void notifyLocationBasedListener(Country country) {
            mNotified = true;
            mNotifiedCountry = country;
            mLocationBasedCountryDetector.notifyListener(country);
        }

        public boolean locationBasedDetectorStarted() {
            return mLocationBasedCountryDetector != null && mLocationBasedDetectorStarted;
        }

        public boolean locationBasedDetectorStopped() {
            return mLocationBasedCountryDetector == null && mLocationBasedDetectorStopped;
        }

        public boolean locationRefreshStarted() {
            return mLocationRefreshTimer != null;
        }

        public boolean locationRefreshCancelled() {
            return mLocationRefreshTimer == null;
        }

        @Override
        protected CountryDetectorBase createLocationBasedCountryDetector() {
            return new CountryDetectorBase(mContext) {
                @Override
                public Country detectCountry() {
                    mLocationBasedDetectorStarted = true;
                    return null;
                }

                @Override
                public void stop() {
                    mLocationBasedDetectorStopped = true;
                }
            };
        }

        @Override
        protected Country getNetworkBasedCountry() {
            return null;
        }

        @Override
        protected Country getLastKnownLocationBasedCountry() {
            return mNotifiedCountry;
        }

        @Override
        protected Country getSimBasedCountry() {
            return null;
        }

        @Override
        protected Country getLocaleCountry() {
            return null;
        }

        @Override
        protected void runAfterDetectionAsync(final Country country, final Country detectedCountry,
                final boolean notifyChange, final boolean startLocationBasedDetection) {
            runAfterDetection(country, detectedCountry, notifyChange, startLocationBasedDetection);
        };

        @Override
        protected boolean isAirplaneModeOff() {
            return true;
        }

        @Override
        protected synchronized void addPhoneStateListener() {
            listenerAdded = true;
        }

        @Override
        protected synchronized void removePhoneStateListener() {
            listenerAdded = false;
        }

        @Override
        protected boolean isGeoCoderImplemented() {
            return true;
        }

        public boolean isPhoneStateListenerAdded() {
            return listenerAdded;
        }
    }

    private class CountryListenerImpl implements CountryListener {
        private boolean mNotified;
        private Country mCountry;

        public void onCountryDetected(Country country) {
            mNotified = true;
            mCountry = country;
        }

        public boolean notified() {
            return mNotified;
        }

        public Country getCountry() {
            return mCountry;
        }
    }

    public void testDetectNetworkBasedCountry() {
        final Country resultCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_NETWORK);
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected Country getNetworkBasedCountry() {
                return resultCountry;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, resultCountry));
        assertFalse(listener.notified());
        assertFalse(countryDetector.locationBasedDetectorStarted());
        assertFalse(countryDetector.locationRefreshStarted());
        countryDetector.stop();
    }

    public void testDetectLocationBasedCountry() {
        final Country resultCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_SIM);
        final Country locationBasedCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_LOCATION);
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected Country getSimBasedCountry() {
                return resultCountry;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, resultCountry));
        assertTrue(countryDetector.locationBasedDetectorStarted());
        countryDetector.notifyLocationBasedListener(locationBasedCountry);
        assertTrue(listener.notified());
        assertTrue(sameCountry(listener.getCountry(), locationBasedCountry));
        assertTrue(countryDetector.locationBasedDetectorStopped());
        assertTrue(countryDetector.locationRefreshStarted());
        countryDetector.stop();
        assertTrue(countryDetector.locationRefreshCancelled());
    }

    public void testLocaleBasedCountry() {
        final Country resultCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_LOCALE);
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected Country getLocaleCountry() {
                return resultCountry;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, resultCountry));
        assertFalse(listener.notified());
        assertTrue(countryDetector.locationBasedDetectorStarted());
        assertTrue(countryDetector.locationRefreshStarted());
        countryDetector.stop();
        assertTrue(countryDetector.locationRefreshCancelled());
    }

    public void testStoppingDetector() {
        // Test stopping detector when LocationBasedCountryDetector was started
        final Country resultCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_SIM);
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected Country getSimBasedCountry() {
                return resultCountry;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, resultCountry));
        assertTrue(countryDetector.locationBasedDetectorStarted());
        countryDetector.stop();
        // The LocationBasedDetector should be stopped.
        assertTrue(countryDetector.locationBasedDetectorStopped());
        // The location refresh should not running.
        assertTrue(countryDetector.locationRefreshCancelled());
    }

    public void testLocationBasedCountryNotFound() {
        final Country resultCountry = new Country(
                TestCountryDetector.COUNTRY_ISO, Country.COUNTRY_SOURCE_SIM);
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected Country getSimBasedCountry() {
                return resultCountry;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, resultCountry));
        assertTrue(countryDetector.locationBasedDetectorStarted());
        countryDetector.notifyLocationBasedListener(null);
        assertFalse(listener.notified());
        assertTrue(sameCountry(listener.getCountry(), null));
        assertTrue(countryDetector.locationBasedDetectorStopped());
        assertTrue(countryDetector.locationRefreshStarted());
        countryDetector.stop();
        assertTrue(countryDetector.locationRefreshCancelled());
    }

    public void testNoCountryFound() {
        TestCountryDetector countryDetector = new TestCountryDetector();
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        Country country = countryDetector.detectCountry();
        assertTrue(sameCountry(country, null));
        assertTrue(countryDetector.locationBasedDetectorStarted());
        countryDetector.notifyLocationBasedListener(null);
        assertFalse(listener.notified());
        assertTrue(sameCountry(listener.getCountry(), null));
        assertTrue(countryDetector.locationBasedDetectorStopped());
        assertTrue(countryDetector.locationRefreshStarted());
        countryDetector.stop();
        assertTrue(countryDetector.locationRefreshCancelled());
    }

    public void testAddRemoveListener() {
        TestCountryDetector countryDetector = new TestCountryDetector();
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        assertTrue(countryDetector.isPhoneStateListenerAdded());
        assertTrue(countryDetector.locationBasedDetectorStarted());
        countryDetector.setCountryListener(null);
        assertFalse(countryDetector.isPhoneStateListenerAdded());
        assertTrue(countryDetector.locationBasedDetectorStopped());
    }

    public void testGeocoderNotImplemented() {
        TestCountryDetector countryDetector = new TestCountryDetector() {
            @Override
            protected boolean isGeoCoderImplemented() {
                return false;
            }
        };
        CountryListenerImpl listener = new CountryListenerImpl();
        countryDetector.setCountryListener(listener);
        assertTrue(countryDetector.isPhoneStateListenerAdded());
        assertFalse(countryDetector.locationBasedDetectorStarted());
        countryDetector.setCountryListener(null);
        assertFalse(countryDetector.isPhoneStateListenerAdded());
    }

    private boolean sameCountry(Country country1, Country country2) {
        return country1 == null && country2 == null || country1 != null && country2 != null &&
        country1.getCountryIso().equalsIgnoreCase(country2.getCountryIso()) &&
        country1.getSource() == country2.getSource();
    }
}
