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
 * limitations under the License.
 */

package android.telecomm;

import com.android.internal.telecomm.IConnectionService;

import android.os.RemoteException;
import android.telephony.DisconnectCause;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a conference call which can contain any number of {@link Connection} objects.
 */
public final class RemoteConference {

    public abstract static class Listener {
        public void onStateChanged(RemoteConference conference, int oldState, int newState) {}
        public void onDisconnected(RemoteConference conference, int cause, String message) {}
        public void onConnectionAdded(RemoteConference conference, RemoteConnection connection) {}
        public void onConnectionRemoved(RemoteConference conference, RemoteConnection connection) {}
        public void onCapabilitiesChanged(RemoteConference conference, int capabilities) {}
        public void onDestroyed(RemoteConference conference) {}
    }

    private final String mId;
    private final IConnectionService mConnectionService;

    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final List<RemoteConnection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<RemoteConnection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);

    private int mState = Connection.STATE_NEW;
    private int mDisconnectCause = DisconnectCause.NOT_VALID;
    private int mCallCapabilities;
    private String mDisconnectMessage;

    /** {@hide} */
    RemoteConference(String id, IConnectionService connectionService) {
        mId = id;
        mConnectionService = connectionService;
    }

    /** {@hide} */
    String getId() {
        return mId;
    }

    /** {@hide} */
    void setDestroyed() {
        for (RemoteConnection connection : mChildConnections) {
            connection.setConference(null);
        }
        for (Listener l : mListeners) {
            l.onDestroyed(this);
        }
    }

    /** {@hide} */
    void setState(int newState) {
        if (newState != Connection.STATE_ACTIVE &&
                newState != Connection.STATE_HOLDING &&
                newState != Connection.STATE_DISCONNECTED) {
            Log.w(this, "Unsupported state transition for Conference call.",
                    Connection.stateToString(newState));
            return;
        }

        if (mState != newState) {
            int oldState = mState;
            mState = newState;
            for (Listener l : mListeners) {
                l.onStateChanged(this, oldState, newState);
            }
        }
    }

    /** {@hide} */
    void addConnection(RemoteConnection connection) {
        if (!mChildConnections.contains(connection)) {
            mChildConnections.add(connection);
            connection.setConference(this);
            for (Listener l : mListeners) {
                l.onConnectionAdded(this, connection);
            }
        }
    }

    /** {@hide} */
    void removeConnection(RemoteConnection connection) {
        if (mChildConnections.contains(connection)) {
            mChildConnections.remove(connection);
            connection.setConference(null);
            for (Listener l : mListeners) {
                l.onConnectionRemoved(this, connection);
            }
        }
    }

    /** {@hide} */
    void setCallCapabilities(int capabilities) {
        if (mCallCapabilities != capabilities) {
            mCallCapabilities = capabilities;
            for (Listener l : mListeners) {
                l.onCapabilitiesChanged(this, mCallCapabilities);
            }
        }
    }

    /** {@hide} */
    void setDisconnected(int cause, String message) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mDisconnectCause = cause;
            mDisconnectMessage = message;
            setState(Connection.STATE_DISCONNECTED);
            for (Listener l : mListeners) {
                l.onDisconnected(this, cause, message);
            }
        }
    }

    public final List<RemoteConnection> getConnections() {
        return mUnmodifiableChildConnections;
    }

    public final int getState() {
        return mState;
    }

    public final int getCallCapabilities() {
        return mCallCapabilities;
    }

    public void disconnect() {
        try {
            mConnectionService.disconnect(mId);
        } catch (RemoteException e) {
        }
    }

    public void separate(RemoteConnection connection) {
        if (mChildConnections.contains(connection)) {
            try {
                mConnectionService.splitFromConference(connection.getId());
            } catch (RemoteException e) {
            }
        }
    }

    public void hold() {
        try {
            mConnectionService.hold(mId);
        } catch (RemoteException e) {
        }
    }

    public void unhold() {
        try {
            mConnectionService.unhold(mId);
        } catch (RemoteException e) {
        }
    }

    public int getDisconnectCause() {
        return mDisconnectCause;
    }

    public String getDisconnectMessage() {
        return mDisconnectMessage;
    }

    public final void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public final void removeListener(Listener listener) {
        mListeners.remove(listener);
    }
}
