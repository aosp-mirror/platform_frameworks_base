/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.audio;

import android.media.AudioManager;
import android.media.AudioSystem;

import com.android.server.audio.AudioDeviceInventory.WiredDeviceConnectionState;


public class AudioServiceEvents {

    final static class PhoneStateEvent extends AudioEventLogger.Event {
        final String mPackage;
        final int mOwnerPid;
        final int mRequesterPid;
        final int mRequestedMode;
        final int mActualMode;

        PhoneStateEvent(String callingPackage, int requesterPid, int requestedMode,
                        int ownerPid, int actualMode) {
            mPackage = callingPackage;
            mRequesterPid = requesterPid;
            mRequestedMode = requestedMode;
            mOwnerPid = ownerPid;
            mActualMode = actualMode;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("setMode(").append(AudioSystem.modeToString(mRequestedMode))
                    .append(") from package=").append(mPackage)
                    .append(" pid=").append(mRequesterPid)
                    .append(" selected mode=").append(AudioSystem.modeToString(mActualMode))
                    .append(" by pid=").append(mOwnerPid).toString();
        }
    }

    final static class WiredDevConnectEvent extends AudioEventLogger.Event {
        final WiredDeviceConnectionState mState;

        WiredDevConnectEvent(WiredDeviceConnectionState state) {
            mState = state;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("setWiredDeviceConnectionState(")
                    .append(" type:").append(Integer.toHexString(mState.mType))
                    .append(" state:").append(AudioSystem.deviceStateToString(mState.mState))
                    .append(" addr:").append(mState.mAddress)
                    .append(" name:").append(mState.mName)
                    .append(") from ").append(mState.mCaller).toString();
        }
    }

    final static class ForceUseEvent extends AudioEventLogger.Event {
        final int mUsage;
        final int mConfig;
        final String mReason;

        ForceUseEvent(int usage, int config, String reason) {
            mUsage = usage;
            mConfig = config;
            mReason = reason;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("setForceUse(")
                    .append(AudioSystem.forceUseUsageToString(mUsage))
                    .append(", ").append(AudioSystem.forceUseConfigToString(mConfig))
                    .append(") due to ").append(mReason).toString();
        }
    }

    final static class VolumeEvent extends AudioEventLogger.Event {
        static final int VOL_ADJUST_SUGG_VOL = 0;
        static final int VOL_ADJUST_STREAM_VOL = 1;
        static final int VOL_SET_STREAM_VOL = 2;
        static final int VOL_SET_HEARING_AID_VOL = 3;
        static final int VOL_SET_AVRCP_VOL = 4;
        static final int VOL_ADJUST_VOL_UID = 5;
        static final int VOL_VOICE_ACTIVITY_HEARING_AID = 6;
        static final int VOL_MODE_CHANGE_HEARING_AID = 7;

        final int mOp;
        final int mStream;
        final int mVal1;
        final int mVal2;
        final String mCaller;

        /** used for VOL_ADJUST_VOL_UID,
         *           VOL_ADJUST_SUGG_VOL,
         *           VOL_ADJUST_STREAM_VOL,
         *           VOL_SET_STREAM_VOL */
        VolumeEvent(int op, int stream, int val1, int val2, String caller) {
            mOp = op;
            mStream = stream;
            mVal1 = val1;
            mVal2 = val2;
            mCaller = caller;
        }

        /** used for VOL_SET_HEARING_AID_VOL*/
        VolumeEvent(int op, int index, int gainDb) {
            mOp = op;
            mVal1 = index;
            mVal2 = gainDb;
            // unused
            mStream = -1;
            mCaller = null;
        }

        /** used for VOL_SET_AVRCP_VOL */
        VolumeEvent(int op, int index) {
            mOp = op;
            mVal1 = index;
            // unused
            mVal2 = 0;
            mStream = -1;
            mCaller = null;
        }

        /** used for VOL_VOICE_ACTIVITY_HEARING_AID */
        VolumeEvent(int op, boolean voiceActive, int stream, int index) {
            mOp = op;
            mStream = stream;
            mVal1 = index;
            mVal2 = voiceActive ? 1 : 0;
            // unused
            mCaller = null;
        }

        /** used for VOL_MODE_CHANGE_HEARING_AID */
        VolumeEvent(int op, int mode, int stream, int index) {
            mOp = op;
            mStream = stream;
            mVal1 = index;
            mVal2 = mode;
            // unused
            mCaller = null;
        }

        @Override
        public String eventToString() {
            switch (mOp) {
                case VOL_ADJUST_SUGG_VOL:
                    return new StringBuilder("adjustSuggestedStreamVolume(sugg:")
                            .append(AudioSystem.streamToString(mStream))
                            .append(" dir:").append(AudioManager.adjustToString(mVal1))
                            .append(" flags:0x").append(Integer.toHexString(mVal2))
                            .append(") from ").append(mCaller)
                            .toString();
                case VOL_ADJUST_STREAM_VOL:
                    return new StringBuilder("adjustStreamVolume(stream:")
                            .append(AudioSystem.streamToString(mStream))
                            .append(" dir:").append(AudioManager.adjustToString(mVal1))
                            .append(" flags:0x").append(Integer.toHexString(mVal2))
                            .append(") from ").append(mCaller)
                            .toString();
                case VOL_SET_STREAM_VOL:
                    return new StringBuilder("setStreamVolume(stream:")
                            .append(AudioSystem.streamToString(mStream))
                            .append(" index:").append(mVal1)
                            .append(" flags:0x").append(Integer.toHexString(mVal2))
                            .append(") from ").append(mCaller)
                            .toString();
                case VOL_SET_HEARING_AID_VOL:
                    return new StringBuilder("setHearingAidVolume:")
                            .append(" index:").append(mVal1)
                            .append(" gain dB:").append(mVal2)
                            .toString();
                case VOL_SET_AVRCP_VOL:
                    return new StringBuilder("setAvrcpVolume:")
                            .append(" index:").append(mVal1)
                            .toString();
                case VOL_ADJUST_VOL_UID:
                    return new StringBuilder("adjustStreamVolumeForUid(stream:")
                            .append(AudioSystem.streamToString(mStream))
                            .append(" dir:").append(AudioManager.adjustToString(mVal1))
                            .append(" flags:0x").append(Integer.toHexString(mVal2))
                            .append(") from ").append(mCaller)
                            .toString();
                case VOL_VOICE_ACTIVITY_HEARING_AID:
                    return new StringBuilder("Voice activity change (")
                            .append(mVal2 == 1 ? "active" : "inactive")
                            .append(") causes setting HEARING_AID volume to idx:").append(mVal1)
                            .append(" stream:").append(AudioSystem.streamToString(mStream))
                            .toString();
                case VOL_MODE_CHANGE_HEARING_AID:
                    return new StringBuilder("setMode(")
                            .append(AudioSystem.modeToString(mVal2))
                            .append(") causes setting HEARING_AID volume to idx:").append(mVal1)
                            .append(" stream:").append(AudioSystem.streamToString(mStream))
                            .toString();
                default: return new StringBuilder("FIXME invalid op:").append(mOp).toString();
            }
        }
    }
}
