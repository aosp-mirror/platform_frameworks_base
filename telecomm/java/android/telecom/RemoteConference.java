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
import android.os.Handler;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a conference call which can contain any number of {@link Connection} objects.
 */
public final class RemoteConference {

    public abstract static class Callback {
        public void onStateChanged(RemoteConference conference, int oldState, int newState) {}
        public void onDisconnected(RemoteConference conference, DisconnectCause disconnectCause) {}
        public void onConnectionAdded(RemoteConference conference, RemoteConnection connection) {}
        public void onConnectionRemoved(RemoteConference conference, RemoteConnection connection) {}
        public void onConnectionCapabilitiesChanged(
                RemoteConference conference,
                int connectionCapabilities) {}
        public void onConferenceableConnectionsChanged(
                RemoteConference conference,
                List<RemoteConnection> conferenceableConnections) {}
        public void onDestroyed(RemoteConference conference) {}
    }

    private final String mId;
    private final IConnectionService mConnectionService;

    private final Set<CallbackRecord<Callback>> mCallbackRecords = new CopyOnWriteArraySet<>();
    private final List<RemoteConnection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<RemoteConnection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);
    private final List<RemoteConnection> mConferenceableConnections = new ArrayList<>();
    private final List<RemoteConnection> mUnmodifiableConferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private int mState = Connection.STATE_NEW;
    private DisconnectCause mDisconnectCause;
    private int mConnectionCapabilities;

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
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final RemoteConference conference = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onDestroyed(conference);
                }
            });
        }
    }

    /** {@hide} */
    void setState(final int newState) {
        if (newState != Connection.STATE_ACTIVE &&
                newState != Connection.STATE_HOLDING &&
                newState != Connection.STATE_DISCONNECTED) {
            Log.w(this, "Unsupported state transition for Conference call.",
                    Connection.stateToString(newState));
            return;
        }

        if (mState != newState) {
            final int oldState = mState;
            mState = newState;
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onStateChanged(conference, oldState, newState);
                    }
                });
            }
        }
    }

    /** {@hide} */
    void addConnection(final RemoteConnection connection) {
        if (!mChildConnections.contains(connection)) {
            mChildConnections.add(connection);
            connection.setConference(this);
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConnectionAdded(conference, connection);
                    }
                });
            }
        }
    }

    /** {@hide} */
    void removeConnection(final RemoteConnection connection) {
        if (mChildConnections.contains(connection)) {
            mChildConnections.remove(connection);
            connection.setConference(null);
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConnectionRemoved(conference, connection);
                    }
                });
            }
        }
    }

    /** {@hide} */
    void setConnectionCapabilities(final int connectionCapabilities) {
        if (mConnectionCapabilities != connectionCapabilities) {
            mConnectionCapabilities = connectionCapabilities;
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConnectionCapabilitiesChanged(
                                conference, mConnectionCapabilities);
                    }
                });
            }
        }
    }

    /** @hide */
    void setConferenceableConnections(List<RemoteConnection> conferenceableConnections) {
        mConferenceableConnections.clear();
        mConferenceableConnections.addAll(conferenceableConnections);
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final RemoteConference conference = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onConferenceableConnectionsChanged(
                            conference, mUnmodifiableConferenceableConnections);
                }
            });
        }
    }

    /** {@hide} */
    void setDisconnected(final DisconnectCause disconnectCause) {
        if (mState != Connection.STATE_DISCONNECTED) {
            mDisconnectCause = disconnectCause;
            setState(Connection.STATE_DISCONNECTED);
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDisconnected(conference, disconnectCause);
                    }
                });
            }
        }
    }

    public final List<RemoteConnection> getConnections() {
        return mUnmodifiableChildConnections;
    }

    public final int getState() {
        return mState;
    }

    public final int getConnectionCapabilities() {
        return mConnectionCapabilities;
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

    /**
     * Request to change the conference's audio routing to the specified state. The specified state
     * can include audio routing (Bluetooth, Speaker, etc) and muting state.
     *
     * @see android.telecom.AudioState
     * @deprecated Use {@link #setCallAudioState(CallAudioState)} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public void setAudioState(AudioState state) {
        setCallAudioState(new CallAudioState(state));
    }

    /**
     * Request to change the conference's audio routing to the specified state. The specified state
     * can include audio routing (Bluetooth, Speaker, etc) and muting state.
     */
    public void setCallAudioState(CallAudioState state) {
        try {
            mConnectionService.onCallAudioStateChanged(mId, state);
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns a list of independent connections that can me merged with this conference.
     *
     * @return A list of conferenceable connections.
     */
    public List<RemoteConnection> getConferenceableConnections() {
        return mUnmodifiableConferenceableConnections;
    }

    public final void registerCallback(Callback callback) {
        registerCallback(callback, new Handler());
    }

    public final void registerCallback(Callback callback, Handler handler) {
        unregisterCallback(callback);
        if (callback != null && handler != null) {
            mCallbackRecords.add(new CallbackRecord(callback, handler));
        }
    }

    public final void unregisterCallback(Callback callback) {
        if (callback != null) {
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                if (record.getCallback() == callback) {
                    mCallbackRecords.remove(record);
                    break;
                }
            }
        }
    }
}
