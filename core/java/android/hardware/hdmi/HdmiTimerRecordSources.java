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

import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_ANALOGUE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_DIGITAL;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_EXTERNAL;

import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiRecordSources.AnalogueServiceSource;
import android.hardware.hdmi.HdmiRecordSources.DigitalServiceSource;
import android.hardware.hdmi.HdmiRecordSources.ExternalPhysicalAddress;
import android.hardware.hdmi.HdmiRecordSources.ExternalPlugData;
import android.hardware.hdmi.HdmiRecordSources.RecordSource;
import android.util.Log;

/**
 * Container for timer record source used for timer recording. Timer source consists of two parts,
 * timer info and record source.
 * <p>
 * Timer info contains all timing information used for recording. It consists of the following
 * values.
 * <ul>
 * <li>[Day of Month]
 * <li>[Month of Year]
 * <li>[Start Time]
 * <li>[Duration]
 * <li>[Recording Sequence]
 * </ul>
 * <p>
 * Record source containers all program information used for recording.
 * For more details, look at {@link HdmiRecordSources}.
 * <p>
 * Usage
 * <pre>
 * TimeOrDuration startTime = HdmiTimerRecordSources.ofTime(18, 00);  // 6PM.
 * TimeOrDuration duration = HdmiTimerRecordSource.ofDuration(1, 00);  // 1 hour duration.
 * // For 1 hour from 6PM, August 10th every SaturDay and Sunday.
 * TimerInfo timerInfo = HdmiTimerRecordSource.timerInfoOf(10, 8, starTime, duration,
 *            HdmiTimerRecordSource.RECORDING_SEQUENCE_REPEAT_SATURDAY |
 *            HdmiTimerRecordSource.RECORDING_SEQUENCE_REPEAT_SUNDAY);
 * // create digital source.
 * DigitalServiceSource recordSource = HdmiRecordSource.ofDvb(...);
 * TimerRecordSource source = ofDigitalSource(timerInfo, recordSource);
 * </pre>
 *
 * @hide
 */
@SystemApi
public class HdmiTimerRecordSources {
    private static final String TAG = "HdmiTimerRecordingSources";

    private HdmiTimerRecordSources() {}

    /**
     * Creates {@link TimerRecordSource} for digital source which is used for &lt;Set Digital
     * Timer&gt;.
     *
     * @param timerInfo timer info used for timer recording
     * @param source digital source used for timer recording
     * @return {@link TimerRecordSource}
     * @throws IllegalArgumentException if {@code timerInfo} or {@code source} is null
     */
    public static TimerRecordSource ofDigitalSource(TimerInfo timerInfo,
            DigitalServiceSource source) {
        checkTimerRecordSourceInputs(timerInfo, source);
        return new TimerRecordSource(timerInfo, source);
    }

    /**
     * Creates {@link TimerRecordSource} for analogue source which is used for &lt;Set Analogue
     * Timer&gt;.
     *
     * @param timerInfo timer info used for timer recording
     * @param source digital source used for timer recording
     * @return {@link TimerRecordSource}
     * @throws IllegalArgumentException if {@code timerInfo} or {@code source} is null
     */
    public static TimerRecordSource ofAnalogueSource(TimerInfo timerInfo,
            AnalogueServiceSource source) {
        checkTimerRecordSourceInputs(timerInfo, source);
        return new TimerRecordSource(timerInfo, source);
    }

    /**
     * Creates {@link TimerRecordSource} for external plug which is used for &lt;Set External
     * Timer&gt;.
     *
     * @param timerInfo timer info used for timer recording
     * @param source digital source used for timer recording
     * @return {@link TimerRecordSource}
     * @throws IllegalArgumentException if {@code timerInfo} or {@code source} is null
     */
    public static TimerRecordSource ofExternalPlug(TimerInfo timerInfo, ExternalPlugData source) {
        checkTimerRecordSourceInputs(timerInfo, source);
        return new TimerRecordSource(timerInfo,
                new ExternalSourceDecorator(source, EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PLUG));
    }

    /**
     * Creates {@link TimerRecordSource} for external physical address which is used for &lt;Set
     * External Timer&gt;.
     *
     * @param timerInfo timer info used for timer recording
     * @param source digital source used for timer recording
     * @return {@link TimerRecordSource}
     * @throws IllegalArgumentException if {@code timerInfo} or {@code source} is null
     */
    public static TimerRecordSource ofExternalPhysicalAddress(TimerInfo timerInfo,
            ExternalPhysicalAddress source) {
        checkTimerRecordSourceInputs(timerInfo, source);
        return new TimerRecordSource(timerInfo,
                new ExternalSourceDecorator(source,
                        EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PHYSICAL_ADDRESS));
    }

    private static void checkTimerRecordSourceInputs(TimerInfo timerInfo, RecordSource source) {
        if (timerInfo == null) {
            Log.w(TAG, "TimerInfo should not be null.");
            throw new IllegalArgumentException("TimerInfo should not be null.");
        }
        if (source == null) {
            Log.w(TAG, "source should not be null.");
            throw new IllegalArgumentException("source should not be null.");
        }
    }

    /**
     * Creates {@link Duration} for time value.
     *
     * @param hour hour in range of [0, 23]
     * @param minute minute in range of [0, 60]
     * @return {@link Duration}
     * @throws IllegalArgumentException if hour or minute is out of range
     */
    public static Time timeOf(int hour, int minute) {
        checkTimeValue(hour, minute);
        return new Time(hour, minute);
    }

    private static void checkTimeValue(int hour, int minute) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("Hour should be in rage of [0, 23]:" + hour);
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Minute should be in rage of [0, 59]:" + minute);
        }
    }

    /**
     * Creates {@link Duration} for duration value.
     *
     * @param hour hour in range of [0, 99]
     * @param minute minute in range of [0, 59]
     * @return {@link Duration}
     * @throws IllegalArgumentException if hour or minute is out of range
     */
    public static Duration durationOf(int hour, int minute) {
        checkDurationValue(hour, minute);
        return new Duration(hour, minute);
    }

    private static void checkDurationValue(int hour, int minute) {
        if (hour < 0 || hour > 99) {
            throw new IllegalArgumentException("Hour should be in rage of [0, 99]:" + hour);
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute should be in rage of [0, 59]:" + minute);
        }
    }

    /**
     * Base class for time-related information.
     * @hide
     */
    /* package */ static class TimeUnit {
        /* package */ final int mHour;
        /* package */ final int mMinute;

        /* package */ TimeUnit(int hour, int minute) {
            mHour = hour;
            mMinute = minute;
        }

        /* package */ int toByteArray(byte[] data, int index) {
            data[index] = toBcdByte(mHour);
            data[index + 1] = toBcdByte(mMinute);
            return 2;
        }

        /* package */ static byte toBcdByte(int value) {
            int digitOfTen = (value / 10) % 10;
            int digitOfOne = value % 10;
            return (byte) ((digitOfTen << 4) | digitOfOne);
        }
    }

    /**
     * Place holder for time value.
     * @hide
     */
    @SystemApi
    public static final class Time extends TimeUnit {
        private Time(int hour, int minute) {
            super(hour, minute);
        }
    }

    /**
     * Place holder for duration value.
     * @hide
     */
    @SystemApi
    public static final class Duration extends TimeUnit {
        private Duration(int hour, int minute) {
            super(hour, minute);
        }
    }

    /**
     * Fields for recording sequence.
     * The following can be merged by OR(|) operation.
     */
    public static final int RECORDING_SEQUENCE_REPEAT_ONCE_ONLY = 0;
    public static final int RECORDING_SEQUENCE_REPEAT_SUNDAY = 1 << 0;
    public static final int RECORDING_SEQUENCE_REPEAT_MONDAY = 1 << 1;
    public static final int RECORDING_SEQUENCE_REPEAT_TUESDAY = 1 << 2;
    public static final int RECORDING_SEQUENCE_REPEAT_WEDNESDAY = 1 << 3;
    public static final int RECORDING_SEQUENCE_REPEAT_THURSDAY = 1 << 4;
    public static final int RECORDING_SEQUENCE_REPEAT_FRIDAY = 1 << 5;
    public static final int RECORDING_SEQUENCE_REPEAT_SATUREDAY = 1 << 6;

    private static final int RECORDING_SEQUENCE_REPEAT_MASK =
            (RECORDING_SEQUENCE_REPEAT_SUNDAY | RECORDING_SEQUENCE_REPEAT_MONDAY |
            RECORDING_SEQUENCE_REPEAT_TUESDAY | RECORDING_SEQUENCE_REPEAT_WEDNESDAY |
            RECORDING_SEQUENCE_REPEAT_THURSDAY | RECORDING_SEQUENCE_REPEAT_FRIDAY |
            RECORDING_SEQUENCE_REPEAT_SATUREDAY);

    /**
     * Creates {@link TimerInfo} with the given information.
     *
     * @param dayOfMonth day of month
     * @param monthOfYear month of year
     * @param startTime start time in {@link Time}
     * @param duration duration in {@link Duration}
     * @param recordingSequence recording sequence. Use RECORDING_SEQUENCE_REPEAT_ONCE_ONLY for no
     *            repeat. Otherwise use combination of {@link #RECORDING_SEQUENCE_REPEAT_SUNDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_MONDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_TUESDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_WEDNESDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_THURSDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_FRIDAY},
     *            {@link #RECORDING_SEQUENCE_REPEAT_SATUREDAY}.
     * @return {@link TimerInfo}.
     * @throws IllegalArgumentException if input value is invalid
     */
    public static TimerInfo timerInfoOf(int dayOfMonth, int monthOfYear, Time startTime,
            Duration duration, int recordingSequence) {
        if (dayOfMonth < 0 || dayOfMonth > 31) {
            throw new IllegalArgumentException(
                    "Day of month should be in range of [0, 31]:" + dayOfMonth);
        }
        if (monthOfYear < 1 || monthOfYear > 12) {
            throw new IllegalArgumentException(
                    "Month of year should be in range of [1, 12]:" + monthOfYear);
        }
        checkTimeValue(startTime.mHour, startTime.mMinute);
        checkDurationValue(duration.mHour, duration.mMinute);
        // Recording sequence should use least 7 bits or no bits.
        if ((recordingSequence != 0)
                && ((recordingSequence & ~RECORDING_SEQUENCE_REPEAT_MASK) != 0)) {
            throw new IllegalArgumentException(
                    "Invalid reecording sequence value:" + recordingSequence);
        }

        return new TimerInfo(dayOfMonth, monthOfYear, startTime, duration, recordingSequence);
    }

    /**
     * Container basic timer information. It consists of the following fields.
     * <ul>
     * <li>[Day of Month]
     * <li>[Month of Year]
     * <li>[Start Time]
     * <li>[Duration]
     * <li>[Recording Sequence]
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class TimerInfo {
        private static final int DAY_OF_MONTH_SIZE = 1;
        private static final int MONTH_OF_YEAR_SIZE = 1;
        private static final int START_TIME_SIZE = 2; // 1byte for hour and 1byte for minute.
        private static final int DURATION_SIZE = 2; // 1byte for hour and 1byte for minute.
        private static final int RECORDING_SEQUENCE_SIZE = 1;
        private static final int BASIC_INFO_SIZE = DAY_OF_MONTH_SIZE + MONTH_OF_YEAR_SIZE
                + START_TIME_SIZE + DURATION_SIZE + RECORDING_SEQUENCE_SIZE;

        /** Day of month. */
        private final int mDayOfMonth;
        /** Month of year. */
        private final int mMonthOfYear;
        /**
         * Time of day.
         * [Hour][Minute]. 0 &lt;= Hour &lt;= 24, 0 &lt;= Minute &lt;= 60 in BCD format.
         */
        private final Time mStartTime;
        /**
         * Duration. [Hour][Minute].
         * 0 &lt;= Hour &lt;= 99, 0 &lt;= Minute &lt;= 60 in BCD format.
         * */
        private final Duration mDuration;
        /**
         * Indicates if recording is repeated and, if so, on which days. For repeated recordings,
         * the recording sequence value is the bitwise OR of the days when recordings are required.
         * [Recording Sequence] shall be set to 0x00 when the recording is not repeated. Bit 7 is
         * reserved and shall be set to zero.
         */
        private final int mRecordingSequence;

        private TimerInfo(int dayOfMonth, int monthOfYear, Time startTime,
                Duration duration, int recordingSequence) {
            mDayOfMonth = dayOfMonth;
            mMonthOfYear = monthOfYear;
            mStartTime = startTime;
            mDuration = duration;
            mRecordingSequence = recordingSequence;
        }

        int toByteArray(byte[] data, int index) {
            // [Day of Month]
            data[index] = (byte) mDayOfMonth;
            index += DAY_OF_MONTH_SIZE;
            // [Month of Year]
            data[index] = (byte) mMonthOfYear;
            index += MONTH_OF_YEAR_SIZE;
            // [Start Time]
            index += mStartTime.toByteArray(data, index);
            index += mDuration.toByteArray(data, index);
            // [Duration]
            // [Recording Sequence]
            data[index] = (byte) mRecordingSequence;
            return getDataSize();
        }

        int getDataSize() {
            return BASIC_INFO_SIZE;
        }
    }

    /**
     * Record source container for timer record. This is used to set parameter for &lt;Set Digital
     * Timer&gt;, &lt;Set Analogue Timer&gt;, and &lt;Set External Timer&gt; message.
     * <p>
     * In order to create this from each source type, use one of helper method.
     * <ul>
     * <li>{@link #ofDigitalSource} for digital source
     * <li>{@link #ofAnalogueSource} for analogue source
     * <li>{@link #ofExternalPlug} for external plug type
     * <li>{@link #ofExternalPhysicalAddress} for external physical address type.
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class TimerRecordSource {
        private final RecordSource mRecordSource;
        private final TimerInfo mTimerInfo;

        private TimerRecordSource(TimerInfo timerInfo, RecordSource recordSource) {
            mTimerInfo = timerInfo;
            mRecordSource = recordSource;
        }

        int getDataSize() {
            return mTimerInfo.getDataSize() + mRecordSource.getDataSize(false);
        }

        int toByteArray(byte[] data, int index) {
            // Basic infos including [Day of Month] [Month of Year] [Start Time] [Duration]
            // [Recording Sequence]
            index += mTimerInfo.toByteArray(data, index);
            // [Record Source]
            mRecordSource.toByteArray(false, data, index);
            return getDataSize();
        }
    }

    /**
     * External source specifier types.
     */
    private static final int EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PLUG = 4;
    private static final int EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PHYSICAL_ADDRESS = 5;

    /**
     * Decorator for external source. Beside digital or analogue source, external source starts with
     * [External Source Specifier] because it covers both external plug type and external specifier.
     */
    private static class ExternalSourceDecorator extends RecordSource {
        private final RecordSource mRecordSource;
        private final int mExternalSourceSpecifier;

        private ExternalSourceDecorator(RecordSource recordSource, int externalSourceSpecifier) {
            // External source has one byte field for [External Source Specifier].
            super(recordSource.mSourceType, recordSource.getDataSize(false) + 1);
            mRecordSource = recordSource;
            mExternalSourceSpecifier = externalSourceSpecifier;
        }

        @Override
        int extraParamToByteArray(byte[] data, int index) {
            data[index] = (byte) mExternalSourceSpecifier;
            mRecordSource.toByteArray(false, data, index + 1);
            return getDataSize(false);
        }
    }

    /**
     * Checks the byte array of timer record source.
     * @param sourcetype
     * @param recordSource
     * @hide
     */
    @SystemApi
    public static boolean checkTimerRecordSource(int sourcetype, byte[] recordSource) {
        int recordSourceSize = recordSource.length - TimerInfo.BASIC_INFO_SIZE;
        switch (sourcetype) {
            case TIMER_RECORDING_TYPE_DIGITAL:
                return DigitalServiceSource.EXTRA_DATA_SIZE == recordSourceSize;
            case TIMER_RECORDING_TYPE_ANALOGUE:
                return AnalogueServiceSource.EXTRA_DATA_SIZE == recordSourceSize;
            case TIMER_RECORDING_TYPE_EXTERNAL:
                int specifier = recordSource[TimerInfo.BASIC_INFO_SIZE];
                if (specifier == EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PLUG) {
                    // One byte for specifier.
                    return ExternalPlugData.EXTRA_DATA_SIZE + 1 == recordSourceSize;
                } else if (specifier == EXTERNAL_SOURCE_SPECIFIER_EXTERNAL_PHYSICAL_ADDRESS) {
                    // One byte for specifier.
                    return ExternalPhysicalAddress.EXTRA_DATA_SIZE + 1 == recordSourceSize;
                } else {
                    // Invalid specifier.
                    return false;
                }
            default:
                return false;
        }
    }
}
