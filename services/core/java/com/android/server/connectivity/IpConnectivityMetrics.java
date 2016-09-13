/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import android.content.Context;
import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.metrics.IpConnectivityLog;
import android.os.IBinder;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

import static com.android.server.connectivity.metrics.IpConnectivityLogClass.IpConnectivityEvent;

/** {@hide} */
final public class IpConnectivityMetrics extends SystemService {
    private static final String TAG = IpConnectivityMetrics.class.getSimpleName();
    private static final boolean DBG = false;

    private static final String SERVICE_NAME = IpConnectivityLog.SERVICE_NAME;

    // Default size of the event buffer. Once the buffer is full, incoming events are dropped.
    private static final int DEFAULT_BUFFER_SIZE = 2000;

    // Lock ensuring that concurrent manipulations of the event buffer are correct.
    // There are three concurrent operations to synchronize:
    //  - appending events to the buffer.
    //  - iterating throught the buffer.
    //  - flushing the buffer content and replacing it by a new buffer.
    private final Object mLock = new Object();

    @VisibleForTesting
    public final Impl impl = new Impl();
    private DnsEventListenerService mDnsListener;

    @GuardedBy("mLock")
    private ArrayList<ConnectivityMetricsEvent> mBuffer;
    @GuardedBy("mLock")
    private int mDropped;
    @GuardedBy("mLock")
    private int mCapacity;

    public IpConnectivityMetrics(Context ctx) {
        super(ctx);
        initBuffer();
    }

    @Override
    public void onStart() {
        if (DBG) Log.d(TAG, "onStart");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (DBG) Log.d(TAG, "onBootPhase");
            mDnsListener = new DnsEventListenerService(getContext());

            publishBinderService(SERVICE_NAME, impl);
            publishBinderService(mDnsListener.SERVICE_NAME, mDnsListener);
        }
    }

    @VisibleForTesting
    public int bufferCapacity() {
        return DEFAULT_BUFFER_SIZE; // TODO: read from config
    }

    private void initBuffer() {
        synchronized (mLock) {
            mDropped = 0;
            mCapacity = bufferCapacity();
            mBuffer = new ArrayList<>(mCapacity);
        }
    }

    private int append(ConnectivityMetricsEvent event) {
        if (DBG) Log.d(TAG, "logEvent: " + event);
        synchronized (mLock) {
            final int left = mCapacity - mBuffer.size();
            if (event == null) {
                return left;
            }
            if (left == 0) {
                mDropped++;
                return 0;
            }
            mBuffer.add(event);
            return left - 1;
        }
    }

    private String flushEncodedOutput() {
        final ArrayList<ConnectivityMetricsEvent> events;
        final int dropped;
        synchronized (mLock) {
            events = mBuffer;
            dropped = mDropped;
            initBuffer();
        }

        final byte[] data;
        try {
            data = IpConnectivityEventBuilder.serialize(dropped, events);
        } catch (IOException e) {
            Log.e(TAG, "could not serialize events", e);
            return "";
        }

        return Base64.encodeToString(data, Base64.DEFAULT);
    }

    /**
     * Clears the event buffer and prints its content as a protobuf serialized byte array
     * inside a base64 encoded string.
     */
    private void cmdFlush(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print(flushEncodedOutput());
    }

    /**
     * Prints the content of the event buffer, either using the events ASCII representation
     * or using protobuf text format.
     */
    private void cmdList(FileDescriptor fd, PrintWriter pw, String[] args) {
        final ArrayList<ConnectivityMetricsEvent> events;
        synchronized (mLock) {
            events = new ArrayList(mBuffer);
        }

        if (args.length > 1 && args[1].equals("proto")) {
            for (IpConnectivityEvent ev : IpConnectivityEventBuilder.toProto(events)) {
                pw.print(ev.toString());
            }
            return;
        }

        for (ConnectivityMetricsEvent ev : events) {
            pw.println(ev.toString());
        }
    }

    private void cmdStats(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("Buffered events: " + mBuffer.size());
            pw.println("Buffer capacity: " + mCapacity);
            pw.println("Dropped events: " + mDropped);
        }
        if (mDnsListener != null) {
            mDnsListener.dump(pw);
        }
    }

    private void cmdDefault(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args.length == 0) {
            pw.println("No command");
            return;
        }
        pw.println("Unknown command " + TextUtils.join(" ", args));
    }

    public final class Impl extends IIpConnectivityMetrics.Stub {
        static final String CMD_FLUSH   = "flush";
        static final String CMD_LIST    = "list";
        static final String CMD_STATS   = "stats";
        static final String CMD_DEFAULT = CMD_STATS;

        @Override
        public int logEvent(ConnectivityMetricsEvent event) {
            enforceConnectivityInternalPermission();
            return append(event);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            enforceDumpPermission();
            if (DBG) Log.d(TAG, "dumpsys " + TextUtils.join(" ", args));
            final String cmd = (args.length > 0) ? args[0] : CMD_DEFAULT;
            switch (cmd) {
                case CMD_FLUSH:
                    cmdFlush(fd, pw, args);
                    return;
                case CMD_LIST:
                    cmdList(fd, pw, args);
                    return;
                case CMD_STATS:
                    cmdStats(fd, pw, args);
                    return;
                default:
                    cmdDefault(fd, pw, args);
            }
        }

        private void enforceConnectivityInternalPermission() {
            enforcePermission(android.Manifest.permission.CONNECTIVITY_INTERNAL);
        }

        private void enforceDumpPermission() {
            enforcePermission(android.Manifest.permission.DUMP);
        }

        private void enforcePermission(String what) {
            getContext().enforceCallingOrSelfPermission(what, "IpConnectivityMetrics");
        }
    };
}
