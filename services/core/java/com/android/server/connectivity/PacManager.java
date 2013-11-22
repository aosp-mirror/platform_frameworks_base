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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.net.IProxyCallback;
import com.android.net.IProxyPortListener;
import com.android.net.IProxyService;
import com.android.server.IoThread;

import libcore.io.Streams;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * @hide
 */
public class PacManager {
    public static final String PAC_PACKAGE = "com.android.pacprocessor";
    public static final String PAC_SERVICE = "com.android.pacprocessor.PacService";
    public static final String PAC_SERVICE_NAME = "com.android.net.IProxyService";

    public static final String PROXY_PACKAGE = "com.android.proxyhandler";
    public static final String PROXY_SERVICE = "com.android.proxyhandler.ProxyService";

    private static final String TAG = "PacManager";

    private static final String ACTION_PAC_REFRESH = "android.net.proxy.PAC_REFRESH";

    private static final String DEFAULT_DELAYS = "8 32 120 14400 43200";
    private static final int DELAY_1 = 0;
    private static final int DELAY_4 = 3;
    private static final int DELAY_LONG = 4;

    /** Keep these values up-to-date with ProxyService.java */
    public static final String KEY_PROXY = "keyProxy";
    private String mCurrentPac;
    @GuardedBy("mProxyLock")
    private String mPacUrl;

    private AlarmManager mAlarmManager;
    @GuardedBy("mProxyLock")
    private IProxyService mProxyService;
    private PendingIntent mPacRefreshIntent;
    private ServiceConnection mConnection;
    private ServiceConnection mProxyConnection;
    private Context mContext;

    private int mCurrentDelay;
    private int mLastPort;

    private boolean mHasSentBroadcast;
    private boolean mHasDownloaded;

    private Handler mConnectivityHandler;
    private int mProxyMessage;

    /**
     * Used for locking when setting mProxyService and all references to mPacUrl or mCurrentPac.
     */
    private final Object mProxyLock = new Object();

    private Runnable mPacDownloader = new Runnable() {
        @Override
        public void run() {
            String file;
            synchronized (mProxyLock) {
                if (mPacUrl == null) return;
                try {
                    file = get(mPacUrl);
                } catch (IOException ioe) {
                    file = null;
                    Log.w(TAG, "Failed to load PAC file: " + ioe);
                }
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

    class PacRefreshIntentReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            IoThread.getHandler().post(mPacDownloader);
        }
    }

    public PacManager(Context context, Handler handler, int proxyMessage) {
        mContext = context;
        mLastPort = -1;

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
     * the ConnectivityService directly before a broadcast takes place to allow
     * the PacManager to indicate that the broadcast should not be sent and the
     * PacManager will trigger a new broadcast when it is ready.
     *
     * @param proxy Proxy information that is about to be broadcast.
     * @return Returns true when the broadcast should not be sent
     */
    public synchronized boolean setCurrentProxyScriptUrl(ProxyProperties proxy) {
        if (!TextUtils.isEmpty(proxy.getPacFileUrl())) {
            if (proxy.getPacFileUrl().equals(mPacUrl) && (proxy.getPort() > 0)) {
                // Allow to send broadcast, nothing to do.
                return false;
            }
            synchronized (mProxyLock) {
                mPacUrl = proxy.getPacFileUrl();
            }
            mCurrentDelay = DELAY_1;
            mHasSentBroadcast = false;
            mHasDownloaded = false;
            getAlarmManager().cancel(mPacRefreshIntent);
            bind();
            return true;
        } else {
            getAlarmManager().cancel(mPacRefreshIntent);
            synchronized (mProxyLock) {
                mPacUrl = null;
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
            return false;
        }
    }

    /**
     * Does a post and reports back the status code.
     *
     * @throws IOException
     */
    private static String get(String urlString) throws IOException {
        URL url = new URL(urlString);
        URLConnection urlConnection = url.openConnection(java.net.Proxy.NO_PROXY);
        return new String(Streams.readFully(urlConnection.getInputStream()));
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

        /** Check system properties for the default value then use secure settings value, if any. */
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

    private boolean setCurrentProxyScript(String script) {
        if (mProxyService == null) {
            Log.e(TAG, "setCurrentProxyScript: no proxy service");
            return false;
        }
        try {
            mProxyService.setPacFile(script);
            mCurrentPac = script;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set PAC file", e);
        }
        return true;
    }

    private void bind() {
        if (mContext == null) {
            Log.e(TAG, "No context for binding");
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(PAC_PACKAGE, PAC_SERVICE);
        // Already bound no need to bind again.
        if ((mProxyConnection != null) && (mConnection != null)) {
            if (mLastPort != -1) {
                sendPacBroadcast(new ProxyProperties(mPacUrl, mLastPort));
            } else {
                Log.e(TAG, "Received invalid port from Local Proxy,"
                        + " PAC will not be operational");
            }
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
                        IoThread.getHandler().post(mPacDownloader);
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
                            public void setProxyPort(int port) throws RemoteException {
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

    private void sendPacBroadcast(ProxyProperties proxy) {
        mConnectivityHandler.sendMessage(mConnectivityHandler.obtainMessage(mProxyMessage, proxy));
    }

    private synchronized void sendProxyIfNeeded() {
        if (!mHasDownloaded || (mLastPort == -1)) {
            return;
        }
        if (!mHasSentBroadcast) {
            sendPacBroadcast(new ProxyProperties(mPacUrl, mLastPort));
            mHasSentBroadcast = true;
        }
    }
}
