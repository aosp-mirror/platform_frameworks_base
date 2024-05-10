/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.DemuxFilterMainType;
import android.hardware.tv.tuner.DemuxFilterMonitorEventType;
import android.hardware.tv.tuner.DemuxFilterStatus;
import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.Tuner.Result;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.TunerVersionChecker;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Tuner data filter.
 *
 * <p>This class is used to filter wanted data according to the filter's configuration.
 *
 * @hide
 */
@SystemApi
public class Filter implements AutoCloseable {
    /** @hide */
    @IntDef(prefix = "TYPE_",
            value = {TYPE_TS, TYPE_MMTP, TYPE_IP, TYPE_TLV, TYPE_ALP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Undefined filter type.
     */
    public static final int TYPE_UNDEFINED = 0;
    /**
     * TS filter type.
     */
    public static final int TYPE_TS = DemuxFilterMainType.TS;
    /**
     * MMTP filter type.
     */
    public static final int TYPE_MMTP = DemuxFilterMainType.MMTP;
    /**
     * IP filter type.
     */
    public static final int TYPE_IP = DemuxFilterMainType.IP;
    /**
     * TLV filter type.
     */
    public static final int TYPE_TLV = DemuxFilterMainType.TLV;
    /**
     * ALP filter type.
     */
    public static final int TYPE_ALP = DemuxFilterMainType.ALP;

    /** @hide */
    @IntDef(prefix = "SUBTYPE_",
            value = {SUBTYPE_UNDEFINED, SUBTYPE_SECTION, SUBTYPE_PES, SUBTYPE_AUDIO, SUBTYPE_VIDEO,
                    SUBTYPE_DOWNLOAD, SUBTYPE_RECORD, SUBTYPE_TS, SUBTYPE_PCR, SUBTYPE_TEMI,
                    SUBTYPE_MMTP, SUBTYPE_NTP, SUBTYPE_IP_PAYLOAD, SUBTYPE_IP,
                    SUBTYPE_PAYLOAD_THROUGH, SUBTYPE_TLV, SUBTYPE_PTP, })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Subtype {}
    /**
     * Filter subtype undefined.
     */
    public static final int SUBTYPE_UNDEFINED = 0;
    /**
     * Section filter subtype.
     */
    public static final int SUBTYPE_SECTION = 1;
    /**
     * PES filter subtype.
     */
    public static final int SUBTYPE_PES = 2;
    /**
     * Audio filter subtype.
     */
    public static final int SUBTYPE_AUDIO = 3;
    /**
     * Video filter subtype.
     */
    public static final int SUBTYPE_VIDEO = 4;
    /**
     * Download filter subtype.
     */
    public static final int SUBTYPE_DOWNLOAD = 5;
    /**
     * Record filter subtype.
     */
    public static final int SUBTYPE_RECORD = 6;
    /**
     * TS filter subtype.
     */
    public static final int SUBTYPE_TS = 7;
    /**
     * PCR filter subtype.
     */
    public static final int SUBTYPE_PCR = 8;
    /**
     * TEMI filter subtype.
     */
    public static final int SUBTYPE_TEMI = 9;
    /**
     * MMTP filter subtype.
     */
    public static final int SUBTYPE_MMTP = 10;
    /**
     * NTP filter subtype.
     */
    public static final int SUBTYPE_NTP = 11;
    /**
     * Payload filter subtype.
     */
    public static final int SUBTYPE_IP_PAYLOAD = 12;
    /**
     * IP filter subtype.
     */
    public static final int SUBTYPE_IP = 13;
    /**
     * Payload through filter subtype.
     */
    public static final int SUBTYPE_PAYLOAD_THROUGH = 14;
    /**
     * TLV filter subtype.
     */
    public static final int SUBTYPE_TLV = 15;
    /**
     * PTP filter subtype.
     */
    public static final int SUBTYPE_PTP = 16;


    /** @hide */
    @IntDef(prefix = "STATUS_",
            value = {STATUS_DATA_READY, STATUS_LOW_WATER, STATUS_HIGH_WATER, STATUS_OVERFLOW,
                    STATUS_NO_DATA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    /**
     * The status of a filter that the data in the filter buffer is ready to be read. It can also be
     * used to know the STC (System Time Clock) ready status if it's PCR filter.
     */
    public static final int STATUS_DATA_READY = DemuxFilterStatus.DATA_READY;
    /**
     * The status of a filter that the amount of available data in the filter buffer is at low
     * level.
     *
     * The value is set to 25 percent of the buffer size by default. It can be changed when
     * configuring the filter.
     */
    public static final int STATUS_LOW_WATER = DemuxFilterStatus.LOW_WATER;
    /**
     * The status of a filter that the amount of available data in the filter buffer is at high
     * level.
     * The value is set to 75 percent of the buffer size by default. It can be changed when
     * configuring the filter.
     */
    public static final int STATUS_HIGH_WATER = DemuxFilterStatus.HIGH_WATER;
    /**
     * The status of a filter that the filter buffer is full and newly filtered data is being
     * discarded.
     */
    public static final int STATUS_OVERFLOW = DemuxFilterStatus.OVERFLOW;
    /**
     * The status of a filter that the filter buffer is empty and no filtered data is coming.
     */
    public static final int STATUS_NO_DATA = DemuxFilterStatus.NO_DATA;

    /** @hide */
    @IntDef(prefix = "SCRAMBLING_STATUS_",
            value = {SCRAMBLING_STATUS_UNKNOWN, SCRAMBLING_STATUS_NOT_SCRAMBLED,
                    SCRAMBLING_STATUS_SCRAMBLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScramblingStatus {}

    /**
     * Content’s scrambling status is unknown
     */
    public static final int SCRAMBLING_STATUS_UNKNOWN =
            android.hardware.tv.tuner.ScramblingStatus.UNKNOWN;
    /**
     * Content is not scrambled.
     */
    public static final int SCRAMBLING_STATUS_NOT_SCRAMBLED =
            android.hardware.tv.tuner.ScramblingStatus.NOT_SCRAMBLED;
    /**
     * Content is scrambled.
     */
    public static final int SCRAMBLING_STATUS_SCRAMBLED =
            android.hardware.tv.tuner.ScramblingStatus.SCRAMBLED;

    /** @hide */
    @IntDef(prefix = "MONITOR_EVENT_",
            value = {MONITOR_EVENT_SCRAMBLING_STATUS, MONITOR_EVENT_IP_CID_CHANGE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MonitorEventMask {}

    /**
     * Monitor scrambling status change.
     */
    public static final int MONITOR_EVENT_SCRAMBLING_STATUS =
            DemuxFilterMonitorEventType.SCRAMBLING_STATUS;
    /**
     * Monitor ip cid change.
     */
    public static final int MONITOR_EVENT_IP_CID_CHANGE = DemuxFilterMonitorEventType.IP_CID_CHANGE;

    private static final String TAG = "Filter";

    private long mNativeContext;
    private FilterCallback mCallback;
    private Executor mExecutor;
    private final Object mCallbackLock = new Object();
    private final long mId;
    private int mMainType;
    private int mSubtype;
    private Filter mSource;
    private boolean mStarted;
    private boolean mIsClosed = false;
    private boolean mIsStarted = false;
    private boolean mIsShared = false;
    private final Object mLock = new Object();

    private native int nativeConfigureFilter(
            int type, int subType, FilterConfiguration settings);
    private native int nativeGetId();
    private native long nativeGetId64Bit();
    private native int nativeConfigureMonitorEvent(int monitorEventMask);
    private native int nativeSetDataSource(Filter source);
    private native int nativeStartFilter();
    private native int nativeStopFilter();
    private native int nativeFlushFilter();
    private native int nativeRead(byte[] buffer, long offset, long size);
    private native int nativeClose();
    private native String nativeAcquireSharedFilterToken();
    private native void nativeFreeSharedFilterToken(String token);
    private native int nativeSetTimeDelayHint(int timeDelayInMs);
    private native int nativeSetDataSizeDelayHint(int dataSizeDelayInBytes);

    // Called by JNI
    private Filter(long id) {
        mId = id;
    }

    private void onFilterStatus(int status) {
        synchronized (mCallbackLock) {
            if (mCallback != null && mExecutor != null) {
                mExecutor.execute(() -> {
                    FilterCallback callback;
                    synchronized (mCallbackLock) {
                        callback = mCallback;
                    }
                    if (callback != null) {
                        try {
                            callback.onFilterStatusChanged(this, status);
                        } catch (NullPointerException e) {
                            Log.d(TAG, "catch exception:" + e);
                        }
                    }
                    if (callback != null) {
                        callback.onFilterStatusChanged(this, status);
                    }
                });
            }
        }
    }

    private void onFilterEvent(FilterEvent[] events) {
        synchronized (mCallbackLock) {
            if (mCallback != null && mExecutor != null) {
                mExecutor.execute(() -> {
                    FilterCallback callback;
                    synchronized (mCallbackLock) {
                        callback = mCallback;
                    }
                    if (callback != null) {
                        try {
                            callback.onFilterEvent(this, events);
                        } catch (NullPointerException e) {
                            Log.d(TAG, "catch exception:" + e);
                        }
                    } else {
                        for (FilterEvent event : events) {
                            if (event instanceof MediaEvent) {
                                ((MediaEvent) event).release();
                            }
                        }
                    }
                });
            } else {
                for (FilterEvent event : events) {
                    if (event instanceof MediaEvent) {
                        ((MediaEvent) event).release();
                    }
                }
            }
        }
    }

    /** @hide */
    public void setType(@Type int mainType, @Subtype int subtype) {
        mMainType = mainType;
        mSubtype = TunerUtils.getFilterSubtype(mainType, subtype);
    }

    /** @hide */
    public void setCallback(FilterCallback cb, Executor executor) {
        synchronized (mCallbackLock) {
            mCallback = cb;
            mExecutor = executor;
        }
    }

    /** @hide */
    public FilterCallback getCallback() {
        synchronized (mCallbackLock) {
            return mCallback;
        }
    }

    /**
     * Configures the filter.
     *
     * <p>Recofiguring must happen after stopping the filter.
     *
     * <p>When stopping, reconfiguring and restarting the filter, the client should discard all
     * coming events until it receives {@link RestartEvent} through {@link FilterCallback} to avoid
     * using the events from the previous configuration.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @param config the configuration of the filter.
     * @return result status of the operation.
     */
    @Result
    public int configure(@NonNull FilterConfiguration config) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            Settings s = config.getSettings();
            int subType = (s == null) ? mSubtype : s.getType();
            if (mMainType != config.getType() || mSubtype != subType) {
                throw new IllegalArgumentException("Invalid filter config. filter main type="
                        + mMainType + ", filter subtype=" + mSubtype + ". config main type="
                        + config.getType() + ", config subtype=" + subType);
            }
            // Tuner only support VVC after tuner 3.0
            if (s instanceof RecordSettings
                    && ((RecordSettings) s).getScIndexType() == RecordSettings.INDEX_TYPE_SC_VVC
                    && !TunerVersionChecker.isHigherOrEqualVersionTo(
                            TunerVersionChecker.TUNER_VERSION_3_0)) {
                Log.e(TAG, "Tuner version " + TunerVersionChecker.getTunerVersion()
                        + " does not support VVC");
                return Tuner.RESULT_UNAVAILABLE;
            }
            return nativeConfigureFilter(config.getType(), subType, config);
        }
    }

    /**
     * Gets the filter Id in 32-bit. For any Tuner SoC that supports 64-bit filter architecture,
     * use {@link #getIdLong()}.
     * @deprecated Use {@link #getIdLong()} for both 32-bit and 64-bit filter architectures.
     */
    public int getId() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeGetId();
        }
    }

    /**
     * Gets the filter Id.
     */
    public long getIdLong() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeGetId64Bit();
        }
    }

    /**
     * Configure the Filter to monitor scrambling status and ip cid change. Set corresponding bit
     * to monitor the change. Reset to stop monitoring.
     *
     * <p>{@link ScramblingStatusEvent} should be sent at the following two scenarios:
     * <ul>
     *   <li>When this method is called with {@link #MONITOR_EVENT_SCRAMBLING_STATUS}, the first
     *       detected scrambling status should be sent.
     *   <li>When the Scrambling status transits into different status, event should be sent.
     *     <ul/>
     *
     * <p>{@link IpCidChangeEvent} should be sent at the following two scenarios:
     * <ul>
     *   <li>When this method is called with {@link #MONITOR_EVENT_IP_CID_CHANGE}, the first
     *       detected CID for the IP should be sent.
     *   <li>When the CID is changed to different value for the IP filter, event should be sent.
     *     <ul/>
     *
     * <p>This configuration is only supported in Tuner 1.1 or higher version. Unsupported version
     * will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the version
     * information.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @param monitorEventMask Types of event to be monitored. Set corresponding bit to
     *                         monitor it. Reset to stop monitoring.
     * @return result status of the operation.
     */
    @Result
    public int setMonitorEventMask(@MonitorEventMask int monitorEventMask) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1, "setMonitorEventMask")) {
                return Tuner.RESULT_UNAVAILABLE;
            }
            return nativeConfigureMonitorEvent(monitorEventMask);
        }
    }

    /**
     * Sets the filter's data source.
     *
     * A filter uses demux as data source by default. If the data was packetized
     * by multiple protocols, multiple filters may need to work together to
     * extract all protocols' header. Then a filter's data source can be output
     * from another filter.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @param source the filter instance which provides data input. Switch to
     * use demux as data source if the filter instance is NULL.
     * @return result status of the operation.
     * @throws IllegalStateException if the data source has been set.
     */
    @Result
    public int setDataSource(@Nullable Filter source) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            if (mSource != null) {
                throw new IllegalStateException("Data source is existing");
            }
            int res = nativeSetDataSource(source);
            if (res == Tuner.RESULT_SUCCESS) {
                mSource = source;
            }
            return res;
        }
    }

    /**
     * Starts filtering data.
     *
     * <p>Does nothing if the filter is already started.
     *
     * <p>When stopping, reconfiguring and restarting the filter, the client should discard all
     * coming events until it receives {@link RestartEvent} through {@link FilterCallback} to avoid
     * using the events from the previous configuration.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @return result status of the operation.
     */
    @Result
    public int start() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            int res = nativeStartFilter();
            if (res == Tuner.RESULT_SUCCESS) {
                mIsStarted = true;
            }
            return res;
        }
    }

    /**
     * Stops filtering data.
     *
     * <p>Does nothing if the filter is stopped or not started.
     *
     * <p>Filter must be stopped to reconfigure.
     *
     * <p>When stopping, reconfiguring and restarting the filter, the client should discard all
     * coming events until it receives {@link RestartEvent} through {@link FilterCallback} to avoid
     * using the events from the previous configuration.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @return result status of the operation.
     */
    @Result
    public int stop() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            int res = nativeStopFilter();
            if (res == Tuner.RESULT_SUCCESS) {
                mIsStarted = false;
            }
            return res;
        }
    }

    /**
     * Flushes the filter.
     *
     * <p>The data which is already produced by filter but not consumed yet will
     * be cleared.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @return result status of the operation.
     */
    @Result
    public int flush() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return Tuner.RESULT_INVALID_STATE;
            }
            return nativeFlushFilter();
        }
    }

    /**
     * Copies filtered data from filter output to the given byte array.
     *
     * <p>If this filter is shared, do nothing and just return {@link Tuner#RESULT_INVALID_STATE}.
     *
     * @param buffer the buffer to store the filtered data.
     * @param offset the index of the first byte in {@code buffer} to write.
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    public int read(@NonNull byte[] buffer, @BytesLong long offset, @BytesLong long size) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsShared) {
                return 0;
            }
            size = Math.min(size, buffer.length - offset);
            return nativeRead(buffer, offset, size);
        }
    }

    /**
     * Stops filtering data and releases the Filter instance.
     *
     * <p>If this filter is shared, this filter will be closed and a
     * {@link SharedFilterCallback#STATUS_INACCESSIBLE} event will be sent to shared filter before
     * closing.
     */
    @Override
    public void close() {
        synchronized (mCallbackLock) {
            mCallback = null;
            mExecutor = null;
        }

        synchronized (mLock) {
            if (mIsClosed) {
                return;
            }
            int res = nativeClose();
            if (res != Tuner.RESULT_SUCCESS) {
                TunerUtils.throwExceptionForResult(res, "Failed to close filter.");
            } else {
                mIsStarted = false;
                mIsClosed = true;
            }
        }
    }

    /**
     * Acquires a shared filter token.
     *
     * @return a string shared filter token.
     */
    @Nullable
    public String acquireSharedFilterToken() {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (mIsStarted || mIsShared) {
                Log.d(TAG, "Acquire shared filter in a wrong state, started: " +
                     mIsStarted + "shared: " + mIsShared);
                return null;
            }
            String token = nativeAcquireSharedFilterToken();
            if (token != null) {
                mIsShared = true;
            }
            return token;
        }
    }

    /**
     * Frees a shared filter token.
     *
     * @param filterToken the token of the shared filter being released.
     */
    public void freeSharedFilterToken(@NonNull String filterToken) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            if (!mIsShared) {
                return;
            }
            nativeFreeSharedFilterToken(filterToken);
            mIsShared = false;
        }
    }

    /**
     * Set filter time delay.
     *
     * <p> Setting a time delay instructs the filter to delay its event callback invocation until
     * the specified amount of time has passed. The default value (delay disabled) is {@code 0}.
     *
     * <p>This functionality is only available in Tuner version 2.0 and higher and will otherwise
     * be a no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * @param durationInMs specifies the duration of the delay in milliseconds.
     * @return one of the following results: {@link Tuner#RESULT_SUCCESS},
     * {@link Tuner#RESULT_UNAVAILABLE}, {@link Tuner#RESULT_NOT_INITIALIZED},
     * {@link Tuner#RESULT_INVALID_STATE}, {@link Tuner#RESULT_INVALID_ARGUMENT},
     * {@link Tuner#RESULT_OUT_OF_MEMORY}, or {@link Tuner#RESULT_UNKNOWN_ERROR}.
     */
    public int delayCallbackForDurationMillis(long durationInMs) {
        if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                  TunerVersionChecker.TUNER_VERSION_2_0, "setTimeDelayHint")) {
            return Tuner.RESULT_UNAVAILABLE;
        }

        if (durationInMs >= 0 && durationInMs <= Integer.MAX_VALUE) {
            synchronized (mLock) {
                return nativeSetTimeDelayHint((int) durationInMs);
            }
        }
        return Tuner.RESULT_INVALID_ARGUMENT;
    }

    /**
     * Set filter data size delay.
     *
     * <p> Setting a data size delay instructs the filter to delay its event callback invocation
     * until a specified amount of data has accumulated. The default value (delay disabled) is
     * {@code 0}.
     *
     * <p>This functionality is only available in Tuner version 2.0 and higher and will otherwise
     * be a no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * @param bytesAccumulated specifies the delay condition in bytes.
     * @return one of the following results: {@link Tuner#RESULT_SUCCESS},
     * {@link Tuner#RESULT_UNAVAILABLE}, {@link Tuner#RESULT_NOT_INITIALIZED},
     * {@link Tuner#RESULT_INVALID_STATE}, {@link Tuner#RESULT_INVALID_ARGUMENT},
     * {@link Tuner#RESULT_OUT_OF_MEMORY}, or {@link Tuner#RESULT_UNKNOWN_ERROR}.
     */
    public int delayCallbackUntilBytesAccumulated(int bytesAccumulated) {
        if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                  TunerVersionChecker.TUNER_VERSION_2_0, "setTimeDelayHint")) {
            return Tuner.RESULT_UNAVAILABLE;
        }

        synchronized (mLock) {
            return nativeSetDataSizeDelayHint(bytesAccumulated);
        }
    }
}
