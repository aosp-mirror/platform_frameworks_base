/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.view.accessibility;

import static android.util.Config.LOGV;

import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s.
 * Such events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@link android.accessibilityservice.AccessibilityService}.
 *
 * @see AccessibilityEvent
 * @see android.accessibilityservice.AccessibilityService
 * @see android.content.Context#getSystemService
 */
public final class AccessibilityManager {
    private static final String LOG_TAG = "AccessibilityManager";

    static final Object sInstanceSync = new Object();

    private static AccessibilityManager sInstance;

    private static final int DO_SET_ENABLED = 10;

    final IAccessibilityManager mService;

    final Handler mHandler;

    boolean mIsEnabled;

    final IAccessibilityManagerClient.Stub mClient = new IAccessibilityManagerClient.Stub() {
        public void setEnabled(boolean enabled) {
            mHandler.obtainMessage(DO_SET_ENABLED, enabled ? 1 : 0, 0).sendToTarget();
        }
    };

    class MyHandler extends Handler {

        MyHandler(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case DO_SET_ENABLED :
                    synchronized (mHandler) {
                        mIsEnabled = (message.arg1 == 1);
                    }
                    return;
                default :
                    Log.w(LOG_TAG, "Unknown message type: " + message.what);
            }
        }
    }

    /**
     * Get an AccessibilityManager instance (create one if necessary).
     *
     * @hide
     */
    public static AccessibilityManager getInstance(Context context) {
        synchronized (sInstanceSync) {
            if (sInstance == null) {
                sInstance = new AccessibilityManager(context);
            }
        }
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     */
    private AccessibilityManager(Context context) {
        mHandler = new MyHandler(context.getMainLooper());
        IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
        mService = IAccessibilityManager.Stub.asInterface(iBinder);
        try {
            mService.addClient(mClient);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "AccessibilityManagerService is dead", re);
        }
    }

    /**
     * Returns if the {@link AccessibilityManager} is enabled.
     *
     * @return True if this {@link AccessibilityManager} is enabled, false otherwise.
     */
    public boolean isEnabled() {
        synchronized (mHandler) {
            return mIsEnabled;
        }
    }

    /**
     * Sends an {@link AccessibilityEvent}. If this {@link AccessibilityManager} is not
     * enabled the call is a NOOP.
     *
     * @param event The {@link AccessibilityEvent}.
     *
     * @throws IllegalStateException if a client tries to send an {@link AccessibilityEvent}
     *         while accessibility is not enabled.
     */
    public void sendAccessibilityEvent(AccessibilityEvent event) {
        if (!mIsEnabled) {
            throw new IllegalStateException("Accessibility off. Did you forget to check that?");
        }
        boolean doRecycle = false;
        try {
            event.setEventTime(SystemClock.uptimeMillis());
            // it is possible that this manager is in the same process as the service but
            // client using it is called through Binder from another process. Example: MMS
            // app adds a SMS notification and the NotificationManagerService calls this method
            long identityToken = Binder.clearCallingIdentity();
            doRecycle = mService.sendAccessibilityEvent(event);
            Binder.restoreCallingIdentity(identityToken);
            if (LOGV) {
                Log.i(LOG_TAG, event + " sent");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error during sending " + event + " ", re);
        } finally {
            if (doRecycle) {
                event.recycle();
            }
        }
    }

    /**
     * Requests interruption of the accessibility feedback from all accessibility services.
     */
    public void interrupt() {
        if (!mIsEnabled) {
            throw new IllegalStateException("Accessibility off. Did you forget to check that?");
        }
        try {
            mService.interrupt();
            if (LOGV) {
                Log.i(LOG_TAG, "Requested interrupt from all services");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while requesting interrupt from all services. ", re);
        }
    }

    /**
     * Returns the {@link ServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link ServiceInfo}s.
     */
    public List<ServiceInfo> getAccessibilityServiceList() {
        List<ServiceInfo> services = null;
        try {
            services = mService.getAccessibilityServiceList();
            if (LOGV) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        return Collections.unmodifiableList(services);
    }
}
