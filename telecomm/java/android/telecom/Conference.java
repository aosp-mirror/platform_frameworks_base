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

import android.annotation.SystemApi;

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
public abstract class Conference {

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Conference conference, int oldState, int newState) {}
        public void onDisconnected(Conference conference, DisconnectCause disconnectCause) {}
        public void onConnectionAdded(Conference conference, Connection connection) {}
        public void onConnectionRemoved(Conference conference, Connection connection) {}
        public void onConferenceableConnectionsChanged(
                Conference conference, List<Connection> conferenceableConnections) {}
        public void onDestroyed(Conference conference) {}
        public void onCapabilitiesChanged(Conference conference, int capabilities) {}
    }

    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final List<Connection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<Connection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);
    private final List<Connection> mConferenceableConnections = new ArrayList<>();
    private final List<Connection> mUnmodifiableConferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private PhoneAccountHandle mPhoneAccount;
    private AudioState mAudioState;
    private int mState = Connection.STATE_NEW;
    private DisconnectCause mDisconnectCause;
    private int mCapabilities;
    private String mDisconnectMessage;

    private final Connection.Listener mConnectionDeathListener = new Connection.Listener() {
        @Override
        public void onDestroyed(Connection c) {
            if (mConferenceableConnections.remove(c)) {
                fireOnConferenceableConnectionsChanged();
            }
        }
    };

    /**
     * Constructs a new Conference with a mandatory {@link PhoneAccountHandle}
     *
     * @param phoneAccount The {@code PhoneAccountHandle} associated with the conference.
     */
    public Conference(PhoneAccountHandle phoneAccount) {
        mPhoneAccount = phoneAccount;
    }

    /**
     * Returns the {@link PhoneAccountHandle} the conference call is being placed through.
     *
     * @return A {@code PhoneAccountHandle} object representing the PhoneAccount of the conference.
     */
    public final PhoneAccountHandle getPhoneAccountHandle() {
        return mPhoneAccount;
    }

    /**
     * Returns the list of connections currently associated with the conference call.
     *
     * @return A list of {@code Connection} objects which represent the children of the conference.
     */
    public final List<Connection> getConnections() {
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
     * Returns the capabilities of a conference. See {@link PhoneCapabilities} for valid values.
     *
     * @return A bitmask of the {@code PhoneCapabilities} of the conference call.
     */
    public final int getCapabilities() {
        return mCapabilities;
    }

    /**
     * @return The audio state of the conference, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Conference
     *         does not directly know about its audio state.
     */
    public final AudioState getAudioState() {
        return mAudioState;
    }

    /**
     * Invoked when the Conference and all it's {@link Connection}s should be disconnected.
     */
    public void onDisconnect() {}

    /**
     * Invoked when the specified {@link Connection} should be separated from the conference call.
     *
     * @param connection The connection to separate.
     */
    public void onSeparate(Connection connection) {}

    /**
     * Invoked when the specified {@link Connection} should merged with the conference call.
     *
     * @param connection The {@code Connection} to merge.
     */
    public void onMerge(Connection connection) {}

    /**
     * Invoked when the conference should be put on hold.
     */
    public void onHold() {}

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    public void onUnhold() {}

    /**
     * Invoked when the child calls should be merged. Only invoked if the conference contains the
     * capability {@link PhoneCapabilities#MERGE_CONFERENCE}.
     */
    public void onMerge() {}

    /**
     * Invoked when the child calls should be swapped. Only invoked if the conference contains the
     * capability {@link PhoneCapabilities#SWAP_CONFERENCE}.
     */
    public void onSwap() {}

    /**
     * Notifies this conference of a request to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    public void onPlayDtmfTone(char c) {}

    /**
     * Notifies this conference of a request to stop any currently playing DTMF tones.
     */
    public void onStopDtmfTone() {}

    /**
     * Notifies this conference that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new call audio state.
     */
    public void onAudioStateChanged(AudioState state) {}

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        setState(Connection.STATE_HOLDING);
    }

    /**
     * Sets state to be active.
     */
    public final void setActive() {
        setState(Connection.STATE_ACTIVE);
    }

    /**
     * Sets state to disconnected.
     *
     * @param disconnectCause The reason for the disconnection, as described by
     *     {@link android.telecom.DisconnectCause}.
     */
    public final void setDisconnected(DisconnectCause disconnectCause) {
        mDisconnectCause = disconnectCause;;
        setState(Connection.STATE_DISCONNECTED);
        for (Listener l : mListeners) {
            l.onDisconnected(this, mDisconnectCause);
        }
    }

    /**
     * Sets the capabilities of a conference. See {@link PhoneCapabilities} for valid values.
     *
     * @param capabilities A bitmask of the {@code PhoneCapabilities} of the conference call.
     */
    public final void setCapabilities(int capabilities) {
        if (capabilities != mCapabilities) {
            mCapabilities = capabilities;

            for (Listener l : mListeners) {
                l.onCapabilitiesChanged(this, mCapabilities);
            }
        }
    }

    /**
     * Adds the specified connection as a child of this conference.
     *
     * @param connection The connection to add.
     * @return True if the connection was successfully added.
     */
    public final boolean addConnection(Connection connection) {
        if (connection != null && !mChildConnections.contains(connection)) {
            if (connection.setConference(this)) {
                mChildConnections.add(connection);
                for (Listener l : mListeners) {
                    l.onConnectionAdded(this, connection);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the specified connection as a child of this conference.
     *
     * @param connection The connection to remove.
     */
    public final void removeConnection(Connection connection) {
        Log.d(this, "removing %s from %s", connection, mChildConnections);
        if (connection != null && mChildConnections.remove(connection)) {
            connection.resetConference();
            for (Listener l : mListeners) {
                l.onConnectionRemoved(this, connection);
            }
        }
    }

    /**
     * Sets the connections with which this connection can be conferenced.
     *
     * @param conferenceableConnections The set of connections this connection can conference with.
     */
    public final void setConferenceableConnections(List<Connection> conferenceableConnections) {
        clearConferenceableList();
        for (Connection c : conferenceableConnections) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceableConnections.contains(c)) {
                c.addConnectionListener(mConnectionDeathListener);
                mConferenceableConnections.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    private final void fireOnConferenceableConnectionsChanged() {
        for (Listener l : mListeners) {
            l.onConferenceableConnectionsChanged(this, getConferenceableConnections());
        }
    }

    /**
     * Returns the connections with which this connection can be conferenced.
     */
    public final List<Connection> getConferenceableConnections() {
        return mUnmodifiableConferenceableConnections;
    }

    /**
     * Tears down the conference object and any of its current connections.
     */
    public final void destroy() {
        Log.d(this, "destroying conference : %s", this);
        // Tear down the children.
        for (Connection connection : mChildConnections) {
            Log.d(this, "removing connection %s", connection);
            removeConnection(connection);
        }

        // If not yet disconnected, set the conference call as disconnected first.
        if (mState != Connection.STATE_DISCONNECTED) {
            Log.d(this, "setting to disconnected");
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        }

        // ...and notify.
        for (Listener l : mListeners) {
            l.onDestroyed(this);
        }
    }

    /**
     * Add a listener to be notified of a state change.
     *
     * @param listener The new listener.
     * @return This conference.
     * @hide
     */
    public final Conference addListener(Listener listener) {
        mListeners.add(listener);
        return this;
    }

    /**
     * Removes the specified listener.
     *
     * @param listener The listener to remove.
     * @return This conference.
     * @hide
     */
    public final Conference removeListener(Listener listener) {
        mListeners.remove(listener);
        return this;
    }

    /**
     * Inform this Conference that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setAudioState(AudioState state) {
        Log.d(this, "setAudioState %s", state);
        mAudioState = state;
        onAudioStateChanged(state);
    }

    private void setState(int newState) {
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

    private final void clearConferenceableList() {
        for (Connection c : mConferenceableConnections) {
            c.removeConnectionListener(mConnectionDeathListener);
        }
        mConferenceableConnections.clear();
    }
}
