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

import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.view.IWindow;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s,
 * and provides facilities for querying the accessibility state of the system.
 * Accessibility events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@link android.accessibilityservice.AccessibilityService}.
 * <p>
 * To obtain a handle to the accessibility manager do the following:
 * </p>
 * <p>
 * <code>
 * <pre>AccessibilityManager accessibilityManager =
 *        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);</pre>
 * </code>
 * </p>
 *
 * @see AccessibilityEvent
 * @see AccessibilityNodeInfo
 * @see android.accessibilityservice.AccessibilityService
 * @see Context#getSystemService
 * @see Context#ACCESSIBILITY_SERVICE
 */
public final class AccessibilityManager {
    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManager";

    /** @hide */
    public static final int STATE_FLAG_ACCESSIBILITY_ENABLED = 0x00000001;

    /** @hide */
    public static final int STATE_FLAG_TOUCH_EXPLORATION_ENABLED = 0x00000002;

    static final Object sInstanceSync = new Object();

    private static AccessibilityManager sInstance;

    private static final int DO_SET_STATE = 10;

    final IAccessibilityManager mService;

    final Handler mHandler;

    boolean mIsEnabled;

    boolean mIsTouchExplorationEnabled;

    final CopyOnWriteArrayList<AccessibilityStateChangeListener> mAccessibilityStateChangeListeners =
        new CopyOnWriteArrayList<AccessibilityStateChangeListener>();

    /**
     * Listener for the system accessibility state. To listen for changes to the accessibility
     * state on the device, implement this interface and register it with the system by
     * calling {@link AccessibilityManager#addAccessibilityStateChangeListener
     * addAccessibilityStateChangeListener()}.
     */
    public interface AccessibilityStateChangeListener {

        /**
         * Called back on change in the accessibility state.
         *
         * @param enabled Whether accessibility is enabled.
         */
        public void onAccessibilityStateChanged(boolean enabled);
    }

    final IAccessibilityManagerClient.Stub mClient = new IAccessibilityManagerClient.Stub() {
        public void setState(int state) {
            mHandler.obtainMessage(DO_SET_STATE, state, 0).sendToTarget();
        }
    };

    class MyHandler extends Handler {

        MyHandler(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case DO_SET_STATE :
                    setState(message.arg1);
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
                IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
                IAccessibilityManager service = IAccessibilityManager.Stub.asInterface(iBinder);
                sInstance = new AccessibilityManager(context, service);
            }
        }
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     * @param service An interface to the backing service.
     *
     * @hide
     */
    public AccessibilityManager(Context context, IAccessibilityManager service) {
        mHandler = new MyHandler(context.getMainLooper());
        mService = service;

        try {
            final int stateFlags = mService.addClient(mClient);
            setState(stateFlags);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "AccessibilityManagerService is dead", re);
        }
    }

    /**
     * Returns if the accessibility in the system is enabled.
     *
     * @return True if accessibility is enabled, false otherwise.
     */
    public boolean isEnabled() {
        synchronized (mHandler) {
            return mIsEnabled;
        }
    }

    /**
     * Returns if the touch exploration in the system is enabled.
     *
     * @return True if touch exploration is enabled, false otherwise.
     */
    public boolean isTouchExplorationEnabled() {
        synchronized (mHandler) {
            return mIsTouchExplorationEnabled;
        }
    }

    /**
     * Returns the client interface this instance registers in
     * the centralized accessibility manager service.
     *
     * @return The client.
     *
     * @hide
     */
    public IAccessibilityManagerClient getClient() {
       return (IAccessibilityManagerClient) mClient.asBinder();
    }

    /**
     * Sends an {@link AccessibilityEvent}.
     *
     * @param event The event to send.
     *
     * @throws IllegalStateException if accessibility is not enabled.
     *
     * <strong>Note:</strong> The preferred mechanism for sending custom accessibility
     * events is through calling
     * {@link android.view.ViewParent#requestSendAccessibilityEvent(View, AccessibilityEvent)}
     * instead of this method to allow predecessors to augment/filter events sent by
     * their descendants.
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
            if (DEBUG) {
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
     * Requests feedback interruption from all accessibility services.
     */
    public void interrupt() {
        if (!mIsEnabled) {
            throw new IllegalStateException("Accessibility off. Did you forget to check that?");
        }
        try {
            mService.interrupt();
            if (DEBUG) {
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
     *
     * @deprecated Use {@link #getInstalledAccessibilityServiceList()}
     */
    @Deprecated
    public List<ServiceInfo> getAccessibilityServiceList() {
        List<AccessibilityServiceInfo> infos = getInstalledAccessibilityServiceList();
        List<ServiceInfo> services = new ArrayList<ServiceInfo>();
        final int infoCount = infos.size();
        for (int i = 0; i < infoCount; i++) {
            AccessibilityServiceInfo info = infos.get(i);
            services.add(info.getResolveInfo().serviceInfo);
        }
        return Collections.unmodifiableList(services);
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     */
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        List<AccessibilityServiceInfo> services = null;
        try {
            services = mService.getInstalledAccessibilityServiceList();
            if (DEBUG) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        return Collections.unmodifiableList(services);
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the enabled accessibility services
     * for a given feedback type.
     *
     * @param feedbackTypeFlags The feedback type flags.
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     *
     * @see AccessibilityServiceInfo#FEEDBACK_AUDIBLE
     * @see AccessibilityServiceInfo#FEEDBACK_GENERIC
     * @see AccessibilityServiceInfo#FEEDBACK_HAPTIC
     * @see AccessibilityServiceInfo#FEEDBACK_SPOKEN
     * @see AccessibilityServiceInfo#FEEDBACK_VISUAL
     */
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
            int feedbackTypeFlags) {
        List<AccessibilityServiceInfo> services = null;
        try {
            services = mService.getEnabledAccessibilityServiceList(feedbackTypeFlags);
            if (DEBUG) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        return Collections.unmodifiableList(services);
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system.
     *
     * @param listener The listener.
     * @return True if successfully registered.
     */
    public boolean addAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return mAccessibilityStateChangeListeners.add(listener);
    }

    /**
     * Unregisters an {@link AccessibilityStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if successfully unregistered.
     */
    public boolean removeAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return mAccessibilityStateChangeListeners.remove(listener);
    }

    /**
     * Sets the current state.
     *
     * @param stateFlags The state flags.
     */
    private void setState(int stateFlags) {
        final boolean accessibilityEnabled = (stateFlags & STATE_FLAG_ACCESSIBILITY_ENABLED) != 0;
        setAccessibilityState(accessibilityEnabled);
        mIsTouchExplorationEnabled = (stateFlags & STATE_FLAG_TOUCH_EXPLORATION_ENABLED) != 0;
    }

    /**
     * Sets the enabled state.
     *
     * @param isEnabled The accessibility state.
     */
    private void setAccessibilityState(boolean isEnabled) {
        synchronized (mHandler) {
            if (isEnabled != mIsEnabled) {
                mIsEnabled = isEnabled;
                notifyAccessibilityStateChanged();
            }
        }
    }

    /**
     * Notifies the registered {@link AccessibilityStateChangeListener}s.
     */
    private void notifyAccessibilityStateChanged() {
        final int listenerCount = mAccessibilityStateChangeListeners.size();
        for (int i = 0; i < listenerCount; i++) {
            mAccessibilityStateChangeListeners.get(i).onAccessibilityStateChanged(mIsEnabled);
        }
    }

    /**
     * Adds an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is added.
     * @param connection The connection.
     *
     * @hide
     */
    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection) {
        try {
            return mService.addAccessibilityInteractionConnection(windowToken, connection);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while adding an accessibility interaction connection. ", re);
        }
        return View.NO_ID;
    }

    /**
     * Removed an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is removed.
     *
     * @hide
     */
    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
        try {
            mService.removeAccessibilityInteractionConnection(windowToken);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while removing an accessibility interaction connection. ", re);
        }
    }
}
