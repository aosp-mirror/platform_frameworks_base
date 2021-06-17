/**
 * Copyright (c) 2016, The Android Open Source Project
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
package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.Date;

/**
 * Manages the lifecycle of an application-provided service bound from system server.
 *
 * @hide
 */
public class ManagedApplicationService {
    private final String TAG = getClass().getSimpleName();

    /**
     * Attempt to reconnect service forever if an onBindingDied or onServiceDisconnected event
     * is received.
     */
    public static final int RETRY_FOREVER = 1;

    /**
     * Never attempt to reconnect the service - a single onBindingDied or onServiceDisconnected
     * event will cause this to fully unbind the service and never attempt to reconnect.
     */
    public static final int RETRY_NEVER = 2;

    /**
     * Attempt to reconnect the service until the maximum number of retries is reached, then stop.
     *
     * The first retry will occur MIN_RETRY_DURATION_MS after the disconnection, and each
     * subsequent retry will occur after 2x the duration used for the previous retry up to the
     * MAX_RETRY_DURATION_MS duration.
     *
     * In this case, retries mean a full unbindService/bindService pair to handle cases when the
     * usual service re-connection logic in ActiveServices has very high backoff times or when the
     * serviceconnection has fully died due to a package update or similar.
     */
    public static final int RETRY_BEST_EFFORT = 3;

    // Maximum number of retries before giving up (for RETRY_BEST_EFFORT).
    private static final int MAX_RETRY_COUNT = 4;
    // Max time between retry attempts.
    private static final long MAX_RETRY_DURATION_MS = 16000;
    // Min time between retry attempts.
    private static final long MIN_RETRY_DURATION_MS = 2000;
    // Time since the last retry attempt after which to clear the retry attempt counter.
    private static final long RETRY_RESET_TIME_MS = MAX_RETRY_DURATION_MS * 4;

    private final Context mContext;
    private final int mUserId;
    private final ComponentName mComponent;
    private final int mClientLabel;
    private final String mSettingsAction;
    private final BinderChecker mChecker;
    private final boolean mIsImportant;
    private final int mRetryType;
    private final Handler mHandler;
    private final Runnable mRetryRunnable = this::doRetry;
    private final EventCallback mEventCb;

    private final Object mLock = new Object();

    // State protected by mLock
    private ServiceConnection mConnection;
    private IInterface mBoundInterface;
    private PendingEvent mPendingEvent;
    private int mRetryCount;
    private long mLastRetryTimeMs;
    private long mNextRetryDurationMs = MIN_RETRY_DURATION_MS;
    private boolean mRetrying;

    public static interface LogFormattable {
       String toLogString(SimpleDateFormat dateFormat);
    }

    /**
     * Lifecycle event of this managed service.
     */
    public static class LogEvent implements LogFormattable {
        public static final int EVENT_CONNECTED = 1;
        public static final int EVENT_DISCONNECTED = 2;
        public static final int EVENT_BINDING_DIED = 3;
        public static final int EVENT_STOPPED_PERMANENTLY = 4;

        // Time of the events in "current time ms" timebase.
        public final long timestamp;
        // Name of the component for this system service.
        public final ComponentName component;
        // ID of the event that occurred.
        public final int event;

        public LogEvent(long timestamp, ComponentName component, int event) {
            this.timestamp = timestamp;
            this.component = component;
            this.event = event;
        }

        @Override
        public String toLogString(SimpleDateFormat dateFormat) {
            return dateFormat.format(new Date(timestamp)) + "   " + eventToString(event)
                    + " Managed Service: "
                    + ((component == null) ? "None" : component.flattenToString());
        }

        public static String eventToString(int event) {
            switch (event) {
                case EVENT_CONNECTED:
                    return "Connected";
                case EVENT_DISCONNECTED:
                    return "Disconnected";
                case EVENT_BINDING_DIED:
                    return "Binding Died For";
                case EVENT_STOPPED_PERMANENTLY:
                    return "Permanently Stopped";
                default:
                    return "Unknown Event Occurred";
            }
        }
    }

    private ManagedApplicationService(final Context context, final ComponentName component,
            final int userId, int clientLabel, String settingsAction,
            BinderChecker binderChecker, boolean isImportant, int retryType, Handler handler,
            EventCallback eventCallback) {
        mContext = context;
        mComponent = component;
        mUserId = userId;
        mClientLabel = clientLabel;
        mSettingsAction = settingsAction;
        mChecker = binderChecker;
        mIsImportant = isImportant;
        mRetryType = retryType;
        mHandler = handler;
        mEventCb = eventCallback;
    }

    /**
     * Implement to validate returned IBinder instance.
     */
    public interface BinderChecker {
        IInterface asInterface(IBinder binder);
        boolean checkType(IInterface service);
    }

    /**
     * Implement to call IInterface methods after service is connected.
     */
    public interface PendingEvent {
        void runEvent(IInterface service) throws RemoteException;
    }

    /**
     * Implement to be notified about any problems with remote service.
     */
    public interface EventCallback {
        /**
         * Called when an sevice lifecycle event occurs.
         */
        void onServiceEvent(LogEvent event);
    }

    /**
     * Create a new ManagedApplicationService object but do not yet bind to the user service.
     *
     * @param context a Context to use for binding the application service.
     * @param component the {@link ComponentName} of the application service to bind.
     * @param userId the user ID of user to bind the application service as.
     * @param clientLabel the resource ID of a label displayed to the user indicating the
     *      binding service, or 0 if none is desired.
     * @param settingsAction an action that can be used to open the Settings UI to enable/disable
     *      binding to these services, or null if none is desired.
     * @param binderChecker an interface used to validate the returned binder object, or null if
     *      this interface is unchecked.
     * @param isImportant bind the user service with BIND_IMPORTANT.
     * @param retryType reconnect behavior to have when bound service is disconnected.
     * @param handler the Handler to use for retries and delivering EventCallbacks.
     * @param eventCallback a callback used to deliver disconnection events, or null if you
     *      don't care.
     * @return a ManagedApplicationService instance.
     */
    public static ManagedApplicationService build(@NonNull final Context context,
            @NonNull final ComponentName component, final int userId, int clientLabel,
            @Nullable String settingsAction, @Nullable BinderChecker binderChecker,
            boolean isImportant, int retryType, @NonNull Handler handler,
            @Nullable EventCallback eventCallback) {
        return new ManagedApplicationService(context, component, userId, clientLabel,
            settingsAction, binderChecker, isImportant, retryType, handler, eventCallback);
    }


    /**
     * @return the user ID of the user that owns the bound service.
     */
    public int getUserId() {
        return mUserId;
    }

    /**
     * @return the component of the bound service.
     */
    public ComponentName getComponent() {
        return mComponent;
    }

    /**
     * Asynchronously unbind from the application service if the bound service component and user
     * does not match the given signature.
     *
     * @param componentName the component that must match.
     * @param userId the user ID that must match.
     * @return {@code true} if not matching.
     */
    public boolean disconnectIfNotMatching(final ComponentName componentName, final int userId) {
        if (matches(componentName, userId)) {
            return false;
        }
        disconnect();
        return true;
    }

    /**
     * Send an event to run as soon as the binder interface is available.
     *
     * @param event a {@link PendingEvent} to send.
     */
    public void sendEvent(@NonNull PendingEvent event) {
        IInterface iface;
        synchronized (mLock) {
            iface = mBoundInterface;
            if (iface == null) {
                mPendingEvent = event;
            }
        }

        if (iface != null) {
            try {
                event.runEvent(iface);
            } catch (RuntimeException | RemoteException ex) {
                Slog.e(TAG, "Received exception from user service: ", ex);
            }
        }
    }

    /**
     * Asynchronously unbind from the application service if bound.
     */
    public void disconnect() {
        synchronized (mLock) {
            // Unbind existing connection, if it exists
            if (mConnection == null) {
                return;
            }

            mContext.unbindService(mConnection);
            mConnection = null;
            mBoundInterface = null;
        }
    }

    /**
     * Asynchronously bind to the application service if not bound.
     */
    public void connect() {
        synchronized (mLock) {
            if (mConnection != null) {
                // We're already connected or are trying to connect
                return;
            }

            Intent intent  = new Intent().setComponent(mComponent);
            if (mClientLabel != 0) {
                intent.putExtra(Intent.EXTRA_CLIENT_LABEL, mClientLabel);
            }
            if (mSettingsAction != null) {
                intent.putExtra(Intent.EXTRA_CLIENT_INTENT,
                        PendingIntent.getActivity(mContext, 0, new Intent(mSettingsAction),
                                PendingIntent.FLAG_IMMUTABLE));
            }

            mConnection = new ServiceConnection() {
                @Override
                public void onBindingDied(ComponentName componentName) {
                    final long timestamp = System.currentTimeMillis();
                    Slog.w(TAG, "Service binding died: " + componentName);
                    synchronized (mLock) {
                        if (mConnection != this) {
                            return;
                        }
                        mHandler.post(() -> {
                            mEventCb.onServiceEvent(new LogEvent(timestamp, mComponent,
                                  LogEvent.EVENT_BINDING_DIED));
                        });

                        mBoundInterface = null;
                        startRetriesLocked();
                    }
                }

                @Override
                public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                    final long timestamp = System.currentTimeMillis();
                    Slog.i(TAG, "Service connected: " + componentName);
                    IInterface iface = null;
                    PendingEvent pendingEvent = null;
                    synchronized (mLock) {
                        if (mConnection != this) {
                            // Must've been unbound.
                            return;
                        }
                        mHandler.post(() -> {
                            mEventCb.onServiceEvent(new LogEvent(timestamp, mComponent,
                                  LogEvent.EVENT_CONNECTED));
                        });

                        stopRetriesLocked();

                        mBoundInterface = null;
                        if (mChecker != null) {
                            mBoundInterface = mChecker.asInterface(iBinder);
                            if (!mChecker.checkType(mBoundInterface)) {
                                // Received an invalid binder, disconnect.
                                mBoundInterface = null;
                                Slog.w(TAG, "Invalid binder from " + componentName);
                                startRetriesLocked();
                                return;
                            }
                            iface = mBoundInterface;
                            pendingEvent = mPendingEvent;
                            mPendingEvent = null;
                        }
                    }
                    if (iface != null && pendingEvent != null) {
                        try {
                            pendingEvent.runEvent(iface);
                        } catch (RuntimeException | RemoteException ex) {
                            Slog.e(TAG, "Received exception from user service: ", ex);
                            startRetriesLocked();
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    final long timestamp = System.currentTimeMillis();
                    Slog.w(TAG, "Service disconnected: " + componentName);
                    synchronized (mLock) {
                        if (mConnection != this) {
                            return;
                        }

                        mHandler.post(() -> {
                            mEventCb.onServiceEvent(new LogEvent(timestamp, mComponent,
                                  LogEvent.EVENT_DISCONNECTED));
                        });

                        mBoundInterface = null;
                        startRetriesLocked();
                    }
                }
            };

            int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
            if (mIsImportant) {
                flags |= Context.BIND_IMPORTANT;
            }
            try {
                if (!mContext.bindServiceAsUser(intent, mConnection, flags,
                        new UserHandle(mUserId))) {
                    Slog.w(TAG, "Unable to bind service: " + intent);
                    startRetriesLocked();
                }
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to bind service: " + intent, e);
                startRetriesLocked();
            }
        }
    }

    private boolean matches(final ComponentName component, final int userId) {
        return Objects.equals(mComponent, component) && mUserId == userId;
    }

    private void startRetriesLocked() {
        if (checkAndDeliverServiceDiedCbLocked()) {
            // If we delivered the service callback, disconnect and stop retrying.
            disconnect();
            return;
        }

        if (mRetrying) {
            // Retry already queued, don't queue a new one.
            return;
        }
        mRetrying = true;
        queueRetryLocked();
    }

    private void stopRetriesLocked() {
        mRetrying = false;
        mHandler.removeCallbacks(mRetryRunnable);
    }

    private void queueRetryLocked() {
        long now = SystemClock.uptimeMillis();
        if ((now - mLastRetryTimeMs) > RETRY_RESET_TIME_MS) {
            // It's been longer than the reset time since we last had to retry.  Re-initialize.
            mNextRetryDurationMs = MIN_RETRY_DURATION_MS;
            mRetryCount = 0;
        }
        mLastRetryTimeMs = now;
        mHandler.postDelayed(mRetryRunnable, mNextRetryDurationMs);
        mNextRetryDurationMs = Math.min(2 * mNextRetryDurationMs, MAX_RETRY_DURATION_MS);
        mRetryCount++;
    }

    private boolean checkAndDeliverServiceDiedCbLocked() {

       if (mRetryType == RETRY_NEVER || (mRetryType == RETRY_BEST_EFFORT
                && mRetryCount >= MAX_RETRY_COUNT)) {
            // If we never retry, or we've exhausted our retries, post the onServiceDied callback.
            Slog.e(TAG, "Service " + mComponent + " has died too much, not retrying.");
            if (mEventCb != null) {
                final long timestamp = System.currentTimeMillis();
                mHandler.post(() -> {
                  mEventCb.onServiceEvent(new LogEvent(timestamp, mComponent,
                        LogEvent.EVENT_STOPPED_PERMANENTLY));
                });
            }
            return true;
        }
        return false;
    }

    private void doRetry() {
        synchronized (mLock) {
            if (mConnection == null) {
                // We disconnected for good.  Don't attempt to retry.
                return;
            }
            if (!mRetrying) {
                // We successfully connected.  Don't attempt to retry.
                return;
            }
            Slog.i(TAG, "Attempting to reconnect " + mComponent + "...");
            // While frameworks may restart the remote Service if we stay bound, we have little
            // control of the backoff timing for reconnecting the service.  In the event of a
            // process crash, the backoff time can be very large (1-30 min), which is not
            // acceptable for the types of services this is used for.  Instead force an unbind/bind
            // sequence to cause a more immediate retry.
            disconnect();
            if (checkAndDeliverServiceDiedCbLocked()) {
                // No more retries.
                return;
            }
            queueRetryLocked();
            connect();
        }
    }
}
