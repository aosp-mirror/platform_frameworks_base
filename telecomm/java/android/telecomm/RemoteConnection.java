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
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import com.android.internal.telecomm.IConnectionService;

import java.util.HashSet;
import java.util.Set;

/**
 * RemoteConnection object used by RemoteConnectionService.
 */
public final class RemoteConnection {
    public interface Listener {
        void onStateChanged(RemoteConnection connection, int state);
        void onDisconnected(RemoteConnection connection, int cause, String message);
        void onRequestingRingback(RemoteConnection connection, boolean ringback);
        void onPostDialWait(RemoteConnection connection, String remainingDigits);
        void onFeaturesChanged(RemoteConnection connection, int features);
        void onSetAudioModeIsVoip(RemoteConnection connection, boolean isVoip);
        void onDestroyed(RemoteConnection connection);
    }

    private final IConnectionService mConnectionService;
    private final String mConnectionId;
    private final Set<Listener> mListeners = new HashSet<>();

    private int mState = Connection.State.NEW;
    private int mDisconnectCause = DisconnectCause.NOT_VALID;
    private String mDisconnectMessage;
    private boolean mRequestingRingback;
    private boolean mConnected;
    private int mFeatures;
    private boolean mAudioModeIsVoip;

    /**
     * @hide
     */
    RemoteConnection(IConnectionService connectionService, String connectionId) {
        mConnectionService = connectionService;
        mConnectionId = connectionId;

        mConnected = true;
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

    public int getFeatures() {
        return mFeatures;
    }

    public boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    public void abort() {
        try {
            if (mConnected) {
                mConnectionService.abort(mConnectionId);
            }
        } catch (RemoteException ignored) {
        }
    }

    public void answer() {
        try {
            if (mConnected) {
                mConnectionService.answer(mConnectionId);
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
    void setFeatures(int features) {
        mFeatures = features;
        for (Listener l : mListeners) {
            l.onFeaturesChanged(this, features);
        }
    }

    /** @hide */
    void setAudioModeIsVoip(boolean isVoip) {
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onSetAudioModeIsVoip(this, isVoip);
        }
    }
}
