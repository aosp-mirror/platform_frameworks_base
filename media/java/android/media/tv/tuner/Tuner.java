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

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.tv.tuner.TunerConstants.FilterStatus;
import android.media.tv.tuner.TunerConstants.FilterSubtype;
import android.media.tv.tuner.TunerConstants.FrontendScanType;
import android.media.tv.tuner.TunerConstants.Result;
import android.media.tv.tuner.dvr.Dvr;
import android.media.tv.tuner.dvr.DvrCallback;
import android.media.tv.tuner.dvr.DvrSettings;
import android.media.tv.tuner.filter.FilterConfiguration.FilterType;
import android.media.tv.tuner.filter.FilterEvent;
import android.media.tv.tuner.filter.TimeFilter;
import android.media.tv.tuner.frontend.FrontendCallback;
import android.media.tv.tuner.frontend.FrontendInfo;
import android.media.tv.tuner.frontend.FrontendStatus;
import android.media.tv.tuner.frontend.ScanCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.List;
import java.util.concurrent.Executor;

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
    @Nullable
    private ScanCallback mScanCallback;
    @Nullable
    private Executor mScanCallbackExecutor;

    /**
     * Constructs a Tuner instance.
     *
     * @param context context of the caller.
     */
    public Tuner(@NonNull Context context) {
        mContext = context;
        nativeSetup();
    }

    /**
     * Constructs a Tuner instance.
     *
     * @param context the context of the caller.
     * @param tvInputSessionId the session ID of the TV input.
     * @param useCase the use case of this Tuner instance.
     *
     * @hide
     * TODO: replace the other constructor
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public Tuner(@NonNull Context context, @NonNull String tvInputSessionId, int useCase) {
        mContext = context;
    }

    /**
     * Shares the frontend resource with another Tuner instance
     *
     * @param tuner the Tuner instance to share frontend resource with.
     *
     * @hide
     */
    public void shareFrontend(@NonNull Tuner tuner) { }


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
    private native FrontendStatus nativeGetFrontendStatus(int[] statusTypes);
    private native int nativeGetAvSyncHwId(Filter filter);
    private native long nativeGetAvSyncTime(int avSyncId);
    private native int nativeConnectCiCam(int ciCamId);
    private native int nativeDisconnectCiCam();
    private native FrontendInfo nativeGetFrontendInfo(int id);
    private native Filter nativeOpenFilter(int type, int subType, long bufferSize);
    private native TimeFilter nativeOpenTimeFilter();

    private native List<Integer> nativeGetLnbIds();
    private native Lnb nativeOpenLnbById(int id);

    private native Descrambler nativeOpenDescrambler();

    private native Dvr nativeOpenDvr(int type, long bufferSize);

    private static native DemuxCapabilities nativeGetDemuxCapabilities();


    /**
     * Callback interface for receiving information from the corresponding filters.
     * TODO: remove
     */
    public interface FilterCallback {
        /**
         * Invoked when there are filter events.
         *
         * @param filter the corresponding filter which sent the events.
         * @param events the filter events sent from the filter.
         */
        void onFilterEvent(@NonNull Filter filter, @NonNull FilterEvent[] events);
        /**
         * Invoked when filter status changed.
         *
         * @param filter the corresponding filter whose status is changed.
         * @param status the new status of the filter.
         */
        void onFilterStatusChanged(@NonNull Filter filter, @FilterStatus int status);
    }


    /**
     * Listener for resource lost.
     *
     * @hide
     */
    public interface OnResourceLostListener {
        /**
         * Invoked when resource lost.
         *
         * @param tuner the tuner instance whose resource is being reclaimed.
         */
        void onResourceLost(@NonNull Tuner tuner);
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
                        filter.mCallback.onFilterStatusChanged(filter, msg.arg1);
                    }
                    break;
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
     * <p>If the method completes successfully, the frontend is no longer tuned and no data
     * will be sent to attached filters.
     *
     * @return result status of the operation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int stopTune() {
        TunerUtils.checkTunerPermission(mContext);
        return nativeStopTune();
    }

    /**
     * Scan channels.
     *
     * @param settings A {@link FrontendSettings} to configure the frontend.
     * @param scanType The scan type.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public int scan(@NonNull FrontendSettings settings, @FrontendScanType int scanType,
            @NonNull @CallbackExecutor Executor executor, @NonNull ScanCallback scanCallback) {
        TunerUtils.checkTunerPermission(mContext);
        mScanCallback = scanCallback;
        mScanCallbackExecutor = executor;
        return nativeScan(settings.getType(), settings, scanType);
    }

    /**
     * Stops a previous scanning.
     *
     * <p>
     * The {@link ScanCallback} and it's {@link Executor} will be removed.
     *
     * <p>
     * If the method completes successfully, the frontend stopped previous scanning.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int stopScan() {
        TunerUtils.checkTunerPermission(mContext);
        int retVal = nativeStopScan();
        mScanCallback = null;
        mScanCallbackExecutor = null;
        return retVal;
    }

    /**
     * Sets Low-Noise Block downconverter (LNB) for satellite frontend.
     *
     * <p>This assigns a hardware LNB resource to the satellite tuner. It can be
     * called multiple times to update LNB assignment.
     *
     * @param lnb the LNB instance.
     *
     * @return result status of the operation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int setLnb(@NonNull Lnb lnb) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeSetLnb(lnb.mId);
    }

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     *
     * @param enable {@code true} to activate LNA module; {@code false} to deactivate LNA.
     *
     * @return result status of the operation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int setLna(boolean enable) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeSetLna(enable);
    }

    /**
     * Gets the statuses of the frontend.
     *
     * <p>This retrieve the statuses of the frontend for given status types.
     *
     * @param statusTypes an array of status types which the caller requests.
     * @return statuses which response the caller's requests.
     * @hide
     */
    @Nullable
    public FrontendStatus getFrontendStatus(int[] statusTypes) {
        return nativeGetFrontendStatus(statusTypes);
    }

    /**
     * Gets hardware sync ID for audio and video.
     *
     * @param filter the filter instance for the hardware sync ID.
     * @return the id of hardware A/V sync.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public int getAvSyncHwId(@NonNull Filter filter) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeGetAvSyncHwId(filter);
    }

    /**
     * Gets the current timestamp for Audio/Video sync
     *
     * <p>The timestamp is maintained by hardware. The timestamp based on 90KHz, and it's format is
     * the same as PTS (Presentation Time Stamp).
     *
     * @param avSyncHwId the hardware id of A/V sync.
     * @return the current timestamp of hardware A/V sync.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public long getAvSyncTime(int avSyncHwId) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeGetAvSyncTime(avSyncHwId);
    }

    /**
     * Connects Conditional Access Modules (CAM) through Common Interface (CI)
     *
     * <p>The demux uses the output from the frontend as the input by default, and must change to
     * use the output from CI-CAM as the input after this call.
     *
     * @param ciCamId specify CI-CAM Id to connect.
     * @return result status of the operation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int connectCiCam(int ciCamId) {
        TunerUtils.checkTunerPermission(mContext);
        return nativeConnectCiCam(ciCamId);
    }

    /**
     * Disconnects Conditional Access Modules (CAM)
     *
     * <p>The demux will use the output from the frontend as the input after this call.
     *
     * @return result status of the operation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Result
    public int disconnectCiCam() {
        TunerUtils.checkTunerPermission(mContext);
        return nativeDisconnectCiCam();
    }

    /**
     * Gets the frontend information.
     *
     * @return The frontend information. {@code null} if the operation failed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public FrontendInfo getFrontendInfo() {
        TunerUtils.checkTunerPermission(mContext);
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        return nativeGetFrontendInfo(mFrontend.mId);
    }

    /**
     * Gets the frontend ID.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public int getFrontendId() {
        TunerUtils.checkTunerPermission(mContext);
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        return mFrontend.mId;
    }

    /**
     * Gets Demux capabilities.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public static DemuxCapabilities getDemuxCapabilities(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return nativeGetDemuxCapabilities();
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

    /**
     * Tuner data filter.
     *
     * <p> This class is used to filter wanted data according to the filter's configuration.
     * TODO: remove
     */
    public class Filter {
        FilterCallback mCallback;
        private Filter() {}
    }

    /**
     * Opens a filter object based on the given types and buffer size.
     *
     * @param mainType the main type of the filter.
     * @param subType the subtype of the filter.
     * @param bufferSize the buffer size of the filter to be opened in bytes. The buffer holds the
     * data output from the filter.
     * @param cb the callback to receive notifications from filter.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @return the opened filter. {@code null} if the operation failed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Filter openFilter(@FilterType int mainType, @FilterSubtype int subType,
            @BytesLong long bufferSize, @Nullable FilterCallback cb,
            @CallbackExecutor @Nullable Executor executor) {
        TunerUtils.checkTunerPermission(mContext);
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

    /**
     * Opens an LNB (low-noise block downconverter) object.
     *
     * @param cb the callback to receive notifications from LNB.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @return the opened LNB object. {@code null} if the operation failed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Lnb openLnb(LnbCallback cb, @CallbackExecutor @Nullable Executor executor) {
        TunerUtils.checkTunerPermission(mContext);
        // TODO: use resource manager to get LNB ID.
        return new Lnb(0);
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
     * TODO: Remove
     */
    public class Descrambler {
        private Descrambler() {
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

    /**
     * Open a DVR (Digital Video Record) instance.
     *
     * @param type the DVR type to be opened.
     * @param bufferSize the buffer size of the output in bytes. It's used to hold output data of
     * the attached filters.
     * @param cb the callback to receive notifications from DVR.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @return the opened DVR object. {@code null} if the operation failed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @Nullable
    public Dvr openDvr(@DvrSettings.Type int type, @BytesLong long bufferSize, DvrCallback cb,
            @CallbackExecutor @Nullable Executor executor) {
        TunerUtils.checkTunerPermission(mContext);
        Dvr dvr = nativeOpenDvr(type, bufferSize);
        return dvr;
    }
}
