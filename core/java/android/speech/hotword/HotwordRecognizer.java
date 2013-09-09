/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.hotword;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * This class provides access to the Hotword recognition service.
 * This class's methods must be invoked on the main application thread.
 * {@hide}
 */
public class HotwordRecognizer {
    /** DEBUG value to enable verbose debug prints */
    private final static boolean DBG = false;

    /** Log messages identifier */
    private static final String TAG = "HotwordRecognizer";

    /** action codes */
    private static final int MSG_START = 1;
    private static final int MSG_STOP = 2;

    /** The underlying HotwordRecognitionService endpoint */
    private IHotwordRecognitionService mService;

    /** The connection to the actual service */
    private Connection mConnection;

    /** Context with which the manager was created */
    private final Context mContext;

    /** Component to direct service intent to */
    private final ComponentName mServiceComponent;

    /** Handler that will execute the main tasks */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START:
                    handleStartRecognition();
                    break;
                case MSG_STOP:
                    handleStopRecognition();
                    break;
            }
        }
    };

    /**
     * Temporary queue, saving the messages until the connection will be established, afterwards,
     * only mHandler will receive the messages
     */
    private final Queue<Message> mPendingTasks = new LinkedList<Message>();

    /** The Listener that will receive all the callbacks */
    private final InternalListener mListener = new InternalListener();

    /**
     * Checks whether a hotword recognition service is available on the system. If this method
     * returns {@code false}, {@link HotwordRecognizer#createHotwordRecognizer(Context)} will
     * fail.
     *
     * @param context with which {@code HotwordRecognizer} will be created
     * @return {@code true} if recognition is available, {@code false} otherwise
     */
    public static boolean isHotwordRecognitionAvailable(final Context context) {
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(HotwordRecognitionService.SERVICE_INTERFACE), 0);
        return list != null && list.size() != 0;
    }

    /**
     * Factory method to create a new {@code HotwordRecognizer}.
     *
     * @param context in which to create {@code HotwordRecognizer}
     * @return a new {@code HotwordRecognizer}
     */
    public static HotwordRecognizer createHotwordRecognizer(final Context context) {
        ComponentName serviceComponent = null;
        // Resolve to a default ComponentName.
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(HotwordRecognitionService.SERVICE_INTERFACE), 0);
        for (int i = 0; i < list.size(); i++) {
            final ResolveInfo ri = list.get(i);
            if (!ri.serviceInfo.enabled) {
                continue;
            }
            if ((ri.serviceInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)
                    != PackageManager.MATCH_DEFAULT_ONLY) {
                serviceComponent = new ComponentName(
                        ri.serviceInfo.packageName, ri.serviceInfo.name);
                break;
            }
        }
        // If all else fails, pick the first one.
        if (serviceComponent == null && !list.isEmpty()) {
            serviceComponent =  new ComponentName(
                    list.get(0).serviceInfo.packageName, list.get(0).serviceInfo.name);
        }
        return createHotwordRecognizer(context, serviceComponent);
    }

    /**
     * Factory method to create a new {@code HotwordRecognizer}.
     *
     * Use this version of the method to specify a specific service to direct this
     * {@link HotwordRecognizer} to. Normally you would not use this; use
     * {@link #createHotwordRecognizer(Context)} instead to use the system default recognition
     * service.
     *
     * @param context in which to create {@code HotwordRecognizer}
     * @param serviceComponent the {@link ComponentName} of a specific service to direct this
     *        {@code HotwordRecognizer} to
     * @return a new {@code HotwordRecognizer}
     */
    public static HotwordRecognizer createHotwordRecognizer(
            final Context context, final ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null)");
        }
        checkIsCalledFromMainThread();
        return new HotwordRecognizer(context, serviceComponent);
    }

    /**
     * Starts recognizing hotword and sets the listener that will receive the callbacks.
     *
     * @param listener listener that will receive all the callbacks from the created
     *        {@link HotwordRecognizer}, this must not be null.
     */
    public void startRecognition(HotwordRecognitionListener listener) {
        checkIsCalledFromMainThread();
        if (mConnection == null) { // first time connection
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            mConnection = new Connection();
            Intent serviceIntent = new Intent(HotwordRecognitionService.SERVICE_INTERFACE);
            mListener.mInternalListener = listener;

            if (mServiceComponent == null) {
                Log.e(TAG, "no selected voice recognition service");
                mListener.onHotwordError(HotwordRecognitionService.ERROR_CLIENT);
                return;
            } else {
                serviceIntent.setComponent(mServiceComponent);
            }

            if (!mContext.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.e(TAG, "bind to recognition service failed");
                mConnection = null;
                mService = null;
                mListener.onHotwordError(HotwordRecognitionService.ERROR_CLIENT);
                return;
            }
            putMessage(Message.obtain(mHandler, MSG_START));
        } else {
            mListener.onHotwordError(HotwordRecognitionService.ERROR_SERVICE_ALREADY_STARTED);
            return;
        }
    }

    /**
     * Stops recognizing hotword.
     */
    public void stopRecognition() {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_STOP));
    }

    // Private constructor.
    private HotwordRecognizer(Context context, ComponentName serviceComponent) {
        mContext = context;
        mServiceComponent = serviceComponent;
    }

    private void handleStartRecognition() {
        if (!checkOpenConnection()) {
            return;
        }
        try {
            mService.startHotwordRecognition(mListener);
            if (DBG) Log.d(TAG, "service startRecognition command succeeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "startRecognition() failed", e);
            mListener.onHotwordError(HotwordRecognitionService.ERROR_CLIENT);
        }
    }

    private void handleStopRecognition() {
        if (!checkOpenConnection()) {
            return;
        }
        try {
            mService.stopHotwordRecognition(mListener);
            if (mConnection != null) {
                mContext.unbindService(mConnection);
            }
            if (DBG) Log.d(TAG, "service stopRecognition command succeeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "stopRecognition() failed", e);
            mListener.onHotwordError(HotwordRecognitionService.ERROR_CLIENT);
        } finally {
            mPendingTasks.clear();
            mService = null;
            mConnection = null;
            mListener.mInternalListener = null;
        }
    }

    private boolean checkOpenConnection() {
        if (mService != null) {
            return true;
        }
        mListener.onHotwordError(HotwordRecognitionService.ERROR_CLIENT);
        Log.e(TAG, "not connected to the recognition service");
        return false;
    }

    private static void checkIsCalledFromMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException(
                    "HotwordRecognizer should be used only from the application's main thread");
        }
    }

    private void putMessage(Message msg) {
        if (mService == null) {
            mPendingTasks.offer(msg);
        } else {
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Basic ServiceConnection that records the mService variable.
     * Additionally, on creation it invokes
     * {@link IHotwordRecognitionService#startHotwordRecognition(IHotwordRecognitionListener)}.
     */
    private class Connection implements ServiceConnection {

        public void onServiceConnected(final ComponentName name, final IBinder service) {
            // always done on the application main thread, so no need to send message to mHandler
            mService = IHotwordRecognitionService.Stub.asInterface(service);
            if (DBG) Log.d(TAG, "onServiceConnected - Success");
            while (!mPendingTasks.isEmpty()) {
                mHandler.sendMessage(mPendingTasks.poll());
            }
        }

        public void onServiceDisconnected(final ComponentName name) {
            // always done on the application main thread, so no need to send message to mHandler
            mService = null;
            mConnection = null;
            mPendingTasks.clear();
            if (DBG) Log.d(TAG, "onServiceDisconnected - Success");
        }
    }

    /**
     * Internal wrapper of IHotwordRecognitionListener which will propagate the results to
     * HotwordRecognitionListener.
     */
    private class InternalListener extends IHotwordRecognitionListener.Stub {
        private HotwordRecognitionListener mInternalListener;

        private final static int MSG_ON_START = 1;
        private final static int MSG_ON_STOP = 2;
        private final static int MSG_ON_EVENT = 3;
        private final static int MSG_ON_RECOGNIZED = 4;
        private final static int MSG_ON_ERROR = 5;

        private final Handler mInternalHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (mInternalListener == null) {
                    return;
                }
                switch (msg.what) {
                    case MSG_ON_START:
                        mInternalListener.onHotwordRecognitionStarted();
                        break;
                    case MSG_ON_STOP:
                        mInternalListener.onHotwordRecognitionStopped();
                        break;
                    case MSG_ON_EVENT:
                        mInternalListener.onHotwordEvent(msg.arg1, (Bundle) msg.obj);
                        break;
                    case MSG_ON_RECOGNIZED:
                        mInternalListener.onHotwordRecognized((Intent) msg.obj);
                        break;
                    case MSG_ON_ERROR:
                        mInternalListener.onHotwordError((Integer) msg.obj);
                        break;
                }
            }
        };

        @Override
        public void onHotwordRecognitionStarted() throws RemoteException {
            Message.obtain(mInternalHandler, MSG_ON_START).sendToTarget();
        }

        @Override
        public void onHotwordRecognitionStopped() throws RemoteException {
            Message.obtain(mInternalHandler, MSG_ON_STOP).sendToTarget();
        }

        @Override
        public void onHotwordEvent(final int eventType, final Bundle params) {
            Message.obtain(mInternalHandler, MSG_ON_EVENT, eventType, eventType, params)
                    .sendToTarget();
        }

        @Override
        public void onHotwordRecognized(Intent activityIntent) throws RemoteException {
            Message.obtain(mInternalHandler, MSG_ON_RECOGNIZED, activityIntent)
                    .sendToTarget();
        }

        @Override
        public void onHotwordError(final int error) {
            Message.obtain(mInternalHandler, MSG_ON_ERROR, error).sendToTarget();
        }
    }
}
