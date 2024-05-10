/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the system country detector service. This service allows
 * applications to obtain the country that the user is in.
 *
 * <p>The country will be detected in order of reliability, like
 *
 * <ul>
 *   <li>Mobile network
 *   <li>Location
 *   <li>SIM's country
 *   <li>Phone's locale
 * </ul>
 *
 * <p>Call the {@link #detectCountry()} to get the available country immediately.
 *
 * <p>To be notified of the future country change, use the {@link #addCountryListener}
 *
 * <p>
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
@SystemService(Context.COUNTRY_DETECTOR)
public class CountryDetector {

    /**
     * The class to wrap the ICountryListener.Stub , CountryListener & {@code Consumer<Country>}
     * objects together.
     *
     * <p>The CountryListener will be notified through the Handler Executor once the country changed
     * and detected.
     *
     * <p>{@code Consumer<Country>} callback interface is notified through the specific executor
     * once the country changed and detected.
     */
    private static final class ListenerTransport extends ICountryListener.Stub {

        private final Consumer<Country> mListener;
        private final Executor mExecutor;

        ListenerTransport(Consumer<Country> consumer, Executor executor) {
            mListener = consumer;
            mExecutor = executor;
        }

        public void onCountryDetected(final Country country) {
            mExecutor.execute(() -> mListener.accept(country));
        }
    }

    private static final String TAG = "CountryDetector";
    private final ICountryDetector mService;
    private final HashMap<Consumer<Country>, ListenerTransport> mListeners;

    /**
     * @hide - hide this constructor because it has a parameter of type ICountryDetector, which is a
     *     system private class. The right way to create an instance of this class is using the
     *     factory Context.getSystemService.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public CountryDetector(ICountryDetector service) {
        mService = service;
        mListeners = new HashMap<>();
    }

    /**
     * Start detecting the country that the user is in.
     *
     * @return the country if it is available immediately, otherwise null will be returned.
     * @hide
     */
    @UnsupportedAppUsage
    public Country detectCountry() {
        try {
            return mService.detectCountry();
        } catch (RemoteException e) {
            Log.e(TAG, "detectCountry: RemoteException", e);
            return null;
        }
    }

    /**
     * Add a listener to receive the notification when the country is detected or changed.
     *
     * @param listener will be called when the country is detected or changed.
     * @param looper a Looper object whose message queue will be used to implement the callback
     *     mechanism. If looper is null then the callbacks will be called on the main thread.
     * @hide
     * @deprecated client using this api should use {@link
     *     #registerCountryDetectorCallback(Executor, Consumer)} }
     */
    @UnsupportedAppUsage
    @Deprecated
    public void addCountryListener(@NonNull CountryListener listener, @Nullable Looper looper) {
        Handler handler = looper != null ? new Handler(looper) : new Handler();
        registerCountryDetectorCallback(new HandlerExecutor(handler), listener);
    }

    /**
     * Remove the listener
     *
     * @hide
     * @deprecated client using this api should use {@link
     *     #unregisterCountryDetectorCallback(Consumer)}
     */
    @UnsupportedAppUsage
    @Deprecated
    public void removeCountryListener(CountryListener listener) {
        unregisterCountryDetectorCallback(listener);
    }

    /**
     * Add a callback interface, to be notified when country code is added or changes.
     *
     * @param executor The callback executor for the response.
     * @param consumer {@link Consumer} callback to receive the country code when changed/detected
     */
    public void registerCountryDetectorCallback(
            @NonNull Executor executor, @NonNull Consumer<Country> consumer) {
        synchronized (mListeners) {
            unregisterCountryDetectorCallback(consumer);
            ListenerTransport transport = new ListenerTransport(consumer, executor);
            try {
                mService.addCountryListener(transport);
                mListeners.put(consumer, transport);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** Remove the callback subscribed to Update country code */
    public void unregisterCountryDetectorCallback(@NonNull Consumer<Country> consumer) {
        synchronized (mListeners) {
            ListenerTransport transport = mListeners.remove(consumer);
            if (transport != null) {
                try {
                    mService.removeCountryListener(transport);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }
}
