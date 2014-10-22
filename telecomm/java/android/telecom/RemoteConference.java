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

package android.telecom;

import com.android.internal.telecom.IConnectionService;

import android.annotation.SystemApi;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a conference call which can contain any number of {@link Connection} objects.
 * @hide
 */
@SystemApi
public final class RemoteConference {

    public abstract static class Callback {
        public void onStateChanged(RemoteConference conference, int oldState, int newState) {}
        public void onDisconnected(RemoteConference conference, DisconnectCause disconnectCause) {}
        public void onConnectionAdded(RemoteConference conference, RemoteConnection connection) {}
        public void onConnectionRemoved(RemoteConference conference, RemoteConnection connection) {}
        public void onCapabilitiesChanged(RemoteConference conference, int capabilities) {}
        public void onConferenceableConnectionsChanged(
                RemoteConference conference,
                List<RemoteConnection> conferenceableConnections) {}
        public void onDestroyed(RemoteConference conference) {}
    }

    private final String mId;
    private final IConnectionService mConnectionService;

    private final Set<Callback> mCallbacks = new CopyOnWriteArraySet<>();
    private final List<RemoteConnection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<RemoteConnection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);
    private final List<RemoteConnection> mConferenceableConnections = new ArrayList<>();
    private final List<RemoteConnection> mUnmodifiableConferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private int mState = Connection.STATE_NEW;
    private DisconnectCause mDisconnectCause;
    private int mCallCapabilities;

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
        for (Callback c : mCallbacks) {
            c.onDestroyed(this);
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
            for (Callback c : mCallbacks) {
                c.onStateChanged(this, oldState, newState);
            }
        }
    }

    /** {@hide} */
    void addConnection(RemoteConnection connection) {
        if (!mChildConnections.contains(connection)) {
            mChildConnections.add(connection);
            connection.setConference(this);
            for (Callback c : mCallbacks) {
                c.onConnectionAdded(this, connection);
            }
        }
    }

    /** {@hide} */
    void removeConnection(RemoteConnection connection) {
        if (mChildConnections.contains(connection)) {
            mChildConnections.remove(connection);
            connection.setConference(null);
            for (Callback c : mCallbacks) {
                c.onConnectionRemoved(this, connection);
            }
        }
    }

    /** {@hide} */
    void setCallCapabilities(int capabilities) {
        if (mCallCapabilities != capabilities) {
            mCallCapabilities = capabilities;
            for (Callback c : mCallbacks) {
                c.onCapabilitiesChanged(this, mCallCapabilities);
            }
        }
    }

    /** @hide */
    void setConferenceableConnections(List<RemoteConnection> conferenceableConnections) {
        mConferenceableConnections.clear();
        mConferenceableConnections.addAll(conferenceableConnections);
        for (Callback c : mCallbacks) {
            c.onConferenceableConnectionsChanged(this, mUnmodifiableConferenceableConnections);
        }
    }

    /** {@hide} */
    void setDisconnected(DisconnectCause disconnectCause) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mDisconnectCause = disconnectCause;
            setState(Connection.STATE_DISCONNECTED);
            for (Callback c : mCallbacks) {
                c.onDisconnected(this, disconnectCause);
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

    public void merge() {
        try {
            mConnectionService.mergeConference(mId);
        } catch (RemoteException e) {
        }
    }

    public void swap() {
        try {
            mConnectionService.swapConference(mId);
        } catch (RemoteException e) {
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

    public DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    public void playDtmfTone(char digit) {
        try {
            mConnectionService.playDtmfTone(mId, digit);
        } catch (RemoteException e) {
        }
    }

    public void stopDtmfTone() {
        try {
            mConnectionService.stopDtmfTone(mId);
        } catch (RemoteException e) {
        }
    }

    public void setAudioState(AudioState state) {
        try {
            mConnectionService.onAudioStateChanged(mId, state);
        } catch (RemoteException e) {
        }
    }

    public List<RemoteConnection> getConferenceableConnections() {
        return mUnmodifiableConferenceableConnections;
    }

    public final void registerCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public final void unregisterCallback(Callback callback) {
        mCallbacks.remove(callback);
    }
}
