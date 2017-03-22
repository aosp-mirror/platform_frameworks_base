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
import android.net.INetdEventCallback;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.TokenBucket;
import com.android.server.SystemService;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** {@hide} */
final public class IpConnectivityMetrics extends SystemService {
    private static final String TAG = IpConnectivityMetrics.class.getSimpleName();
    private static final boolean DBG = false;

    // The logical version numbers of ipconnectivity.proto, corresponding to the
    // "version" field of IpConnectivityLog.
    private static final int NYC      = 0;
    private static final int NYC_MR1  = 1;
    private static final int NYC_MR2  = 2;
    public static final int VERSION   = NYC_MR2;

    private static final String SERVICE_NAME = IpConnectivityLog.SERVICE_NAME;

    // Default size of the event buffer. Once the buffer is full, incoming events are dropped.
    private static final int DEFAULT_BUFFER_SIZE = 2000;
    // Maximum size of the event buffer.
    private static final int MAXIMUM_BUFFER_SIZE = DEFAULT_BUFFER_SIZE * 10;

    private static final int MAXIMUM_CONNECT_LATENCY_RECORDS = 20000;

    private static final int ERROR_RATE_LIMITED = -1;

    // Lock ensuring that concurrent manipulations of the event buffer are correct.
    // There are three concurrent operations to synchronize:
    //  - appending events to the buffer.
    //  - iterating throught the buffer.
    //  - flushing the buffer content and replacing it by a new buffer.
    private final Object mLock = new Object();

    @VisibleForTesting
    public final Impl impl = new Impl();
    @VisibleForTesting
    NetdEventListenerService mNetdListener;

    @GuardedBy("mLock")
    private ArrayList<ConnectivityMetricsEvent> mBuffer;
    @GuardedBy("mLock")
    private int mDropped;
    @GuardedBy("mLock")
    private int mCapacity;
    @GuardedBy("mLock")
    private final ArrayMap<Class<?>, TokenBucket> mBuckets = makeRateLimitingBuckets();

    private final ToIntFunction<Context> mCapacityGetter;

    public IpConnectivityMetrics(Context ctx, ToIntFunction<Context> capacityGetter) {
        super(ctx);
        mCapacityGetter = capacityGetter;
        initBuffer();
    }

    public IpConnectivityMetrics(Context ctx) {
        this(ctx, READ_BUFFER_SIZE);
    }

    @Override
    public void onStart() {
        if (DBG) Log.d(TAG, "onStart");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (DBG) Log.d(TAG, "onBootPhase");
            mNetdListener = new NetdEventListenerService(getContext());

            publishBinderService(SERVICE_NAME, impl);
            publishBinderService(mNetdListener.SERVICE_NAME, mNetdListener);
        }
    }

    @VisibleForTesting
    public int bufferCapacity() {
        return mCapacityGetter.applyAsInt(getContext());
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
            if (isRateLimited(event)) {
                // Do not count as a dropped event. TODO: consider adding separate counter
                return ERROR_RATE_LIMITED;
            }
            if (left == 0) {
                mDropped++;
                return 0;
            }
            mBuffer.add(event);
            return left - 1;
        }
    }

    private boolean isRateLimited(ConnectivityMetricsEvent event) {
        TokenBucket tb = mBuckets.get(event.data.getClass());
        return (tb != null) && !tb.get();
    }

    private String flushEncodedOutput() {
        final ArrayList<ConnectivityMetricsEvent> events;
        final int dropped;
        synchronized (mLock) {
            events = mBuffer;
            dropped = mDropped;
            initBuffer();
        }

        final List<IpConnectivityEvent> protoEvents = IpConnectivityEventBuilder.toProto(events);

        if (mNetdListener != null) {
            mNetdListener.flushStatistics(protoEvents);
        }

        final byte[] data;
        try {
            data = IpConnectivityEventBuilder.serialize(dropped, protoEvents);
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
            if (mNetdListener != null) {
                mNetdListener.listAsProtos(pw);
            }
            return;
        }

        for (ConnectivityMetricsEvent ev : events) {
            pw.println(ev.toString());
        }
        if (mNetdListener != null) {
            mNetdListener.list(pw);
        }
    }

    private void cmdStats(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("Buffered events: " + mBuffer.size());
            pw.println("Buffer capacity: " + mCapacity);
            pw.println("Dropped events: " + mDropped);
        }
        if (mNetdListener != null) {
            mNetdListener.dump(pw);
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
        static final String CMD_DUMPSYS = "-a"; // dumpsys.cpp dumps services with "-a" as arguments
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
                case CMD_DUMPSYS:
                    // Fallthrough to CMD_LIST when dumpsys.cpp dumps services states (bug reports)
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

        private void enforceNetdEventListeningPermission() {
            final int uid = Binder.getCallingUid();
            if (uid != Process.SYSTEM_UID) {
                throw new SecurityException(String.format("Uid %d has no permission to listen for"
                        + " netd events.", uid));
            }
        }

        @Override
        public boolean registerNetdEventCallback(INetdEventCallback callback) {
            enforceNetdEventListeningPermission();
            if (mNetdListener == null) {
                return false;
            }
            return mNetdListener.registerNetdEventCallback(callback);
        }

        @Override
        public boolean unregisterNetdEventCallback() {
            enforceNetdEventListeningPermission();
            if (mNetdListener == null) {
                // if the service is null, we aren't registered anyway
                return true;
            }
            return mNetdListener.unregisterNetdEventCallback();
        }
    };

    private static final ToIntFunction<Context> READ_BUFFER_SIZE = (ctx) -> {
        int size = Settings.Global.getInt(ctx.getContentResolver(),
                Settings.Global.CONNECTIVITY_METRICS_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        if (size <= 0) {
            return DEFAULT_BUFFER_SIZE;
        }
        return Math.min(size, MAXIMUM_BUFFER_SIZE);
    };

    private static ArrayMap<Class<?>, TokenBucket> makeRateLimitingBuckets() {
        ArrayMap<Class<?>, TokenBucket> map = new ArrayMap<>();
        // one token every minute, 50 tokens max: burst of ~50 events every hour.
        map.put(ApfProgramEvent.class, new TokenBucket((int)DateUtils.MINUTE_IN_MILLIS, 50));
        return map;
    }
}
