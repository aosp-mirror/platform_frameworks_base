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

import android.telephony.DisconnectCause;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a conference call which can contain any number of {@link Connection} objects.
 */
public abstract class Conference {

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Conference conference, int oldState, int newState) {}
        public void onDisconnected(Conference conference, int cause, String message) {}
        public void onConnectionAdded(Conference conference, Connection connection) {}
        public void onConnectionRemoved(Conference conference, Connection connection) {}
        public void onDestroyed(Conference conference) {}
        public void onCapabilitiesChanged(Conference conference, int capabilities) {}
    }

    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final List<Connection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<Connection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);

    private PhoneAccountHandle mPhoneAccount;
    private int mState = Connection.STATE_NEW;
    private int mDisconnectCause = DisconnectCause.NOT_VALID;
    private int mCapabilities;
    private String mDisconnectMessage;

    public Conference(PhoneAccountHandle phoneAccount) {
        mPhoneAccount = phoneAccount;
    }

    public final PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    public final List<Connection> getConnections() {
        return mUnmodifiableChildConnections;
    }

    public final int getState() {
        return mState;
    }

    public final int getCapabilities() {
        return mCapabilities;
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
     * Invoked when the conference should be put on hold.
     */
    public void onHold() {}

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    public void onUnhold() {}

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
     * @param cause The reason for the disconnection, any of
     *         {@link android.telephony.DisconnectCause}.
     * @param message Optional call-service-provided message about the disconnect.
     */
    public final void setDisconnected(int cause, String message) {
        mDisconnectCause = cause;
        mDisconnectMessage = message;
        setState(Connection.STATE_DISCONNECTED);
        for (Listener l : mListeners) {
            l.onDisconnected(this, mDisconnectCause, mDisconnectMessage);
        }
    }

    /**
     * Sets the capabilities of a conference.
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
    public boolean addConnection(Connection connection) {
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
     * @return True if the connection was successfully removed.
     */
    public void removeConnection(Connection connection) {
        Log.d(this, "removing %s from %s", connection, mChildConnections);
        if (connection != null && mChildConnections.remove(connection)) {
            connection.resetConference();
            for (Listener l : mListeners) {
                l.onConnectionRemoved(this, connection);
            }
        }
    }

    /**
     * Tears down the conference object and any of it's current connections.
     */
    public void destroy() {
        Log.d(this, "destroying conference : %s", this);
        // Tear down the children.
        for (Connection connection : mChildConnections) {
            Log.d(this, "removing connection %s", connection);
            removeConnection(connection);
        }

        // If not yet disconnected, set the conference call as disconnected first.
        if (mState != Connection.STATE_DISCONNECTED) {
            Log.d(this, "setting to disconnected");
            setDisconnected(DisconnectCause.LOCAL, null);
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
}
