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

package com.android.location.timezone.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.location.timezone.ILocationTimeZoneProvider;
import com.android.internal.location.timezone.ILocationTimeZoneProviderManager;
import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;

import java.util.Objects;

/**
 * A base class for location time zone providers implemented as unbundled services.
 *
 * <p>Provider implementations are enabled / disabled via a call to {@link
 * #onSetRequest(LocationTimeZoneProviderRequestUnbundled)}.
 *
 * <p>Once enabled, providers are expected to detect the time zone if possible, and report the
 * result via {@link #reportLocationTimeZoneEvent(LocationTimeZoneEventUnbundled)}  with a type of
 * either {@link LocationTimeZoneEventUnbundled#EVENT_TYPE_UNCERTAIN} or {@link
 * LocationTimeZoneEventUnbundled#EVENT_TYPE_SUCCESS}. Providers may also report that they have
 * permanently failed by sending an event of type {@link
 * LocationTimeZoneEventUnbundled#EVENT_TYPE_PERMANENT_FAILURE}. See the javadocs for each event
 * type for details.
 *
 * <p>Providers are expected to issue their first event within {@link
 * LocationTimeZoneProviderRequest#getInitializationTimeoutMillis()}.
 *
 * <p>Once disabled or have failed, providers are required to stop producing events.
 *
 * <p>Threading:
 *
 * <p>Calls to {@link #reportLocationTimeZoneEvent(LocationTimeZoneEventUnbundled)} can be made on
 * on any thread, but may be processed asynchronously by the system server. Similarly, calls to
 * {@link #onSetRequest(LocationTimeZoneProviderRequestUnbundled)} may occur on any thread.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled applications, and must remain
 * API stable.
 */
public abstract class LocationTimeZoneProviderBase {

    private final Context mContext;
    private final String mTag;
    private final IBinder mBinder;

    // write locked on mBinder, read lock is optional depending on atomicity requirements
    @Nullable private volatile ILocationTimeZoneProviderManager mManager;

    public LocationTimeZoneProviderBase(Context context, String tag) {
        mContext = context;
        mTag = tag;
        mBinder = new Service();
        mManager = null;
    }

    protected final Context getContext() {
        return mContext;
    }

    public final IBinder getBinder() {
        return mBinder;
    }

    /**
     * Reports a new location time zone event from this provider.
     */
    protected final void reportLocationTimeZoneEvent(
            @NonNull LocationTimeZoneEventUnbundled event) {
        ILocationTimeZoneProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onLocationTimeZoneEvent(event.getInternalLocationTimeZoneEvent());
            } catch (RemoteException | RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Set the {@link LocationTimeZoneProviderRequestUnbundled} requirements for this provider. Each
     * call to this method overrides all previous requests. This method might trigger the provider
     * to start returning location time zones, or to stop returning location time zones, depending
     * on the parameters in the request.
     */
    protected abstract void onSetRequest(@NonNull LocationTimeZoneProviderRequestUnbundled request);

    private final class Service extends ILocationTimeZoneProvider.Stub {

        @Override
        public void setLocationTimeZoneProviderManager(ILocationTimeZoneProviderManager manager) {
            mManager = Objects.requireNonNull(manager);
        }

        @Override
        public void setRequest(LocationTimeZoneProviderRequest request) {
            onSetRequest(new LocationTimeZoneProviderRequestUnbundled(request));
        }
    }
}
