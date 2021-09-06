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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.IPacProxyInstalledListener;
import android.net.IPacProxyManager;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.TrafficStatsConstants;
import com.android.net.IProxyCallback;
import com.android.net.IProxyPortListener;
import com.android.net.IProxyService;
import com.android.net.module.util.PermissionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * @hide
 */
public class PacProxyService extends IPacProxyManager.Stub {
    private static final String PAC_PACKAGE = "com.android.pacprocessor";
    private static final String PAC_SERVICE = "com.android.pacprocessor.PacService";
    private static final String PAC_SERVICE_NAME = "com.android.net.IProxyService";

    private static final String PROXY_PACKAGE = "com.android.proxyhandler";
    private static final String PROXY_SERVICE = "com.android.proxyhandler.ProxyService";

    private static final String TAG = "PacProxyService";

    private static final String ACTION_PAC_REFRESH = "android.net.proxy.PAC_REFRESH";

    private static final String DEFAULT_DELAYS = "8 32 120 14400 43200";
    private static final int DELAY_1 = 0;
    private static final int DELAY_4 = 3;
    private static final int DELAY_LONG = 4;
    private static final long MAX_PAC_SIZE = 20 * 1000 * 1000;

    private String mCurrentPac;
    @GuardedBy("mProxyLock")
    private volatile Uri mPacUrl = Uri.EMPTY;

    private AlarmManager mAlarmManager;
    @GuardedBy("mProxyLock")
    private IProxyService mProxyService;
    private PendingIntent mPacRefreshIntent;
    private ServiceConnection mConnection;
    private ServiceConnection mProxyConnection;
    private Context mContext;

    private int mCurrentDelay;
    private int mLastPort;

    private volatile boolean mHasSentBroadcast;
    private volatile boolean mHasDownloaded;

    private final RemoteCallbackList<IPacProxyInstalledListener>
            mCallbacks = new RemoteCallbackList<>();

    /**
     * Used for locking when setting mProxyService and all references to mCurrentPac.
     */
    private final Object mProxyLock = new Object();

    /**
     * Lock ensuring consistency between the values of mHasSentBroadcast, mHasDownloaded, the
     * last URL and port, and the broadcast message being sent with the correct arguments.
     * TODO : this should probably protect all instances of these variables
     */
    private final Object mBroadcastStateLock = new Object();

    /**
     * Runnable to download PAC script.
     * The behavior relies on the assumption it always runs on mNetThread to guarantee that the
     * latest data fetched from mPacUrl is stored in mProxyService.
     */
    private Runnable mPacDownloader = new Runnable() {
        @Override
        @WorkerThread
        public void run() {
            String file;
            final Uri pacUrl = mPacUrl;
            if (Uri.EMPTY.equals(pacUrl)) return;
            final int oldTag = TrafficStats.getAndSetThreadStatsTag(
                    TrafficStatsConstants.TAG_SYSTEM_PAC);
            try {
                file = get(pacUrl);
            } catch (IOException ioe) {
                file = null;
                Log.w(TAG, "Failed to load PAC file: " + ioe);
            } finally {
                TrafficStats.setThreadStatsTag(oldTag);
            }
            if (file != null) {
                synchronized (mProxyLock) {
                    if (!file.equals(mCurrentPac)) {
                        setCurrentProxyScript(file);
                    }
                }
                mHasDownloaded = true;
                sendProxyIfNeeded();
                longSchedule();
            } else {
                reschedule();
            }
        }
    };

    private final Handler mNetThreadHandler;

    class PacRefreshIntentReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            mNetThreadHandler.post(mPacDownloader);
        }
    }

    public PacProxyService(@NonNull Context context) {
        mContext = context;
        mLastPort = -1;
        final HandlerThread netThread = new HandlerThread("android.pacproxyservice",
                android.os.Process.THREAD_PRIORITY_DEFAULT);
        netThread.start();
        mNetThreadHandler = new Handler(netThread.getLooper());

        mPacRefreshIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_PAC_REFRESH), PendingIntent.FLAG_IMMUTABLE);
        context.registerReceiver(new PacRefreshIntentReceiver(),
                new IntentFilter(ACTION_PAC_REFRESH));
    }

    private AlarmManager getAlarmManager() {
        if (mAlarmManager == null) {
            mAlarmManager = mContext.getSystemService(AlarmManager.class);
        }
        return mAlarmManager;
    }

    @Override
    public void addListener(IPacProxyInstalledListener listener) {
        PermissionUtils.enforceNetworkStackPermissionOr(mContext,
                android.Manifest.permission.NETWORK_SETTINGS);
        mCallbacks.register(listener);
    }

    @Override
    public void removeListener(IPacProxyInstalledListener listener) {
        PermissionUtils.enforceNetworkStackPermissionOr(mContext,
                android.Manifest.permission.NETWORK_SETTINGS);
        mCallbacks.unregister(listener);
    }

    /**
     * Updates the PAC Proxy Service with current Proxy information. This is called by
     * the ProxyTracker through PacProxyManager before a broadcast takes place to allow
     * the PacProxyService to indicate that the broadcast should not be sent and the
     * PacProxyService will trigger a new broadcast when it is ready.
     *
     * @param proxy Proxy information that is about to be broadcast.
     */
    @Override
    public void setCurrentProxyScriptUrl(@Nullable ProxyInfo proxy) {
        PermissionUtils.enforceNetworkStackPermissionOr(mContext,
                android.Manifest.permission.NETWORK_SETTINGS);

        synchronized (mBroadcastStateLock) {
            if (proxy != null && !Uri.EMPTY.equals(proxy.getPacFileUrl())) {
                if (proxy.getPacFileUrl().equals(mPacUrl) && (proxy.getPort() > 0)) return;
                mPacUrl = proxy.getPacFileUrl();
                mCurrentDelay = DELAY_1;
                mHasSentBroadcast = false;
                mHasDownloaded = false;
                getAlarmManager().cancel(mPacRefreshIntent);
                bind();
            } else {
                getAlarmManager().cancel(mPacRefreshIntent);
                synchronized (mProxyLock) {
                    mPacUrl = Uri.EMPTY;
                    mCurrentPac = null;
                    if (mProxyService != null) {
                        unbind();
                    }
                }
            }
        }
    }

    /**
     * Does a post and reports back the status code.
     *
     * @throws IOException if the URL is malformed, or the PAC file is too big.
     */
    private static String get(Uri pacUri) throws IOException {
        URL url = new URL(pacUri.toString());
        URLConnection urlConnection = url.openConnection(java.net.Proxy.NO_PROXY);
        long contentLength = -1;
        try {
            contentLength = Long.parseLong(urlConnection.getHeaderField("Content-Length"));
        } catch (NumberFormatException e) {
            // Ignore
        }
        if (contentLength > MAX_PAC_SIZE) {
            throw new IOException("PAC too big: " + contentLength + " bytes");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = urlConnection.getInputStream().read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
            if (bytes.size() > MAX_PAC_SIZE) {
                throw new IOException("PAC too big");
            }
        }
        return bytes.toString();
    }

    private int getNextDelay(int currentDelay) {
        if (++currentDelay > DELAY_4) {
            return DELAY_4;
        }
        return currentDelay;
    }

    private void longSchedule() {
        mCurrentDelay = DELAY_1;
        setDownloadIn(DELAY_LONG);
    }

    private void reschedule() {
        mCurrentDelay = getNextDelay(mCurrentDelay);
        setDownloadIn(mCurrentDelay);
    }

    private String getPacChangeDelay() {
        final ContentResolver cr = mContext.getContentResolver();

        // Check system properties for the default value then use secure settings value, if any.
        String defaultDelay = SystemProperties.get(
                "conn." + Settings.Global.PAC_CHANGE_DELAY,
                DEFAULT_DELAYS);
        String val = Settings.Global.getString(cr, Settings.Global.PAC_CHANGE_DELAY);
        return (val == null) ? defaultDelay : val;
    }

    private long getDownloadDelay(int delayIndex) {
        String[] list = getPacChangeDelay().split(" ");
        if (delayIndex < list.length) {
            return Long.parseLong(list[delayIndex]);
        }
        return 0;
    }

    private void setDownloadIn(int delayIndex) {
        long delay = getDownloadDelay(delayIndex);
        long timeTillTrigger = 1000 * delay + SystemClock.elapsedRealtime();
        getAlarmManager().set(AlarmManager.ELAPSED_REALTIME, timeTillTrigger, mPacRefreshIntent);
    }

    @GuardedBy("mProxyLock")
    private void setCurrentProxyScript(String script) {
        if (mProxyService == null) {
            Log.e(TAG, "setCurrentProxyScript: no proxy service");
            return;
        }
        try {
            mProxyService.setPacFile(script);
            mCurrentPac = script;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set PAC file", e);
        }
    }

    private void bind() {
        if (mContext == null) {
            Log.e(TAG, "No context for binding");
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(PAC_PACKAGE, PAC_SERVICE);
        if ((mProxyConnection != null) && (mConnection != null)) {
            // Already bound: no need to bind again, just download the new file.
            mNetThreadHandler.post(mPacDownloader);
            return;
        }
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName component) {
                synchronized (mProxyLock) {
                    mProxyService = null;
                }
            }

            @Override
            public void onServiceConnected(ComponentName component, IBinder binder) {
                synchronized (mProxyLock) {
                    try {
                        Log.d(TAG, "Adding service " + PAC_SERVICE_NAME + " "
                                + binder.getInterfaceDescriptor());
                    } catch (RemoteException e1) {
                        Log.e(TAG, "Remote Exception", e1);
                    }
                    ServiceManager.addService(PAC_SERVICE_NAME, binder);
                    mProxyService = IProxyService.Stub.asInterface(binder);
                    if (mProxyService == null) {
                        Log.e(TAG, "No proxy service");
                    } else {
                        // If mCurrentPac is not null, then the PacService might have
                        // crashed and restarted. The download task will not actually
                        // call setCurrentProxyScript, so call setCurrentProxyScript here.
                        if (mCurrentPac != null) {
                            setCurrentProxyScript(mCurrentPac);
                        } else {
                            mNetThreadHandler.post(mPacDownloader);
                        }
                    }
                }
            }
        };
        mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE,
                UserHandle.SYSTEM);

        intent = new Intent();
        intent.setClassName(PROXY_PACKAGE, PROXY_SERVICE);
        mProxyConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName component) {
            }

            @Override
            public void onServiceConnected(ComponentName component, IBinder binder) {
                IProxyCallback callbackService = IProxyCallback.Stub.asInterface(binder);
                if (callbackService != null) {
                    try {
                        callbackService.getProxyPort(new IProxyPortListener.Stub() {
                            @Override
                            public void setProxyPort(int port) {
                                if (mLastPort != -1) {
                                    // Always need to send if port changed
                                    // TODO: Here lacks synchronization because this write cannot
                                    // guarantee that it's visible from sendProxyIfNeeded() when
                                    // it's called by a Runnable which is post by mNetThread.
                                    mHasSentBroadcast = false;
                                }
                                mLastPort = port;
                                if (port != -1) {
                                    Log.d(TAG, "Local proxy is bound on " + port);
                                    sendProxyIfNeeded();
                                } else {
                                    Log.e(TAG, "Received invalid port from Local Proxy,"
                                            + " PAC will not be operational");
                                }
                            }
                        });
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        mContext.bindServiceAsUser(intent, mProxyConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE,
                mNetThreadHandler, UserHandle.SYSTEM);
    }

    private void unbind() {
        if (mConnection != null) {
            mContext.unbindService(mConnection);
            mConnection = null;
        }
        if (mProxyConnection != null) {
            mContext.unbindService(mProxyConnection);
            mProxyConnection = null;
        }
        mProxyService = null;
        mLastPort = -1;
    }

    private void sendPacBroadcast(ProxyInfo proxy) {
        final int length = mCallbacks.beginBroadcast();
        for (int i = 0; i < length; i++) {
            final IPacProxyInstalledListener listener = mCallbacks.getBroadcastItem(i);
            if (listener != null) {
                try {
                    listener.onPacProxyInstalled(null /* network */, proxy);
                } catch (RemoteException ignored) { }
            }
        }
        mCallbacks.finishBroadcast();
    }

    // This method must be called on mNetThreadHandler.
    private void sendProxyIfNeeded() {
        synchronized (mBroadcastStateLock) {
            if (!mHasDownloaded || (mLastPort == -1)) {
                return;
            }
            if (!mHasSentBroadcast) {
                sendPacBroadcast(ProxyInfo.buildPacProxy(mPacUrl, mLastPort));
                mHasSentBroadcast = true;
            }
        }
    }
}
