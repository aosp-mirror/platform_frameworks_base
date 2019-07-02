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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.Connection.VideoProvider;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a conference call which can contain any number of {@link Connection} objects.
 */
public abstract class Conference extends Conferenceable {

    /**
     * Used to indicate that the conference connection time is not specified.  If not specified,
     * Telecom will set the connect time.
     */
    public static final long CONNECT_TIME_NOT_SPECIFIED = 0;

    /** @hide */
    public abstract static class Listener {
        public void onStateChanged(Conference conference, int oldState, int newState) {}
        public void onDisconnected(Conference conference, DisconnectCause disconnectCause) {}
        public void onConnectionAdded(Conference conference, Connection connection) {}
        public void onConnectionRemoved(Conference conference, Connection connection) {}
        public void onConferenceableConnectionsChanged(
                Conference conference, List<Connection> conferenceableConnections) {}
        public void onDestroyed(Conference conference) {}
        public void onConnectionCapabilitiesChanged(
                Conference conference, int connectionCapabilities) {}
        public void onConnectionPropertiesChanged(
                Conference conference, int connectionProperties) {}
        public void onVideoStateChanged(Conference c, int videoState) { }
        public void onVideoProviderChanged(Conference c, Connection.VideoProvider videoProvider) {}
        public void onStatusHintsChanged(Conference conference, StatusHints statusHints) {}
        public void onExtrasChanged(Conference c, Bundle extras) {}
        public void onExtrasRemoved(Conference c, List<String> keys) {}
        public void onConferenceStateChanged(Conference c, boolean isConference) {}
        public void onAddressChanged(Conference c, Uri newAddress, int presentation) {}
        public void onConnectionEvent(Conference c, String event, Bundle extras) {}
        public void onCallerDisplayNameChanged(
                Conference c, String callerDisplayName, int presentation) {}
    }

    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final List<Connection> mChildConnections = new CopyOnWriteArrayList<>();
    private final List<Connection> mUnmodifiableChildConnections =
            Collections.unmodifiableList(mChildConnections);
    private final List<Connection> mConferenceableConnections = new ArrayList<>();
    private final List<Connection> mUnmodifiableConferenceableConnections =
            Collections.unmodifiableList(mConferenceableConnections);

    private String mTelecomCallId;
    private PhoneAccountHandle mPhoneAccount;
    private CallAudioState mCallAudioState;
    private int mState = Connection.STATE_NEW;
    private DisconnectCause mDisconnectCause;
    private int mConnectionCapabilities;
    private int mConnectionProperties;
    private String mDisconnectMessage;
    private long mConnectTimeMillis = CONNECT_TIME_NOT_SPECIFIED;
    private long mConnectionStartElapsedRealTime = CONNECT_TIME_NOT_SPECIFIED;
    private StatusHints mStatusHints;
    private Bundle mExtras;
    private Set<String> mPreviousExtraKeys;
    private final Object mExtrasLock = new Object();
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;

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
     * Returns the telecom internal call ID associated with this conference.
     *
     * @return The telecom call ID.
     * @hide
     */
    public final String getTelecomCallId() {
        return mTelecomCallId;
    }

    /**
     * Sets the telecom internal call ID associated with this conference.
     *
     * @param telecomCallId The telecom call ID.
     * @hide
     */
    public final void setTelecomCallId(String telecomCallId) {
        mTelecomCallId = telecomCallId;
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
     * Whether the given capabilities support the specified capability.
     *
     * @param capabilities A capability bit field.
     * @param capability The capability to check capabilities for.
     * @return Whether the specified capability is supported.
     * @hide
     */
    public static boolean can(int capabilities, int capability) {
        return (capabilities & capability) != 0;
    }

    /**
     * Whether the capabilities of this {@code Connection} supports the specified capability.
     *
     * @param capability The capability to check capabilities for.
     * @return Whether the specified capability is supported.
     * @hide
     */
    public boolean can(int capability) {
        return can(mConnectionCapabilities, capability);
    }

    /**
     * Removes the specified capability from the set of capabilities of this {@code Conference}.
     *
     * @param capability The capability to remove from the set.
     * @hide
     */
    public void removeCapability(int capability) {
        int newCapabilities = mConnectionCapabilities;
        newCapabilities &= ~capability;

        setConnectionCapabilities(newCapabilities);
    }

    /**
     * Adds the specified capability to the set of capabilities of this {@code Conference}.
     *
     * @param capability The capability to add to the set.
     * @hide
     */
    public void addCapability(int capability) {
        int newCapabilities = mConnectionCapabilities;
        newCapabilities |= capability;

        setConnectionCapabilities(newCapabilities);
    }

    /**
     * @return The audio state of the conference, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Conference
     *         does not directly know about its audio state.
     * @deprecated Use {@link #getCallAudioState()} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public final AudioState getAudioState() {
        return new AudioState(mCallAudioState);
    }

    /**
     * @return The audio state of the conference, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Conference
     *         does not directly know about its audio state.
     */
    public final CallAudioState getCallAudioState() {
        return mCallAudioState;
    }

    /**
     * Returns VideoProvider of the primary call. This can be null.
     */
    public VideoProvider getVideoProvider() {
        return null;
    }

    /**
     * Returns video state of the primary call.
     */
    public int getVideoState() {
        return VideoProfile.STATE_AUDIO_ONLY;
    }

    /**
     * Notifies the {@link Conference} when the Conference and all it's {@link Connection}s should
     * be disconnected.
     */
    public void onDisconnect() {}

    /**
     * Notifies the {@link Conference} when the specified {@link Connection} should be separated
     * from the conference call.
     *
     * @param connection The connection to separate.
     */
    public void onSeparate(Connection connection) {}

    /**
     * Notifies the {@link Conference} when the specified {@link Connection} should merged with the
     * conference call.
     *
     * @param connection The {@code Connection} to merge.
     */
    public void onMerge(Connection connection) {}

    /**
     * Notifies the {@link Conference} when it should be put on hold.
     */
    public void onHold() {}

    /**
     * Notifies the {@link Conference} when it should be moved from a held to active state.
     */
    public void onUnhold() {}

    /**
     * Notifies the {@link Conference} when the child calls should be merged.  Only invoked if the
     * conference contains the capability {@link Connection#CAPABILITY_MERGE_CONFERENCE}.
     */
    public void onMerge() {}

    /**
     * Notifies the {@link Conference} when the child calls should be swapped. Only invoked if the
     * conference contains the capability {@link Connection#CAPABILITY_SWAP_CONFERENCE}.
     */
    public void onSwap() {}

    /**
     * Notifies the {@link Conference} of a request to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    public void onPlayDtmfTone(char c) {}

    /**
     * Notifies the {@link Conference} of a request to stop any currently playing DTMF tones.
     */
    public void onStopDtmfTone() {}

    /**
     * Notifies the {@link Conference} that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new call audio state.
     * @deprecated Use {@link #onCallAudioStateChanged(CallAudioState)} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public void onAudioStateChanged(AudioState state) {}

    /**
     * Notifies the {@link Conference} that the {@link #getCallAudioState()} property has a new
     * value.
     *
     * @param state The new call audio state.
     */
    public void onCallAudioStateChanged(CallAudioState state) {}

    /**
     * Notifies the {@link Conference} that a {@link Connection} has been added to it.
     *
     * @param connection The newly added connection.
     */
    public void onConnectionAdded(Connection connection) {}

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        setState(Connection.STATE_HOLDING);
    }

    /**
     * Sets state to be dialing.
     */
    public final void setDialing() {
        setState(Connection.STATE_DIALING);
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
     * @return The {@link DisconnectCause} for this connection.
     */
    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * Sets the capabilities of a conference. See {@code CAPABILITY_*} constants of class
     * {@link Connection} for valid values.
     *
     * @param connectionCapabilities A bitmask of the {@code Capabilities} of the conference call.
     */
    public final void setConnectionCapabilities(int connectionCapabilities) {
        if (connectionCapabilities != mConnectionCapabilities) {
            mConnectionCapabilities = connectionCapabilities;

            for (Listener l : mListeners) {
                l.onConnectionCapabilitiesChanged(this, mConnectionCapabilities);
            }
        }
    }

    /**
     * Sets the properties of a conference. See {@code PROPERTY_*} constants of class
     * {@link Connection} for valid values.
     *
     * @param connectionProperties A bitmask of the {@code Properties} of the conference call.
     */
    public final void setConnectionProperties(int connectionProperties) {
        if (connectionProperties != mConnectionProperties) {
            mConnectionProperties = connectionProperties;

            for (Listener l : mListeners) {
                l.onConnectionPropertiesChanged(this, mConnectionProperties);
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
        Log.d(this, "Connection=%s, connection=", connection);
        if (connection != null && !mChildConnections.contains(connection)) {
            if (connection.setConference(this)) {
                mChildConnections.add(connection);
                onConnectionAdded(connection);
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

    /**
     * Set the video state for the conference.
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
     *
     * @param videoState The new video state.
     */
    public final void setVideoState(Connection c, int videoState) {
        Log.d(this, "setVideoState Conference: %s Connection: %s VideoState: %s",
                this, c, videoState);
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, videoState);
        }
    }

    /**
     * Sets the video connection provider.
     *
     * @param videoProvider The video provider.
     */
    public final void setVideoProvider(Connection c, Connection.VideoProvider videoProvider) {
        Log.d(this, "setVideoProvider Conference: %s Connection: %s VideoState: %s",
                this, c, videoProvider);
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(this, videoProvider);
        }
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
     * Retrieves the primary connection associated with the conference.  The primary connection is
     * the connection from which the conference will retrieve its current state.
     *
     * @return The primary connection.
     * @hide
     */
    @TestApi
    @SystemApi
    public Connection getPrimaryConnection() {
        if (mUnmodifiableChildConnections == null || mUnmodifiableChildConnections.isEmpty()) {
            return null;
        }
        return mUnmodifiableChildConnections.get(0);
    }

    /**
     * Updates RIL voice radio technology used for current conference after its creation.
     *
     * @hide
     */
    public void updateCallRadioTechAfterCreation() {
        final Connection primaryConnection = getPrimaryConnection();
        if (primaryConnection != null) {
            setCallRadioTech(primaryConnection.getCallRadioTech());
        } else {
            Log.w(this, "No primary connection found while updateCallRadioTechAfterCreation");
        }
    }

    /**
     * @hide
     * @deprecated Use {@link #setConnectionTime}.
     */
    @Deprecated
    @SystemApi
    public final void setConnectTimeMillis(long connectTimeMillis) {
        setConnectionTime(connectTimeMillis);
    }

    /**
     * Sets the connection start time of the {@code Conference}.  This is used in the call log to
     * indicate the date and time when the conference took place.
     * <p>
     * Should be specified in wall-clock time returned by {@link System#currentTimeMillis()}.
     * <p>
     * When setting the connection time, you should always set the connection elapsed time via
     * {@link #setConnectionStartElapsedRealTime(long)} to ensure the duration is reflected.
     *
     * @param connectionTimeMillis The connection time, in milliseconds, as returned by
     *                             {@link System#currentTimeMillis()}.
     */
    public final void setConnectionTime(long connectionTimeMillis) {
        mConnectTimeMillis = connectionTimeMillis;
    }

    /**
     * Sets the start time of the {@link Conference} which is the basis for the determining the
     * duration of the {@link Conference}.
     * <p>
     * You should use a value returned by {@link SystemClock#elapsedRealtime()} to ensure that time
     * zone changes do not impact the conference duration.
     * <p>
     * When setting this, you should also set the connection time via
     * {@link #setConnectionTime(long)}.
     *
     * @param connectionStartElapsedRealTime The connection time, as measured by
     * {@link SystemClock#elapsedRealtime()}.
     */
    public final void setConnectionStartElapsedRealTime(long connectionStartElapsedRealTime) {
        mConnectionStartElapsedRealTime = connectionStartElapsedRealTime;
    }

    /**
     * @hide
     * @deprecated Use {@link #getConnectionTime}.
     */
    @Deprecated
    @SystemApi
    public final long getConnectTimeMillis() {
        return getConnectionTime();
    }

    /**
     * Retrieves the connection start time of the {@code Conference}, if specified.  A value of
     * {@link #CONNECT_TIME_NOT_SPECIFIED} indicates that Telecom should determine the start time
     * of the conference.
     *
     * @return The time at which the {@code Conference} was connected.
     */
    public final long getConnectionTime() {
        return mConnectTimeMillis;
    }

    /**
     * Retrieves the connection start time of the {@link Conference}, if specified.  A value of
     * {@link #CONNECT_TIME_NOT_SPECIFIED} indicates that Telecom should determine the start time
     * of the conference.
     *
     * This is based on the value of {@link SystemClock#elapsedRealtime()} to ensure that it is not
     * impacted by wall clock changes (user initiated, network initiated, time zone change, etc).
     *
     * @return The elapsed time at which the {@link Conference} was connected.
     * @hide
     */
    public final long getConnectionStartElapsedRealTime() {
        return mConnectionStartElapsedRealTime;
    }

    /**
     * Sets RIL voice radio technology used for current conference.
     *
     * @param vrat the RIL voice radio technology used for current conference,
     *             see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     *
     * @hide
     */
    public final void setCallRadioTech(@ServiceState.RilRadioTechnology int vrat) {
        putExtra(TelecomManager.EXTRA_CALL_NETWORK_TYPE,
                ServiceState.rilRadioTechnologyToNetworkType(vrat));
    }

    /**
     * Returns RIL voice radio technology used for current conference.
     *
     * @return the RIL voice radio technology used for current conference,
     *         see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     *
     * @hide
     */
    public final @ServiceState.RilRadioTechnology int getCallRadioTech() {
        int voiceNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        Bundle extras = getExtras();
        if (extras != null) {
            voiceNetworkType = extras.getInt(TelecomManager.EXTRA_CALL_NETWORK_TYPE,
                    TelephonyManager.NETWORK_TYPE_UNKNOWN);
        }
        return ServiceState.networkTypeToRilRadioTechnology(voiceNetworkType);
    }

    /**
     * Inform this Conference that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setCallAudioState(CallAudioState state) {
        Log.d(this, "setCallAudioState %s", state);
        mCallAudioState = state;
        onAudioStateChanged(getAudioState());
        onCallAudioStateChanged(state);
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

    @Override
    public String toString() {
        return String.format(Locale.US,
                "[State: %s,Capabilites: %s, VideoState: %s, VideoProvider: %s, ThisObject %s]",
                Connection.stateToString(mState),
                Call.Details.capabilitiesToString(mConnectionCapabilities),
                getVideoState(),
                getVideoProvider(),
                super.toString());
    }

    /**
     * Sets the label and icon status to display in the InCall UI.
     *
     * @param statusHints The status label and icon to set.
     */
    public final void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this, statusHints);
        }
    }

    /**
     * @return The status hints for this conference.
     */
    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * Replaces all the extras associated with this {@code Conference}.
     * <p>
     * New or existing keys are replaced in the {@code Conference} extras.  Keys which are no longer
     * in the new extras, but were present the last time {@code setExtras} was called are removed.
     * <p>
     * Alternatively you may use the {@link #putExtras(Bundle)}, and
     * {@link #removeExtras(String...)} methods to modify the extras.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these extras.
     * Keys should be fully qualified (e.g., com.example.extras.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with this {@code Conference}.
     */
    public final void setExtras(@Nullable Bundle extras) {
        // Keeping putExtras and removeExtras in the same lock so that this operation happens as a
        // block instead of letting other threads put/remove while this method is running.
        synchronized (mExtrasLock) {
            // Add/replace any new or changed extras values.
            putExtras(extras);
            // If we have used "setExtras" in the past, compare the key set from the last invocation
            // to the current one and remove any keys that went away.
            if (mPreviousExtraKeys != null) {
                List<String> toRemove = new ArrayList<String>();
                for (String oldKey : mPreviousExtraKeys) {
                    if (extras == null || !extras.containsKey(oldKey)) {
                        toRemove.add(oldKey);
                    }
                }

                if (!toRemove.isEmpty()) {
                    removeExtras(toRemove);
                }
            }

            // Track the keys the last time set called setExtras.  This way, the next time setExtras
            // is called we can see if the caller has removed any extras values.
            if (mPreviousExtraKeys == null) {
                mPreviousExtraKeys = new ArraySet<String>();
            }
            mPreviousExtraKeys.clear();
            if (extras != null) {
                mPreviousExtraKeys.addAll(extras.keySet());
            }
        }
    }

    /**
     * Adds some extras to this {@link Conference}.  Existing keys are replaced and new ones are
     * added.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these extras.
     * Keys should be fully qualified (e.g., com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras to add.
     */
    public final void putExtras(@NonNull Bundle extras) {
        if (extras == null) {
            return;
        }

        // Creating a Bundle clone so we don't have to synchronize on mExtrasLock while calling
        // onExtrasChanged.
        Bundle listenersBundle;
        synchronized (mExtrasLock) {
            if (mExtras == null) {
                mExtras = new Bundle();
            }
            mExtras.putAll(extras);
            listenersBundle = new Bundle(mExtras);
        }

        for (Listener l : mListeners) {
            l.onExtrasChanged(this, new Bundle(listenersBundle));
        }
    }

    /**
     * Adds a boolean extra to this {@link Conference}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, boolean value) {
        Bundle newExtras = new Bundle();
        newExtras.putBoolean(key, value);
        putExtras(newExtras);
    }

    /**
     * Adds an integer extra to this {@link Conference}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, int value) {
        Bundle newExtras = new Bundle();
        newExtras.putInt(key, value);
        putExtras(newExtras);
    }

    /**
     * Adds a string extra to this {@link Conference}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, String value) {
        Bundle newExtras = new Bundle();
        newExtras.putString(key, value);
        putExtras(newExtras);
    }

    /**
     * Removes extras from this {@link Conference}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        synchronized (mExtrasLock) {
            if (mExtras != null) {
                for (String key : keys) {
                    mExtras.remove(key);
                }
            }
        }

        List<String> unmodifiableKeys = Collections.unmodifiableList(keys);
        for (Listener l : mListeners) {
            l.onExtrasRemoved(this, unmodifiableKeys);
        }
    }

    /**
     * Removes extras from this {@link Conference}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(String ... keys) {
        removeExtras(Arrays.asList(keys));
    }

    /**
     * Returns the extras associated with this conference.
     * <p>
     * Extras should be updated using {@link #putExtras(Bundle)} and {@link #removeExtras(List)}.
     * <p>
     * Telecom or an {@link InCallService} can also update the extras via
     * {@link android.telecom.Call#putExtras(Bundle)}, and
     * {@link Call#removeExtras(List)}.
     * <p>
     * The conference is notified of changes to the extras made by Telecom or an
     * {@link InCallService} by {@link #onExtrasChanged(Bundle)}.
     *
     * @return The extras associated with this connection.
     */
    public final Bundle getExtras() {
        return mExtras;
    }

    /**
     * Notifies this {@link Conference} of a change to the extras made outside the
     * {@link ConnectionService}.
     * <p>
     * These extras changes can originate from Telecom itself, or from an {@link InCallService} via
     * {@link android.telecom.Call#putExtras(Bundle)}, and
     * {@link Call#removeExtras(List)}.
     *
     * @param extras The new extras bundle.
     */
    public void onExtrasChanged(Bundle extras) {}

    /**
     * Set whether Telecom should treat this {@link Conference} as a conference call or if it
     * should treat it as a single-party call.
     * This method is used as part of a workaround regarding IMS conference calls and user
     * expectation.  In IMS, once a conference is formed, the UE is connected to an IMS conference
     * server.  If all participants of the conference drop out of the conference except for one, the
     * UE is still connected to the IMS conference server.  At this point, the user logically
     * assumes they're no longer in a conference, yet the underlying network actually is.
     * To help provide a better user experiece, IMS conference calls can pretend to actually be a
     * single-party call when the participant count drops to 1.  Although the dialer/phone app
     * could perform this trickery, it makes sense to do this in Telephony since a fix there will
     * ensure that bluetooth head units, auto and wearable apps all behave consistently.
     *
     * @param isConference {@code true} if this {@link Conference} should be treated like a
     *      conference call, {@code false} if it should be treated like a single-party call.
     * @hide
     */
    public void setConferenceState(boolean isConference) {
        for (Listener l : mListeners) {
            l.onConferenceStateChanged(this, isConference);
        }
    }

    /**
     * Sets the address of this {@link Conference}.  Used when {@link #setConferenceState(boolean)}
     * is called to mark a conference temporarily as NOT a conference.
     *
     * @param address The new address.
     * @param presentation The presentation requirements for the address.
     *        See {@link TelecomManager} for valid values.
     * @hide
     */
    public final void setAddress(Uri address, int presentation) {
        Log.d(this, "setAddress %s", address);
        mAddress = address;
        mAddressPresentation = presentation;
        for (Listener l : mListeners) {
            l.onAddressChanged(this, address, presentation);
        }
    }

    /**
     * Returns the "address" associated with the conference.  This is applicable in two cases:
     * <ol>
     *     <li>When {@link #setConferenceState(boolean)} is used to mark a conference as
     *     temporarily "not a conference"; we need to present the correct address in the in-call
     *     UI.</li>
     *     <li>When the conference is not hosted on the current device, we need to know the address
     *     information for the purpose of showing the original address to the user, as well as for
     *     logging to the call log.</li>
     * </ol>
     * @return The address of the conference, or {@code null} if not applicable.
     * @hide
     */
    public final Uri getAddress() {
        return mAddress;
    }

    /**
     * Returns the address presentation associated with the conference.
     * <p>
     * This is applicable in two cases:
     * <ol>
     *     <li>When {@link #setConferenceState(boolean)} is used to mark a conference as
     *     temporarily "not a conference"; we need to present the correct address in the in-call
     *     UI.</li>
     *     <li>When the conference is not hosted on the current device, we need to know the address
     *     information for the purpose of showing the original address to the user, as well as for
     *     logging to the call log.</li>
     * </ol>
     * @return The address of the conference, or {@code null} if not applicable.
     * @hide
     */
    public final int getAddressPresentation() {
        return mAddressPresentation;
    }

    /**
     * @return The caller display name (CNAP).
     * @hide
     */
    public final String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * @return The presentation requirements for the handle.
     *         See {@link TelecomManager} for valid values.
     * @hide
     */
    public final int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /**
     * Sets the caller display name (CNAP) of this {@link Conference}.  Used when
     * {@link #setConferenceState(boolean)} is called to mark a conference temporarily as NOT a
     * conference.
     *
     * @param callerDisplayName The new display name.
     * @param presentation The presentation requirements for the handle.
     *        See {@link TelecomManager} for valid values.
     * @hide
     */
    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
        Log.d(this, "setCallerDisplayName %s", callerDisplayName);
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Listener l : mListeners) {
            l.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /**
     * Handles a change to extras received from Telecom.
     *
     * @param extras The new extras.
     * @hide
     */
    final void handleExtrasChanged(Bundle extras) {
        Bundle b = null;
        synchronized (mExtrasLock) {
            mExtras = extras;
            if (mExtras != null) {
                b = new Bundle(mExtras);
            }
        }
        onExtrasChanged(b);
    }

    /**
     * See {@link Connection#sendConnectionEvent(String, Bundle)}
     * @hide
     */
    public void sendConnectionEvent(String event, Bundle extras) {
        for (Listener l : mListeners) {
            l.onConnectionEvent(this, event, extras);
        }
    }
}
