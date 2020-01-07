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

package android.media.tv.tuner;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.tv.tuner.TunerConstants.DemuxPidType;
import android.media.tv.tuner.TunerConstants.FilterSubtype;
import android.media.tv.tuner.TunerConstants.FilterType;
import android.media.tv.tuner.TunerConstants.FrontendScanType;
import android.media.tv.tuner.TunerConstants.LnbPosition;
import android.media.tv.tuner.TunerConstants.LnbTone;
import android.media.tv.tuner.TunerConstants.LnbVoltage;
import android.media.tv.tuner.TunerConstants.Result;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.FileDescriptor;
import java.util.List;

/**
 * This class is used to interact with hardware tuners devices.
 *
 * <p> Each TvInputService Session should create one instance of this class.
 *
 * <p> This class controls the TIS interaction with Tuner HAL.
 *
 * @hide
 */
@SystemApi
public final class Tuner implements AutoCloseable  {
    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = false;

    private static final int MSG_ON_FRONTEND_EVENT = 1;
    private static final int MSG_ON_FILTER_EVENT = 2;
    private static final int MSG_ON_FILTER_STATUS = 3;
    private static final int MSG_ON_LNB_EVENT = 4;

    static {
        System.loadLibrary("media_tv_tuner");
        nativeInit();
    }

    private final Context mContext;

    private List<Integer> mFrontendIds;
    private Frontend mFrontend;
    private EventHandler mHandler;

    private List<Integer> mLnbIds;
    private Lnb mLnb;

    /**
     * Constructs a Tuner instance.
     *
     * @param context context of the caller.
     */
    public Tuner(@NonNull Context context) {
        mContext = context;
        nativeSetup();
    }

    private long mNativeContext; // used by native jMediaTuner

    /** @hide */
    @Override
    public void close() {}

    /**
     * Native Initialization.
     */
    private static native void nativeInit();

    /**
     * Native setup.
     */
    private native void nativeSetup();

    /**
     * Native method to get all frontend IDs.
     */
    private native List<Integer> nativeGetFrontendIds();

    /**
     * Native method to open frontend of the given ID.
     */
    private native Frontend nativeOpenFrontendById(int id);
    private native int nativeTune(int type, FrontendSettings settings);
    private native int nativeStopTune();
    private native int nativeScan(int settingsType, FrontendSettings settings, int scanType);
    private native int nativeStopScan();
    private native int nativeSetLnb(int lnbId);
    private native int nativeSetLna(boolean enable);
    private native FrontendStatus[] nativeGetFrontendStatus(int[] statusTypes);
    private native int nativeGetAvSyncHwId(Filter filter);
    private native long nativeGetAvSyncTime(int avSyncId);
    private native int nativeConnectCiCam(int ciCamId);
    private native int nativeDisconnectCiCam();
    private native FrontendInfo nativeGetFrontendInfo(int id);
    private native Filter nativeOpenFilter(int type, int subType, int bufferSize);

    private native List<Integer> nativeGetLnbIds();
    private native Lnb nativeOpenLnbById(int id);

    private native Descrambler nativeOpenDescrambler();

    private native Dvr nativeOpenDvr(int type, int bufferSize);

    /**
     * Frontend Callback.
     *
     * @hide
     */
    public interface FrontendCallback {

        /**
         * Invoked when there is a frontend event.
         */
        void onEvent(int frontendEventType);

        /**
         * Invoked when there is a scan message.
         * @param msg
         */
        void onScanMessage(ScanMessage msg);
    }

    /**
     * LNB Callback.
     *
     * @hide
     */
    public interface LnbCallback {
        /**
         * Invoked when there is a LNB event.
         */
        void onEvent(int lnbEventType);

        /**
         * Invoked when there is a new DiSEqC message.
         *
         * @param diseqcMessage a byte array of data for DiSEqC (Digital Satellite
         * Equipment Control) message which is specified by EUTELSAT Bus Functional
         * Specification Version 4.2.
         */
        void onDiseqcMessage(byte[] diseqcMessage);
    }

    /**
     * Frontend Callback.
     *
     * @hide
     */
    public interface FilterCallback {
        /**
         * Invoked when there are filter events.
         */
        void onFilterEvent(FilterEvent[] events);
        /**
         * Invoked when filter status changed.
         */
        void onFilterStatus(int status);
    }

    /**
     * DVR Callback.
     *
     * @hide
     */
    public interface DvrCallback {
        /**
         * Invoked when record status changed.
         */
        void onRecordStatus(int status);
        /**
         * Invoked when playback status changed.
         */
        void onPlaybackStatus(int status);
    }

    @Nullable
    private EventHandler createEventHandler() {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            return new EventHandler(looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            return new EventHandler(looper);
        }
        return null;
    }

    private class EventHandler extends Handler {
        private EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_FRONTEND_EVENT:
                    if (mFrontend != null && mFrontend.mCallback != null) {
                        mFrontend.mCallback.onEvent(msg.arg1);
                    }
                    break;
                case MSG_ON_FILTER_STATUS: {
                    Filter filter = (Filter) msg.obj;
                    if (filter.mCallback != null) {
                        filter.mCallback.onFilterStatus(msg.arg1);
                    }
                    break;
                }
                case MSG_ON_LNB_EVENT: {
                    if (mLnb != null && mLnb.mCallback != null) {
                        mLnb.mCallback.onEvent(msg.arg1);
                    }
                }
                default:
                    // fall through
            }
        }
    }

    private class Frontend {
        private int mId;
        private FrontendCallback mCallback;

        private Frontend(int id) {
            mId = id;
        }

        public void setCallback(@Nullable FrontendCallback callback, @Nullable Handler handler) {
            mCallback = callback;

            if (mCallback == null) {
                return;
            }

            if (handler == null) {
                // use default looper if handler is null
                if (mHandler == null) {
                    mHandler = createEventHandler();
                }
                return;
            }

            Looper looper = handler.getLooper();
            if (mHandler != null && mHandler.getLooper() == looper) {
                // the same looper. reuse mHandler
                return;
            }
            mHandler = new EventHandler(looper);
        }
    }

    /**
     * Tunes the frontend to the settings given.
     *
     * @return result status of tune operation.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * TODO: add result constants or throw exceptions.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public int tune(@NonNull FrontendSettings settings) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeTune(settings.getType(), settings);
    }

    /**
     * Stops a previous tuning.
     *
     * If the method completes successfully the frontend is no longer tuned and no data
     * will be sent to attached filters.
     *
     * @return result status of the operation.
     * @hide
     */
    public int stopTune() {
        return nativeStopTune();
    }

    /**
     * Scan channels.
     * @hide
     */
    public int scan(@NonNull FrontendSettings settings, @FrontendScanType int scanType) {
        return nativeScan(settings.getType(), settings, scanType);
    }

    /**
     * Stops a previous scanning.
     *
     * If the method completes successfully, the frontend stop previous scanning.
     * @hide
     */
    public int stopScan() {
        return nativeStopScan();
    }

    /**
     * Sets Low-Noise Block downconverter (LNB) for satellite frontend.
     *
     * This assigns a hardware LNB resource to the satellite tuner. It can be
     * called multiple times to update LNB assignment.
     *
     * @param lnb the LNB instance.
     *
     * @return result status of the operation.
     * @hide
     */
    public int setLnb(@NonNull Lnb lnb) {
        return nativeSetLnb(lnb.mId);
    }

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     *
     * @param enable true to activate LNA module; false to deactivate LNA
     *
     * @return result status of the operation.
     * @hide
     */
    public int setLna(boolean enable) {
        return nativeSetLna(enable);
    }

    /**
     * Gets the statuses of the frontend.
     *
     * This retrieve the statuses of the frontend for given status types.
     *
     * @param statusTypes an array of status type which the caller request.
     *
     * @return statuses an array of statuses which response the caller's
     *         request.
     * @hide
     */
    public FrontendStatus[] getFrontendStatus(int[] statusTypes) {
        return nativeGetFrontendStatus(statusTypes);
    }

    /**
     * Gets hardware sync ID for audio and video.
     *
     * @param filter the filter instance for the hardware sync ID.
     * @return the id of hardware A/V sync.
     * @hide
     */
    public int getAvSyncHwId(Filter filter) {
        return nativeGetAvSyncHwId(filter);
    }
    /**
     * Gets the current timestamp for A/V sync
     *
     * The timestamp is maintained by hardware. The timestamp based on 90KHz, and it's format is the
     * same as PTS (Presentation Time Stamp).
     *
     * @param avSyncHwId the hardware id of A/V sync.
     * @return the current timestamp of hardware A/V sync.
     * @hide
     */
    public long getAvSyncTime(int avSyncHwId) {
        return nativeGetAvSyncTime(avSyncHwId);
    }


    /**
     * Connects Conditional Access Modules (CAM) through Common Interface (CI)
     *
     * The demux uses the output from the frontend as the input by default, and must change to use
     * the output from CI-CAM as the input after this call.
     *
     * @param ciCamId specify CI-CAM Id to connect.
     * @return result status of the operation.
     * @hide
     */
    @Result
    public int connectCiCam(int ciCamId) {
        return nativeConnectCiCam(ciCamId);
    }

    /**
     * Disconnects Conditional Access Modules (CAM)
     *
     * The demux will use the output from the frontend as the input after this call.
     *
     * @return result status of the operation.
     * @hide
     */
    @Result
    public int disconnectCiCam() {
        return nativeDisconnectCiCam();
    }

    /**
     * Retrieve the frontend information.
     * @hide
     */
    public FrontendInfo getFrontendInfo() {
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        return nativeGetFrontendInfo(mFrontend.mId);
    }

    /**
     * Gets frontend ID.
     * @hide
     */
    public int getFrontendId() {
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        return mFrontend.mId;
    }

    private List<Integer> getFrontendIds() {
        mFrontendIds = nativeGetFrontendIds();
        return mFrontendIds;
    }

    private Frontend openFrontendById(int id) {
        if (mFrontendIds == null) {
            mFrontendIds = getFrontendIds();
        }
        if (!mFrontendIds.contains(id)) {
            return null;
        }
        mFrontend = nativeOpenFrontendById(id);
        return mFrontend;
    }

    private void onFrontendEvent(int eventType) {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_FRONTEND_EVENT, eventType, 0));
        }
    }

    /** @hide */
    public class Filter {
        private long mNativeContext;
        private FilterCallback mCallback;
        int mId;

        private native int nativeConfigureFilter(int type, int subType, FilterSettings settings);
        private native int nativeGetId();
        private native int nativeSetDataSource(Filter source);
        private native int nativeStartFilter();
        private native int nativeStopFilter();
        private native int nativeFlushFilter();
        private native int nativeRead(byte[] buffer, int offset, int size);
        private native int nativeClose();

        private Filter(int id) {
            mId = id;
        }

        private void onFilterStatus(int status) {
            if (mHandler != null) {
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_ON_FILTER_STATUS, status, 0, this));
            }
        }

        /**
         * Configures the filter.
         *
         * @param settings the settings of the filter.
         * @return result status of the operation.
         */
        public int configure(FilterSettings settings) {
            int subType = -1;
            if (settings.mSettings != null) {
                subType = settings.mSettings.getType();
            }
            return nativeConfigureFilter(settings.getType(), subType, settings);
        }

        /**
         * Gets the filter Id.
         *
         * @return the hardware resource Id for the filter.
         */
        public int getId() {
            return nativeGetId();
        }

        /**
         * Sets the filter's data source.
         *
         * A filter uses demux as data source by default. If the data was packetized
         * by multiple protocols, multiple filters may need to work together to
         * extract all protocols' header. Then a filter's data source can be output
         * from another filter.
         *
         * @param source the filter instance which provides data input. Switch to
         * use demux as data source if the filter instance is NULL.
         * @return result status of the operation.
         */
        public int setDataSource(@Nullable Filter source) {
            return nativeSetDataSource(source);
        }

        /**
         * Starts the filter.
         *
         * @return result status of the operation.
         */
        public int start() {
            return nativeStartFilter();
        }


        /**
         * Stops the filter.
         *
         * @return result status of the operation.
         */
        public int stop() {
            return nativeStopFilter();
        }

        /**
         * Flushes the filter.
         *
         * @return result status of the operation.
         */
        public int flush() {
            return nativeFlushFilter();
        }

        public int read(@NonNull byte[] buffer, int offset, int size) {
            size = Math.min(size, buffer.length - offset);
            return nativeRead(buffer, offset, size);
        }

        /**
         * Release the Filter instance.
         *
         * @return result status of the operation.
         */
        public int close() {
            return nativeClose();
        }
    }

    private Filter openFilter(@FilterType int mainType, @FilterSubtype int subType, int bufferSize,
            FilterCallback cb) {
        Filter filter = nativeOpenFilter(
                mainType, TunerUtils.getFilterSubtype(mainType, subType), bufferSize);
        if (filter != null) {
            filter.mCallback = cb;
            if (mHandler == null) {
                mHandler = createEventHandler();
            }
        }
        return filter;
    }

    /** @hide */
    public class Lnb {
        private int mId;
        private LnbCallback mCallback;

        private native int nativeSetVoltage(int voltage);
        private native int nativeSetTone(int tone);
        private native int nativeSetSatellitePosition(int position);
        private native int nativeSendDiseqcMessage(byte[] message);
        private native int nativeClose();

        private Lnb(int id) {
            mId = id;
        }

        public void setCallback(@Nullable LnbCallback callback) {
            mCallback = callback;
            if (mCallback == null) {
                return;
            }
            if (mHandler == null) {
                mHandler = createEventHandler();
            }
        }

        /**
         * Sets the LNB's power voltage.
         *
         * @param voltage the power voltage the Lnb to use.
         * @return result status of the operation.
         */
        @Result
        public int setVoltage(@LnbVoltage int voltage) {
            return nativeSetVoltage(voltage);
        }

        /**
         * Sets the LNB's tone mode.
         *
         * @param tone the tone mode the Lnb to use.
         * @return result status of the operation.
         */
        @Result
        public int setTone(@LnbTone int tone) {
            return nativeSetTone(tone);
        }

        /**
         * Selects the LNB's position.
         *
         * @param position the position the Lnb to use.
         * @return result status of the operation.
         */
        @Result
        public int setSatellitePosition(@LnbPosition int position) {
            return nativeSetSatellitePosition(position);
        }

        /**
         * Sends DiSEqC (Digital Satellite Equipment Control) message.
         *
         * The response message from the device comes back through callback onDiseqcMessage.
         *
         * @param message a byte array of data for DiSEqC message which is specified by EUTELSAT Bus
         *         Functional Specification Version 4.2.
         *
         * @return result status of the operation.
         */
        @Result
        public int sendDiseqcMessage(byte[] message) {
            return nativeSendDiseqcMessage(message);
        }

        /**
         * Releases the LNB instance
         *
         * @return result status of the operation.
         */
        @Result
        public int close() {
            return nativeClose();
        }
    }

    private List<Integer> getLnbIds() {
        mLnbIds = nativeGetLnbIds();
        return mLnbIds;
    }

    private Lnb openLnbById(int id) {
        if (mLnbIds == null) {
            mLnbIds = getLnbIds();
        }
        if (!mLnbIds.contains(id)) {
            return null;
        }
        mLnb = nativeOpenLnbById(id);
        return mLnb;
    }

    private void onLnbEvent(int eventType) {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_LNB_EVENT, eventType, 0));
        }
    }

    /**
     * This class is used to interact with descramblers.
     *
     * <p> Descrambler is a hardware component used to descramble data.
     *
     * <p> This class controls the TIS interaction with Tuner HAL.
     * TODO: make it static and extends Closable.
     */
    public class Descrambler {
        private long mNativeContext;

        private native int nativeAddPid(int pidType, int pid, Filter filter);
        private native int nativeRemovePid(int pidType, int pid, Filter filter);
        private native int nativeSetKeyToken(byte[] keyToken);
        private native int nativeClose();

        private Descrambler() {}

        /**
         * Add packets' PID to the descrambler for descrambling.
         *
         * The descrambler will start descrambling packets with this PID. Multiple PIDs can be added
         * into one descrambler instance because descambling can happen simultaneously on packets
         * from different PIDs.
         *
         * If the Descrambler previously contained a filter for the PID, the old filter is replaced
         * by the specified filter.
         *
         * @param pidType the type of the PID.
         * @param pid the PID of packets to start to be descrambled.
         * @param filter an optional filter instance to identify upper stream.
         * @return result status of the operation.
         *
         * @hide
         */
        public int addPid(@DemuxPidType int pidType, int pid, @Nullable Filter filter) {
            return nativeAddPid(pidType, pid, filter);
        }

        /**
         * Remove packets' PID from the descrambler
         *
         * The descrambler will stop descrambling packets with this PID.
         *
         * @param pidType the type of the PID.
         * @param pid the PID of packets to stop to be descrambled.
         * @param filter an optional filter instance to identify upper stream.
         * @return result status of the operation.
         *
         * @hide
         */
        public int removePid(@DemuxPidType int pidType, int pid, @Nullable Filter filter) {
            return nativeRemovePid(pidType, pid, filter);
        }

        /**
         * Set a key token to link descrambler to a key slot
         *
         * A descrambler instance can have only one key slot to link, but a key slot can hold a few
         * keys for different purposes.
         *
         * @param keyToken the token to be used to link the key slot.
         * @return result status of the operation.
         *
         * @hide
         */
        public int setKeyToken(byte[] keyToken) {
            return nativeSetKeyToken(keyToken);
        }

        /**
         * Release the descrambler instance.
         *
         * @return result status of the operation.
         *
         * @hide
         */
        public int close() {
            return nativeClose();
        }

    }

    /**
     * Opens a Descrambler in tuner.
     *
     * @return  a {@link Descrambler} object.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Descrambler openDescrambler() {
        TunerUtils.checkTunerPermission(mContext);
        return nativeOpenDescrambler();
    }

    // TODO: consider splitting Dvr to Playback and Recording
    /** @hide */
    public class Dvr {
        private long mNativeContext;
        private DvrCallback mCallback;

        private native int nativeAttachFilter(Filter filter);
        private native int nativeDetachFilter(Filter filter);
        private native int nativeConfigureDvr(DvrSettings settings);
        private native int nativeStartDvr();
        private native int nativeStopDvr();
        private native int nativeFlushDvr();
        private native int nativeClose();
        private native void nativeSetFileDescriptor(FileDescriptor fd);
        private native int nativeRead(int size);
        private native int nativeRead(byte[] bytes, int offset, int size);

        private Dvr() {}

        /**
         * Attaches a filter to DVR interface for recording.
         *
         * @param filter the filter to be attached.
         * @return result status of the operation.
         */
        public int attachFilter(Filter filter) {
            return nativeAttachFilter(filter);
        }

        /**
         * Detaches a filter from DVR interface.
         *
         * @param filter the filter to be detached.
         * @return result status of the operation.
         */
        public int detachFilter(Filter filter) {
            return nativeDetachFilter(filter);
        }

        /**
         * Configures the DVR.
         *
         * @param settings the settings of the DVR interface.
         * @return result status of the operation.
         */
        public int configure(DvrSettings settings) {
            return nativeConfigureDvr(settings);
        }

        /**
         * Starts DVR.
         *
         * Starts consuming playback data or producing data for recording.
         *
         * @return result status of the operation.
         */
        public int start() {
            return nativeStartDvr();
        }

        /**
         * Stops DVR.
         *
         * Stops consuming playback data or producing data for recording.
         *
         * @return result status of the operation.
         */
        public int stop() {
            return nativeStopDvr();
        }

        /**
         * Flushed DVR data.
         *
         * @return result status of the operation.
         */
        public int flush() {
            return nativeFlushDvr();
        }

        /**
         * closes the DVR instance to release resources.
         *
         * @return result status of the operation.
         */
        public int close() {
            return nativeClose();
        }

        /**
         * Sets file descriptor to read/write data.
         */
        public void setFileDescriptor(FileDescriptor fd) {
            nativeSetFileDescriptor(fd);
        }

        /**
         * Reads data from the file for DVR playback.
         */
        public int read(int size) {
            return nativeRead(size);
        }

        /**
         * Reads data from the buffer for DVR playback.
         */
        public int read(@NonNull byte[] bytes, int offset, int size) {
            if (size + offset > bytes.length) {
                throw new ArrayIndexOutOfBoundsException(
                        "Array length=" + bytes.length + ", offset=" + offset + ", size=" + size);
            }
            return nativeRead(bytes, offset, size);
        }
    }

    private Dvr openDvr(int type, int bufferSize) {
        Dvr dvr = nativeOpenDvr(type, bufferSize);
        return dvr;
    }
}
