/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A helper class that handles operations in remote listeners.
 *
 * @param <TRequest> the type of request.
 * @param <TListener> the type of GNSS data listener.
 */
public abstract class RemoteListenerHelper<TRequest, TListener extends IInterface> {

    protected static final int RESULT_SUCCESS = 0;
    protected static final int RESULT_NOT_AVAILABLE = 1;
    protected static final int RESULT_NOT_SUPPORTED = 2;
    protected static final int RESULT_GPS_LOCATION_DISABLED = 3;
    protected static final int RESULT_INTERNAL_ERROR = 4;
    protected static final int RESULT_UNKNOWN = 5;
    protected static final int RESULT_NOT_ALLOWED = 6;

    protected final Handler mHandler;
    private final String mTag;

    protected final Map<IBinder, IdentifiedListener> mListenerMap = new HashMap<>();

    protected final Context mContext;
    protected final AppOpsManager mAppOps;

    private volatile boolean mIsRegistered;  // must access only on handler thread, or read-only

    private boolean mHasIsSupported;
    private boolean mIsSupported;

    private int mLastReportedResult = RESULT_UNKNOWN;

    protected RemoteListenerHelper(Context context, Handler handler, String name) {
        Objects.requireNonNull(name);
        mHandler = handler;
        mTag = name;
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    }

    // read-only access for a dump() thread assured via volatile
    public boolean isRegistered() {
        return mIsRegistered;
    }

    /**
     * Adds GNSS data listener {@code listener} with caller identify {@code callerIdentify}.
     */
    public void addListener(@Nullable TRequest request, @NonNull TListener listener,
            CallerIdentity callerIdentity) {
        Objects.requireNonNull(listener, "Attempted to register a 'null' listener.");
        IBinder binder = listener.asBinder();
        synchronized (mListenerMap) {
            if (mListenerMap.containsKey(binder)) {
                // listener already added
                return;
            }

            IdentifiedListener identifiedListener = new IdentifiedListener(request, listener,
                    callerIdentity);
            mListenerMap.put(binder, identifiedListener);

            // update statuses we already know about, starting from the ones that will never change
            int result;
            if (!isAvailableInPlatform()) {
                result = RESULT_NOT_AVAILABLE;
            } else if (mHasIsSupported && !mIsSupported) {
                result = RESULT_NOT_SUPPORTED;
            } else if (!isGpsEnabled()) {
                // only attempt to register if GPS is enabled, otherwise we will register once GPS
                // becomes available
                result = RESULT_GPS_LOCATION_DISABLED;
            } else if (mHasIsSupported && mIsSupported) {
                tryRegister();
                // initially presume success, possible internal error could follow asynchornously
                result = RESULT_SUCCESS;
            } else {
                // at this point if the supported flag is not set, the notification will be sent
                // asynchronously in the future
                return;
            }
            post(identifiedListener, getHandlerOperation(result));
        }
    }

    /**
     * Remove GNSS data listener {@code listener}.
     */
    public void removeListener(@NonNull TListener listener) {
        Objects.requireNonNull(listener, "Attempted to remove a 'null' listener.");
        synchronized (mListenerMap) {
            mListenerMap.remove(listener.asBinder());
            if (mListenerMap.isEmpty()) {
                tryUnregister();
            }
        }
    }

    protected abstract boolean isAvailableInPlatform();
    protected abstract boolean isGpsEnabled();
    // must access only on handler thread
    protected abstract int registerWithService();
    protected abstract void unregisterFromService(); // must access only on handler thread
    protected abstract ListenerOperation<TListener> getHandlerOperation(int result);

    protected interface ListenerOperation<TListener extends IInterface> {
        void execute(TListener listener, CallerIdentity callerIdentity) throws RemoteException;
    }

    protected void foreach(ListenerOperation<TListener> operation) {
        synchronized (mListenerMap) {
            foreachUnsafe(operation);
        }
    }

    protected void setSupported(boolean value) {
        synchronized (mListenerMap) {
            mHasIsSupported = true;
            mIsSupported = value;
        }
    }

    protected void tryUpdateRegistrationWithService() {
        synchronized (mListenerMap) {
            if (!isGpsEnabled()) {
                tryUnregister();
                return;
            }
            if (mListenerMap.isEmpty()) {
                return;
            }
            tryRegister();
        }
    }

    protected void updateResult() {
        synchronized (mListenerMap) {
            int newResult = calculateCurrentResultUnsafe();
            if (mLastReportedResult == newResult) {
                return;
            }
            foreachUnsafe(getHandlerOperation(newResult));
            mLastReportedResult = newResult;
        }
    }

    protected boolean hasPermission(Context context, CallerIdentity callerIdentity) {
        if (LocationPermissionUtil.doesCallerReportToAppOps(context, callerIdentity)) {
            // The caller is identified as a location provider that will report location
            // access to AppOps. Skip noteOp but do checkOp to check for location permission.
            return mAppOps.checkOpNoThrow(AppOpsManager.OP_FINE_LOCATION, callerIdentity.mUid,
                    callerIdentity.mPackageName) == AppOpsManager.MODE_ALLOWED;
        }

        return mAppOps.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION, callerIdentity.mUid,
                callerIdentity.mPackageName, callerIdentity.mFeatureId,
                "Location sent to " + callerIdentity.mListenerIdentifier)
                == AppOpsManager.MODE_ALLOWED;
    }

    protected void logPermissionDisabledEventNotReported(String tag, String packageName,
            String event) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, "Location permission disabled. Skipping " + event + " reporting for app: "
                    + packageName);
        }
    }

    private void foreachUnsafe(ListenerOperation<TListener> operation) {
        for (IdentifiedListener identifiedListener : mListenerMap.values()) {
            post(identifiedListener, operation);
        }
    }

    private void post(IdentifiedListener identifiedListener,
            ListenerOperation<TListener> operation) {
        if (operation != null) {
            mHandler.post(new HandlerRunnable(identifiedListener, operation));
        }
    }

    private void tryRegister() {
        mHandler.post(new Runnable() {
            int registrationState = RESULT_INTERNAL_ERROR;
            @Override
            public void run() {
                if (!mIsRegistered) {
                    registrationState = registerWithService();
                    mIsRegistered = registrationState == RESULT_SUCCESS;
                }
                if (!mIsRegistered) {
                    // post back a failure
                    mHandler.post(() -> {
                        synchronized (mListenerMap) {
                            foreachUnsafe(getHandlerOperation(registrationState));
                        }
                    });
                }
            }
        });
    }

    private void tryUnregister() {
        mHandler.post(() -> {
                    if (!mIsRegistered) {
                        return;
                    }
                    unregisterFromService();
                    mIsRegistered = false;
                }
        );
    }

    private int calculateCurrentResultUnsafe() {
        // update statuses we already know about, starting from the ones that will never change
        if (!isAvailableInPlatform()) {
            return RESULT_NOT_AVAILABLE;
        }
        if (!mHasIsSupported || mListenerMap.isEmpty()) {
            // we'll update once we have a supported status available
            return RESULT_UNKNOWN;
        }
        if (!mIsSupported) {
            return RESULT_NOT_SUPPORTED;
        }
        if (!isGpsEnabled()) {
            return RESULT_GPS_LOCATION_DISABLED;
        }
        return RESULT_SUCCESS;
    }

    protected class IdentifiedListener {
        @Nullable private final TRequest mRequest;
        private final TListener mListener;
        private final CallerIdentity mCallerIdentity;

        private IdentifiedListener(@Nullable TRequest request, @NonNull TListener listener,
                CallerIdentity callerIdentity) {
            mListener = listener;
            mRequest = request;
            mCallerIdentity = callerIdentity;
        }

        @Nullable
        public TRequest getRequest() {
            return mRequest;
        }
    }

    private class HandlerRunnable implements Runnable {
        private final IdentifiedListener mIdentifiedListener;
        private final ListenerOperation<TListener> mOperation;

        private HandlerRunnable(IdentifiedListener identifiedListener,
                ListenerOperation<TListener> operation) {
            mIdentifiedListener = identifiedListener;
            mOperation = operation;
        }

        @Override
        public void run() {
            try {
                mOperation.execute(mIdentifiedListener.mListener,
                        mIdentifiedListener.mCallerIdentity);
            } catch (RemoteException e) {
                Log.v(mTag, "Error in monitored listener.", e);
            }
        }
    }
}
