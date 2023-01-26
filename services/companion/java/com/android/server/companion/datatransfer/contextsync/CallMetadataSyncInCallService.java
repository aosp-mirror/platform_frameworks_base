/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.Nullable;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.TelecomManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.companion.CompanionDeviceConfig;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * In-call service to sync call metadata across a user's devices. Note that mute and silence are
 * global states and apply to all current calls.
 */
public class CallMetadataSyncInCallService extends InCallService {

    private static final long NOT_VALID = -1L;

    @VisibleForTesting
    final Map<Call, CrossDeviceCall> mCurrentCalls = new HashMap<>();
    final Call.Callback mTelecomCallback = new Call.Callback() {
        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            mCurrentCalls.get(call).updateCallDetails(details);
        }
    };
    final CallMetadataSyncCallback mCallMetadataSyncCallback = new CallMetadataSyncCallback() {
        @Override
        void processCallControlAction(int crossDeviceCallId, int callControlAction) {
            final CrossDeviceCall crossDeviceCall = getCallForId(crossDeviceCallId,
                    mCurrentCalls.values());
            switch (callControlAction) {
                case android.companion.Telecom.Call.ACCEPT:
                    if (crossDeviceCall != null) {
                        crossDeviceCall.doAccept();
                    }
                    break;
                case android.companion.Telecom.Call.REJECT:
                    if (crossDeviceCall != null) {
                        crossDeviceCall.doReject();
                    }
                    break;
                case android.companion.Telecom.Call.SILENCE:
                    doSilence();
                    break;
                case android.companion.Telecom.Call.MUTE:
                    doMute();
                    break;
                case android.companion.Telecom.Call.UNMUTE:
                    doUnmute();
                    break;
                case android.companion.Telecom.Call.END:
                    if (crossDeviceCall != null) {
                        crossDeviceCall.doEnd();
                    }
                    break;
                case android.companion.Telecom.Call.PUT_ON_HOLD:
                    if (crossDeviceCall != null) {
                        crossDeviceCall.doPutOnHold();
                    }
                    break;
                case android.companion.Telecom.Call.TAKE_OFF_HOLD:
                    if (crossDeviceCall != null) {
                        crossDeviceCall.doTakeOffHold();
                    }
                    break;
                default:
            }
        }

        @Override
        void requestCrossDeviceSync(int userId) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCurrentCalls.putAll(getCalls().stream().collect(Collectors.toMap(call -> call,
                    call -> new CrossDeviceCall(getPackageManager(), call, getCallAudioState()))));
        }
    }

    @Nullable
    @VisibleForTesting
    CrossDeviceCall getCallForId(long crossDeviceCallId, Collection<CrossDeviceCall> calls) {
        if (crossDeviceCallId == NOT_VALID) {
            return null;
        }
        for (CrossDeviceCall crossDeviceCall : calls) {
            if (crossDeviceCall.getId() == crossDeviceCallId) {
                return crossDeviceCall;
            }
        }
        return null;
    }

    @Override
    public void onCallAdded(Call call) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCurrentCalls.put(call,
                    new CrossDeviceCall(getPackageManager(), call, getCallAudioState()));
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCurrentCalls.remove(call);
        }
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCurrentCalls.values().forEach(call -> call.updateMuted(isMuted));
        }
    }

    @Override
    public void onSilenceRinger() {
        if (CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            mCurrentCalls.values().forEach(call -> call.updateSilencedIfRinging());
        }
    }

    private void doMute() {
        setMuted(/* shouldMute= */ true);
    }

    private void doUnmute() {
        setMuted(/* shouldMute= */ false);
    }

    private void doSilence() {
        final TelecomManager telecomManager = getSystemService(TelecomManager.class);
        if (telecomManager != null) {
            telecomManager.silenceRinger();
        }
    }
}