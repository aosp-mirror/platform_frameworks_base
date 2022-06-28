/**
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.statusbar;

import static android.app.StatusBarManager.ALL_SESSIONS;
import static android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT;
import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.app.StatusBarManager.SessionFlags;

import android.Manifest;
import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.ISessionListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Monitors session starts and ends. Session instanceIds can be used to correlate logs.
 */
public class SessionMonitor {
    private static final String TAG = "SessionMonitor";

    private final Context mContext;
    private final Map<Integer, Set<ISessionListener>> mSessionToListeners =
            new HashMap<>();

    /** */
    public SessionMonitor(Context context) {
        mContext = context;
        // initialize all sessions in the map
        for (int session : ALL_SESSIONS) {
            mSessionToListeners.put(session, new HashSet<>());
        }
    }

    /**
     * Registers a listener for all sessionTypes included in sessionFlags.
     */
    public void registerSessionListener(@SessionFlags int sessionFlags,
            ISessionListener listener) {
        requireListenerPermissions(sessionFlags);
        synchronized (mSessionToListeners) {
            for (int sessionType : ALL_SESSIONS) {
                if ((sessionFlags & sessionType) != 0) {
                    mSessionToListeners.get(sessionType).add(listener);
                }
            }
        }
    }

    /**
     * Unregisters a listener for all sessionTypes included in sessionFlags.
     */
    public void unregisterSessionListener(@SessionFlags int sessionFlags,
            ISessionListener listener) {
        synchronized (mSessionToListeners) {
            for (int sessionType : ALL_SESSIONS) {
                if ((sessionFlags & sessionType) != 0) {
                    mSessionToListeners.get(sessionType).remove(listener);
                }
            }
        }
    }

    /**
     * Starts a session with the given sessionType, creating a new instanceId.
     * Sends this message to all listeners registered for the given sessionType.
     *
     * Callers require special permission to start and end a session depending on the session.
     */
    public void onSessionStarted(@SessionFlags int sessionType, @NonNull InstanceId instanceId) {
        requireSetterPermissions(sessionType);

        if (!isValidSessionType(sessionType)) {
            Log.e(TAG, "invalid onSessionStarted sessionType=" + sessionType);
            return;
        }

        synchronized (mSessionToListeners) {
            for (ISessionListener listener : mSessionToListeners.get(sessionType)) {
                try {
                    listener.onSessionStarted(sessionType, instanceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "unable to send session start to listener=" + listener, e);
                }
            }
        }
    }

    /**
     * Ends a session with the given sessionType and instanceId. Sends this message
     * to all listeners registered for the given sessionType.
     *
     * Callers require special permission to start and end a session depending on the session.
     */
    public void onSessionEnded(@SessionFlags int sessionType, @NonNull InstanceId instanceId) {
        requireSetterPermissions(sessionType);

        if (!isValidSessionType(sessionType)) {
            Log.e(TAG, "invalid onSessionEnded sessionType=" + sessionType);
            return;
        }

        synchronized (mSessionToListeners) {
            for (ISessionListener listener : mSessionToListeners.get(sessionType)) {
                try {
                    listener.onSessionEnded(sessionType, instanceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "unable to send session end to listener=" + listener, e);
                }
            }
        }
    }

    private boolean isValidSessionType(@SessionFlags int sessionType) {
        return ALL_SESSIONS.contains(sessionType);
    }

    private void requireListenerPermissions(@SessionFlags int sessionFlags) {
        if ((sessionFlags & SESSION_KEYGUARD) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_BIOMETRIC,
                    "StatusBarManagerService.SessionMonitor");
        }

        if ((sessionFlags & SESSION_BIOMETRIC_PROMPT) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_BIOMETRIC,
                    "StatusBarManagerService.SessionMonitor");
        }
    }

    private void requireSetterPermissions(@SessionFlags int sessionFlags) {
        if ((sessionFlags & SESSION_KEYGUARD) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_KEYGUARD,
                    "StatusBarManagerService.SessionMonitor");
        }

        if ((sessionFlags & SESSION_BIOMETRIC_PROMPT) != 0) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                    "StatusBarManagerService.SessionMonitor");
        }
    }
}
