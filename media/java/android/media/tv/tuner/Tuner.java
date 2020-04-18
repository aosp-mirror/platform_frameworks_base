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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.TvInputService;
import android.media.tv.tuner.dvr.DvrPlayback;
import android.media.tv.tuner.dvr.DvrRecorder;
import android.media.tv.tuner.dvr.OnPlaybackStatusChangedListener;
import android.media.tv.tuner.dvr.OnRecordStatusChangedListener;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.Filter.Subtype;
import android.media.tv.tuner.filter.Filter.Type;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.tuner.filter.TimeFilter;
import android.media.tv.tuner.frontend.Atsc3PlpInfo;
import android.media.tv.tuner.frontend.FrontendInfo;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.FrontendStatus;
import android.media.tv.tuner.frontend.FrontendStatus.FrontendStatusType;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
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
public class Tuner implements AutoCloseable  {
    /**
     * Invalid TS packet ID.
     */
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    /**
     * Invalid stream ID.
     */
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;
    /**
     * Invalid filter ID.
     */
    public static final int INVALID_FILTER_ID = Constants.Constant.INVALID_FILTER_ID;
    /**
     * Invalid AV Sync ID.
     */
    public static final int INVALID_AV_SYNC_ID = Constants.Constant.INVALID_AV_SYNC_ID;
    /**
     * Invalid timestamp.
     *
     * <p>Returned by {@link android.media.tv.tuner.filter.TimeFilter#getSourceTime()},
     * {@link android.media.tv.tuner.filter.TimeFilter#getTimeStamp()}, or
     * {@link Tuner#getAvSyncTime(int)} when the requested timestamp is not available.
     *
     * @see android.media.tv.tuner.filter.TimeFilter#getSourceTime()
     * @see android.media.tv.tuner.filter.TimeFilter#getTimeStamp()
     * @see Tuner#getAvSyncTime(int)
     */
    public static final long INVALID_TIMESTAMP = -1L;


    /** @hide */
    @IntDef(prefix = "SCAN_TYPE_", value = {SCAN_TYPE_UNDEFINED, SCAN_TYPE_AUTO, SCAN_TYPE_BLIND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanType {}
    /**
     * Scan type undefined.
     */
    public static final int SCAN_TYPE_UNDEFINED = Constants.FrontendScanType.SCAN_UNDEFINED;
    /**
     * Scan type auto.
     *
     * <p> Tuner will send {@link android.media.tv.tuner.frontend.ScanCallback#onLocked}
     */
    public static final int SCAN_TYPE_AUTO = Constants.FrontendScanType.SCAN_AUTO;
    /**
     * Blind scan.
     *
     * <p>Frequency range is not specified. The {@link android.media.tv.tuner.Tuner} will scan an
     * implementation specific range.
     */
    public static final int SCAN_TYPE_BLIND = Constants.FrontendScanType.SCAN_BLIND;


    /** @hide */
    @IntDef({RESULT_SUCCESS, RESULT_UNAVAILABLE, RESULT_NOT_INITIALIZED, RESULT_INVALID_STATE,
            RESULT_INVALID_ARGUMENT, RESULT_OUT_OF_MEMORY, RESULT_UNKNOWN_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * Operation succeeded.
     */
    public static final int RESULT_SUCCESS = Constants.Result.SUCCESS;
    /**
     * Operation failed because the corresponding resources are not available.
     */
    public static final int RESULT_UNAVAILABLE = Constants.Result.UNAVAILABLE;
    /**
     * Operation failed because the corresponding resources are not initialized.
     */
    public static final int RESULT_NOT_INITIALIZED = Constants.Result.NOT_INITIALIZED;
    /**
     * Operation failed because it's not in a valid state.
     */
    public static final int RESULT_INVALID_STATE = Constants.Result.INVALID_STATE;
    /**
     * Operation failed because there are invalid arguments.
     */
    public static final int RESULT_INVALID_ARGUMENT = Constants.Result.INVALID_ARGUMENT;
    /**
     * Memory allocation failed.
     */
    public static final int RESULT_OUT_OF_MEMORY = Constants.Result.OUT_OF_MEMORY;
    /**
     * Operation failed due to unknown errors.
     */
    public static final int RESULT_UNKNOWN_ERROR = Constants.Result.UNKNOWN_ERROR;



    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = false;

    private static final int MSG_RESOURCE_LOST = 1;
    private static final int MSG_ON_FILTER_EVENT = 2;
    private static final int MSG_ON_FILTER_STATUS = 3;
    private static final int MSG_ON_LNB_EVENT = 4;

    /** @hide */
    @IntDef(prefix = "DVR_TYPE_", value = {DVR_TYPE_RECORD, DVR_TYPE_PLAYBACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DvrType {}

    /**
     * DVR for recording.
     * @hide
     */
    public static final int DVR_TYPE_RECORD = Constants.DvrType.RECORD;
    /**
     * DVR for playback of recorded programs.
     * @hide
     */
    public static final int DVR_TYPE_PLAYBACK = Constants.DvrType.PLAYBACK;

    static {
        try {
            System.loadLibrary("media_tv_tuner");
            nativeInit();
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "tuner JNI library not found!");
        }
    }

    private final Context mContext;
    private final TunerResourceManager mTunerResourceManager;
    private final int mClientId;

    private Frontend mFrontend;
    private EventHandler mHandler;
    @Nullable
    private FrontendInfo mFrontendInfo;
    private Integer mFrontendHandle;
    private int mFrontendType = FrontendSettings.TYPE_UNDEFINED;

    private Lnb mLnb;
    private Integer mLnbHandle;
    @Nullable
    private OnTuneEventListener mOnTuneEventListener;
    @Nullable
    private Executor mOnTunerEventExecutor;
    @Nullable
    private ScanCallback mScanCallback;
    @Nullable
    private Executor mScanCallbackExecutor;
    @Nullable
    private OnResourceLostListener mOnResourceLostListener;
    @Nullable
    private Executor mOnResourceLostListenerExecutor;

    private Integer mDemuxHandle;
    private Integer mDescramblerHandle;
    private Descrambler mDescrambler;

    private final TunerResourceManager.ResourcesReclaimListener mResourceListener =
            new TunerResourceManager.ResourcesReclaimListener() {
                @Override
                public void onReclaimResources() {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_RESOURCE_LOST));
                }
            };

    /**
     * Constructs a Tuner instance.
     *
     * @param context the context of the caller.
     * @param tvInputSessionId the session ID of the TV input.
     * @param useCase the use case of this Tuner instance.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    public Tuner(@NonNull Context context, @Nullable String tvInputSessionId,
            @TvInputService.PriorityHintUseCaseType int useCase) {
        nativeSetup();
        mContext = context;
        mTunerResourceManager = (TunerResourceManager)
                context.getSystemService(Context.TV_TUNER_RESOURCE_MGR_SERVICE);
        if (mHandler == null) {
            mHandler = createEventHandler();
        }

        mHandler = createEventHandler();
        int[] clientId = new int[1];
        ResourceClientProfile profile = new ResourceClientProfile(tvInputSessionId, useCase);
        mTunerResourceManager.registerClientProfile(
                profile, new HandlerExecutor(mHandler), mResourceListener, clientId);
        mClientId = clientId[0];

        setFrontendInfoList();
        setLnbIds();
    }

    private void setFrontendInfoList() {
        List<Integer> ids = getFrontendIds();
        if (ids == null) {
            return;
        }
        TunerFrontendInfo[] infos = new TunerFrontendInfo[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            FrontendInfo frontendInfo = getFrontendInfoById(id);
            if (frontendInfo == null) {
                continue;
            }
            TunerFrontendInfo tunerFrontendInfo = new TunerFrontendInfo(
                    id, frontendInfo.getType(), frontendInfo.getExclusiveGroupId());
            infos[i] = tunerFrontendInfo;
        }
        mTunerResourceManager.setFrontendInfoList(infos);
    }

    /** @hide */
    public List<Integer> getFrontendIds() {
        return nativeGetFrontendIds();
    }

    private void setLnbIds() {
        int[] ids = nativeGetLnbIds();
        if (ids == null) {
            return;
        }
        mTunerResourceManager.setLnbInfoList(ids);
    }

    /**
     * Sets the listener for resource lost.
     *
     * @param executor the executor on which the listener should be invoked.
     * @param listener the listener that will be run.
     */
    public void setResourceLostListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnResourceLostListener listener) {
        Objects.requireNonNull(executor, "OnResourceLostListener must not be null");
        Objects.requireNonNull(listener, "executor must not be null");
        mOnResourceLostListener = listener;
        mOnResourceLostListenerExecutor = executor;
    }

    /**
     * Removes the listener for resource lost.
     */
    public void clearResourceLostListener() {
        mOnResourceLostListener = null;
        mOnResourceLostListenerExecutor = null;
    }

    /**
     * Shares the frontend resource with another Tuner instance
     *
     * @param tuner the Tuner instance to share frontend resource with.
     */
    public void shareFrontendFromTuner(@NonNull Tuner tuner) {
        mTunerResourceManager.shareFrontend(mClientId, tuner.mClientId);
        mFrontendHandle = tuner.mFrontendHandle;
        mFrontend = nativeOpenFrontendByHandle(mFrontendHandle);
    }

    /**
     * Updates client priority with an arbitrary value along with a nice value.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to reclaim insufficient resources from another client.
     * <p>The nice value represents how much the client intends to give up the resource when an
     * insufficient resource situation happens.
     *
     * @param priority the new priority.
     * @param niceValue the nice value.
     */
    public void updateResourcePriority(int priority, int niceValue) {
        mTunerResourceManager.updateClientPriority(mClientId, priority, niceValue);
    }

    private long mNativeContext; // used by native jMediaTuner

    /**
     * Releases the Tuner instance.
     */
    @Override
    public void close() {
        if (mFrontendHandle != null) {
            nativeCloseFrontendByHandle(mFrontendHandle);
            mTunerResourceManager.releaseFrontend(mFrontendHandle, mClientId);
            mFrontendHandle = null;
            mFrontend = null;
        }
        if (mLnb != null) {
            mTunerResourceManager.releaseLnb(mLnbHandle, mClientId);
            mLnb = null;
            mLnbHandle = null;
        }
        TunerUtils.throwExceptionForResult(nativeClose(), "failed to close tuner");
    }

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
    private native Frontend nativeOpenFrontendByHandle(int handle);
    @Result
    private native int nativeCloseFrontendByHandle(int handle);
    private native int nativeTune(int type, FrontendSettings settings);
    private native int nativeStopTune();
    private native int nativeScan(int settingsType, FrontendSettings settings, int scanType);
    private native int nativeStopScan();
    private native int nativeSetLnb(int lnbId);
    private native int nativeSetLna(boolean enable);
    private native FrontendStatus nativeGetFrontendStatus(int[] statusTypes);
    private native Integer nativeGetAvSyncHwId(Filter filter);
    private native Long nativeGetAvSyncTime(int avSyncId);
    private native int nativeConnectCiCam(int ciCamId);
    private native int nativeDisconnectCiCam();
    private native FrontendInfo nativeGetFrontendInfo(int id);
    private native Filter nativeOpenFilter(int type, int subType, long bufferSize);
    private native TimeFilter nativeOpenTimeFilter();

    private native int[] nativeGetLnbIds();
    private native Lnb nativeOpenLnbByHandle(int handle);
    private native Lnb nativeOpenLnbByName(String name);

    private native Descrambler nativeOpenDescramblerByHandle(int handle);
    private native int nativeOpenDemuxByhandle(int handle);

    private native DvrRecorder nativeOpenDvrRecorder(long bufferSize);
    private native DvrPlayback nativeOpenDvrPlayback(long bufferSize);

    private static native DemuxCapabilities nativeGetDemuxCapabilities();

    private native int nativeClose();


    /**
     * Listener for resource lost.
     *
     * <p>Insufficient resources are reclaimed by higher priority clients.
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
                case MSG_ON_FILTER_STATUS: {
                    Filter filter = (Filter) msg.obj;
                    if (filter.getCallback() != null) {
                        filter.getCallback().onFilterStatusChanged(filter, msg.arg1);
                    }
                    break;
                }
                case MSG_RESOURCE_LOST: {
                    if (mOnResourceLostListener != null
                                && mOnResourceLostListenerExecutor != null) {
                        mOnResourceLostListenerExecutor.execute(
                                () -> mOnResourceLostListener.onResourceLost(Tuner.this));
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

        private Frontend(int id) {
            mId = id;
        }
    }

    /**
     * Listens for tune events.
     *
     * <p>
     * Tuner events are started when {@link #tune(FrontendSettings)} is called and end when {@link
     * #cancelTuning()} is called.
     *
     * @param eventListener receives tune events.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #tune(FrontendSettings)
     */
    public void setOnTuneEventListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnTuneEventListener eventListener) {
        mOnTuneEventListener = eventListener;
        mOnTunerEventExecutor = executor;
    }

    /**
     * Clears the {@link OnTuneEventListener} and its associated {@link Executor}.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    public void clearOnTuneEventListener() {
        mOnTuneEventListener = null;
        mOnTunerEventExecutor = null;

    }

    /**
     * Tunes the frontend to using the settings given.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to get frontend resource. If the client can't get the resource, this call returns {@link
     * Result#RESULT_UNAVAILABLE}.
     *
     * <p>
     * This locks the frontend to a frequency by providing signal
     * delivery information. If previous tuning isn't completed, this stop the previous tuning, and
     * start a new tuning.
     *
     * <p>
     * Tune is an async call, with {@link OnTuneEventListener#SIGNAL_LOCKED} and {@link
     * OnTuneEventListener#SIGNAL_NO_SIGNAL} events sent to the {@link OnTuneEventListener}
     * specified in {@link #setOnTuneEventListener(Executor, OnTuneEventListener)}.
     *
     * @param settings Signal delivery information the frontend uses to
     *                 search and lock the signal.
     * @return result status of tune operation.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    @Result
    public int tune(@NonNull FrontendSettings settings) {
        mFrontendType = settings.getType();
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND);

        mFrontendInfo = null;
        return nativeTune(settings.getType(), settings);
    }

    /**
     * Stops a previous tuning.
     *
     * <p>If the method completes successfully, the frontend is no longer tuned and no data
     * will be sent to attached filters.
     *
     * @return result status of the operation.
     */
    @Result
    public int cancelTuning() {
        return nativeStopTune();
    }

    /**
     * Scan for channels.
     *
     * <p>Details for channels found are returned via {@link ScanCallback}.
     *
     * @param settings A {@link FrontendSettings} to configure the frontend.
     * @param scanType The scan type.
     * @throws SecurityException     if the caller does not have appropriate permissions.
     * @throws IllegalStateException if {@code scan} is called again before
     *                               {@link #cancelScanning()} is called.
     */
    @Result
    public int scan(@NonNull FrontendSettings settings, @ScanType int scanType,
            @NonNull @CallbackExecutor Executor executor, @NonNull ScanCallback scanCallback) {
        if (mScanCallback != null || mScanCallbackExecutor != null) {
            throw new IllegalStateException(
                    "Scan already in progress.  stopScan must be called before a new scan can be "
                            + "started.");
        }
        mFrontendType = settings.getType();
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND);
        mScanCallback = scanCallback;
        mScanCallbackExecutor = executor;
        mFrontendInfo = null;
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
     * @throws SecurityException if the caller does not have appropriate permissions.
     */
    @Result
    public int cancelScanning() {
        int retVal = nativeStopScan();
        mScanCallback = null;
        mScanCallbackExecutor = null;
        return retVal;
    }

    private boolean requestFrontend() {
        int[] feHandle = new int[1];
        TunerFrontendRequest request = new TunerFrontendRequest(mClientId, mFrontendType);
        boolean granted = mTunerResourceManager.requestFrontend(request, feHandle);
        if (granted) {
            mFrontendHandle = feHandle[0];
            mFrontend = nativeOpenFrontendByHandle(mFrontendHandle);
        }
        return granted;
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
     */
    @Result
    private int setLnb(@NonNull Lnb lnb) {
        return nativeSetLnb(lnb.mId);
    }

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     *
     * @param enable {@code true} to activate LNA module; {@code false} to deactivate LNA.
     *
     * @return result status of the operation.
     */
    @Result
    public int setLnaEnabled(boolean enable) {
        return nativeSetLna(enable);
    }

    /**
     * Gets the statuses of the frontend.
     *
     * <p>This retrieve the statuses of the frontend for given status types.
     *
     * @param statusTypes an array of status types which the caller requests.
     * @return statuses which response the caller's requests. {@code null} if the operation failed.
     */
    @Nullable
    public FrontendStatus getFrontendStatus(@NonNull @FrontendStatusType int[] statusTypes) {
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        return nativeGetFrontendStatus(statusTypes);
    }

    /**
     * Gets hardware sync ID for audio and video.
     *
     * @param filter the filter instance for the hardware sync ID.
     * @return the id of hardware A/V sync.
     */
    public int getAvSyncHwId(@NonNull Filter filter) {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        Integer id = nativeGetAvSyncHwId(filter);
        return id == null ? INVALID_AV_SYNC_ID : id;
    }

    /**
     * Gets the current timestamp for Audio/Video sync
     *
     * <p>The timestamp is maintained by hardware. The timestamp based on 90KHz, and it's format is
     * the same as PTS (Presentation Time Stamp).
     *
     * @param avSyncHwId the hardware id of A/V sync.
     * @return the current timestamp of hardware A/V sync.
     */
    public long getAvSyncTime(int avSyncHwId) {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        Long time = nativeGetAvSyncTime(avSyncHwId);
        return time == null ? INVALID_TIMESTAMP : time;
    }

    /**
     * Connects Conditional Access Modules (CAM) through Common Interface (CI)
     *
     * <p>The demux uses the output from the frontend as the input by default, and must change to
     * use the output from CI-CAM as the input after this call.
     *
     * @param ciCamId specify CI-CAM Id to connect.
     * @return result status of the operation.
     */
    @Result
    public int connectCiCam(int ciCamId) {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        return nativeConnectCiCam(ciCamId);
    }

    /**
     * Disconnects Conditional Access Modules (CAM)
     *
     * <p>The demux will use the output from the frontend as the input after this call.
     *
     * @return result status of the operation.
     */
    @Result
    public int disconnectCiCam() {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        return nativeDisconnectCiCam();
    }

    /**
     * Gets the frontend information.
     *
     * @return The frontend information. {@code null} if the operation failed.
     */
    @Nullable
    public FrontendInfo getFrontendInfo() {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND);
        if (mFrontend == null) {
            throw new IllegalStateException("frontend is not initialized");
        }
        if (mFrontendInfo == null) {
            mFrontendInfo = getFrontendInfoById(mFrontend.mId);
        }
        return mFrontendInfo;
    }

    /** @hide */
    public FrontendInfo getFrontendInfoById(int id) {
        return nativeGetFrontendInfo(id);
    }

    /**
     * Gets Demux capabilities.
     *
     * @return A {@link DemuxCapabilities} instance that represents the demux capabilities.
     *         {@code null} if the operation failed.
     */
    @Nullable
    public DemuxCapabilities getDemuxCapabilities() {
        return nativeGetDemuxCapabilities();
    }

    private void onFrontendEvent(int eventType) {
        if (mOnTunerEventExecutor != null && mOnTuneEventListener != null) {
            mOnTunerEventExecutor.execute(() -> mOnTuneEventListener.onTuneEvent(eventType));
        }
    }

    private void onLocked() {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onLocked());
        }
    }

    private void onScanStopped() {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onScanStopped());
        }
    }

    private void onProgress(int percent) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onProgress(percent));
        }
    }

    private void onFrequenciesReport(int[] frequency) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onFrequenciesReported(frequency));
        }
    }

    private void onSymbolRates(int[] rate) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onSymbolRatesReported(rate));
        }
    }

    private void onHierarchy(int hierarchy) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onHierarchyReported(hierarchy));
        }
    }

    private void onSignalType(int signalType) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onSignalTypeReported(signalType));
        }
    }

    private void onPlpIds(int[] plpIds) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onPlpIdsReported(plpIds));
        }
    }

    private void onGroupIds(int[] groupIds) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onGroupIdsReported(groupIds));
        }
    }

    private void onInputStreamIds(int[] inputStreamIds) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(
                    () -> mScanCallback.onInputStreamIdsReported(inputStreamIds));
        }
    }

    private void onDvbsStandard(int dvbsStandandard) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(
                    () -> mScanCallback.onDvbsStandardReported(dvbsStandandard));
        }
    }

    private void onDvbtStandard(int dvbtStandard) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onDvbtStandardReported(dvbtStandard));
        }
    }

    private void onAnalogSifStandard(int sif) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(() -> mScanCallback.onAnalogSifStandardReported(sif));
        }
    }

    private void onAtsc3PlpInfos(Atsc3PlpInfo[] atsc3PlpInfos) {
        if (mScanCallbackExecutor != null && mScanCallback != null) {
            mScanCallbackExecutor.execute(
                    () -> mScanCallback.onAtsc3PlpInfosReported(atsc3PlpInfos));
        }
    }

    /**
     * Opens a filter object based on the given types and buffer size.
     *
     * @param mainType the main type of the filter.
     * @param subType the subtype of the filter.
     * @param bufferSize the buffer size of the filter to be opened in bytes. The buffer holds the
     * data output from the filter.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from filter.
     * @return the opened filter. {@code null} if the operation failed.
     */
    @Nullable
    public Filter openFilter(@Type int mainType, @Subtype int subType,
            @BytesLong long bufferSize, @CallbackExecutor @Nullable Executor executor,
            @Nullable FilterCallback cb) {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        Filter filter = nativeOpenFilter(
                mainType, TunerUtils.getFilterSubtype(mainType, subType), bufferSize);
        if (filter != null) {
            filter.setMainType(mainType);
            filter.setSubtype(subType);
            filter.setCallback(cb, executor);
            if (mHandler == null) {
                mHandler = createEventHandler();
            }
        }
        return filter;
    }

    /**
     * Opens an LNB (low-noise block downconverter) object.
     *
     * <p>If there is an existing Lnb object, it will be replace by the newly opened one.
     *
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from LNB.
     * @return the opened LNB object. {@code null} if the operation failed.
     */
    @Nullable
    public Lnb openLnb(@CallbackExecutor @NonNull Executor executor, @NonNull LnbCallback cb) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(cb, "LnbCallback must not be null");
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_LNB);
        if (mLnb != null) {
            mLnb.setCallback(executor, cb);
        }
        return mLnb;
    }

    /**
     * Opens an LNB (low-noise block downconverter) object specified by the give name.
     *
     * @param name the LNB name.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param cb the callback to receive notifications from LNB.
     * @return the opened LNB object. {@code null} if the operation failed.
     */
    @Nullable
    public Lnb openLnbByName(@NonNull String name, @CallbackExecutor @NonNull Executor executor,
            @NonNull LnbCallback cb) {
        Objects.requireNonNull(name, "LNB name must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(cb, "LnbCallback must not be null");
        mLnb = nativeOpenLnbByName(name);
        if (mLnb != null) {
            mLnb.setCallback(executor, cb);
        }
        return mLnb;
    }

    private boolean requestLnb() {
        int[] lnbHandle = new int[1];
        TunerLnbRequest request = new TunerLnbRequest(mClientId);
        boolean granted = mTunerResourceManager.requestLnb(request, lnbHandle);
        if (granted) {
            mLnbHandle = lnbHandle[0];
            mLnb = nativeOpenLnbByHandle(mLnbHandle);
        }
        return granted;
    }

    /**
     * Open a time filter object.
     *
     * @return the opened time filter object. {@code null} if the operation failed.
     */
    @Nullable
    public TimeFilter openTimeFilter() {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        return nativeOpenTimeFilter();
    }

    /**
     * Opens a Descrambler in tuner.
     *
     * @return  a {@link Descrambler} object.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_DESCRAMBLER)
    @Nullable
    public Descrambler openDescrambler() {
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DESCRAMBLER);
        return mDescrambler;
    }

    /**
     * Open a DVR (Digital Video Record) recorder instance.
     *
     * @param bufferSize the buffer size of the output in bytes. It's used to hold output data of
     * the attached filters.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param l the listener to receive notifications from DVR recorder.
     * @return the opened DVR recorder object. {@code null} if the operation failed.
     */
    @Nullable
    public DvrRecorder openDvrRecorder(
            @BytesLong long bufferSize,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OnRecordStatusChangedListener l) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(l, "OnRecordStatusChangedListener must not be null");
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        DvrRecorder dvr = nativeOpenDvrRecorder(bufferSize);
        dvr.setListener(executor, l);
        return dvr;
    }

    /**
     * Open a DVR (Digital Video Record) playback instance.
     *
     * @param bufferSize the buffer size of the output in bytes. It's used to hold output data of
     * the attached filters.
     * @param executor the executor on which callback will be invoked. The default event handler
     * executor is used if it's {@code null}.
     * @param l the listener to receive notifications from DVR recorder.
     * @return the opened DVR playback object. {@code null} if the operation failed.
     */
    @Nullable
    public DvrPlayback openDvrPlayback(
            @BytesLong long bufferSize,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OnPlaybackStatusChangedListener l) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(l, "OnPlaybackStatusChangedListener must not be null");
        checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX);
        DvrPlayback dvr = nativeOpenDvrPlayback(bufferSize);
        dvr.setListener(executor, l);
        return dvr;
    }

    private boolean requestDemux() {
        int[] demuxHandle = new int[1];
        TunerDemuxRequest request = new TunerDemuxRequest(mClientId);
        boolean granted = mTunerResourceManager.requestDemux(request, demuxHandle);
        if (granted) {
            mDemuxHandle = demuxHandle[0];
            nativeOpenDemuxByhandle(mDemuxHandle);
        }
        return granted;
    }

    private boolean requestDescrambler() {
        int[] descramblerHandle = new int[1];
        TunerDescramblerRequest request = new TunerDescramblerRequest(mClientId);
        boolean granted = mTunerResourceManager.requestDescrambler(request, descramblerHandle);
        if (granted) {
            mDescramblerHandle = descramblerHandle[0];
            mDescrambler = nativeOpenDescramblerByHandle(mDescramblerHandle);
        }
        return granted;
    }

    private boolean checkResource(int resourceType)  {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND: {
                if (mFrontendHandle == null && !requestFrontend()) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_LNB: {
                if (mLnbHandle == null && !requestLnb()) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX: {
                if (mDemuxHandle == null && !requestDemux()) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_DESCRAMBLER: {
                if (mDescramblerHandle == null && !requestDescrambler()) {
                    return false;
                }
                break;
            }
        }
        return true;
    }
}
