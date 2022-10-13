/**
 * Copyright (c) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.connectivity;

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
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.webkit.URLUtil;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.TrafficStatsConstants;
import com.android.net.IProxyCallback;
import com.android.net.IProxyPortListener;
import com.android.net.IProxyService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * @hide
 */
public class PacManager {
    private static final String PAC_PACKAGE = "com.android.pacprocessor";
    private static final String PAC_SERVICE = "com.android.pacprocessor.PacService";
    private static final String PAC_SERVICE_NAME = "com.android.net.IProxyService";

    private static final String PROXY_PACKAGE = "com.android.proxyhandler";
    private static final String PROXY_SERVICE = "com.android.proxyhandler.ProxyService";

    private static final String TAG = "PacManager";

    private static final String ACTION_PAC_REFRESH = "android.net.proxy.PAC_REFRESH";

    private static final String DEFAULT_DELAYS = "8 32 120 14400 43200";
    private static final int DELAY_1 = 0;
    private static final int DELAY_4 = 3;
    private static final int DELAY_LONG = 4;
    private static final long MAX_PAC_SIZE = 20 * 1000 * 1000;

    // Return values for #setCurrentProxyScriptUrl
    public static final boolean DONT_SEND_BROADCAST = false;
    public static final boolean DO_SEND_BROADCAST = true;

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

    private Handler mConnectivityHandler;
    private final int mProxyMessage;

    /**
     * Used for locking when setting mProxyService and all references to mCurrentPac.
     */
    private final Object mProxyLock = new Object();

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

    public PacManager(Context context, Handler handler, int proxyMessage) {
        mContext = context;
        mLastPort = -1;
        final HandlerThread netThread = new HandlerThread("android.pacmanager",
                android.os.Process.THREAD_PRIORITY_DEFAULT);
        netThread.start();
        mNetThreadHandler = new Handler(netThread.getLooper());

        mPacRefreshIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_PAC_REFRESH), 0);
        context.registerReceiver(new PacRefreshIntentReceiver(),
                new IntentFilter(ACTION_PAC_REFRESH));
        mConnectivityHandler = handler;
        mProxyMessage = proxyMessage;
    }

    private AlarmManager getAlarmManager() {
        if (mAlarmManager == null) {
            mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        }
        return mAlarmManager;
    }

    /**
     * Updates the PAC Manager with current Proxy information. This is called by
     * the ProxyTracker directly before a broadcast takes place to allow
     * the PacManager to indicate that the broadcast should not be sent and the
     * PacManager will trigger a new broadcast when it is ready.
     *
     * @param proxy Proxy information that is about to be broadcast.
     * @return Returns whether the broadcast should be sent : either DO_ or DONT_SEND_BROADCAST
     */
    synchronized boolean setCurrentProxyScriptUrl(ProxyInfo proxy) {
        if (!Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            if (proxy.getPacFileUrl().equals(mPacUrl) && (proxy.getPort() > 0)) {
                // Allow to send broadcast, nothing to do.
                return DO_SEND_BROADCAST;
            }
            mPacUrl = proxy.getPacFileUrl();
            mCurrentDelay = DELAY_1;
            mHasSentBroadcast = false;
            mHasDownloaded = false;
            getAlarmManager().cancel(mPacRefreshIntent);
            bind();
            return DONT_SEND_BROADCAST;
        } else {
            getAlarmManager().cancel(mPacRefreshIntent);
            synchronized (mProxyLock) {
                mPacUrl = Uri.EMPTY;
                mCurrentPac = null;
                if (mProxyService != null) {
                    try {
                        mProxyService.stopPacSystem();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to stop PAC service", e);
                    } finally {
                        unbind();
                    }
                }
            }
            return DO_SEND_BROADCAST;
        }
    }

    /**
     * Does a post and reports back the status code.
     *
     * @throws IOException if the URL is malformed, or the PAC file is too big.
     */
    private static String get(Uri pacUri) throws IOException {
        if (!URLUtil.isValidUrl(pacUri.toString()))  {
            throw new IOException("Malformed URL:" + pacUri);
        }

        final URL url = new URL(pacUri.toString());
        URLConnection urlConnection;
        try {
            urlConnection = url.openConnection(java.net.Proxy.NO_PROXY);
            // Catch the possible exceptions and rethrow as IOException to not to crash the system
            // for illegal input.
        } catch (IllegalArgumentException e) {
            throw new IOException("Incorrect proxy type for " + pacUri);
        } catch (UnsupportedOperationException e) {
            throw new IOException("Unsupported URL connection type for " + pacUri);
        }

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
                        try {
                            mProxyService.startPacSystem();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to reach ProxyService - PAC will not be started", e);
                        }
                        mNetThreadHandler.post(mPacDownloader);
                    }
                }
            }
        };
        mContext.bindService(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE);

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
        mContext.bindService(intent, mProxyConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_NOT_VISIBLE);
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
        mConnectivityHandler.sendMessage(mConnectivityHandler.obtainMessage(mProxyMessage, proxy));
    }

    private synchronized void sendProxyIfNeeded() {
        if (!mHasDownloaded || (mLastPort == -1)) {
            return;
        }
        if (!mHasSentBroadcast) {
            sendPacBroadcast(new ProxyInfo(mPacUrl, mLastPort));
            mHasSentBroadcast = true;
        }
    }
}
