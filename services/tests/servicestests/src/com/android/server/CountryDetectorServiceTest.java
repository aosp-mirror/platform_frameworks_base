/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryListener;
import android.os.RemoteException;
import android.test.AndroidTestCase;

public class CountryDetectorServiceTest extends AndroidTestCase {
    private class CountryListenerTester extends ICountryListener.Stub {
        private Country mCountry;

        @Override
        public void onCountryDetected(Country country) throws RemoteException {
            mCountry = country;
        }

        public Country getCountry() {
            return mCountry;
        }

        public boolean isNotified() {
            return mCountry != null;
        }
    }

    private class CountryDetectorServiceTester extends CountryDetectorService {

        private CountryListener mListener;

        public CountryDetectorServiceTester(Context context) {
            super(context);
        }

        @Override
        public void notifyReceivers(Country country) {
            super.notifyReceivers(country);
        }

        @Override
        protected void setCountryListener(final CountryListener listener) {
            mListener = listener;
        }

        public boolean isListenerSet() {
            return mListener != null;
        }
    }

    public void testAddRemoveListener() throws RemoteException {
        CountryDetectorServiceTester serviceTester = new CountryDetectorServiceTester(getContext());
        serviceTester.systemRunning();
        waitForSystemReady(serviceTester);
        CountryListenerTester listenerTester = new CountryListenerTester();
        serviceTester.addCountryListener(listenerTester);
        assertTrue(serviceTester.isListenerSet());
        serviceTester.removeCountryListener(listenerTester);
        assertFalse(serviceTester.isListenerSet());
    }

    public void testNotifyListeners() throws RemoteException {
        CountryDetectorServiceTester serviceTester = new CountryDetectorServiceTester(getContext());
        CountryListenerTester listenerTesterA = new CountryListenerTester();
        CountryListenerTester listenerTesterB = new CountryListenerTester();
        Country country = new Country("US", Country.COUNTRY_SOURCE_NETWORK);
        serviceTester.systemRunning();
        waitForSystemReady(serviceTester);
        serviceTester.addCountryListener(listenerTesterA);
        serviceTester.addCountryListener(listenerTesterB);
        serviceTester.notifyReceivers(country);
        assertTrue(serviceTester.isListenerSet());
        assertTrue(listenerTesterA.isNotified());
        assertTrue(listenerTesterB.isNotified());
        serviceTester.removeCountryListener(listenerTesterA);
        serviceTester.removeCountryListener(listenerTesterB);
        assertFalse(serviceTester.isListenerSet());
    }

    private void waitForSystemReady(CountryDetectorService service) {
        int count = 5;
        while (count-- > 0) {
            try {
                Thread.sleep(500);
            } catch (Exception e) {
            }
            if (service.isSystemReady()) {
                return;
            }
        }
        throw new RuntimeException("Wait System Ready timeout");
    }
}
