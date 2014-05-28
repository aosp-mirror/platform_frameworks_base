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

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a connection to a remote endpoint that carries voice traffic.
 */
public abstract class Connection {

    public interface Listener {
        void onStateChanged(Connection c, int state);
        void onAudioStateChanged(Connection c, CallAudioState state);
        void onHandleChanged(Connection c, Uri newHandle);
        void onSignalChanged(Connection c, Bundle details);
        void onDisconnected(Connection c, int cause, String message);
        void onRequestingRingback(Connection c, boolean ringback);
        void onDestroyed(Connection c);
    }

    public static class ListenerBase implements Listener {
        /** {@inheritDoc} */
        @Override
        public void onStateChanged(Connection c, int state) {}

        /** {@inheritDoc} */
         @Override
        public void onAudioStateChanged(Connection c, CallAudioState state) {}

        /** {@inheritDoc} */
        @Override
        public void onHandleChanged(Connection c, Uri newHandle) {}

        /** {@inheritDoc} */
        @Override
        public void onSignalChanged(Connection c, Bundle details) {}

        /** {@inheritDoc} */
        @Override
        public void onDisconnected(Connection c, int cause, String message) {}

        /** {@inheritDoc} */
        @Override
        public void onDestroyed(Connection c) {}

        /** {@inheritDoc} */
        @Override
        public void onRequestingRingback(Connection c, boolean ringback) {}
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
    private int mState = State.NEW;
    private CallAudioState mCallAudioState;
    private Uri mHandle;
    private boolean mRequestingRingback = false;

    /**
     * Create a new Connection.
     */
    protected Connection() {}

    /**
     * @return The handle (e.g., phone number) to which this Connection
     *         is currently communicating.
     */
    public final Uri getHandle() {
        return mHandle;
    }

    /**
     * @return The state of this Connection.
     *
     * @hide
     */
    public final int getState() {
        return mState;
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
     * Play a DTMF tone in this Connection.
     *
     * @param c A DTMF character.
     *
     * @hide
     */
    public final void playDtmfTone(char c) {
        Log.d(this, "playDtmfTone %c", c);
        onPlayDtmfTone(c);
    }

    /**
     * Stop any DTMF tones which may be playing in this Connection.
     *
     * @hide
     */
    public final void stopDtmfTone() {
        Log.d(this, "stopDtmfTone");
        onStopDtmfTone();
    }

    /**
     * Disconnect this Connection. If and when the Connection can comply with
     * this request, it will transition to the {@link State#DISCONNECTED}
     * state and notify its listeners.
     *
     * @hide
     */
    public final void disconnect() {
        Log.d(this, "disconnect");
        onDisconnect();
    }

    /**
     * Abort this Connection. The Connection will immediately transition to
     * the {@link State#DISCONNECTED} state, and send no notifications of this
     * or any other future events.
     *
     * @hide
     */
    public final void abort() {
        Log.d(this, "abort");
        onAbort();
    }

    /**
     * Place this Connection on hold. If and when the Connection can comply with
     * this request, it will transition to the {@link State#HOLDING}
     * state and notify its listeners.
     *
     * @hide
     */
    public final void hold() {
        Log.d(this, "hold");
        onHold();
    }

    /**
     * Un-hold this Connection. If and when the Connection can comply with
     * this request, it will transition to the {@link State#ACTIVE}
     * state and notify its listeners.
     *
     * @hide
     */
    public final void unhold() {
        Log.d(this, "unhold");
        onUnhold();
    }

    /**
     * Accept a {@link State#RINGING} Connection. If and when the Connection
     * can comply with this request, it will transition to the {@link State#ACTIVE}
     * state and notify its listeners.
     *
     * @hide
     */
    public final void answer() {
        Log.d(this, "answer");
        if (mState == State.RINGING) {
            onAnswer();
        }
    }

    /**
     * Reject a {@link State#RINGING} Connection. If and when the Connection
     * can comply with this request, it will transition to the {@link State#ACTIVE}
     * state and notify its listeners.
     *
     * @hide
     */
    public final void reject() {
        Log.d(this, "reject");
        if (mState == State.RINGING) {
            onReject();
        }
    }

    /**
     * Inform this Connection that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     */
    public void setAudioState(CallAudioState state) {
        Log.d(this, "setAudioState %s", state);
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
     * @return Whether this connection is requesting that the system play a ringback tone
     * on its behalf.
     */
    public boolean isRequestingRingback() {
        return mRequestingRingback;
    }

    /**
     * Sets the value of the {@link #getHandle()} property and notifies listeners.
     *
     * @param handle The new handle.
     */
    protected void setHandle(Uri handle) {
        Log.d(this, "setHandle %s", handle);
        // TODO: Enforce super called
        mHandle = handle;
        for (Listener l : mListeners) {
            l.onHandleChanged(this, handle);
        }
    }

    /**
     * Sets state to active (e.g., an ongoing call where two or more parties can actively
     * communicate).
     */
    protected void setActive() {
        setRequestingRingback(false);
        setState(State.ACTIVE);
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing call).
     */
    protected void setRinging() {
        setState(State.RINGING);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound call).
     */
    protected void setDialing() {
        setState(State.DIALING);
    }

    /**
     * Sets state to be on hold.
     */
    protected void setOnHold() {
        setState(State.HOLDING);
    }

    /**
     * Sets state to disconnected. This will first notify listeners with an
     * {@link Listener#onStateChanged(Connection, int)} event, then will fire an
     * {@link Listener#onDisconnected(Connection, int, String)} event with additional
     * details.
     *
     * @param cause The reason for the disconnection, any of
     *         {@link android.telephony.DisconnectCause}.
     * @param message Optional call-service-provided message about the disconnect.
     */
    protected void setDisconnected(int cause, String message) {
        setState(State.DISCONNECTED);
        Log.d(this, "Disconnected with cause %d message %s", cause, message);
        for (Listener l : mListeners) {
            l.onDisconnected(this, cause, message);
        }
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the call's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    protected void setRequestingRingback(boolean ringback) {
        if (mRequestingRingback != ringback) {
            mRequestingRingback = ringback;
            for (Listener l : mListeners) {
                l.onRequestingRingback(this, ringback);
            }
        }
    }

    /**
     * Notifies this Connection and listeners that the {@link #getCallAudioState()} property
     * has a new value.
     *
     * @param state The new call audio state.
     */
    protected void onSetAudioState(CallAudioState state) {
        // TODO: Enforce super called
        mCallAudioState = state;
        for (Listener l : mListeners) {
            l.onAudioStateChanged(this, state);
        }
    }

    /**
     * Notifies this Connection and listeners of a change in the current signal levels
     * for the underlying data transport.
     *
     * @param details A {@link android.os.Bundle} containing details of the current level.
     */
    protected void onSetSignal(Bundle details) {
        // TODO: Enforce super called
        for (Listener l : mListeners) {
            l.onSignalChanged(this, details);
        }
    }

    /**
     * Notifies this Connection of an internal state change. This method is called before the
     * state is actually changed. Overriding implementations must call
     * {@code super.onSetState(state)}.
     *
     * @param state The new state, a {@link Connection.State} member.
     */
    protected void onSetState(int state) {
        // TODO: Enforce super called
        this.mState = state;
        for (Listener l : mListeners) {
            l.onStateChanged(this, state);
        }
    }

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

    private void setState(int state) {
        Log.d(this, "setState: %s", stateToString(state));
        onSetState(state);
    }
}
