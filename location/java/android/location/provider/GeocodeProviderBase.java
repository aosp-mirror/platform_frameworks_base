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
 * limitations under the License.
 */

package android.location.provider;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.flags.Flags;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for geocode providers outside the system server.
 *
 * <p>Geocode providers should be wrapped in a non-exported service which returns the result of
 * {@link #getBinder()} from the service's {@link android.app.Service#onBind(Intent)} method. The
 * service should not be exported so that components other than the system server cannot bind to it.
 * Alternatively, the service may be guarded by a permission that only system server can obtain. The
 * service may specify metadata on its capabilities:
 *
 * <ul>
 *   <li>"serviceVersion": An integer version code to help tie break if multiple services are
 *       capable of implementing the geocode provider. All else equal, the service with the highest
 *       version code will be chosen. Assumed to be 0 if not specified.
 *   <li>"serviceIsMultiuser": A boolean property, indicating if the service wishes to take
 *       responsibility for handling changes to the current user on the device. If true, the service
 *       will always be bound from the system user. If false, the service will always be bound from
 *       the current user. If the current user changes, the old binding will be released, and a new
 *       binding established under the new user. Assumed to be false if not specified.
 * </ul>
 *
 * <p>The service should have an intent filter in place for the geocode provider as specified by the
 * constant in this class.
 *
 * <p>Geocode providers are identified by their UID / package name / attribution tag. Based on this
 * identity, geocode providers may be given some special privileges.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_NEW_GEOCODER)
@SystemApi
public abstract class GeocodeProviderBase {

    /**
     * The action the wrapping service should have in its intent filter to implement the geocode
     * provider.
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_GEOCODE_PROVIDER =
            "com.android.location.service.GeocodeProvider";

    final String mTag;
    @Nullable final String mAttributionTag;
    final IBinder mBinder;

    /**
     * Subclasses should pass in a context and an arbitrary tag that may be used for logcat logging
     * of errors, and thus should uniquely identify the class.
     */
    public GeocodeProviderBase(@NonNull Context context, @NonNull String tag) {
        mTag = tag;
        mAttributionTag = context.getAttributionTag();
        mBinder = new Service();
    }

    /**
     * Returns the IBinder instance that should be returned from the {@link
     * android.app.Service#onBind(Intent)} method of the wrapping service.
     */
    @NonNull
    public final IBinder getBinder() {
        return mBinder;
    }

    /**
     * Requests forward geocoding of the given arguments. The given callback must be invoked once.
     */
    public abstract void onForwardGeocode(
            @NonNull ForwardGeocodeRequest request,
            @NonNull OutcomeReceiver<List<Address>, Throwable> callback);

    /**
     * Requests reverse geocoding of the given arguments. The given callback must be invoked once.
     */
    public abstract void onReverseGeocode(
            @NonNull ReverseGeocodeRequest request,
            @NonNull OutcomeReceiver<List<Address>, Throwable> callback);

    private class Service extends IGeocodeProvider.Stub {
        @Override
        public void forwardGeocode(ForwardGeocodeRequest request, IGeocodeCallback callback) {
            try {
                onForwardGeocode(request, new SingleUseCallback(callback));
            } catch (RuntimeException e) {
                // exceptions on one-way binder threads are dropped - move to a different thread
                Log.w(mTag, e);
                new Handler(Looper.getMainLooper())
                        .post(
                                () -> {
                                    throw new AssertionError(e);
                                });
            }
        }

        @Override
        public void reverseGeocode(ReverseGeocodeRequest request, IGeocodeCallback callback) {
            try {
                onReverseGeocode(request, new SingleUseCallback(callback));
            } catch (RuntimeException e) {
                // exceptions on one-way binder threads are dropped - move to a different thread
                Log.w(mTag, e);
                new Handler(Looper.getMainLooper())
                        .post(
                                () -> {
                                    throw new AssertionError(e);
                                });
            }
        }
    }

    private static class SingleUseCallback implements OutcomeReceiver<List<Address>, Throwable> {

        private final AtomicReference<IGeocodeCallback> mCallback;

        SingleUseCallback(IGeocodeCallback callback) {
            mCallback = new AtomicReference<>(callback);
        }

        @Override
        public void onError(Throwable e) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onError(e.toString());
            } catch (RemoteException r) {
                throw r.rethrowFromSystemServer();
            }
        }

        @Override
        public void onResult(List<Address> results) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onResults(results);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
