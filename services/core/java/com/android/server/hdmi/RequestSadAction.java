/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.hdmi;

import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Feature action that queries the Short Audio Descriptor (SAD) of another device. This action is
 * initiated from the Android system working as TV device to get the SAD of the connected audio
 * system device.
 * <p>
 * Package-private
 */
final class RequestSadAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestSadAction";

    // State in which the action is waiting for <Report Short Audio Descriptor>.
    private static final int STATE_WAITING_FOR_REPORT_SAD = 1;

    private static final List<Integer> ALL_CEC_CODECS = new ArrayList<Integer>(Arrays.asList(
            Constants.AUDIO_CODEC_LPCM,
            Constants.AUDIO_CODEC_DD,
            Constants.AUDIO_CODEC_MPEG1,
            Constants.AUDIO_CODEC_MP3,
            Constants.AUDIO_CODEC_MPEG2,
            Constants.AUDIO_CODEC_AAC,
            Constants.AUDIO_CODEC_DTS,
            Constants.AUDIO_CODEC_ATRAC,
            Constants.AUDIO_CODEC_ONEBITAUDIO,
            Constants.AUDIO_CODEC_DDP,
            Constants.AUDIO_CODEC_DTSHD,
            Constants.AUDIO_CODEC_TRUEHD,
            Constants.AUDIO_CODEC_DST,
            Constants.AUDIO_CODEC_WMAPRO,
            Constants.AUDIO_CODEC_MAX));
    private static final int MAX_SAD_PER_REQUEST = 4;
    private static final int RETRY_COUNTER_MAX = 1;
    private final int mTargetAddress;
    private final RequestSadCallback mCallback;
    // List of all valid SADs reported by the target device. Not parsed nor deduplicated.
    private final List<byte[]> mSupportedSads = new ArrayList<>();
    private int mQueriedSadCount = 0; // Number of SADs queries that has already been completed
    private int mTimeoutRetry = 0; // Number of times we have already retried on time-out

    /**
     * Constructor.
     *
     * @param source        an instance of {@link HdmiCecLocalDevice}.
     * @param targetAddress the logical address the SAD is directed at.
     */
    RequestSadAction(HdmiCecLocalDevice source, int targetAddress, RequestSadCallback callback) {
        super(source);
        mTargetAddress = targetAddress;
        mCallback = Objects.requireNonNull(callback);
    }


    @Override
    boolean start() {
        querySad();
        return true;
    }

    private void querySad() {
        if (mQueriedSadCount >= ALL_CEC_CODECS.size()) {
            wrapUpAndFinish();
            return;
        }
        int[] codecsToQuery = ALL_CEC_CODECS.subList(mQueriedSadCount,
                Math.min(ALL_CEC_CODECS.size(), mQueriedSadCount + MAX_SAD_PER_REQUEST))
                    .stream().mapToInt(i -> i).toArray();
        sendCommand(HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(getSourceAddress(),
                mTargetAddress, codecsToQuery));
        mState = STATE_WAITING_FOR_REPORT_SAD;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_SAD
                || mTargetAddress != cmd.getSource()) {
            return false;
        }
        if (cmd.getOpcode() == Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR) {
            if (cmd.getParams() == null || cmd.getParams().length == 0
                    || cmd.getParams().length % 3 != 0) {
                // Invalid message. Wait for time-out and query again.
                return true;
            }
            for (int i = 0; i < cmd.getParams().length - 2; i += 3) {
                if (isValidCodec(cmd.getParams()[i])) {
                    byte[] sad = new byte[]{cmd.getParams()[i], cmd.getParams()[i + 1],
                            cmd.getParams()[i + 2]};
                    updateResult(sad);
                }
                // Don't include invalid codecs in the result. Don't query again.
                Slog.w(TAG, "Received invalid codec " + cmd.getParams()[i] + ".");
            }
            mQueriedSadCount += MAX_SAD_PER_REQUEST;
            mTimeoutRetry = 0;
            querySad();
            return true;
        }
        if (cmd.getOpcode() == Constants.MESSAGE_FEATURE_ABORT
                && (cmd.getParams()[0] & 0xFF) == Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR
                && (cmd.getParams()[1] & 0xFF) == Constants.ABORT_INVALID_OPERAND) {
            // Queried SADs are not supported
            mQueriedSadCount += MAX_SAD_PER_REQUEST;
            mTimeoutRetry = 0;
            querySad();
            return true;
        }
        return false;
    }

    private boolean isValidCodec(byte codec) {
        return Constants.AUDIO_CODEC_NONE < (codec & 0xFF)
                && (codec & 0xFF) <= Constants.AUDIO_CODEC_MAX;
    }

    private void updateResult(byte[] sad) {
        mSupportedSads.add(sad);
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_REPORT_SAD) {
            if (++mTimeoutRetry <= RETRY_COUNTER_MAX) {
                querySad();
                return;
            }
            mQueriedSadCount += MAX_SAD_PER_REQUEST;
            mTimeoutRetry = 0;
            querySad();
        }
    }

    private void wrapUpAndFinish() {
        mCallback.onRequestSadDone(mSupportedSads);
        finish();
    }

    /**
     * Interface used to report result of SAD request.
     */
    interface RequestSadCallback {
        /**
         * Called when SAD request is done.
         *
         * @param sads a list of all supported SADs. It can be an empty list.
         */
        void onRequestSadDone(List<byte[]> supportedSads);
    }
}
