/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.location.flags.Flags;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider for population density.
 * The population density is defined as the S2 level at which the S2 cell around the latitude /
 * longitude contains at least a thousand people.
 * It exposes two methods: one about providing population density around a latitude / longitude,
 * and one about providing a "default" population density to fall back to in case the first API
 * can't be used or returns an error.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_POPULATION_DENSITY_PROVIDER)
public abstract class PopulationDensityProviderBase {

    final String mTag;
    final @Nullable String mAttributionTag;
    final IBinder mBinder;

    /**
     * The action the wrapping service should have in its intent filter to implement the
     * PopulationDensity provider.
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_POPULATION_DENSITY_PROVIDER =
            "com.android.location.service.PopulationDensityProvider";

    public PopulationDensityProviderBase(@NonNull Context context, @NonNull String tag) {
        mTag = tag;
        mAttributionTag = context.getAttributionTag();
        mBinder = new Service();
    }

    /**
     * Returns the IBinder instance that should be returned from the
     * {@link android.app.Service#onBind(Intent)} method of the wrapping service.
     */
    public final @Nullable IBinder getBinder() {
        return mBinder;
    }

    /**
     * Called upon receiving a new request for the default coarsening level.
     * The callback {@link OutcomeReceiver#onResult} should be called with the result; or, in case
     * an error occurs, {@link OutcomeReceiver#onError} should be called.
     * The callback is single-use, calling more than any one of these two methods throws an
     * AssertionException.
     *
     * @param callback A single-use callback that either returns the coarsening level, or an error.
     */
    public abstract void onGetDefaultCoarseningLevel(@NonNull OutcomeReceiver<Integer, Throwable>
            callback);

    /**
     * Called upon receiving a new request for population density at a specific latitude/longitude,
     * expressed in degrees.
     * The answer is at least one S2CellId corresponding to the coarsening level at the specified
     * location. This must be the first element of the result array. Optionally, if
     * numAdditionalCells is greater than zero, additional nearby S2CellIds can be returned. One use
     * for the optional nearby cells is when the client has a local cache that needs to be filled
     * with the local area around a certain latitude/longitude. The callback
     * {@link OutcomeReceiver#onResult} should be called with the result; or, in case an error
     * occurs, {@link OutcomeReceiver#onError} should be called. The callback is single-use, calling
     * more than any one of these two methods throws an AssertionException.
     *
     * @param callback A single-use callback that either returns S2CellIds, or an error.
     */
    public abstract void onGetCoarsenedS2Cells(double latitudeDegrees, double longitudeDegrees,
            @IntRange(from = 0) int numAdditionalCells,
            @NonNull OutcomeReceiver<long[], Throwable> callback);

    private final class Service extends IPopulationDensityProvider.Stub {
        @Override
        public void getDefaultCoarseningLevel(@NonNull IS2LevelCallback callback) {
            try {
                onGetDefaultCoarseningLevel(new SingleUseS2LevelCallback(callback));
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
        public void getCoarsenedS2Cells(double latitudeDegrees, double longitudeDegrees,
                int numAdditionalCells, @NonNull IS2CellIdsCallback callback) {
            try {
                onGetCoarsenedS2Cells(latitudeDegrees, longitudeDegrees, numAdditionalCells,
                        new SingleUseS2CellIdsCallback(callback));
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

    private static class SingleUseS2LevelCallback implements OutcomeReceiver<Integer, Throwable> {

        private final AtomicReference<IS2LevelCallback> mCallback;

        SingleUseS2LevelCallback(IS2LevelCallback callback) {
            mCallback = new AtomicReference<>(callback);
        }

        @Override
        public void onResult(Integer level) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onResult(level);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void onError(Throwable e) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onError();
            } catch (RemoteException r) {
                throw r.rethrowFromSystemServer();
            }
        }
    }

    private static class SingleUseS2CellIdsCallback implements OutcomeReceiver<long[], Throwable> {

        private final AtomicReference<IS2CellIdsCallback> mCallback;

        SingleUseS2CellIdsCallback(IS2CellIdsCallback callback) {
            mCallback = new AtomicReference<>(callback);
        }

        @Override
        public void onResult(long[] s2CellIds) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onResult(s2CellIds);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void onError(Throwable e) {
            try {
                Objects.requireNonNull(mCallback.getAndSet(null)).onError();
            } catch (RemoteException r) {
                throw r.rethrowFromSystemServer();
            }
        }
    }
}
