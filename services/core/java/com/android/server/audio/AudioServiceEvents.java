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

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.MediaMetrics;

import com.android.server.audio.AudioDeviceInventory.WiredDeviceConnectionState;


public class AudioServiceEvents {

    final static class PhoneStateEvent extends AudioEventLogger.Event {
        static final int MODE_SET = 0;
        static final int MODE_IN_COMMUNICATION_TIMEOUT = 1;

        final int mOp;
        final String mPackage;
        final int mOwnerPid;
        final int mRequesterPid;
        final int mRequestedMode;
        final int mActualMode;

        /** used for MODE_SET */
        PhoneStateEvent(String callingPackage, int requesterPid, int requestedMode,
                        int ownerPid, int actualMode) {
            mOp = MODE_SET;
            mPackage = callingPackage;
            mRequesterPid = requesterPid;
            mRequestedMode = requestedMode;
            mOwnerPid = ownerPid;
            mActualMode = actualMode;
            logMetricEvent();
        }

        /** used for MODE_IN_COMMUNICATION_TIMEOUT */
        PhoneStateEvent(String callingPackage, int ownerPid) {
            mOp = MODE_IN_COMMUNICATION_TIMEOUT;
            mPackage = callingPackage;
            mOwnerPid = ownerPid;
            mRequesterPid = 0;
            mRequestedMode = 0;
            mActualMode = 0;
            logMetricEvent();
        }

        @Override
        public String eventToString() {
            switch (mOp) {
                case MODE_SET:
                    return new StringBuilder("setMode(")
                            .append(AudioSystem.modeToString(mRequestedMode))
                            .append(") from package=").append(mPackage)
                            .append(" pid=").append(mRequesterPid)
                            .append(" selected mode=")
                            .append(AudioSystem.modeToString(mActualMode))
                            .append(" by pid=").append(mOwnerPid).toString();
                case MODE_IN_COMMUNICATION_TIMEOUT:
                    return new StringBuilder("mode IN COMMUNICATION timeout")
                            .append(" for package=").append(mPackage)
                            .append(" pid=").append(mOwnerPid).toString();
                default: return new StringBuilder("FIXME invalid op:").append(mOp).toString();
            }
        }

        /**
         * Audio Analytics unique Id.
         */
        private static final String mMetricsId = MediaMetrics.Name.AUDIO_MODE;

        private void logMetricEvent() {
            switch (mOp) {
                case MODE_SET:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "set")
                            .set(MediaMetrics.Property.REQUESTED_MODE,
                                    AudioSystem.modeToString(mRequestedMode))
                            .set(MediaMetrics.Property.MODE, AudioSystem.modeToString(mActualMode))
                            .set(MediaMetrics.Property.CALLING_PACKAGE, mPackage)
                            .record();
                    return;
                case MODE_IN_COMMUNICATION_TIMEOUT:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "inCommunicationTimeout")
                            .set(MediaMetrics.Property.CALLING_PACKAGE, mPackage)
                            .record();
                    return;
                default: return;
            }
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
                    .append(" type:").append(
                            Integer.toHexString(mState.mAttributes.getInternalType()))
                    .append(" state:").append(AudioSystem.deviceStateToString(mState.mState))
                    .append(" addr:").append(mState.mAttributes.getAddress())
                    .append(" name:").append(mState.mAttributes.getName())
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

    static final class DeviceVolumeEvent extends AudioEventLogger.Event {
        final int mStream;
        final int mVolIndex;
        final String mDeviceNativeType;
        final String mDeviceAddress;
        final String mCaller;

        DeviceVolumeEvent(int streamType, int index, @NonNull AudioDeviceAttributes device,
                String callingPackage) {
            mStream = streamType;
            mVolIndex = index;
            mDeviceNativeType = "0x" + Integer.toHexString(device.getInternalType());
            mDeviceAddress = device.getAddress();
            mCaller = callingPackage;
            // log metrics
            new MediaMetrics.Item(MediaMetrics.Name.AUDIO_VOLUME_EVENT)
                    .set(MediaMetrics.Property.EVENT, "setDeviceVolume")
                    .set(MediaMetrics.Property.STREAM_TYPE,
                            AudioSystem.streamToString(mStream))
                    .set(MediaMetrics.Property.INDEX, mVolIndex)
                    .set(MediaMetrics.Property.DEVICE, mDeviceNativeType)
                    .set(MediaMetrics.Property.ADDRESS, mDeviceAddress)
                    .set(MediaMetrics.Property.CALLING_PACKAGE, mCaller)
                    .record();
        }

        @Override
        public String eventToString() {
            return new StringBuilder("setDeviceVolume(stream:")
                    .append(AudioSystem.streamToString(mStream))
                    .append(" index:").append(mVolIndex)
                    .append(" device:").append(mDeviceNativeType)
                    .append(" addr:").append(mDeviceAddress)
                    .append(") from ").append(mCaller).toString();
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
        static final int VOL_SET_GROUP_VOL = 8;
        static final int VOL_MUTE_STREAM_INT = 9;
        static final int VOL_SET_LE_AUDIO_VOL = 10;

        final int mOp;
        final int mStream;
        final int mVal1;
        final int mVal2;
        final String mCaller;
        final String mGroupName;
        final AudioAttributes mAudioAttributes;

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
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }

        /** used for VOL_SET_HEARING_AID_VOL*/
        VolumeEvent(int op, int index, int gainDb) {
            mOp = op;
            mVal1 = index;
            mVal2 = gainDb;
            // unused
            mStream = -1;
            mCaller = null;
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }

        /** used for VOL_SET_AVRCP_VOL */
        VolumeEvent(int op, int index) {
            mOp = op;
            mVal1 = index;
            // unused
            mVal2 = 0;
            mStream = -1;
            mCaller = null;
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }

        /** used for VOL_VOICE_ACTIVITY_HEARING_AID */
        VolumeEvent(int op, boolean voiceActive, int stream, int index) {
            mOp = op;
            mStream = stream;
            mVal1 = index;
            mVal2 = voiceActive ? 1 : 0;
            // unused
            mCaller = null;
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }

        /** used for VOL_MODE_CHANGE_HEARING_AID */
        VolumeEvent(int op, int mode, int stream, int index) {
            mOp = op;
            mStream = stream;
            mVal1 = index;
            mVal2 = mode;
            // unused
            mCaller = null;
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }

        /** used for VOL_SET_GROUP_VOL */
        VolumeEvent(int op, AudioAttributes aa, String group, int index, int flags, String caller) {
            mOp = op;
            mStream = -1;
            mVal1 = index;
            mVal2 = flags;
            mCaller = caller;
            mGroupName = group;
            mAudioAttributes = aa;
            logMetricEvent();
        }

        /** used for VOL_MUTE_STREAM_INT */
        VolumeEvent(int op, int stream, boolean state) {
            mOp = op;
            mStream = stream;
            mVal1 = state ? 1 : 0;
            mVal2 = 0;
            mCaller = null;
            mGroupName = null;
            mAudioAttributes = null;
            logMetricEvent();
        }


        /**
         * Audio Analytics unique Id.
         */
        private static final String mMetricsId = MediaMetrics.Name.AUDIO_VOLUME_EVENT;

        /**
         * Log mediametrics event
         */
        private void logMetricEvent() {
            switch (mOp) {
                case VOL_ADJUST_SUGG_VOL:
                case VOL_ADJUST_VOL_UID:
                case VOL_ADJUST_STREAM_VOL: {
                    String eventName;
                    switch (mOp) {
                        case VOL_ADJUST_SUGG_VOL:
                            eventName = "adjustSuggestedStreamVolume";
                            break;
                        case VOL_ADJUST_STREAM_VOL:
                            eventName = "adjustStreamVolume";
                            break;
                        case VOL_ADJUST_VOL_UID:
                            eventName = "adjustStreamVolumeForUid";
                            break;
                        default:
                            return; // not possible, just return here
                    }
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.CALLING_PACKAGE, mCaller)
                            .set(MediaMetrics.Property.DIRECTION, mVal1 > 0 ? "up" : "down")
                            .set(MediaMetrics.Property.EVENT, eventName)
                            .set(MediaMetrics.Property.FLAGS, mVal2)
                            .set(MediaMetrics.Property.STREAM_TYPE,
                                    AudioSystem.streamToString(mStream))
                            .record();
                    return;
                }
                case VOL_SET_STREAM_VOL:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.CALLING_PACKAGE, mCaller)
                            .set(MediaMetrics.Property.EVENT, "setStreamVolume")
                            .set(MediaMetrics.Property.FLAGS, mVal2)
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .set(MediaMetrics.Property.STREAM_TYPE,
                                    AudioSystem.streamToString(mStream))
                            .record();
                    return;
                case VOL_SET_HEARING_AID_VOL:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "setHearingAidVolume")
                            .set(MediaMetrics.Property.GAIN_DB, (double) mVal2)
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .record();
                    return;
                case VOL_SET_LE_AUDIO_VOL:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "setLeAudioVolume")
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .set(MediaMetrics.Property.MAX_INDEX, mVal2)
                            .record();
                    return;
                case VOL_SET_AVRCP_VOL:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "setAvrcpVolume")
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .record();
                    return;
                case VOL_VOICE_ACTIVITY_HEARING_AID:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "voiceActivityHearingAid")
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .set(MediaMetrics.Property.STATE,
                                    mVal2 == 1 ? "active" : "inactive")
                            .set(MediaMetrics.Property.STREAM_TYPE,
                                    AudioSystem.streamToString(mStream))
                            .record();
                    return;
                case VOL_MODE_CHANGE_HEARING_AID:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.EVENT, "modeChangeHearingAid")
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .set(MediaMetrics.Property.MODE, AudioSystem.modeToString(mVal2))
                            .set(MediaMetrics.Property.STREAM_TYPE,
                                    AudioSystem.streamToString(mStream))
                            .record();
                    return;
                case VOL_SET_GROUP_VOL:
                    new MediaMetrics.Item(mMetricsId)
                            .set(MediaMetrics.Property.ATTRIBUTES, mAudioAttributes.toString())
                            .set(MediaMetrics.Property.CALLING_PACKAGE, mCaller)
                            .set(MediaMetrics.Property.EVENT, "setVolumeIndexForAttributes")
                            .set(MediaMetrics.Property.FLAGS, mVal2)
                            .set(MediaMetrics.Property.GROUP, mGroupName)
                            .set(MediaMetrics.Property.INDEX, mVal1)
                            .record();
                    return;
                case VOL_MUTE_STREAM_INT:
                    // No value in logging metrics for this internal event
                    return;
                default:
                    return;
            }
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
                case VOL_SET_LE_AUDIO_VOL:
                    return new StringBuilder("setLeAudioVolume:")
                            .append(" index:").append(mVal1)
                            .append(" maxIndex:").append(mVal2)
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
                case VOL_SET_GROUP_VOL:
                    return new StringBuilder("setVolumeIndexForAttributes(attr:")
                            .append(mAudioAttributes.toString())
                            .append(" group: ").append(mGroupName)
                            .append(" index:").append(mVal1)
                            .append(" flags:0x").append(Integer.toHexString(mVal2))
                            .append(") from ").append(mCaller)
                            .toString();
                case VOL_MUTE_STREAM_INT:
                    return new StringBuilder("VolumeStreamState.muteInternally(stream:")
                            .append(AudioSystem.streamToString(mStream))
                            .append(mVal1 == 1 ? ", muted)" : ", unmuted)")
                            .toString();
                default: return new StringBuilder("FIXME invalid op:").append(mOp).toString();
            }
        }
    }
}
