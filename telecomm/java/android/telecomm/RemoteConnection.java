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

import android.app.PendingIntent;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import com.android.internal.telecomm.IConnectionService;

import java.util.HashSet;
import java.util.Set;

/**
 * RemoteConnection object used by RemoteConnectionService.
 */
public final class RemoteConnection {
    public static abstract class Listener {
        public void onStateChanged(RemoteConnection connection, int state) {}
        public void onDisconnected(RemoteConnection connection, int cause, String message) {}
        public void onRequestingRingback(RemoteConnection connection, boolean ringback) {}
        public void onCallCapabilitiesChanged(RemoteConnection connection, int callCapabilities) {}
        public void onPostDialWait(RemoteConnection connection, String remainingDigits) {}
        public void onAudioModeIsVoipChanged(RemoteConnection connection, boolean isVoip) {}
        public void onStatusHintsChanged(RemoteConnection connection, StatusHints statusHints) {}
        public void onHandleChanged(RemoteConnection connection, Uri handle, int presentation) {}
        public void onCallerDisplayNameChanged(
                RemoteConnection connection, String callerDisplayName, int presentation) {}
        public void onVideoStateChanged(RemoteConnection connection, int videoState) {}
        public void onStartActivityFromInCall(RemoteConnection connection, PendingIntent intent) {}
        public void onDestroyed(RemoteConnection connection) {}
    }

    private IConnectionService mConnectionService;
    private final String mConnectionId;
    private final Set<Listener> mListeners = new HashSet<>();

    private int mState = Connection.State.NEW;
    private int mDisconnectCause = DisconnectCause.NOT_VALID;
    private String mDisconnectMessage;
    private boolean mRequestingRingback;
    private boolean mConnected;
    private int mCallCapabilities;
    private int mVideoState;
    private boolean mAudioModeIsVoip;
    private StatusHints mStatusHints;
    private Uri mHandle;
    private int mHandlePresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private int mFailureCode;
    private String mFailureMessage;

    /**
     * @hide
     */
    RemoteConnection(IConnectionService connectionService, ConnectionRequest request,
            boolean isIncoming) {
        mConnectionService = connectionService;
        mConnectionId = request.getCallId();

        mConnected = true;
        mState = Connection.State.INITIALIZING;
    }

    /**
     * Create a RemoteConnection which is used for failed connections. Note that using it for any
     * "real" purpose will almost certainly fail. Callers should note the failure and act
     * accordingly (moving on to another RemoteConnection, for example)
     *
     * @param failureCode
     * @param failureMessage
     */
    private RemoteConnection(int failureCode, String failureMessage) {
        this(null, null, true);
        mConnected = false;
        mState = Connection.State.FAILED;
        mFailureCode = failureCode;
        mFailureMessage = failureMessage;
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public int getState() {
        return mState;
    }

    public int getDisconnectCause() {
        return mDisconnectCause;
    }

    public String getDisconnectMessage() {
        return mDisconnectMessage;
    }

    public int getCallCapabilities() {
        return mCallCapabilities;
    }

    public boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    public Uri getHandle() {
        return mHandle;
    }

    public int getHandlePresentation() {
        return mHandlePresentation;
    }

    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    public int getVideoState() {
        return mVideoState;
    }

    public int getFailureCode() {
        return mFailureCode;
    }

    public String getFailureMessage() {
        return mFailureMessage;
    }

    public void abort() {
        try {
            if (mConnected) {
                mConnectionService.abort(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void answer(int videoState) {
        try {
            if (mConnected) {
                mConnectionService.answer(mConnectionId, videoState);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void reject() {
        try {
            if (mConnected) {
                mConnectionService.reject(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void hold() {
        try {
            if (mConnected) {
                mConnectionService.hold(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void unhold() {
        try {
            if (mConnected) {
                mConnectionService.unhold(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void disconnect() {
        try {
            if (mConnected) {
                mConnectionService.disconnect(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void playDtmf(char digit) {
        try {
            if (mConnected) {
                mConnectionService.playDtmfTone(mConnectionId, digit);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void stopDtmf() {
        try {
            if (mConnected) {
                mConnectionService.stopDtmfTone(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void postDialContinue(boolean proceed) {
        try {
            if (mConnected) {
                mConnectionService.onPostDialContinue(mConnectionId, proceed);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void swapWithBackgroundCall() {
        try {
            if (mConnected) {
                mConnectionService.swapWithBackgroundCall(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void setAudioState(CallAudioState state) {
        try {
            if (mConnected) {
                mConnectionService.onAudioStateChanged(mConnectionId, state);
            }
        } catch (RemoteException ignored) {
        }
    }

    /**
     * @hide
     */
    void setState(int state) {
        if (mState != state) {
            mState = state;
            for (Listener l: mListeners) {
                l.onStateChanged(this, state);
            }
        }
    }

    /**
     * @hide
     */
    void setDisconnected(int cause, String message) {
        if (mState != Connection.State.DISCONNECTED) {
            mState = Connection.State.DISCONNECTED;
            mDisconnectCause = cause;
            mDisconnectMessage = message;

            for (Listener l : mListeners) {
                l.onDisconnected(this, cause, message);
            }
        }
    }

    /**
     * @hide
     */
    void setRequestingRingback(boolean ringback) {
        if (mRequestingRingback != ringback) {
            mRequestingRingback = ringback;
            for (Listener l : mListeners) {
                l.onRequestingRingback(this, ringback);
            }
        }
    }

    /**
     * @hide
     */
    void setCallCapabilities(int callCapabilities) {
        mCallCapabilities = callCapabilities;
        for (Listener l : mListeners) {
            l.onCallCapabilitiesChanged(this, callCapabilities);
        }
    }

    /**
     * @hide
     */
    void setDestroyed() {
        if (!mListeners.isEmpty()) {
            // Make sure that the listeners are notified that the call is destroyed first.
            if (mState != Connection.State.DISCONNECTED) {
                setDisconnected(DisconnectCause.ERROR_UNSPECIFIED, "Connection destroyed.");
            }

            Set<Listener> listeners = new HashSet<Listener>(mListeners);
            mListeners.clear();
            for (Listener l : listeners) {
                l.onDestroyed(this);
            }

            mConnected = false;
        }
    }

    /**
     * @hide
     */
    void setPostDialWait(String remainingDigits) {
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remainingDigits);
        }
    }

    /**
     * @hide
     */
    void setVideoState(int videoState) {
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, videoState);
        }
    }

    /** @hide */
    void setAudioModeIsVoip(boolean isVoip) {
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onAudioModeIsVoipChanged(this, isVoip);
        }
    }

    /** @hide */
    void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this, statusHints);
        }
    }

    /** @hide */
    void setHandle(Uri handle, int presentation) {
        mHandle = handle;
        mHandlePresentation = presentation;
        for (Listener l : mListeners) {
            l.onHandleChanged(this, handle, presentation);
        }
    }

    /** @hide */
    void setCallerDisplayName(String callerDisplayName, int presentation) {
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Listener l : mListeners) {
            l.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /** @hide */
    void startActivityFromInCall(PendingIntent intent) {
        for (Listener l : mListeners) {
            l.onStartActivityFromInCall(this, intent);
        }
    }

    /** @hide */
    void setConnectionService(IConnectionService connectionService) {
        mConnectionService = connectionService;
        mConnectionService = null;
        setState(Connection.State.NEW);
    }

    /**
     * Create a RemoteConnection which is in the {@link Connection.State#FAILED} state. Attempting
     * to use it for anything will almost certainly result in bad things happening. Do not do this.
     *
     * @return a failed {@link RemoteConnection}
     *
     * @hide
     *
     */
    public static RemoteConnection failure(int failureCode, String failureMessage) {
        return new RemoteConnection(failureCode, failureMessage);
    }
}
