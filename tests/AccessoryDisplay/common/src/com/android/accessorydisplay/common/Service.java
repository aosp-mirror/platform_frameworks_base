/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.common;

import com.android.accessorydisplay.common.Transport;

import android.content.Context;
import android.os.Looper;

import java.nio.ByteBuffer;

/**
 * Base implementation of a service that communicates over a transport.
 * <p>
 * This object's interface is single-threaded.  It is only intended to be
 * accessed from the {@link Looper} thread on which the transport was created.
 * </p>
 */
public abstract class Service implements Transport.Callback {
    private final Context mContext;
    private final Transport mTransport;
    private final int mServiceId;

    public Service(Context context, Transport transport, int serviceId) {
        mContext = context;
        mTransport = transport;
        mServiceId = serviceId;
    }

    public Context getContext() {
        return mContext;
    }

    public int getServiceId() {
        return mServiceId;
    }

    public Transport getTransport() {
        return mTransport;
    }

    public Logger getLogger() {
        return mTransport.getLogger();
    }

    public void start() {
        mTransport.registerService(mServiceId, this);
    }

    public void stop() {
        mTransport.unregisterService(mServiceId);
    }

    @Override
    public void onMessageReceived(int service, int what, ByteBuffer content) {
    }
}
