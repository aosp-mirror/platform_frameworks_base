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

package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiRecordSources.RecordSource;

/**
 * Listener for hdmi record feature including one touch record and timer recording.
 * @hide
 */
@SystemApi
public abstract class HdmiRecordListener {
    public HdmiRecordListener() {}

    /**
     * Called when TV received one touch record request from record device. The client of this
     * should use {@link HdmiRecordSources} to return it.
     *
     * @param recorderAddress
     * @return record source to be used for recording. Null if no device is available.
     */
    public abstract RecordSource onOneTouchRecordSourceRequested(int recorderAddress);

    /**
     * Called when one touch record is started or failed during initialization.
     *
     * @param recorderAddress An address of recorder that reports result of one touch record
     *            request
     * @param result result code. For more details, please look at all constants starting with
     *            "ONE_TOUCH_RECORD_". Only
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_CURRENTLY_SELECTED_SOURCE},
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_DIGITAL_SERVICE},
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_ANALOGUE_SERVICE}, and
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_EXTERNAL_INPUT} mean normal
     *            start of recording; otherwise, describes failure.
     */
    public void onOneTouchRecordResult(int recorderAddress, int result) {
    }

    /**
     * Called when timer recording is started or failed during initialization.
     *
     * @param recorderAddress An address of recorder that reports result of timer recording
     *            request
     * @param data timer status data. For more details, look at {@link TimerStatusData}.
     */
    public void onTimerRecordingResult(int recorderAddress, TimerStatusData data) {
    }

    /**
     * [Timer overlap warning] [Media Info] [Timer Programmed Info]
     * @hide
     */
    @SystemApi
    public static class TimerStatusData {
        private boolean mOverlapped;
        private int mMediaInfo;
        private boolean mProgrammed;

        private int mProgrammedInfo;
        private int mNotProgrammedError;
        private int mDurationHour;
        private int mDurationMinute;

        private int mExtraError;

        static TimerStatusData parseFrom(int result) {
            TimerStatusData data = new TimerStatusData();
            // Timer Overlap Warning - 1 bit
            data.mOverlapped = ((result >> 31) & 0x1) != 0;
            // Media Info - 2 bits;
            data.mMediaInfo = (result >> 29) & 0x3;
            // Programmed Indicator - 1 bit;
            data.mProgrammed = ((result >> 28) & 0x1) != 0;
            if (data.mProgrammed) {
                data.mProgrammedInfo = (result >> 24) & 0xF;
                data.mDurationHour = bcdByteToInt((byte) ((result >> 16) & 0xFF));
                data.mDurationMinute = bcdByteToInt((byte) ((result >> 8) & 0xFF));
            } else {
                // Programmed Info - 4 bits
                data.mNotProgrammedError = (result >> 24) & 0xF;
                data.mDurationHour = bcdByteToInt((byte) ((result >> 16) & 0xFF));
                data.mDurationMinute = bcdByteToInt((byte) ((result >> 8) & 0xFF));
            }

            // The last byte is used for extra error.
            data.mExtraError = result & 0xFF;
            return data;
        }

        // Most significant 4 bits is used for 10 digits and
        // Least significant 4 bits is used for 1 digits.
        private static int bcdByteToInt(byte value) {
            return ((value >> 4) & 0xF) * 10 + value & 0xF;
        }

        private TimerStatusData() {}

        /**
         * Indicates if there is another timer block already set which overlaps with this new
         * recording request.
         */
        public boolean isOverlapped() {
            return mOverlapped;
        }

        /**
         * Indicates if removable media is present and its write protect state.
         * It should be one of the following values.
         * <ul>
         *   <li>{@link HdmiControlManager#TIMER_STATUS_MEDIA_INFO_PRESENT_NOT_PROTECTED}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_MEDIA_INFO_PRESENT_PROTECTED}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_MEDIA_INFO_NOT_PRESENT}
         * </ul>
         */
        public int getMediaInfo() {
            return mMediaInfo;
        }

        /**
         * Selector for [Timer Programmed Info].
         * If it is {@code true}, {@link #getProgrammedInfo()} would have meaningful value and
         * ignore result of {@link #getNotProgammedError()}.
         */
        public boolean isProgrammed() {
            return mProgrammed;
        }

        /**
         * Information indicating any non-fatal issues with the programming request.
         * It's set only if {@link #isProgrammed()} returns true.
         * It should be one of the following values.
         * <ul>
         *   <li>{@link HdmiControlManager#TIMER_STATUS_PROGRAMMED_INFO_ENOUGH_SPACE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_PROGRAMMED_INFO_NOT_ENOUGH_SPACE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_PROGRAMMED_INFO_MIGHT_NOT_ENOUGH_SPACE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_PROGRAMMED_INFO_NO_MEDIA_INFO}
         * </ul>
         *
         * @throws IllegalStateException if it's called when {@link #isProgrammed()}
         *                               returns false
         */
        public int getProgrammedInfo() {
            if (!isProgrammed()) {
                throw new IllegalStateException(
                        "No programmed info. Call getNotProgammedError() instead.");
            }
            return mProgrammedInfo;
        }

        /**
         * Information indicating any fatal issues with the programming request.
         * It's set only if {@link #isProgrammed()} returns false.
         * it should be one of the following values.
         * <ul>
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_NO_FREE_TIME}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_DATE_OUT_OF_RANGE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_INVALID_SEQUENCE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_INVALID_EXTERNAL_PHYSICAL_NUMBER}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_CA_NOT_SUPPORTED}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_NO_CA_ENTITLEMENTS}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_UNSUPPORTED_RESOLUTION}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_PARENTAL_LOCK_ON}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_CLOCK_FAILURE}
         *   <li>{@link HdmiControlManager#TIMER_STATUS_NOT_PROGRAMMED_DUPLICATED}
         * </ul>
         *
         * @throws IllegalStateException if it's called when {@link #isProgrammed()}
         *                               returns true
         */
        public int getNotProgammedError() {
            if (isProgrammed()) {
                throw new IllegalStateException(
                        "Has no not-programmed error. Call getProgrammedInfo() instead.");
            }
            return mNotProgrammedError;
        }

        /**
         * Duration hours.
         * Optional parameter: Contains an estimate of the space left on the media, expressed as a
         * time. This parameter may be returned when:
         *  - [Programmed Info] is “Not enough space available”; or
         *  - [Not Programmed Info] is “Duplicate: already programmed”
         */
        public int getDurationHour() {
            return mDurationHour;
        }

        /**
         * Duration minutes.
         * Optional parameter: Contains an estimate of the space left on the media, expressed as a
         * time. This parameter may be returned when:
         *  - [Programmed Info] is “Not enough space available”; or
         *  - [Not Programmed Info] is “Duplicate: already programmed”
         */
        public int getDurationMinute() {
            return mDurationMinute;
        }

        /**
         * Extra error code.
         * <ul>
         * <li>{@link HdmiControlManager#TIMER_RECORDING_RESULT_EXTRA_NO_ERROR}
         *     No extra errors. Other values of this class might be available.
         * <li>{@link HdmiControlManager#TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION}
         *     Check record connection. Other values of this class should be ignored.
         * <li>{@link HdmiControlManager#TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE}
         *     Fail to record selected source. Other values of this class should be ignored.
         * <li>{@link HdmiControlManager#TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED}
         *     Cec disabled. Other values of this class should be ignored.
         * </ul>
         */
        public int getExtraError() {
            return mExtraError;
        }
    }

    /**
     * Called when receiving result for clear timer recording request.
     *
     * @param recorderAddress An address of recorder that reports result of clear timer recording
     *            request
     * @param result result of clear timer. It should be one of
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_RECORDING}
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_NO_MATCHING},
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_NO_INFO_AVAILABLE},
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_TIMER_CLEARED},
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION},
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE},
     *            {@link HdmiControlManager#CLEAR_TIMER_STATUS_CEC_DISABLE}.
     */
    public void onClearTimerRecordingResult(int recorderAddress, int result) {
    }
}
