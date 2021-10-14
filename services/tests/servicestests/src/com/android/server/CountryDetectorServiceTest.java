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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryListener;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.R;
import com.android.server.location.countrydetector.ComprehensiveCountryDetector;
import com.android.server.location.countrydetector.CustomCountryDetectorTestClass;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CountryDetectorServiceTest {

    private static final String VALID_CUSTOM_TEST_CLASS =
            "com.android.server.location.countrydetector.CustomCountryDetectorTestClass";
    private static final String INVALID_CUSTOM_TEST_CLASS =
            "com.android.server.location.MissingCountryDetectorTestClass";

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

    @Rule public final Expect expect = Expect.create();
    @Spy private Context mContext = ApplicationProvider.getApplicationContext();
    @Spy private Handler mHandler = new Handler(Looper.myLooper());
    @Mock private Resources mResources;

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

        doReturn(mResources).when(mContext).getResources();
    }

    @Test
    public void addCountryListener_validListener_listenerAdded() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        mCountryDetectorService.systemRunning();
        expect.that(mCountryDetectorService.isListenerSet()).isFalse();
        mCountryDetectorService.addCountryListener(countryListener);

        expect.that(mCountryDetectorService.isListenerSet()).isTrue();
    }

    @Test
    public void removeCountryListener_validListener_listenerRemoved() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        mCountryDetectorService.systemRunning();
        mCountryDetectorService.addCountryListener(countryListener);
        expect.that(mCountryDetectorService.isListenerSet()).isTrue();
        mCountryDetectorService.removeCountryListener(countryListener);

        expect.that(mCountryDetectorService.isListenerSet()).isFalse();
    }

    @Test(expected = RemoteException.class)
    public void addCountryListener_serviceNotReady_throwsException() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        expect.that(mCountryDetectorService.isSystemReady()).isFalse();
        mCountryDetectorService.addCountryListener(countryListener);
    }

    @Test(expected = RemoteException.class)
    public void removeCountryListener_serviceNotReady_throwsException() throws RemoteException {
        CountryListenerTester countryListener = new CountryListenerTester();

        expect.that(mCountryDetectorService.isSystemReady()).isFalse();
        mCountryDetectorService.removeCountryListener(countryListener);
    }

    @Test
    public void detectCountry_serviceNotReady_returnNull() {
        expect.that(mCountryDetectorService.isSystemReady()).isFalse();

        expect.that(mCountryDetectorService.detectCountry()).isNull();
    }

    @Test
    public void notifyReceivers_twoListenersRegistered_bothNotified() throws RemoteException {
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

    @Test
    public void initialize_deviceWithCustomDetector_useCustomDetectorClass() {
        when(mResources.getString(R.string.config_customCountryDetector))
                .thenReturn(VALID_CUSTOM_TEST_CLASS);

        mCountryDetectorService.initialize();

        expect.that(mCountryDetectorService.getCountryDetector())
                .isInstanceOf(CustomCountryDetectorTestClass.class);
    }

    @Test
    public void initialize_deviceWithInvalidCustomDetector_useDefaultDetector() {
        when(mResources.getString(R.string.config_customCountryDetector))
                .thenReturn(INVALID_CUSTOM_TEST_CLASS);

        mCountryDetectorService.initialize();

        expect.that(mCountryDetectorService.getCountryDetector())
                .isInstanceOf(ComprehensiveCountryDetector.class);
    }
}
