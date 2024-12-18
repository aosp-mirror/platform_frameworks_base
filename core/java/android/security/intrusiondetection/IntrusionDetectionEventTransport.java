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

package android.security.intrusiondetection;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.util.CloseGuard;

import android.security.Flags;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.security.intrusiondetection.IIntrusionDetectionEventTransport;

import com.android.internal.infra.AndroidFuture;

import java.lang.AutoCloseable;
import java.util.List;

/**
 * A class that provides a stable API for transporting intrusion detection events
 * to a transport location, such as a file or a network endpoint.
 *
 * This class acts as a bridge between the {@link IIntrusionDetectionEventTransport}
 * interface and its implementations. It allows system components to add intrusion
 * detection events ({@link IntrusionDetectionEvent}) to a transport queue,
 * which will then be delivered to the specified location.
 *
 * Usage:
 * 1. Obtain an instance of {@link IntrusionDetectionEventTransport} using the constructor.
 * 2. Initialize the transport by calling {@link #initialize()}.
 * 3. Add events to the transport queue using {@link #addData(List)}.
 * 4. Release the transport when finished by calling {@link #release()}.
 *
 * Key Components:
 * - {@link IIntrusionDetectionEventTransport}: The underlying AIDL interface
 *     for interacting with transport implementations.
 * - {@link IntrusionDetectionEvent}: Represents a single event.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_AFL_API)
@SuppressLint("NotCloseable")
public class IntrusionDetectionEventTransport {
    IIntrusionDetectionEventTransport mBinderImpl = new TransportImpl();

    /**
     * Returns the binder interface for this transport.
     */
    @NonNull
    public IBinder getBinder() {
        return mBinderImpl.asBinder();
    }

    /**
     * Initializes the transport.
     *
     * @return whether the initialization was successful.
     */
    public boolean initialize() {
        return false;
    }

    /**
     * Adds data to the transport.
     *
     * @param events the events to add.
     * @return whether the addition was successful.
     */
    public boolean addData(@NonNull List<IntrusionDetectionEvent> events) {
        return false;
    }

    /**
     * Releases the transport.
     *
     * The release() method is a callback implemented by the concrete transport
     * endpoint.
     * The "SuppressLint" annotation is used to allow the release() method to be
     * included in the API without requiring the class to implement AutoCloseable.
     *
     * @return whether the release was successful.
     */
    public boolean release() {
        return false;
    }

    /**
     * Bridge between the actual IIntrusionDetectionEventTransport implementation
     * and the stable API.  If the binder interface needs to change, we use this
     * layer to translate so that we can decouple those framework-side changes
     * from the IntrusionDetectionEventTransport implementations.
     */
    class TransportImpl extends IIntrusionDetectionEventTransport.Stub {
        @Override
        public void initialize(AndroidFuture<Boolean> resultFuture) {
            try {
                boolean result = IntrusionDetectionEventTransport.this.initialize();
                resultFuture.complete(result);
            } catch (RuntimeException e) {
                resultFuture.cancel(/* mayInterruptIfRunning */ true);
            }
        }

        @Override
        public void addData(
            List<IntrusionDetectionEvent> events,
            AndroidFuture<Boolean> resultFuture) {
            try {
                boolean result = IntrusionDetectionEventTransport.this.addData(events);
                resultFuture.complete(result);
            } catch (RuntimeException e) {
                resultFuture.cancel(/* mayInterruptIfRunning */ true);
            }
        }

        @Override
        public void release(AndroidFuture<Boolean> resultFuture) {
            try {
                boolean result = IntrusionDetectionEventTransport.this.release();
                resultFuture.complete(result);
            } catch (RuntimeException e) {
                resultFuture.cancel(/* mayInterruptIfRunning */ true);
            }
        }
    }
}