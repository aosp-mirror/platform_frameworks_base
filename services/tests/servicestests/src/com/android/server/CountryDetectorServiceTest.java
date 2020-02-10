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

package com.android.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryListener;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CountryDetectorServiceTest {

    private static class CountryListenerTester extends ICountryListener.Stub {
        private Country mCountry;

        @Override
        public void onCountryDetected(Country country) {
            mCountry = country;
        }

        Country getCountry() {
            return mCountry;
        }

        public boolean isNotified() {
            return mCountry != null;
        }
    }

    private static class CountryDetectorServiceTester extends CountryDetectorService {
        private CountryListener mListener;

        CountryDetectorServiceTester(Context context, Handler handler) {
            super(context, handler);
        }

        @Override
        public void notifyReceivers(Country country) {
            super.notifyReceivers(country);
        }

        @Override
        protected void setCountryListener(final CountryListener listener) {
            mListener = listener;
        }

        boolean isListenerSet() {
            return mListener != null;
        }
    }

    @Rule
    public final Expect expect = Expect.create();
    @Spy
    private Context mContext = ApplicationProvider.getApplicationContext();
    @Spy
    private Handler mHandler = new Handler(Looper.myLooper());
    private CountryDetectorServiceTester mCountryDetectorService;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setUp() {
        mCountryDetectorService = new CountryDetectorServiceTester(mContext, mHandler);

        // Immediately invoke run on the Runnable posted to the handler
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getCallback().run();
            return true;
        }).when(mHandler).sendMessageAtTime(any(Message.class), anyLong());
    }

    @Test
    public void countryListener_add_successful() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        mCountryDetectorService.systemRunning();
        expect.that(mCountryDetectorService.isListenerSet()).isFalse();
        mCountryDetectorService.addCountryListener(countryListener);

        expect.that(mCountryDetectorService.isListenerSet()).isTrue();
    }

    @Test
    public void countryListener_remove_successful() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        mCountryDetectorService.systemRunning();
        mCountryDetectorService.addCountryListener(countryListener);
        expect.that(mCountryDetectorService.isListenerSet()).isTrue();
        mCountryDetectorService.removeCountryListener(countryListener);

        expect.that(mCountryDetectorService.isListenerSet()).isFalse();
    }

    @Test
    public void countryListener_notify_successful() throws RemoteException {
        CountryListenerTester countryListenerA = new CountryListenerTester();
        CountryListenerTester countryListenerB = new CountryListenerTester();
        Country country = new Country("US", Country.COUNTRY_SOURCE_NETWORK);

        mCountryDetectorService.systemRunning();
        mCountryDetectorService.addCountryListener(countryListenerA);
        mCountryDetectorService.addCountryListener(countryListenerB);
        expect.that(countryListenerA.isNotified()).isFalse();
        expect.that(countryListenerB.isNotified()).isFalse();
        mCountryDetectorService.notifyReceivers(country);

        expect.that(countryListenerA.isNotified()).isTrue();
        expect.that(countryListenerB.isNotified()).isTrue();
        expect.that(countryListenerA.getCountry().equalsIgnoreSource(country)).isTrue();
        expect.that(countryListenerB.getCountry().equalsIgnoreSource(country)).isTrue();
    }
}
