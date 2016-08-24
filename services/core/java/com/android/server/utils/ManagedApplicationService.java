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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import java.util.Objects;

/**
 * Manages the lifecycle of an application-provided service bound from system server.
 *
 * @hide
 */
public class ManagedApplicationService {
    private final String TAG = getClass().getSimpleName();

    private final Context mContext;
    private final int mUserId;
    private final ComponentName mComponent;
    private final int mClientLabel;
    private final String mSettingsAction;
    private final BinderChecker mChecker;

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            synchronized (mLock) {
                mBoundInterface = null;
            }
        }
    };

    private final Object mLock = new Object();

    // State protected by mLock
    private ServiceConnection mPendingConnection;
    private ServiceConnection mConnection;
    private IInterface mBoundInterface;
    private PendingEvent mPendingEvent;

    private ManagedApplicationService(final Context context, final ComponentName component,
            final int userId, int clientLabel, String settingsAction,
            BinderChecker binderChecker) {
        mContext = context;
        mComponent = component;
        mUserId = userId;
        mClientLabel = clientLabel;
        mSettingsAction = settingsAction;
        mChecker = binderChecker;
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
     * Create a new ManagedApplicationService object but do not yet bind to the user service.
     *
     * @param context a Context to use for binding the application service.
     * @param component the {@link ComponentName} of the application service to bind.
     * @param userId the user ID of user to bind the application service as.
     * @param clientLabel the resource ID of a label displayed to the user indicating the
     *      binding service.
     * @param settingsAction an action that can be used to open the Settings UI to enable/disable
     *      binding to these services.
     * @param binderChecker an interface used to validate the returned binder object.
     * @return a ManagedApplicationService instance.
     */
    public static ManagedApplicationService build(@NonNull final Context context,
        @NonNull final ComponentName component, final int userId, @NonNull int clientLabel,
        @NonNull String settingsAction, @NonNull BinderChecker binderChecker) {
        return new ManagedApplicationService(context, component, userId, clientLabel,
            settingsAction, binderChecker);
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
            // Wipe out pending connections
            mPendingConnection = null;

            // Unbind existing connection, if it exists
            if (mConnection != null) {
                mContext.unbindService(mConnection);
                mConnection = null;
            }

            mBoundInterface = null;
        }
    }

    /**
     * Asynchronously bind to the application service if not bound.
     */
    public void connect() {
        synchronized (mLock) {
            if (mConnection != null || mPendingConnection != null) {
                // We're already connected or are trying to connect
                return;
            }

            final PendingIntent pendingIntent = PendingIntent.getActivity(
                    mContext, 0, new Intent(mSettingsAction), 0);
            final Intent intent = new Intent().setComponent(mComponent).
                    putExtra(Intent.EXTRA_CLIENT_LABEL, mClientLabel).
                    putExtra(Intent.EXTRA_CLIENT_INTENT, pendingIntent);

            final ServiceConnection serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                    IInterface iface = null;
                    PendingEvent pendingEvent = null;
                    synchronized (mLock) {
                        if (mPendingConnection == this) {
                            // No longer pending, remove from pending connection
                            mPendingConnection = null;
                            mConnection = this;
                        } else {
                            // Service connection wasn't pending, must have been disconnected
                            mContext.unbindService(this);
                            return;
                        }

                        try {
                            iBinder.linkToDeath(mDeathRecipient, 0);
                            mBoundInterface = mChecker.asInterface(iBinder);
                            if (!mChecker.checkType(mBoundInterface)) {
                                // Received an invalid binder, disconnect
                                mContext.unbindService(this);
                                mBoundInterface = null;
                            }
                            iface = mBoundInterface;
                            pendingEvent = mPendingEvent;
                            mPendingEvent = null;
                        } catch (RemoteException e) {
                            // DOA
                            Slog.w(TAG, "Unable to bind service: " + intent, e);
                            mBoundInterface = null;
                        }
                    }
                    if (iface != null && pendingEvent != null) {
                        try {
                            pendingEvent.runEvent(iface);
                        } catch (RuntimeException | RemoteException ex) {
                            Slog.e(TAG, "Received exception from user service: ", ex);
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    Slog.w(TAG, "Service disconnected: " + intent);
                    mConnection = null;
                    mBoundInterface = null;
                }
            };

            mPendingConnection = serviceConnection;

            try {
                if (!mContext.bindServiceAsUser(intent, serviceConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUserId))) {
                    Slog.w(TAG, "Unable to bind service: " + intent);
                }
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to bind service: " + intent, e);
            }
        }
    }

    private boolean matches(final ComponentName component, final int userId) {
        return Objects.equals(mComponent, component) && mUserId == userId;
    }
}
