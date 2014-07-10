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

import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallVideoProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a connection to a remote endpoint that carries voice traffic.
 */
public abstract class Connection {

    /** @hide */
    public interface Listener {
        void onStateChanged(Connection c, int state);
        void onFeaturesChanged(Connection c, int features);
        void onHandleChanged(Connection c, Uri newHandle);
        void onSignalChanged(Connection c, Bundle details);
        void onDisconnected(Connection c, int cause, String message);
        void onPostDialWait(Connection c, String remaining);
        void onRequestingRingback(Connection c, boolean ringback);
        void onDestroyed(Connection c);
        void onCallCapabilitiesChanged(Connection c, int callCapabilities);
        void onParentConnectionChanged(Connection c, Connection parent);
        void onSetCallVideoProvider(Connection c, CallVideoProvider callVideoProvider);
        void onSetAudioModeIsVoip(Connection c, boolean isVoip);
        void onSetStatusHints(Connection c, StatusHints statusHints);
    }

    /** @hide */
    public static class ListenerBase implements Listener {
        @Override
        public void onStateChanged(Connection c, int state) {}

        /** {@inheritDoc} */
        @Override
        public void onFeaturesChanged(Connection c, int features) {}

        @Override
        public void onHandleChanged(Connection c, Uri newHandle) {}

        @Override
        public void onSignalChanged(Connection c, Bundle details) {}

        @Override
        public void onDisconnected(Connection c, int cause, String message) {}

        @Override
        public void onDestroyed(Connection c) {}

        @Override
        public void onPostDialWait(Connection c, String remaining) {}

        @Override
        public void onRequestingRingback(Connection c, boolean ringback) {}

        @Override
        public void onCallCapabilitiesChanged(Connection c, int callCapabilities) {}

        @Override
        public void onParentConnectionChanged(Connection c, Connection parent) {}

        @Override
        public void onSetCallVideoProvider(Connection c, CallVideoProvider callVideoProvider) {}

        @Override
        public void onSetAudioModeIsVoip(Connection c, boolean isVoip) {}

        @Override
        public void onSetStatusHints(Connection c, StatusHints statusHints) {}
    }

    public final class State {
        private State() {}

        public static final int NEW = 0;
        public static final int RINGING = 1;
        public static final int DIALING = 2;
        public static final int ACTIVE = 3;
        public static final int HOLDING = 4;
        public static final int DISCONNECTED = 5;
    }

    private final Set<Listener> mListeners = new HashSet<>();
    private final List<Connection> mChildConnections = new ArrayList<>();

    private int mState = State.NEW;
    private int mFeatures = CallFeatures.NONE;
    private CallAudioState mCallAudioState;
    private Uri mHandle;
    private boolean mRequestingRingback = false;
    private int mCallCapabilities;
    private Connection mParentConnection;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;

    /**
     * Create a new Connection.
     */
    protected Connection() {}

    /**
     * The handle (e.g., phone number) to which this Connection is currently communicating.
     *
     * IMPORTANT: If an incoming connection has a phone number (or other handle) that the user
     * is not supposed to be able to see (e.g. it is PRESENTATION_RESTRICTED), then a compliant
     * ConnectionService implementation MUST NOT reveal this phone number and MUST return
     * {@code null} from this method.
     *
     * @return The handle (e.g., phone number) to which this Connection
     *         is currently communicating.
     */
    public final Uri getHandle() {
        return mHandle;
    }

    /**
     * @return The state of this Connection.
     */
    public final int getState() {
        return mState;
    }

    /**
     * @return The features of the call.  These are items for which the InCall UI may wish to
     *         display a visual indicator.
     */
    public final int getFeatures() {
        return mFeatures;
    }

    /**
     * @return The audio state of the call, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Connection
     *         does not directly know about its audio state.
     */
    public final CallAudioState getCallAudioState() {
        return mCallAudioState;
    }

    /**
     * Returns whether this connection is requesting that the system play a ringback tone
     * on its behalf.
     */
    public final boolean isRequestingRingback() {
        return mRequestingRingback;
    }

    /**
     * Returns whether this connection is a conference connection (has child connections).
     */
    public final boolean isConferenceConnection() {
        return !mChildConnections.isEmpty();
    }

    /**
     * @return True if the connection's audio mode is VOIP.
     */
    public final boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    /**
     * @return The status hints for this connection.
     */
    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * Assign a listener to be notified of state changes.
     *
     * @param l A listener.
     * @return This Connection.
     *
     * @hide
     */
    public final Connection addConnectionListener(Listener l) {
        mListeners.add(l);
        return this;
    }

    /**
     * Remove a previously assigned listener that was being notified of state changes.
     *
     * @param l A Listener.
     * @return This Connection.
     *
     * @hide
     */
    public final Connection removeConnectionListener(Listener l) {
        mListeners.remove(l);
        return this;
    }

    /**
     * Inform this Connection that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setAudioState(CallAudioState state) {
        Log.d(this, "setAudioState %s", state);
        mCallAudioState = state;
        onSetAudioState(state);
    }

    /**
     * @param state An integer value from {@link State}.
     * @return A string representation of the value.
     */
    public static String stateToString(int state) {
        switch (state) {
            case State.NEW:
                return "NEW";
            case State.RINGING:
                return "RINGING";
            case State.DIALING:
                return "DIALING";
            case State.ACTIVE:
                return "ACTIVE";
            case State.HOLDING:
                return "HOLDING";
            case State.DISCONNECTED:
                return "DISCONNECTED";
            default:
                Log.wtf(Connection.class, "Unknown state %d", state);
                return "UNKNOWN";
        }
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void setParentConnection(Connection parentConnection) {
        Log.d(this, "parenting %s to %s", this, parentConnection);
        if (mParentConnection != parentConnection) {
            if (mParentConnection != null) {
                mParentConnection.removeChild(this);
            }
            mParentConnection = parentConnection;
            if (mParentConnection != null) {
                mParentConnection.addChild(this);
                // do something if the child connections goes down to ZERO.
            }
            for (Listener l : mListeners) {
                l.onParentConnectionChanged(this, mParentConnection);
            }
        }
    }

    public final Connection getParentConnection() {
        return mParentConnection;
    }

    public final List<Connection> getChildConnections() {
        return mChildConnections;
    }

    /**
     * Returns the connection's {@link CallCapabilities}
     */
    public final int getCallCapabilities() {
        return mCallCapabilities;
    }

    /**
     * Sets the value of the {@link #getHandle()} property and notifies listeners.
     *
     * @param handle The new handle.
     */
    public final void setHandle(Uri handle) {
        Log.d(this, "setHandle %s", handle);
        mHandle = handle;
        for (Listener l : mListeners) {
            l.onHandleChanged(this, handle);
        }
    }

    /**
     * Set the features applicable to the connection.  These are items for which the InCall UI may
     * wish to display a visual indicator.
     * Features are defined in {@link android.telecomm.CallFeatures} and are passed in as a
     * bit-mask.
     *
     * @param features The features active.
     */
    public final void setFeatures(int features) {
        Log.d(this, "setFeatures %d", features);
        mFeatures = features;
        for (Listener l : mListeners) {
            l.onFeaturesChanged(this, mFeatures);
        }
    }

    /**
     * Sets state to active (e.g., an ongoing call where two or more parties can actively
     * communicate).
     */
    public final void setActive() {
        setRequestingRingback(false);
        setState(State.ACTIVE);
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing call).
     */
    public final void setRinging() {
        setState(State.RINGING);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound call).
     */
    public final void setDialing() {
        setState(State.DIALING);
    }

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        setState(State.HOLDING);
    }

    /**
     * Sets the call video provider.
     * @param callVideoProvider The call video provider.
     */
    public final void setCallVideoProvider(CallVideoProvider callVideoProvider) {
        for (Listener l : mListeners) {
            l.onSetCallVideoProvider(this, callVideoProvider);
        }
    }

    /**
     * Sets state to disconnected.
     *
     * @param cause The reason for the disconnection, any of
     *         {@link android.telephony.DisconnectCause}.
     * @param message Optional call-service-provided message about the disconnect.
     */
    public final void setDisconnected(int cause, String message) {
        setState(State.DISCONNECTED);
        Log.d(this, "Disconnected with cause %d message %s", cause, message);
        for (Listener l : mListeners) {
            l.onDisconnected(this, cause, message);
        }
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void setPostDialWait(String remaining) {
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remaining);
        }
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the call's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    public final void setRequestingRingback(boolean ringback) {
        if (mRequestingRingback != ringback) {
            mRequestingRingback = ringback;
            for (Listener l : mListeners) {
                l.onRequestingRingback(this, ringback);
            }
        }
    }

    /**
     * Sets the connection's {@link CallCapabilities}.
     *
     * @param callCapabilities The new call capabilities.
     */
    public final void setCallCapabilities(int callCapabilities) {
        if (mCallCapabilities != callCapabilities) {
            mCallCapabilities = callCapabilities;
            for (Listener l : mListeners) {
                l.onCallCapabilitiesChanged(this, mCallCapabilities);
            }
        }
    }

    /**
     * TODO(santoscordon): Needs documentation.
     */
    public final void setDestroyed() {
        // It is possible that onDestroy() will trigger the listener to remove itself which will
        // result in a concurrent modification exception. To counteract this we make a copy of the
        // listeners and iterate on that.
        for (Listener l : new ArrayList<>(mListeners)) {
            if (mListeners.contains(l)) {
                l.onDestroyed(this);
            }
        }
    }

    /**
     * Notifies this Connection and listeners of a change in the current signal levels
     * for the underlying data transport.
     *
     * @param details A {@link android.os.Bundle} containing details of the current level.
     */
    public final void setSignal(Bundle details) {
        for (Listener l : mListeners) {
            l.onSignalChanged(this, details);
        }
    }

    /**
     * Requests that the framework use VOIP audio mode for this connection.
     *
     * @param isVoip True if the audio mode is VOIP.
     */
    public final void setAudioModeIsVoip(boolean isVoip) {
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onSetAudioModeIsVoip(this, isVoip);
        }
    }

    /**
     * Sets the label and icon status to display in the in-call UI.
     *
     * @param statusHints The status label and icon to set.
     */
    public final void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onSetStatusHints(this, statusHints);
        }
    }

    /**
     * Notifies this Connection and listeners that the {@link #getCallAudioState()} property
     * has a new value.
     *
     * @param state The new call audio state.
     */
    protected void onSetAudioState(CallAudioState state) {}

    /**
     * Notifies this Connection of an internal state change. This method is called before the
     * state is actually changed.
     *
     * @param state The new state, a {@link Connection.State} member.
     */
    protected void onSetState(int state) {}

    /**
     * Notifies this Connection of a request to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    protected void onPlayDtmfTone(char c) {}

    /**
     * Notifies this Connection of a request to stop any currently playing DTMF tones.
     */
    protected void onStopDtmfTone() {}

    /**
     * Notifies this Connection of a request to disconnect.
     */
    protected void onDisconnect() {}

    /**
     * Notifies this Connection of a request to disconnect.
     */
    protected void onSeparate() {}

    /**
     * Notifies this Connection of a request to abort.
     */
    protected void onAbort() {}

    /**
     * Notifies this Connection of a request to hold.
     */
    protected void onHold() {}

    /**
     * Notifies this Connection of a request to exit a hold state.
     */
    protected void onUnhold() {}

    /**
     * Notifies this Connection, which is in {@link State#RINGING}, of
     * a request to accept.
     */
    protected void onAnswer() {}

    /**
     * Notifies this Connection, which is in {@link State#RINGING}, of
     * a request to reject.
     */
    protected void onReject() {}

    /**
     * Notifies this Connection whether the user wishes to proceed with the post-dial DTMF codes.
     */
    protected void onPostDialContinue(boolean proceed) {}

    /**
     * TODO(santoscordon): Needs documentation.
     */
    protected void onChildrenChanged(List<Connection> children) {}

    /**
     * Called when the phone account UI was clicked.
     */
    protected void onPhoneAccountClicked() {}


    private void addChild(Connection connection) {
        Log.d(this, "adding child %s", connection);
        mChildConnections.add(connection);
        onChildrenChanged(mChildConnections);
    }

    private void removeChild(Connection connection) {
        Log.d(this, "removing child %s", connection);
        mChildConnections.remove(connection);
        onChildrenChanged(mChildConnections);
    }

    private void setState(int state) {
        Log.d(this, "setState: %s", stateToString(state));
        this.mState = state;
        for (Listener l : mListeners) {
            l.onStateChanged(this, state);
        }
        onSetState(state);
    }
}
