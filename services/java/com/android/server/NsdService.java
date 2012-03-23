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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.DnsSdServiceInfo;
import android.net.nsd.DnsSdTxtRecord;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.IBinder;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.List;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.am.BatteryStatsService;
import com.android.server.NativeDaemonConnector.Command;
import com.android.internal.R;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";

    private static final boolean DBG = true;

    private Context mContext;

    /**
     * Clients receiving asynchronous messages
     */
    private List<AsyncChannel> mClients = new ArrayList<AsyncChannel>();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    /**
     * Handles client(app) connections
     */
    private class AsyncServiceHandler extends Handler {

        AsyncServiceHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        AsyncChannel c = (AsyncChannel) msg.obj;
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                        c.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
                        mClients.add(c);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        Slog.e(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    mClients.remove((AsyncChannel) msg.obj);
                    break;
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                case NsdManager.DISCOVER_SERVICES:
                    if (DBG) Slog.d(TAG, "Discover services");
                    DnsSdServiceInfo s = (DnsSdServiceInfo) msg.obj;
                    discoverServices(1, s.getServiceType());
                    mReplyChannel.replyToMessage(msg, NsdManager.DISCOVER_SERVICES_STARTED);
                    break;
                case NsdManager.STOP_DISCOVERY:
                    if (DBG) Slog.d(TAG, "Stop service discovery");
                    mReplyChannel.replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED);
                    break;
                case NsdManager.REGISTER_SERVICE:
                    if (DBG) Slog.d(TAG, "Register service");
                    mReplyChannel.replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED);
                    break;
                case NsdManager.UPDATE_SERVICE:
                    if (DBG) Slog.d(TAG, "Update service");
                    mReplyChannel.replyToMessage(msg, NsdManager.UPDATE_SERVICE_FAILED);
                    break;
                default:
                    Slog.d(TAG, "NsdServicehandler.handleMessage ignoring msg=" + msg);
                    break;
            }
        }
    }
    private AsyncServiceHandler mAsyncServiceHandler;

    private NativeDaemonConnector mNativeConnector;
    private final CountDownLatch mNativeDaemonConnected = new CountDownLatch(1);

    private NsdService(Context context) {
        mContext = context;

        HandlerThread nsdThread = new HandlerThread("NsdService");
        nsdThread.start();
        mAsyncServiceHandler = new AsyncServiceHandler(nsdThread.getLooper());

        /*
        mNativeConnector = new NativeDaemonConnector(new NativeCallbackReceiver(), "mdns", 10,
                MDNS_TAG, 25);
        Thread th = new Thread(mNativeConnector, MDNS_TAG);
        th.start();
        */
    }

    public static NsdService create(Context context) throws InterruptedException {
        NsdService service = new NsdService(context);
        /* service.mNativeDaemonConnected.await(); */
        return service;
    }

    public Messenger getMessenger() {
        return new Messenger(mAsyncServiceHandler);
    }

    /* These should be in sync with system/netd/mDnsResponseCode.h */
    class NativeResponseCode {
        public static final int SERVICE_FOUND               =   101;
        public static final int SERVICE_LOST                =   102;
        public static final int SERVICE_DISCOVERY_FAILED    =   103;

        public static final int SERVICE_REGISTERED          =   104;
        public static final int SERVICE_REGISTRATION_FAILED =   105;

        public static final int SERVICE_UPDATED             =   106;
        public static final int SERVICE_UPDATE_FAILED       =   107;

        public static final int SERVICE_RESOLVED            =   108;
        public static final int SERVICE_RESOLUTION_FAILED   =   109;
    }


    class NativeCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            mNativeDaemonConnected.countDown();
        }

        public boolean onEvent(int code, String raw, String[] cooked) {
            switch (code) {
                case NativeResponseCode.SERVICE_FOUND:
                    /* NNN uniqueId serviceName regType */
                    break;
                case NativeResponseCode.SERVICE_LOST:
                    /* NNN uniqueId serviceName regType */
                    break;
                case NativeResponseCode.SERVICE_DISCOVERY_FAILED:
                    /* NNN uniqueId errorCode */
                    break;
                case NativeResponseCode.SERVICE_REGISTERED:
                    /* NNN regId serviceName regType */
                    break;
                case NativeResponseCode.SERVICE_REGISTRATION_FAILED:
                    /* NNN regId errorCode */
                    break;
                case NativeResponseCode.SERVICE_UPDATED:
                    /* NNN regId */
                    break;
                case NativeResponseCode.SERVICE_UPDATE_FAILED:
                    /* NNN regId errorCode */
                    break;
                case NativeResponseCode.SERVICE_RESOLVED:
                    /* NNN resolveId fullName hostName port txtlen txtdata */
                    break;
                case NativeResponseCode.SERVICE_RESOLUTION_FAILED:
                    /* NNN resovleId errorCode */
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    private void registerService(int regId, DnsSdServiceInfo service) {
        try {
            //Add txtlen and txtdata
            mNativeConnector.execute("mdnssd", "register", regId, service.getServiceName(),
                    service.getServiceType(), service.getPort());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to execute registerService");
        }
    }

    private void updateService(int regId, DnsSdTxtRecord t) {
        try {
            if (t == null) return;
            mNativeConnector.execute("mdnssd", "update", regId, t.size(), t.getRawData());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to updateServices");
        }
    }

    private void discoverServices(int discoveryId, String serviceType) {
        try {
            mNativeConnector.execute("mdnssd", "discover", discoveryId, serviceType);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to discoverServices");
        }
    }

    private void stopServiceDiscovery(int discoveryId) {
        try {
            mNativeConnector.execute("mdnssd", "stopdiscover", discoveryId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stopServiceDiscovery");
        }
    }

    private void resolveService(DnsSdServiceInfo service) {
        try {
        mNativeConnector.execute("mdnssd", "resolve", service.getServiceName(),
                service.getServiceType());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to resolveService");
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ServiceDiscoverService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("Internal state:");
    }
}
