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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;

import com.android.internal.telecom.IConnectionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A conference provided to a {@link ConnectionService} by another {@code ConnectionService} through
 * {@link ConnectionService#conferenceRemoteConnections}. Once created, a {@code RemoteConference}
 * can be used to control the conference call or monitor changes through
 * {@link RemoteConnection.Callback}.
 *
 * @see ConnectionService#onRemoteConferenceAdded
 */
public final class RemoteConference {

    /**
     * Callback base class for {@link RemoteConference}.
     */
    public abstract static class Callback {
        /**
         * Invoked when the state of this {@code RemoteConferece} has changed. See
         * {@link #getState()}.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param oldState The previous state of the {@code RemoteConference}.
         * @param newState The new state of the {@code RemoteConference}.
         */
        public void onStateChanged(RemoteConference conference, int oldState, int newState) {}

        /**
         * Invoked when this {@code RemoteConference} is disconnected.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param disconnectCause The ({@see DisconnectCause}) associated with this failed
         *     conference.
         */
        public void onDisconnected(RemoteConference conference, DisconnectCause disconnectCause) {}

        /**
         * Invoked when a {@link RemoteConnection} is added to the conference call.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param connection The {@link RemoteConnection} being added.
         */
        public void onConnectionAdded(RemoteConference conference, RemoteConnection connection) {}

        /**
         * Invoked when a {@link RemoteConnection} is removed from the conference call.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param connection The {@link RemoteConnection} being removed.
         */
        public void onConnectionRemoved(RemoteConference conference, RemoteConnection connection) {}

        /**
         * Indicates that the call capabilities of this {@code RemoteConference} have changed.
         * See {@link #getConnectionCapabilities()}.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param connectionCapabilities The new capabilities of the {@code RemoteConference}.
         */
        public void onConnectionCapabilitiesChanged(
                RemoteConference conference,
                int connectionCapabilities) {}

        /**
         * Indicates that the call properties of this {@code RemoteConference} have changed.
         * See {@link #getConnectionProperties()}.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param connectionProperties The new properties of the {@code RemoteConference}.
         */
        public void onConnectionPropertiesChanged(
                RemoteConference conference,
                int connectionProperties) {}


        /**
         * Invoked when the set of {@link RemoteConnection}s which can be added to this conference
         * call have changed.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param conferenceableConnections The list of conferenceable {@link RemoteConnection}s.
         */
        public void onConferenceableConnectionsChanged(
                RemoteConference conference,
                List<RemoteConnection> conferenceableConnections) {}

        /**
         * Indicates that this {@code RemoteConference} has been destroyed. No further requests
         * should be made to the {@code RemoteConference}, and references to it should be cleared.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         */
        public void onDestroyed(RemoteConference conference) {}

        /**
         * Handles changes to the {@code RemoteConference} extras.
         *
         * @param conference The {@code RemoteConference} invoking this method.
         * @param extras The extras containing other information associated with the conference.
         */
        public void onExtrasChanged(RemoteConference conference, @Nullable Bundle extras) {}
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
    private int mConnectionProperties;
    private Bundle mExtras;

    /** @hide */
    RemoteConference(String id, IConnectionService connectionService) {
        mId = id;
        mConnectionService = connectionService;
    }

    /** @hide */
    RemoteConference(DisconnectCause disconnectCause) {
        mId = "NULL";
        mConnectionService = null;
        mState = Connection.STATE_DISCONNECTED;
        mDisconnectCause = disconnectCause;
    }

    /** @hide */
    String getId() {
        return mId;
    }

    /** @hide */
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

    /** @hide */
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

    /** @hide */
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

    /** @hide */
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

    /** @hide */
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
    void setConnectionProperties(final int connectionProperties) {
        if (mConnectionProperties != connectionProperties) {
            mConnectionProperties = connectionProperties;
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                final RemoteConference conference = this;
                final Callback callback = record.getCallback();
                record.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConnectionPropertiesChanged(
                                conference, mConnectionProperties);
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

    /** @hide */
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

    /** @hide */
    void putExtras(final Bundle extras) {
        if (extras == null) {
            return;
        }
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putAll(extras);

        notifyExtrasChanged();
    }

    /** @hide */
    void removeExtras(List<String> keys) {
        if (mExtras == null || keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            mExtras.remove(key);
        }

        notifyExtrasChanged();
    }

    private void notifyExtrasChanged() {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final RemoteConference conference = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onExtrasChanged(conference, mExtras);
                }
            });
        }
    }

    /**
     * Returns the list of {@link RemoteConnection}s contained in this conference.
     *
     * @return A list of child connections.
     */
    public final List<RemoteConnection> getConnections() {
        return mUnmodifiableChildConnections;
    }

    /**
     * Gets the state of the conference call. See {@link Connection} for valid values.
     *
     * @return A constant representing the state the conference call is currently in.
     */
    public final int getState() {
        return mState;
    }

    /**
     * Returns the capabilities of the conference. See {@code CAPABILITY_*} constants in class
     * {@link Connection} for valid values.
     *
     * @return A bitmask of the capabilities of the conference call.
     */
    public final int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    /**
     * Returns the properties of the conference. See {@code PROPERTY_*} constants in class
     * {@link Connection} for valid values.
     *
     * @return A bitmask of the properties of the conference call.
     */
    public final int getConnectionProperties() {
        return mConnectionProperties;
    }

    /**
     * Obtain the extras associated with this {@code RemoteConnection}.
     *
     * @return The extras for this connection.
     */
    public final Bundle getExtras() {
        return mExtras;
    }

    /**
     * Disconnects the conference call as well as the child {@link RemoteConnection}s.
     */
    public void disconnect() {
        try {
            mConnectionService.disconnect(mId, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Removes the specified {@link RemoteConnection} from the conference. This causes the
     * {@link RemoteConnection} to become a standalone connection. This is a no-op if the
     * {@link RemoteConnection} does not belong to this conference.
     *
     * @param connection The remote-connection to remove.
     */
    public void separate(RemoteConnection connection) {
        if (mChildConnections.contains(connection)) {
            try {
                mConnectionService.splitFromConference(connection.getId(), null /*Session.Info*/);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Merges all {@link RemoteConnection}s of this conference into a single call. This should be
     * invoked only if the conference contains the capability
     * {@link Connection#CAPABILITY_MERGE_CONFERENCE}, otherwise it is a no-op. The presence of said
     * capability indicates that the connections of this conference, despite being part of the
     * same conference object, are yet to have their audio streams merged; this is a common pattern
     * for CDMA conference calls, but the capability is not used for GSM and SIP conference calls.
     * Invoking this method will cause the unmerged child connections to merge their audio
     * streams.
     */
    public void merge() {
        try {
            mConnectionService.mergeConference(mId, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Swaps the active audio stream between the conference's child {@link RemoteConnection}s.
     * This should be invoked only if the conference contains the capability
     * {@link Connection#CAPABILITY_SWAP_CONFERENCE}, otherwise it is a no-op. This is only used by
     * {@link ConnectionService}s that create conferences for connections that do not yet have
     * their audio streams merged; this is a common pattern for CDMA conference calls, but the
     * capability is not used for GSM and SIP conference calls. Invoking this method will change the
     * active audio stream to a different child connection.
     */
    public void swap() {
        try {
            mConnectionService.swapConference(mId, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Puts the conference on hold.
     */
    public void hold() {
        try {
            mConnectionService.hold(mId, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Unholds the conference call.
     */
    public void unhold() {
        try {
            mConnectionService.unhold(mId, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns the {@link DisconnectCause} for the conference if it is in the state
     * {@link Connection#STATE_DISCONNECTED}. If the conference is not disconnected, this will
     * return null.
     *
     * @return The disconnect cause.
     */
    public DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * Requests that the conference start playing the specified DTMF tone.
     *
     * @param digit The digit for which to play a DTMF tone.
     */
    public void playDtmfTone(char digit) {
        try {
            mConnectionService.playDtmfTone(mId, digit, null /*Session.Info*/);
        } catch (RemoteException e) {
        }
    }

    /**
     * Stops the most recent request to play a DTMF tone.
     *
     * @see #playDtmfTone
     */
    public void stopDtmfTone() {
        try {
            mConnectionService.stopDtmfTone(mId, null /*Session.Info*/);
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
            mConnectionService.onCallAudioStateChanged(mId, state, null /*Session.Info*/);
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

    /**
     * Register a callback through which to receive state updates for this conference.
     *
     * @param callback The callback to notify of state changes.
     */
    public final void registerCallback(Callback callback) {
        registerCallback(callback, new Handler());
    }

    /**
     * Registers a callback through which to receive state updates for this conference.
     * Callbacks will be notified using the specified handler, if provided.
     *
     * @param callback The callback to notify of state changes.
     * @param handler The handler on which to execute the callbacks.
     */
    public final void registerCallback(Callback callback, Handler handler) {
        unregisterCallback(callback);
        if (callback != null && handler != null) {
            mCallbackRecords.add(new CallbackRecord(callback, handler));
        }
    }

    /**
     * Unregisters a previously registered callback.
     *
     * @see #registerCallback
     *
     * @param callback The callback to unregister.
     */
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

    /**
     * Create a {@link RemoteConference} represents a failure, and which will
     * be in {@link Connection#STATE_DISCONNECTED}.
     *
     * @param disconnectCause The disconnect cause.
     * @return a failed {@link RemoteConference}
     * @hide
     */
    public static RemoteConference failure(DisconnectCause disconnectCause) {
        return new RemoteConference(disconnectCause);
    }
}
