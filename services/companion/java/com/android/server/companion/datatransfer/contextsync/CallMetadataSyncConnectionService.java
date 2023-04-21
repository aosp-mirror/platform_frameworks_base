/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import android.content.ComponentName;
import android.media.AudioManager;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Service for Telecom to bind to when call metadata is synced between devices. */
public class CallMetadataSyncConnectionService extends ConnectionService {

    private static final String TAG = "CallMetadataSyncConnectionService";

    private AudioManager mAudioManager;
    private TelecomManager mTelecomManager;
    private final Map<PhoneAccountHandleIdentifier, PhoneAccountHandle> mPhoneAccountHandles =
            new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();

        mAudioManager = getSystemService(AudioManager.class);
        mTelecomManager = getSystemService(TelecomManager.class);
    }

    /**
     * Registers a {@link android.telecom.PhoneAccount} for a given call-capable app on the synced
     * device.
     */
    private void registerPhoneAccount(int associationId, String appIdentifier,
            String humanReadableAppName) {
        final PhoneAccountHandleIdentifier phoneAccountHandleIdentifier =
                new PhoneAccountHandleIdentifier(associationId, appIdentifier);
        final PhoneAccount phoneAccount = createPhoneAccount(phoneAccountHandleIdentifier,
                humanReadableAppName);
        mTelecomManager.registerPhoneAccount(phoneAccount);
        mTelecomManager.enablePhoneAccount(mPhoneAccountHandles.get(phoneAccountHandleIdentifier),
                true);
    }

    /**
     * Unregisters a {@link android.telecom.PhoneAccount} for a given call-capable app on the synced
     * device.
     */
    private void unregisterPhoneAccount(int associationId, String appIdentifier) {
        mTelecomManager.unregisterPhoneAccount(mPhoneAccountHandles.remove(
                new PhoneAccountHandleIdentifier(associationId, appIdentifier)));
    }

    @VisibleForTesting
    PhoneAccount createPhoneAccount(PhoneAccountHandleIdentifier phoneAccountHandleIdentifier,
            String humanReadableAppName) {
        if (mPhoneAccountHandles.containsKey(phoneAccountHandleIdentifier)) {
            // Already exists!
            return null;
        }
        final PhoneAccountHandle handle = new PhoneAccountHandle(
                new ComponentName(this, CallMetadataSyncConnectionService.class),
                UUID.randomUUID().toString());
        mPhoneAccountHandles.put(phoneAccountHandleIdentifier, handle);
        return new PhoneAccount.Builder(handle, humanReadableAppName)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER
                        | PhoneAccount.CAPABILITY_SELF_MANAGED).build();
    }

    static final class PhoneAccountHandleIdentifier {
        private final int mAssociationId;
        private final String mAppIdentifier;

        PhoneAccountHandleIdentifier(int associationId, String appIdentifier) {
            mAssociationId = associationId;
            mAppIdentifier = appIdentifier;
        }

        public int getAssociationId() {
            return mAssociationId;
        }

        public String getAppIdentifier() {
            return mAppIdentifier;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAssociationId, mAppIdentifier);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PhoneAccountHandleIdentifier) {
                return ((PhoneAccountHandleIdentifier) other).getAssociationId() == mAssociationId
                        && mAppIdentifier != null
                        && mAppIdentifier.equals(
                        ((PhoneAccountHandleIdentifier) other).getAppIdentifier());
            }
            return false;
        }
    }

    private static final class CallMetadataSyncConnectionIdentifier {
        private final int mAssociationId;
        private final long mCallId;

        CallMetadataSyncConnectionIdentifier(int associationId, long callId) {
            mAssociationId = associationId;
            mCallId = callId;
        }

        public int getAssociationId() {
            return mAssociationId;
        }

        public long getCallId() {
            return mCallId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAssociationId, mCallId);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof CallMetadataSyncConnectionIdentifier) {
                return ((CallMetadataSyncConnectionIdentifier) other).getAssociationId()
                        == mAssociationId
                        && (((CallMetadataSyncConnectionIdentifier) other).getCallId() == mCallId);
            }
            return false;
        }
    }

    private abstract static class CallMetadataSyncConnectionCallback {

        abstract void sendCallAction(int associationId, long callId, int action);

        abstract void sendStateChange(int associationId, long callId, int newState);
    }

    private static class CallMetadataSyncConnection extends Connection {

        private final TelecomManager mTelecomManager;
        private final AudioManager mAudioManager;
        private final int mAssociationId;
        private final CallMetadataSyncData.Call mCall;
        private final CallMetadataSyncConnectionCallback mCallback;

        CallMetadataSyncConnection(TelecomManager telecomManager, AudioManager audioManager,
                int associationId, CallMetadataSyncData.Call call,
                CallMetadataSyncConnectionCallback callback) {
            mTelecomManager = telecomManager;
            mAudioManager = audioManager;
            mAssociationId = associationId;
            mCall = call;
            mCallback = callback;
        }

        public long getCallId() {
            return mCall.getId();
        }

        public void initialize() {
            final int status = mCall.getStatus();
            if (status == android.companion.Telecom.Call.RINGING_SILENCED) {
                mTelecomManager.silenceRinger();
            }
            final int state = CrossDeviceCall.convertStatusToState(status);
            if (state == Call.STATE_RINGING) {
                setRinging();
            } else if (state == Call.STATE_ACTIVE) {
                setActive();
            } else if (state == Call.STATE_HOLDING) {
                setOnHold();
            } else {
                Slog.e(TAG, "Could not initialize call to unknown state");
            }

            final Bundle extras = new Bundle();
            extras.putLong(CrossDeviceCall.EXTRA_CALL_ID, mCall.getId());
            putExtras(extras);

            int capabilities = getConnectionCapabilities();
            if (mCall.hasControl(android.companion.Telecom.Call.PUT_ON_HOLD)) {
                capabilities |= CAPABILITY_HOLD;
            } else {
                capabilities &= ~CAPABILITY_HOLD;
            }
            if (mCall.hasControl(android.companion.Telecom.Call.MUTE)) {
                capabilities |= CAPABILITY_MUTE;
            } else {
                capabilities &= ~CAPABILITY_MUTE;
            }
            mAudioManager.setMicrophoneMute(
                    mCall.hasControl(android.companion.Telecom.Call.UNMUTE));
            if (capabilities != getConnectionCapabilities()) {
                setConnectionCapabilities(capabilities);
            }
        }

        public void update(CallMetadataSyncData.Call call) {
            final int status = call.getStatus();
            if (status == android.companion.Telecom.Call.RINGING_SILENCED
                    && mCall.getStatus() != android.companion.Telecom.Call.RINGING_SILENCED) {
                mTelecomManager.silenceRinger();
            }
            mCall.setStatus(status);
            final int state = CrossDeviceCall.convertStatusToState(status);
            if (state != getState()) {
                if (state == Call.STATE_RINGING) {
                    setRinging();
                } else if (state == Call.STATE_ACTIVE) {
                    setActive();
                } else if (state == Call.STATE_HOLDING) {
                    setOnHold();
                } else {
                    Slog.e(TAG, "Could not update call to unknown state");
                }
            }

            int capabilities = getConnectionCapabilities();
            final boolean hasHoldControl = mCall.hasControl(
                    android.companion.Telecom.Call.PUT_ON_HOLD)
                    || mCall.hasControl(android.companion.Telecom.Call.TAKE_OFF_HOLD);
            if (hasHoldControl != ((getConnectionCapabilities() & CAPABILITY_HOLD)
                    == CAPABILITY_HOLD)) {
                if (hasHoldControl) {
                    capabilities |= CAPABILITY_HOLD;
                } else {
                    capabilities &= ~CAPABILITY_HOLD;
                }
            }
            final boolean hasMuteControl = mCall.hasControl(android.companion.Telecom.Call.MUTE);
            if (hasMuteControl != ((getConnectionCapabilities() & CAPABILITY_MUTE)
                    == CAPABILITY_MUTE)) {
                if (hasMuteControl) {
                    capabilities |= CAPABILITY_MUTE;
                } else {
                    capabilities &= ~CAPABILITY_MUTE;
                }
            }
            mAudioManager.setMicrophoneMute(
                    mCall.hasControl(android.companion.Telecom.Call.UNMUTE));
            if (capabilities != getConnectionCapabilities()) {
                setConnectionCapabilities(capabilities);
            }
        }

        @Override
        public void onAnswer(int videoState) {
            sendCallAction(android.companion.Telecom.Call.ACCEPT);
        }

        @Override
        public void onReject() {
            sendCallAction(android.companion.Telecom.Call.REJECT);
        }

        @Override
        public void onReject(int rejectReason) {
            onReject();
        }

        @Override
        public void onReject(String replyMessage) {
            onReject();
        }

        @Override
        public void onSilence() {
            sendCallAction(android.companion.Telecom.Call.SILENCE);
        }

        @Override
        public void onHold() {
            sendCallAction(android.companion.Telecom.Call.PUT_ON_HOLD);
        }

        @Override
        public void onUnhold() {
            sendCallAction(android.companion.Telecom.Call.TAKE_OFF_HOLD);
        }

        @Override
        public void onMuteStateChanged(boolean isMuted) {
            sendCallAction(isMuted ? android.companion.Telecom.Call.MUTE
                    : android.companion.Telecom.Call.UNMUTE);
        }

        @Override
        public void onDisconnect() {
            sendCallAction(android.companion.Telecom.Call.END);
        }

        @Override
        public void onStateChanged(int state) {
            mCallback.sendStateChange(mAssociationId, mCall.getId(), state);
        }

        private void sendCallAction(int action) {
            mCallback.sendCallAction(mAssociationId, mCall.getId(), action);
        }
    }
}
