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

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;

/**
 * Manages a persistent connection to a service component defined in a secure setting.
 *
 * <p>If a valid service component is specified in the secure setting, starts it up and keeps it
 * running; handling setting changes, package updates, component disabling, and unexpected
 * process termination.
 *
 * <p>Clients can listen for important events using the supplied {@link Callbacks}.
 */
public class ServiceMonitor {
    private static final int RECHECK_DELAY = 2000;
    private static final int WAIT_FOR_STOP = 500;

    public interface Callbacks {
        /** The service does not exist or failed to bind */
        void onNoService();
        /** The service is about to start, this is a chance to perform cleanup and
         * delay the start if necessary */
        long onServiceStartAttempt();
    }

    // internal handler + messages used to serialize access to internal state
    public static final int MSG_START_SERVICE = 1;
    public static final int MSG_CONTINUE_START_SERVICE = 2;
    public static final int MSG_STOP_SERVICE = 3;
    public static final int MSG_PACKAGE_INTENT = 4;
    public static final int MSG_CHECK_BOUND = 5;
    public static final int MSG_SERVICE_DISCONNECTED = 6;

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_START_SERVICE:
                    startService();
                    break;
                case MSG_CONTINUE_START_SERVICE:
                    continueStartService();
                    break;
                case MSG_STOP_SERVICE:
                    stopService();
                    break;
                case MSG_PACKAGE_INTENT:
                    packageIntent((Intent)msg.obj);
                    break;
                case MSG_CHECK_BOUND:
                    checkBound();
                    break;
                case MSG_SERVICE_DISCONNECTED:
                    serviceDisconnected((ComponentName)msg.obj);
                    break;
            }
        }
    };

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        public void onChange(boolean selfChange, Uri uri) {
            if (mDebug) Log.d(mTag, "onChange selfChange=" + selfChange + " uri=" + uri);
            ComponentName cn = getComponentNameFromSetting();
            if (cn == null && mServiceName == null || cn != null && cn.equals(mServiceName)) {
                if (mDebug) Log.d(mTag, "skipping no-op restart");
                return;
            }
            if (mBound) {
                mHandler.sendEmptyMessage(MSG_STOP_SERVICE);
            }
            mHandler.sendEmptyMessageDelayed(MSG_START_SERVICE, WAIT_FOR_STOP);
        }
    };

    private final class SC implements ServiceConnection, IBinder.DeathRecipient {
        private ComponentName mName;
        private IBinder mService;

        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mDebug) Log.d(mTag, "onServiceConnected name=" + name + " service=" + service);
            mName = name;
            mService = service;
            try {
                service.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.w(mTag, "Error linking to death", e);
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            if (mDebug) Log.d(mTag, "onServiceDisconnected name=" + name);
            boolean unlinked = mService.unlinkToDeath(this, 0);
            if (mDebug) Log.d(mTag, "  unlinked=" + unlinked);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SERVICE_DISCONNECTED, mName));
        }

        public void binderDied() {
            if (mDebug) Log.d(mTag, "binderDied");
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SERVICE_DISCONNECTED, mName));
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String pkg = intent.getData().getSchemeSpecificPart();
            if (mServiceName != null && mServiceName.getPackageName().equals(pkg)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PACKAGE_INTENT, intent));
            }
        }
    };

    private final String mTag;
    private final boolean mDebug;

    private final Context mContext;
    private final String mSettingKey;
    private final Callbacks mCallbacks;

    private ComponentName mServiceName;
    private SC mServiceConnection;
    private boolean mBound;

    public ServiceMonitor(String ownerTag, boolean debug,
            Context context, String settingKey, Callbacks callbacks) {
        mTag = ownerTag + ".ServiceMonitor";
        mDebug = debug;
        mContext = context;
        mSettingKey = settingKey;
        mCallbacks = callbacks;
    }

    public void start() {
        // listen for setting changes
        ContentResolver cr = mContext.getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(mSettingKey),
                false /*notifyForDescendents*/, mSettingObserver, UserHandle.USER_ALL);

        // listen for package/component changes
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mHandler.sendEmptyMessage(MSG_START_SERVICE);
    }

    private ComponentName getComponentNameFromSetting() {
        String cn = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                mSettingKey, UserHandle.USER_CURRENT);
        return cn == null ? null : ComponentName.unflattenFromString(cn);
    }

    // everything below is called on the handler

    private void packageIntent(Intent intent) {
        if (mDebug) Log.d(mTag, "packageIntent intent=" + intent
                + " extras=" + bundleToString(intent.getExtras()));
        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(MSG_START_SERVICE);
        } else if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) {
            PackageManager pm = mContext.getPackageManager();
            boolean serviceEnabled =
                    pm.getApplicationEnabledSetting(mServiceName.getPackageName())
                        != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && pm.getComponentEnabledSetting(mServiceName)
                        != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (mBound && !serviceEnabled) {
                stopService();
                scheduleCheckBound();
            } else if (!mBound && serviceEnabled) {
                startService();
            }
        }
    }

    private void stopService() {
        if (mDebug) Log.d(mTag, "stopService");
        boolean stopped = mContext.stopService(new Intent().setComponent(mServiceName));
        if (mDebug) Log.d(mTag, "  stopped=" + stopped);
        mContext.unbindService(mServiceConnection);
        mBound = false;
    }

    private void startService() {
        mServiceName = getComponentNameFromSetting();
        if (mDebug) Log.d(mTag, "startService mServiceName=" + mServiceName);
        if (mServiceName == null) {
            mBound = false;
            mCallbacks.onNoService();
        } else {
            long delay = mCallbacks.onServiceStartAttempt();
            mHandler.sendEmptyMessageDelayed(MSG_CONTINUE_START_SERVICE, delay);
        }
    }

    private void continueStartService() {
        if (mDebug) Log.d(mTag, "continueStartService");
        Intent intent = new Intent().setComponent(mServiceName);
        try {
            mServiceConnection = new SC();
            mBound = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
            if (mDebug) Log.d(mTag, "mBound: " + mBound);
        } catch (Throwable t) {
            Log.w(mTag, "Error binding to service: " + mServiceName, t);
        }
        if (!mBound) {
            mCallbacks.onNoService();
        }
    }

    private void serviceDisconnected(ComponentName serviceName) {
        if (mDebug) Log.d(mTag, "serviceDisconnected serviceName=" + serviceName
                + " mServiceName=" + mServiceName);
        if (serviceName.equals(mServiceName)) {
            mBound = false;
            scheduleCheckBound();
        }
    }

    private void checkBound() {
        if (mDebug) Log.d(mTag, "checkBound mBound=" + mBound);
        if (!mBound) {
            startService();
        }
    }

    private void scheduleCheckBound() {
        mHandler.removeMessages(MSG_CHECK_BOUND);
        mHandler.sendEmptyMessageDelayed(MSG_CHECK_BOUND, RECHECK_DELAY);
    }

    private static String bundleToString(Bundle bundle) {
        if (bundle == null) return null;
        StringBuilder sb = new StringBuilder('{');
        for (String key : bundle.keySet()) {
            if (sb.length() > 1) sb.append(',');
            Object v = bundle.get(key);
            v = (v instanceof String[]) ? Arrays.asList((String[]) v) : v;
            sb.append(key).append('=').append(v);
        }
        return sb.append('}').toString();
    }
}
