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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.tv.tuner.Constant;
import android.hardware.tv.tuner.Constant64Bit;
import android.hardware.tv.tuner.FrontendScanType;
import android.media.tv.TvInputService;
import android.media.tv.tuner.dvr.DvrPlayback;
import android.media.tv.tuner.dvr.DvrRecorder;
import android.media.tv.tuner.dvr.OnPlaybackStatusChangedListener;
import android.media.tv.tuner.dvr.OnRecordStatusChangedListener;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.Filter.Subtype;
import android.media.tv.tuner.filter.Filter.Type;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.tuner.filter.SharedFilter;
import android.media.tv.tuner.filter.SharedFilterCallback;
import android.media.tv.tuner.filter.TimeFilter;
import android.media.tv.tuner.frontend.Atsc3PlpInfo;
import android.media.tv.tuner.frontend.FrontendInfo;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.FrontendStatus;
import android.media.tv.tuner.frontend.FrontendStatus.FrontendStatusType;
import android.media.tv.tuner.frontend.FrontendStatusReadiness;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

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
    public static final int INVALID_TS_PID = Constant.INVALID_TS_PID;
    /**
     * Invalid stream ID.
     */
    public static final int INVALID_STREAM_ID = Constant.INVALID_STREAM_ID;
    /**
     * Invalid filter ID.
     */
    public static final int INVALID_FILTER_ID = Constant.INVALID_FILTER_ID;
    /**
     * Invalid AV Sync ID.
     */
    public static final int INVALID_AV_SYNC_ID = Constant.INVALID_AV_SYNC_ID;
    /**
     * Invalid timestamp.
     *
     * <p>Returned by {@link android.media.tv.tuner.filter.TimeFilter#getSourceTime()},
     * {@link android.media.tv.tuner.filter.TimeFilter#getTimeStamp()},
     * {@link Tuner#getAvSyncTime(int)} or {@link TsRecordEvent#getPts()} and
     * {@link MmtpRecordEvent#getPts()} when the requested timestamp is not available.
     *
     * @see android.media.tv.tuner.filter.TimeFilter#getSourceTime()
     * @see android.media.tv.tuner.filter.TimeFilter#getTimeStamp()
     * @see Tuner#getAvSyncTime(int)
     * @see android.media.tv.tuner.filter.TsRecordEvent#getPts()
     * @see android.media.tv.tuner.filter.MmtpRecordEvent#getPts()
     */
    public static final long INVALID_TIMESTAMP =
            Constant64Bit.INVALID_PRESENTATION_TIME_STAMP;
    /**
     * Invalid mpu sequence number in MmtpRecordEvent.
     *
     * <p>Returned by {@link MmtpRecordEvent#getMpuSequenceNumber()} when the requested sequence
     * number is not available.
     *
     * @see android.media.tv.tuner.filter.MmtpRecordEvent#getMpuSequenceNumber()
     */
    public static final int INVALID_MMTP_RECORD_EVENT_MPT_SEQUENCE_NUM =
            Constant.INVALID_MMTP_RECORD_EVENT_MPT_SEQUENCE_NUM;
    /**
     * Invalid first macroblock address in MmtpRecordEvent and TsRecordEvent.
     *
     * <p>Returned by {@link MmtpRecordEvent#getMbInSlice()} and
     * {@link TsRecordEvent#getMbInSlice()} when the requested sequence number is not available.
     *
     * @see android.media.tv.tuner.filter.MmtpRecordEvent#getMbInSlice()
     * @see android.media.tv.tuner.filter.TsRecordEvent#getMbInSlice()
     */
    public static final int INVALID_FIRST_MACROBLOCK_IN_SLICE =
            Constant.INVALID_FIRST_MACROBLOCK_IN_SLICE;
    /**
     * Invalid local transport stream id.
     *
     * <p>Returned by {@link #linkFrontendToCiCam(int)} when the requested failed
     * or the hal implementation does not support the operation.
     *
     * @see #linkFrontendToCiCam(int)
     */
    public static final int INVALID_LTS_ID = Constant.INVALID_LTS_ID;
    /**
     * Invalid 64-bit filter ID.
     */
    public static final long INVALID_FILTER_ID_LONG = Constant64Bit.INVALID_FILTER_ID_64BIT;
    /**
     * Invalid frequency that is used as the default frontend frequency setting.
     */
    public static final int INVALID_FRONTEND_SETTING_FREQUENCY =
            Constant.INVALID_FRONTEND_SETTING_FREQUENCY;
    /**
     * Invalid frontend id.
     */
    public static final int INVALID_FRONTEND_ID = Constant.INVALID_FRONTEND_ID;
    /**
     * Invalid LNB id.
     *
     * @hide
     */
    public static final int INVALID_LNB_ID = Constant.INVALID_LNB_ID;
    /**
     * A void key token. It is used to remove the current key from descrambler.
     *
     * <p>If the current keyToken comes from a MediaCas session, App is recommended to
     * to use this constant to remove current key before closing MediaCas session.
     */
    @NonNull
    public static final byte[] VOID_KEYTOKEN = {Constant.INVALID_KEYTOKEN};

    /** @hide */
    @IntDef(prefix = "SCAN_TYPE_", value = {SCAN_TYPE_UNDEFINED, SCAN_TYPE_AUTO, SCAN_TYPE_BLIND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanType {}
    /**
     * Scan type undefined.
     */
    public static final int SCAN_TYPE_UNDEFINED = FrontendScanType.SCAN_UNDEFINED;
    /**
     * Scan type auto.
     *
     * <p> Tuner will send {@link android.media.tv.tuner.frontend.ScanCallback#onLocked}
     */
    public static final int SCAN_TYPE_AUTO = FrontendScanType.SCAN_AUTO;
    /**
     * Blind scan.
     *
     * <p>Frequency range is not specified. The {@link android.media.tv.tuner.Tuner} will scan an
     * implementation specific range.
     */
    public static final int SCAN_TYPE_BLIND = FrontendScanType.SCAN_BLIND;


    /** @hide */
    @IntDef({RESULT_SUCCESS, RESULT_UNAVAILABLE, RESULT_NOT_INITIALIZED, RESULT_INVALID_STATE,
            RESULT_INVALID_ARGUMENT, RESULT_OUT_OF_MEMORY, RESULT_UNKNOWN_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * Operation succeeded.
     */
    public static final int RESULT_SUCCESS = android.hardware.tv.tuner.Result.SUCCESS;
    /**
     * Operation failed because the corresponding resources are not available.
     */
    public static final int RESULT_UNAVAILABLE = android.hardware.tv.tuner.Result.UNAVAILABLE;
    /**
     * Operation failed because the corresponding resources are not initialized.
     */
    public static final int RESULT_NOT_INITIALIZED =
            android.hardware.tv.tuner.Result.NOT_INITIALIZED;
    /**
     * Operation failed because it's not in a valid state.
     */
    public static final int RESULT_INVALID_STATE = android.hardware.tv.tuner.Result.INVALID_STATE;
    /**
     * Operation failed because there are invalid arguments.
     */
    public static final int RESULT_INVALID_ARGUMENT =
            android.hardware.tv.tuner.Result.INVALID_ARGUMENT;
    /**
     * Memory allocation failed.
     */
    public static final int RESULT_OUT_OF_MEMORY = android.hardware.tv.tuner.Result.OUT_OF_MEMORY;
    /**
     * Operation failed due to unknown errors.
     */
    public static final int RESULT_UNKNOWN_ERROR = android.hardware.tv.tuner.Result.UNKNOWN_ERROR;



    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MSG_RESOURCE_LOST = 1;
    private static final int MSG_ON_FILTER_EVENT = 2;
    private static final int MSG_ON_FILTER_STATUS = 3;
    private static final int MSG_ON_LNB_EVENT = 4;

    private static final int FILTER_CLEANUP_THRESHOLD = 256;

    /** @hide */
    @IntDef(prefix = "DVR_TYPE_", value = {DVR_TYPE_RECORD, DVR_TYPE_PLAYBACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DvrType {}

    /**
     * DVR for recording.
     * @hide
     */
    public static final int DVR_TYPE_RECORD = android.hardware.tv.tuner.DvrType.RECORD;
    /**
     * DVR for playback of recorded programs.
     * @hide
     */
    public static final int DVR_TYPE_PLAYBACK = android.hardware.tv.tuner.DvrType.PLAYBACK;

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
    private static int sTunerVersion = TunerVersionChecker.TUNER_VERSION_UNKNOWN;

    private Frontend mFrontend;
    private EventHandler mHandler;
    @Nullable
    private FrontendInfo mFrontendInfo;
    private Integer mFrontendHandle;
    private Tuner mFeOwnerTuner = null;
    private int mFrontendType = FrontendSettings.TYPE_UNDEFINED;
    private int mUserId;
    private Lnb mLnb;
    private Integer mLnbHandle;
    @Nullable
    private OnTuneEventListener mOnTuneEventListener;
    @Nullable
    private Executor mOnTuneEventExecutor;
    @Nullable
    private ScanCallback mScanCallback;
    @Nullable
    private Executor mScanCallbackExecutor;
    @Nullable
    private OnResourceLostListener mOnResourceLostListener;
    @Nullable
    private Executor mOnResourceLostListenerExecutor;

    private final Object mOnTuneEventLock = new Object();
    private final Object mScanCallbackLock = new Object();
    private final Object mOnResourceLostListenerLock = new Object();
    private final ReentrantLock mFrontendLock = new ReentrantLock();
    private final ReentrantLock mLnbLock = new ReentrantLock();
    private final ReentrantLock mFrontendCiCamLock = new ReentrantLock();
    private final ReentrantLock mDemuxLock = new ReentrantLock();
    private int mRequestedCiCamId;

    private Integer mDemuxHandle;
    private Integer mFrontendCiCamHandle;
    private Integer mFrontendCiCamId;
    private Map<Integer, WeakReference<Descrambler>> mDescramblers = new HashMap<>();
    private List<WeakReference<Filter>> mFilters = new ArrayList<WeakReference<Filter>>();

    private final TunerResourceManager.ResourcesReclaimListener mResourceListener =
            new TunerResourceManager.ResourcesReclaimListener() {
                @Override
                public void onReclaimResources() {
                    if (mFrontend != null) {
                        FrameworkStatsLog
                                .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                                    FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__UNKNOWN);
                    }
                    releaseAll();
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
        sTunerVersion = nativeGetTunerVersion();
        if (sTunerVersion == TunerVersionChecker.TUNER_VERSION_UNKNOWN) {
            Log.e(TAG, "Unknown Tuner version!");
        } else {
            Log.d(TAG, "Current Tuner version is "
                    + TunerVersionChecker.getMajorVersion(sTunerVersion) + "."
                    + TunerVersionChecker.getMinorVersion(sTunerVersion) + ".");
        }
        mContext = context;
        mTunerResourceManager = (TunerResourceManager)
                context.getSystemService(Context.TV_TUNER_RESOURCE_MGR_SERVICE);
        if (mHandler == null) {
            mHandler = createEventHandler();
        }

        int[] clientId = new int[1];
        ResourceClientProfile profile = new ResourceClientProfile();
        profile.tvInputSessionId = tvInputSessionId;
        profile.useCase = useCase;
        mTunerResourceManager.registerClientProfile(
                profile, Runnable::run, mResourceListener, clientId);
        mClientId = clientId[0];

        mUserId = Process.myUid();
    }

    /**
     * Get frontend info list from native and build them into a {@link FrontendInfo} list. Any
     * {@code null} FrontendInfo element would be removed.
     */
    private FrontendInfo[] getFrontendInfoListInternal() {
        List<Integer> ids = getFrontendIds();
        if (ids == null) {
            return null;
        }
        FrontendInfo[] infos = new FrontendInfo[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            FrontendInfo frontendInfo = getFrontendInfoById(id);
            if (frontendInfo == null) {
                Log.e(TAG, "Failed to get a FrontendInfo on frontend id:" + id + "!");
                continue;
            }
            infos[i] = frontendInfo;
        }
        return Arrays.stream(infos).filter(Objects::nonNull).toArray(FrontendInfo[]::new);
    }

    /** @hide */
    public static int getTunerVersion() {
        return sTunerVersion;
    }

    /** @hide */
    public List<Integer> getFrontendIds() {
        mFrontendLock.lock();
        try {
            return nativeGetFrontendIds();
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Sets the listener for resource lost.
     *
     * @param executor the executor on which the listener should be invoked.
     * @param listener the listener that will be run.
     */
    public void setResourceLostListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnResourceLostListener listener) {
        synchronized (mOnResourceLostListenerLock) {
            Objects.requireNonNull(executor, "OnResourceLostListener must not be null");
            Objects.requireNonNull(listener, "executor must not be null");
            mOnResourceLostListener = listener;
            mOnResourceLostListenerExecutor = executor;
        }
    }

    /**
     * Removes the listener for resource lost.
     */
    public void clearResourceLostListener() {
        synchronized (mOnResourceLostListenerLock) {
            mOnResourceLostListener = null;
            mOnResourceLostListenerExecutor = null;
        }
    }

    /**
     * Shares the frontend resource with another Tuner instance
     *
     * @param tuner the Tuner instance to share frontend resource with.
     */
    public void shareFrontendFromTuner(@NonNull Tuner tuner) {
        acquireTRMSLock("shareFrontendFromTuner()");
        mFrontendLock.lock();
        try {
            mTunerResourceManager.shareFrontend(mClientId, tuner.mClientId);
            mFeOwnerTuner = tuner;
            mFeOwnerTuner.registerFrontendCallbackListener(this);
            mFrontendHandle = mFeOwnerTuner.mFrontendHandle;
            mFrontend = mFeOwnerTuner.mFrontend;
            nativeShareFrontend(mFrontend.mId);
        } finally {
            releaseTRMSLock();
            mFrontendLock.unlock();
        }
    }

    /**
     * Transfers the ownership of shared frontend and its associated resources.
     *
     * @param newOwner the Tuner instance to be the new owner.
     *
     * @return result status of tune operation.
     */
    public int transferOwner(@NonNull Tuner newOwner) {
        acquireTRMSLock("transferOwner()");
        mFrontendLock.lock();
        mFrontendCiCamLock.lock();
        mLnbLock.lock();
        try {

            if (!isFrontendOwner() || !isNewOwnerQualifiedForTransfer(newOwner)) {
                return RESULT_INVALID_STATE;
            }

            int res = transferFeOwner(newOwner);
            if (res != RESULT_SUCCESS) {
                return res;
            }

            res = transferCiCamOwner(newOwner);
            if (res != RESULT_SUCCESS) {
                return res;
            }

            res = transferLnbOwner(newOwner);
            if (res != RESULT_SUCCESS) {
                return res;
            }
        } finally {
            mFrontendLock.unlock();
            mFrontendCiCamLock.unlock();
            mLnbLock.unlock();
            releaseTRMSLock();
        }
        return RESULT_SUCCESS;
    }

    /**
     * Resets or copies Frontend related settings.
     */
    private void replicateFrontendSettings(@Nullable Tuner src) {
        mFrontendLock.lock();
        try {
            if (src == null) {
                if (DEBUG) {
                    Log.d(TAG, "resetting Frontend params for " + mClientId);
                }
                mFrontend = null;
                mFrontendHandle = null;
                mFrontendInfo = null;
                mFrontendType = FrontendSettings.TYPE_UNDEFINED;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "copying Frontend params from " + src.mClientId
                            + " to " + mClientId);
                }
                mFrontend = src.mFrontend;
                mFrontendHandle = src.mFrontendHandle;
                mFrontendInfo = src.mFrontendInfo;
                mFrontendType = src.mFrontendType;
            }
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Sets the frontend owner. mFeOwnerTuner should be null for the owner Tuner instance.
     */
    private void setFrontendOwner(Tuner owner) {
        mFrontendLock.lock();
        try {
            mFeOwnerTuner = owner;
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Resets or copies the CiCam related settings.
     */
    private void replicateCiCamSettings(@Nullable Tuner src) {
        mFrontendCiCamLock.lock();
        try {
            if (src == null) {
                if (DEBUG) {
                    Log.d(TAG, "resetting CiCamParams: " + mClientId);
                }
                mFrontendCiCamHandle = null;
                mFrontendCiCamId = null;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "copying CiCamParams from " + src.mClientId + " to " + mClientId);
                    Log.d(TAG, "mFrontendCiCamHandle:" + src.mFrontendCiCamHandle + ", "
                            + "mFrontendCiCamId:" + src.mFrontendCiCamId);
                }
                mFrontendCiCamHandle = src.mFrontendCiCamHandle;
                mFrontendCiCamId = src.mFrontendCiCamId;
            }
        } finally {
            mFrontendCiCamLock.unlock();
        }
    }

    /**
     * Resets or copies Lnb related settings.
     */
    private void replicateLnbSettings(@Nullable Tuner src) {
        mLnbLock.lock();
        try {
            if (src == null) {
                if (DEBUG) {
                    Log.d(TAG, "resetting Lnb params");
                }
                mLnb = null;
                mLnbHandle = null;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "copying Lnb params from " + src.mClientId + " to " + mClientId);
                }
                mLnb = src.mLnb;
                mLnbHandle = src.mLnbHandle;
            }
        } finally {
            mLnbLock.unlock();
        }
    }

    /**
     * Checks if it is a frontend resource owner.
     * Proper mutex must be held prior to calling this.
     */
    private boolean isFrontendOwner() {
        boolean notAnOwner = (mFeOwnerTuner != null);
        if (notAnOwner) {
            Log.e(TAG, "transferOwner() - cannot be called on the non-owner");
            return false;
        }
        return true;
    }

    /**
     * Checks if the newOwner is qualified.
     * Proper mutex must be held prior to calling this.
     */
    private boolean isNewOwnerQualifiedForTransfer(@NonNull Tuner newOwner) {
        // new owner must be the current sharee
        boolean newOwnerIsTheCurrentSharee = (newOwner.mFeOwnerTuner == this)
                && (newOwner.mFrontendHandle.equals(mFrontendHandle));
        if (!newOwnerIsTheCurrentSharee) {
            Log.e(TAG, "transferOwner() - new owner must be the current sharee");
            return false;
        }

        // new owner must not be holding any of the to-be-shared resources
        boolean newOwnerAlreadyHoldsToBeSharedResource =
                (newOwner.mFrontendCiCamHandle != null || newOwner.mLnb != null);
        if (newOwnerAlreadyHoldsToBeSharedResource) {
            Log.e(TAG, "transferOwner() - new owner cannot be holding CiCam"
                    + " nor Lnb resource");
            return false;
        }

        return true;
    }

    /**
     * Transfers the ownership of the already held frontend resource.
     * Proper mutex must be held prior to calling this.
     */
    private int transferFeOwner(@NonNull Tuner newOwner) {
        // handle native resource first
        newOwner.nativeUpdateFrontend(getNativeContext());
        nativeUpdateFrontend(0);

        // transfer frontend related settings
        newOwner.replicateFrontendSettings(this);

        // transfer the frontend owner info
        setFrontendOwner(newOwner);
        newOwner.setFrontendOwner(null);

        // handle TRM
        if (mTunerResourceManager.transferOwner(
                TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND,
                mClientId, newOwner.mClientId)) {
            return RESULT_SUCCESS;
        } else {
            return RESULT_UNKNOWN_ERROR;
        }
    }

    /**
     * Transfers the ownership of CiCam resource.
     * This is a no-op if the CiCam resource is not held.
     * Proper mutex must be held prior to calling this.
     */
    private int transferCiCamOwner(Tuner newOwner) {
        boolean notAnOwner = (mFrontendCiCamHandle == null);
        if (notAnOwner) {
            // There is nothing to do here if there is no CiCam
            return RESULT_SUCCESS;
        }

        // no need to handle at native level

        // transfer the CiCam info at Tuner level
        newOwner.replicateCiCamSettings(this);
        replicateCiCamSettings(null);

        // handle TRM
        if (mTunerResourceManager.transferOwner(
                TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM,
                mClientId, newOwner.mClientId)) {
            return RESULT_SUCCESS;
        } else {
            return RESULT_UNKNOWN_ERROR;
        }
    }

    /**
     * Transfers the ownership of Lnb resource.
     * This is a no-op if the Lnb resource is not held.
     * Proper mutex must be held prior to calling this.
     */
    private int transferLnbOwner(Tuner newOwner) {
        boolean notAnOwner = (mLnb == null);
        if (notAnOwner) {
            // There is nothing to do here if there is no Lnb
            return RESULT_SUCCESS;
        }

        // no need to handle at native level

        // set the new owner
        mLnb.setOwner(newOwner);

        newOwner.replicateLnbSettings(this);
        replicateLnbSettings(null);

        // handle TRM
        if (mTunerResourceManager.transferOwner(
                TunerResourceManager.TUNER_RESOURCE_TYPE_LNB,
                mClientId, newOwner.mClientId)) {
            return RESULT_SUCCESS;
        } else {
            return RESULT_UNKNOWN_ERROR;
        }
    }

    /**
     * Updates client priority with an arbitrary value along with a nice value.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to reclaim insufficient resources from another client.
     *
     * <p>The nice value represents how much the client intends to give up the resource when an
     * insufficient resource situation happens.
     *
     * @param priority the new priority. Any negative value would cause no-op on priority setting
     *                 and the API would only process nice value setting in that case.
     * @param niceValue the nice value.
     */
    @RequiresPermission(android.Manifest.permission.TUNER_RESOURCE_ACCESS)
    public void updateResourcePriority(int priority, int niceValue) {
        mTunerResourceManager.updateClientPriority(mClientId, priority, niceValue);
    }

    /**
     * Checks if there is an unused frontend resource available.
     *
     * @param frontendType {@link android.media.tv.tuner.frontend.FrontendSettings.Type} for the
     * query to be done for.
     */
    @RequiresPermission(android.Manifest.permission.TUNER_RESOURCE_ACCESS)
    public boolean hasUnusedFrontend(int frontendType) {
        return mTunerResourceManager.hasUnusedFrontend(frontendType);
    }

    /**
     * Checks if the calling Tuner object has the lowest priority as a client to
     * {@link TunerResourceManager}
     *
     * <p>The priority comparison is done against the current holders of the frontend resource.
     *
     * <p>The behavior of this function is independent of the availability of unused resources.
     *
     * <p>The function returns {@code true} in any of the following sceanrios:
     * <ul>
     * <li>The caller has the priority <= other clients</li>
     * <li>No one is holding the frontend resource of the specified type</li>
     * <li>The caller is the only one who is holding the resource</li>
     * <li>The frontend resource of the specified type does not exist</li>
     *
     * </ul>
     * @param frontendType {@link android.media.tv.tuner.frontend.FrontendSettings.Type} for the
     * query to be done for.
     *
     * @return {@code false} only if someone else with strictly lower priority is holding the
     *         resourece.
     *         {@code true} otherwise.
     */
    public boolean isLowestPriority(int frontendType) {
        return mTunerResourceManager.isLowestPriority(mClientId, frontendType);
    }

    private long mNativeContext; // used by native jMediaTuner

    /**
     * Registers a tuner as a listener for frontend callbacks.
     */
    private void registerFrontendCallbackListener(Tuner tuner) {
        nativeRegisterFeCbListener(tuner.getNativeContext());
    }

    /**
     * Unregisters a tuner as a listener for frontend callbacks.
     */
    private void unregisterFrontendCallbackListener(Tuner tuner) {
        nativeUnregisterFeCbListener(tuner.getNativeContext());
    }

    /**
     * Returns the pointer to the associated JTuner.
     */
    long getNativeContext() {
        return mNativeContext;
    }

    /**
     * Releases the Tuner instance.
     */
    @Override
    public void close() {
        acquireTRMSLock("close()");
        try {
            releaseAll();
            TunerUtils.throwExceptionForResult(nativeClose(), "failed to close tuner");
        } finally {
            releaseTRMSLock();
        }
    }

    /**
     * Either unshares the frontend resource (for sharee) or release Frontend (for owner)
     */
    public void closeFrontend() {
        acquireTRMSLock("closeFrontend()");
        try {
            releaseFrontend();
        } finally {
            releaseTRMSLock();
        }
    }

    /**
     * Releases frontend resource for the owner. Unshares frontend resource for the sharee.
     */
    private void releaseFrontend() {
        if (DEBUG) {
            Log.d(TAG, "Tuner#releaseFrontend");
        }
        mFrontendLock.lock();
        try {
            if (mFrontendHandle != null) {
                if (DEBUG) {
                    Log.d(TAG, "mFrontendHandle not null");
                }
                if (mFeOwnerTuner != null) {
                    if (DEBUG) {
                        Log.d(TAG, "mFeOwnerTuner not null - sharee");
                    }
                    // unregister self from the Frontend callback
                    mFeOwnerTuner.unregisterFrontendCallbackListener(this);
                    mFeOwnerTuner = null;
                    nativeUnshareFrontend();
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "mFeOwnerTuner null - owner");
                    }
                    // close resource as owner
                    int res = nativeCloseFrontend(mFrontendHandle);
                    if (res != Tuner.RESULT_SUCCESS) {
                        TunerUtils.throwExceptionForResult(res, "failed to close frontend");
                    }
                }
                if (DEBUG) {
                    Log.d(TAG, "call TRM#releaseFrontend :" + mFrontendHandle + ", " + mClientId);
                }
                mTunerResourceManager.releaseFrontend(mFrontendHandle, mClientId);
                FrameworkStatsLog
                        .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                        FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__UNKNOWN);
                replicateFrontendSettings(null);
            }
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Releases CiCam resource if held. No-op otherwise.
     */
    private void releaseCiCam() {
        mFrontendCiCamLock.lock();
        try {
            if (mFrontendCiCamHandle != null) {
                if (DEBUG) {
                    Log.d(TAG, "unlinking CiCam : " + mFrontendCiCamHandle + " for " +  mClientId);
                }
                int result = nativeUnlinkCiCam(mFrontendCiCamId);
                if (result == RESULT_SUCCESS) {
                    mTunerResourceManager.releaseCiCam(mFrontendCiCamHandle, mClientId);
                    replicateCiCamSettings(null);
                } else {
                    Log.e(TAG, "nativeUnlinkCiCam(" + mFrontendCiCamHandle + ") for mClientId:"
                            + mClientId + "failed with result:" + result);
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "NOT unlinking CiCam : " + mClientId);
                }
            }
        } finally {
            mFrontendCiCamLock.unlock();
        }
    }

    private void releaseAll() {
        // release CiCam before frontend because frontend handle is needed to unlink CiCam
        releaseCiCam();

        releaseFrontend();

        mLnbLock.lock();
        try {
            // mLnb will be non-null only for owner tuner
            if (mLnb != null) {
                if (DEBUG) {
                    Log.d(TAG, "calling mLnb.close() : " + mClientId);
                }
                mLnb.close();
            } else {
                if (DEBUG) {
                    Log.d(TAG, "NOT calling mLnb.close() : " + mClientId);
                }
            }
        } finally {
            mLnbLock.unlock();
        }


        synchronized (mDescramblers) {
            if (!mDescramblers.isEmpty()) {
                for (Map.Entry<Integer, WeakReference<Descrambler>> d : mDescramblers.entrySet()) {
                    Descrambler descrambler = d.getValue().get();
                    if (descrambler != null) {
                        descrambler.close();
                    }
                    mTunerResourceManager.releaseDescrambler(d.getKey(), mClientId);
                }
                mDescramblers.clear();
            }
        }

        synchronized (mFilters) {
            if (!mFilters.isEmpty()) {
                for (WeakReference<Filter> weakFilter : mFilters) {
                    Filter filter = weakFilter.get();
                    if (filter != null) {
                        filter.close();
                    }
                }
                mFilters.clear();
            }
        }

        mDemuxLock.lock();
        try {
            if (mDemuxHandle != null) {
                int res = nativeCloseDemux(mDemuxHandle);
                if (res != Tuner.RESULT_SUCCESS) {
                    TunerUtils.throwExceptionForResult(res, "failed to close demux");
                }
                mTunerResourceManager.releaseDemux(mDemuxHandle, mClientId);
                mDemuxHandle = null;
            }
        } finally {
            mDemuxLock.unlock();
        }

        mTunerResourceManager.unregisterClientProfile(mClientId);

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
    private native int nativeGetTunerVersion();

    /**
     * Native method to get all frontend IDs.
     */
    private native List<Integer> nativeGetFrontendIds();

    /**
     * Native method to open frontend of the given ID.
     */
    private native Frontend nativeOpenFrontendByHandle(int handle);
    private native int nativeShareFrontend(int id);
    private native int nativeUnshareFrontend();
    private native void nativeRegisterFeCbListener(long nativeContext);
    private native void nativeUnregisterFeCbListener(long nativeContext);
    // nativeUpdateFrontend must be called on the new owner first
    private native void nativeUpdateFrontend(long nativeContext);
    @Result
    private native int nativeTune(int type, FrontendSettings settings);
    private native int nativeStopTune();
    private native int nativeScan(int settingsType, FrontendSettings settings, int scanType);
    private native int nativeStopScan();
    private native int nativeSetLnb(Lnb lnb);
    private native int nativeSetLna(boolean enable);
    private native FrontendStatus nativeGetFrontendStatus(int[] statusTypes);
    private native Integer nativeGetAvSyncHwId(Filter filter);
    private native Long nativeGetAvSyncTime(int avSyncId);
    private native int nativeConnectCiCam(int ciCamId);
    private native int nativeLinkCiCam(int ciCamId);
    private native int nativeDisconnectCiCam();
    private native int nativeUnlinkCiCam(int ciCamId);
    private native FrontendInfo nativeGetFrontendInfo(int id);
    private native Filter nativeOpenFilter(int type, int subType, long bufferSize);
    private native TimeFilter nativeOpenTimeFilter();
    private native String nativeGetFrontendHardwareInfo();
    private native int nativeSetMaxNumberOfFrontends(int frontendType, int maxNumber);
    private native int nativeGetMaxNumberOfFrontends(int frontendType);
    private native int nativeRemoveOutputPid(int pid);
    private native Lnb nativeOpenLnbByHandle(int handle);
    private native Lnb nativeOpenLnbByName(String name);
    private native FrontendStatusReadiness[] nativeGetFrontendStatusReadiness(int[] statusTypes);

    private native Descrambler nativeOpenDescramblerByHandle(int handle);
    private native int nativeOpenDemuxByhandle(int handle);

    private native DvrRecorder nativeOpenDvrRecorder(long bufferSize);
    private native DvrPlayback nativeOpenDvrPlayback(long bufferSize);

    private native DemuxCapabilities nativeGetDemuxCapabilities();

    private native int nativeCloseDemux(int handle);
    private native int nativeCloseFrontend(int handle);
    private native int nativeClose();

    private static native SharedFilter nativeOpenSharedFilter(String token);

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
                    synchronized (mOnResourceLostListenerLock) {
                        if (mOnResourceLostListener != null
                                && mOnResourceLostListenerExecutor != null) {
                            mOnResourceLostListenerExecutor.execute(() -> {
                                synchronized (mOnResourceLostListenerLock) {
                                    if (mOnResourceLostListener != null) {
                                        mOnResourceLostListener.onResourceLost(Tuner.this);
                                    }
                                }
                            });
                        }
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
        synchronized (mOnTuneEventLock) {
            mOnTuneEventListener = eventListener;
            mOnTuneEventExecutor = executor;
        }
    }

    /**
     * Clears the {@link OnTuneEventListener} and its associated {@link Executor}.
     *
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    public void clearOnTuneEventListener() {
        synchronized (mOnTuneEventLock) {
            mOnTuneEventListener = null;
            mOnTuneEventExecutor = null;
        }
    }

    /**
     * Tunes the frontend to using the settings given.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to get frontend resource. If the client can't get the resource, this call returns {@link
     * #RESULT_UNAVAILABLE}.
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
     * <p>Tuning with {@link android.media.tv.tuner.frontend.DtmbFrontendSettings} is only
     * supported in Tuner 1.1 or higher version. Unsupported version will cause no-op. Use {@link
     * TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * <p>Tuning with {@link
     * android.media.tv.tuner.frontend.IsdbtFrontendSettings.PartialReceptionFlag} or {@link
     * android.media.tv.tuner.frontend.IsdbtFrontendSettings.IsdbtLayerSettings} is only supported
     * in Tuner 2.0 or higher version. Unsupported version will cause no-op. Use {@link
     * TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * @param settings Signal delivery information the frontend uses to
     *                 search and lock the signal.
     * @return result status of tune operation.
     * @throws SecurityException if the caller does not have appropriate permissions.
     * @see #setOnTuneEventListener(Executor, OnTuneEventListener)
     */
    @Result
    public int tune(@NonNull FrontendSettings settings) {
        mFrontendLock.lock();
        try {
            final int type = settings.getType();
            if (mFrontendHandle != null && type != mFrontendType) {
                Log.e(TAG, "Frontend was opened with type " + mFrontendType
                        + ", new type is " + type);
                return RESULT_INVALID_STATE;
            }
            Log.d(TAG, "Tune to " + settings.getFrequencyLong());
            mFrontendType = type;
            if (mFrontendType == FrontendSettings.TYPE_DTMB) {
                if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "Tuner with DTMB Frontend")) {
                    return RESULT_UNAVAILABLE;
                }
            }

            if (checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND, mFrontendLock)) {
                mFrontendInfo = null;
                Log.d(TAG, "Write Stats Log for tuning.");
                FrameworkStatsLog
                        .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                            FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__TUNING);
                int res = nativeTune(settings.getType(), settings);
                return res;
            } else {
                return RESULT_UNAVAILABLE;
            }
        } finally {
            mFrontendLock.unlock();
        }
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
        mFrontendLock.lock();
        try {
            return nativeStopTune();
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Scan for channels.
     *
     * <p>Details for channels found are returned via {@link ScanCallback}.
     *
     * <p>Scanning with {@link android.media.tv.tuner.frontend.DtmbFrontendSettings} is only
     * supported in Tuner 1.1 or higher version. Unsupported version will cause no-op. Use {@link
     * TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * * <p>Scanning with {@link
     * android.media.tv.tuner.frontend.IsdbtFrontendSettings.PartialReceptionFlag} or {@link
     * android.media.tv.tuner.frontend.IsdbtFrontendSettings.IsdbtLayerSettings} is only supported
     * in Tuner 2.0 or higher version. Unsupported version will cause no-op. Use {@link
     * TunerVersionChecker#getTunerVersion()} to get the version information.
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

        mFrontendLock.lock();
        try {
            synchronized (mScanCallbackLock) {
                // Scan can be called again for blink scan if scanCallback and executor are same as
                //before.
                if (((mScanCallback != null) && (mScanCallback != scanCallback))
                        || ((mScanCallbackExecutor != null)
                            && (mScanCallbackExecutor != executor))) {
                    throw new IllegalStateException(
                        "Different Scan session already in progress.  stopScan must be called "
                            + "before a new scan session can be " + "started.");
                }
                mFrontendType = settings.getType();
                if (mFrontendType == FrontendSettings.TYPE_DTMB) {
                    if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                            TunerVersionChecker.TUNER_VERSION_1_1,
                            "Scan with DTMB Frontend")) {
                        return RESULT_UNAVAILABLE;
                    }
                }
                if (checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND,
                          mFrontendLock)) {
                    mScanCallback = scanCallback;
                    mScanCallbackExecutor = executor;
                    mFrontendInfo = null;
                    FrameworkStatsLog
                            .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                            FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__SCANNING);
                    return nativeScan(settings.getType(), settings, scanType);
                }
                return RESULT_UNAVAILABLE;
            }
        } finally {
            mFrontendLock.unlock();
        }
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
        mFrontendLock.lock();
        try {
            synchronized (mScanCallbackLock) {
                FrameworkStatsLog.write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                        FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__SCAN_STOPPED);

                int retVal = nativeStopScan();
                mScanCallback = null;
                mScanCallbackExecutor = null;
                return retVal;
            }
        } finally {
            mFrontendLock.unlock();
        }
    }

    private boolean requestFrontend() {
        int[] feHandle = new int[1];
        TunerFrontendRequest request = new TunerFrontendRequest();
        request.clientId = mClientId;
        request.frontendType = mFrontendType;
        boolean granted = mTunerResourceManager.requestFrontend(request, feHandle);
        if (granted) {
            mFrontendHandle = feHandle[0];
            mFrontend = nativeOpenFrontendByHandle(mFrontendHandle);
        }

        // For satellite type, set Lnb if valid handle exists.
        // This is necessary as now that we support closeFrontend().
        if (mFrontendType == FrontendSettings.TYPE_DVBS
                || mFrontendType == FrontendSettings.TYPE_ISDBS
                || mFrontendType == FrontendSettings.TYPE_ISDBS3) {
            mLnbLock.lock();
            try {
                if (mLnbHandle != null && mLnb != null) {
                    nativeSetLnb(mLnb);
                }
            } finally {
                mLnbLock.unlock();
            }
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
        mLnbLock.lock();
        try {
            return nativeSetLnb(lnb);
        } finally {
            mLnbLock.unlock();
        }
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
     * @param statusTypes an array of status types which the caller requests. Any types that are not
     *        in {@link FrontendInfo#getStatusCapabilities()} would be ignored.
     * @return statuses which response the caller's requests. {@code null} if the operation failed.
     */
    @Nullable
    public FrontendStatus getFrontendStatus(@NonNull @FrontendStatusType int[] statusTypes) {
        mFrontendLock.lock();
        try {
            if (mFrontend == null) {
                throw new IllegalStateException("frontend is not initialized");
            }
            return nativeGetFrontendStatus(statusTypes);
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Gets hardware sync ID for audio and video.
     *
     * @param filter the filter instance for the hardware sync ID.
     * @return the id of hardware A/V sync.
     */
    public int getAvSyncHwId(@NonNull Filter filter) {
        mDemuxLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return INVALID_AV_SYNC_ID;
            }
            Integer id = nativeGetAvSyncHwId(filter);
            return id == null ? INVALID_AV_SYNC_ID : id;
        } finally {
            mDemuxLock.unlock();
        }
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
        mDemuxLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return INVALID_TIMESTAMP;
            }
            Long time = nativeGetAvSyncTime(avSyncHwId);
            return time == null ? INVALID_TIMESTAMP : time;
        } finally {
            mDemuxLock.unlock();
        }
    }

    /**
     * Connects Conditional Access Modules (CAM) through Common Interface (CI).
     *
     * <p>The demux uses the output from the frontend as the input by default, and must change to
     * use the output from CI-CAM as the input after this call.
     *
     * <p> Note that this API is used to connect the CI-CAM to the Demux module while
     * {@link #connectFrontendToCiCam(int)} is used to connect CI-CAM to the Frontend module.
     *
     * @param ciCamId specify CI-CAM Id to connect.
     * @return result status of the operation.
     */
    @Result
    public int connectCiCam(int ciCamId) {
        mDemuxLock.lock();
        try {
            if (checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return nativeConnectCiCam(ciCamId);
            }
            return RESULT_UNAVAILABLE;
        } finally {
            mDemuxLock.unlock();
        }
    }

    /**
     * Connect Conditional Access Modules (CAM) Frontend to support Common Interface (CI)
     * by-pass mode.
     *
     * <p>It is used by the client to link CI-CAM to a Frontend. CI by-pass mode requires that
     * the CICAM also receives the TS concurrently from the frontend when the Demux is receiving
     * the TS directly from the frontend.
     *
     * <p> Note that this API is used to connect the CI-CAM to the Frontend module while
     * {@link #connectCiCam(int)} is used to connect CI-CAM to the Demux module.
     *
     * <p>Use {@link #disconnectFrontendToCiCam(int)} to disconnect.
     *
     * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
     * no-op and return {@link #INVALID_LTS_ID}. Use {@link TunerVersionChecker#getTunerVersion()}
     * to check the version.
     *
     * @param ciCamId specify CI-CAM Id, which is the id of the Conditional Access Modules (CAM)
     *                Common Interface (CI), to link.
     * @return Local transport stream id when connection is successfully established. Failed
     *         operation returns {@link #INVALID_LTS_ID} while unsupported version also returns
     *         {@link #INVALID_LTS_ID}. Check the current HAL version using
     *         {@link TunerVersionChecker#getTunerVersion()}.
     */
    public int connectFrontendToCiCam(int ciCamId) {
        // TODO: change this so TRMS lock is held only when the resource handles for
        // CiCam/Frontend is null. Current implementation can only handle one local lock for that.
        acquireTRMSLock("connectFrontendToCiCam()");
        mFrontendCiCamLock.lock();
        mFrontendLock.lock();
        try {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1,
                    "linkFrontendToCiCam")) {
                mRequestedCiCamId = ciCamId;
                // No need to unlock mFrontendCiCamLock and mFrontendLock below becauase
                // TRMS lock is already acquired. Pass null to disable lock related operations
                if (checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, null)
                        && checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND, null)
                    ) {
                    return nativeLinkCiCam(ciCamId);
                }
            }
            return INVALID_LTS_ID;
        } finally {
            releaseTRMSLock();
            mFrontendCiCamLock.unlock();
            mFrontendLock.unlock();
        }
    }

    /**
     * Disconnects Conditional Access Modules (CAM).
     *
     * <p>The demux will use the output from the frontend as the input after this call.
     *
     * <p> Note that this API is used to disconnect the CI-CAM to the Demux module while
     * {@link #disconnectFrontendToCiCam(int)} is used to disconnect CI-CAM to the Frontend module.
     *
     * @return result status of the operation.
     */
    @Result
    public int disconnectCiCam() {
        mDemuxLock.lock();
        try {
            if (mDemuxHandle != null) {
                return nativeDisconnectCiCam();
            }
            return RESULT_UNAVAILABLE;
        } finally {
            mDemuxLock.unlock();
        }
    }

    /**
     * Disconnect Conditional Access Modules (CAM) Frontend.
     *
     * <p>It is used by the client to unlink CI-CAM to a Frontend.
     *
     * <p> Note that this API is used to disconnect the CI-CAM to the Demux module while
     * {@link #disconnectCiCam(int)} is used to disconnect CI-CAM to the Frontend module.
     *
     * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
     * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * @param ciCamId specify CI-CAM Id, which is the id of the Conditional Access Modules (CAM)
     *                Common Interface (CI), to disconnect.
     * @return result status of the operation. Unsupported version would return
     *         {@link #RESULT_UNAVAILABLE}
     */
    @Result
    public int disconnectFrontendToCiCam(int ciCamId) {
        acquireTRMSLock("disconnectFrontendToCiCam()");
        try {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1,
                    "unlinkFrontendToCiCam")) {
                mFrontendCiCamLock.lock();
                if (mFrontendCiCamHandle != null && mFrontendCiCamId != null
                        && mFrontendCiCamId == ciCamId) {
                    int result = nativeUnlinkCiCam(ciCamId);
                    if (result == RESULT_SUCCESS) {
                        mTunerResourceManager.releaseCiCam(mFrontendCiCamHandle, mClientId);
                        mFrontendCiCamId = null;
                        mFrontendCiCamHandle = null;
                    }
                    return result;
                }
            }
            return RESULT_UNAVAILABLE;
        } finally {
            if (mFrontendCiCamLock.isLocked()) {
                mFrontendCiCamLock.unlock();
            }
            releaseTRMSLock();
        }
    }

    /**
     * Remove PID (packet identifier) from frontend output.
     *
     * <p>It is used by the client to remove a video or audio PID of other program to reduce the
     * total amount of recorded TS.
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported version would cause
     * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * @return result status of the operation. Unsupported version or if current active frontend
     *         doesn’t support PID filtering out would return {@link #RESULT_UNAVAILABLE}.
     * @throws IllegalStateException if there is no active frontend currently.
     */
    @Result
    public int removeOutputPid(@IntRange(from = 0) int pid) {
        mFrontendLock.lock();
        try {
            if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "Remove output PID")) {
                return RESULT_UNAVAILABLE;
            }
            if (mFrontend == null) {
                throw new IllegalStateException("frontend is not initialized");
            }
            return nativeRemoveOutputPid(pid);
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Gets Frontend Status Readiness statuses for given status types.
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported versions would cause
     * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * @param statusTypes an array of status types.
     *
     * @return a list of current readiness states. It is empty if the operation fails or unsupported
     *         versions.
     * @throws IllegalStateException if there is no active frontend currently.
     */
    @NonNull
    public List<FrontendStatusReadiness> getFrontendStatusReadiness(
            @NonNull @FrontendStatusType int[] statusTypes) {
        mFrontendLock.lock();
        try {
            if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "Get fronted status readiness")) {
                return Collections.EMPTY_LIST;
            }
            if (mFrontend == null) {
                throw new IllegalStateException("frontend is not initialized");
            }
            FrontendStatusReadiness[] readiness = nativeGetFrontendStatusReadiness(statusTypes);
            if (readiness == null) {
                return Collections.EMPTY_LIST;
            }
            return Arrays.asList(readiness);
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Gets the currently initialized and activated frontend information. To get all the available
     * frontend info on the device, use {@link getAvailableFrontendInfos()}.
     *
     * @return The active frontend information. {@code null} if the operation failed.
     * @throws IllegalStateException if there is no active frontend currently.
     */
    @Nullable
    public FrontendInfo getFrontendInfo() {
        mFrontendLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND, mFrontendLock)) {
                return null;
            }
            if (mFrontend == null) {
                throw new IllegalStateException("frontend is not initialized");
            }
            if (mFrontendInfo == null) {
                mFrontendInfo = getFrontendInfoById(mFrontend.mId);
            }
            return mFrontendInfo;
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Gets a list of all the available frontend information on the device. To get the information
     * of the currently active frontend, use {@link getFrontendInfo()}. The active frontend
     * information is also included in the list of the available frontend information.
     *
     * @return The list of all the available frontend information. {@code null} if the operation
     * failed.
     */
    @Nullable
    @SuppressLint("NullableCollection")
    public List<FrontendInfo> getAvailableFrontendInfos() {
        FrontendInfo[] feInfoList = getFrontendInfoListInternal();
        if (feInfoList == null) {
            return null;
        }
        return Arrays.asList(feInfoList);
    }

    /**
     * Gets the currently initialized and activated frontend hardware information. The return values
     * would differ per device makers. E.g. RF chip version, Demod chip version, detailed status of
     * dvbs blind scan, etc
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported version would return
     * {@code null}. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * @return The active frontend hardware information. {@code null} if the operation failed.
     * @throws IllegalStateException if there is no active frontend currently.
     */
    @Nullable
    public String getCurrentFrontendHardwareInfo() {
        mFrontendLock.lock();
        try {
            if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "Get Frontend hardware info")) {
                return null;
            }
            if (mFrontend == null) {
                throw new IllegalStateException("frontend is not initialized");
            }
            return nativeGetFrontendHardwareInfo();
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Sets the maximum usable frontends number of a given frontend type. It is used to enable or
     * disable frontends when cable connection status is changed by user.
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported version would return
     * {@link RESULT_UNAVAILABLE}. Use {@link TunerVersionChecker#getTunerVersion()} to check the
     * version.
     *
     * @param frontendType the {@link android.media.tv.tuner.frontend.FrontendSettings.Type} which
     *                     the maximum usable number will be set.
     * @param maxNumber the new maximum usable number.
     * @return result status of the operation.
     */
    @Result
    public int setMaxNumberOfFrontends(
            @FrontendSettings.Type int frontendType, @IntRange(from = 0) int maxNumber) {
        if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_2_0, "Set maximum Frontends")) {
            return RESULT_UNAVAILABLE;
        }
        if (maxNumber < 0) {
            return RESULT_INVALID_ARGUMENT;
        }
        int res = nativeSetMaxNumberOfFrontends(frontendType, maxNumber);
        if (res == RESULT_SUCCESS) {
            if (!mTunerResourceManager.setMaxNumberOfFrontends(frontendType, maxNumber)) {
                res = RESULT_INVALID_ARGUMENT;
            }
        }
        return res;
    }

    /**
     * Get the maximum usable frontends number of a given frontend type.
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported version would return
     * {@code -1}. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * @param frontendType the {@link android.media.tv.tuner.frontend.FrontendSettings.Type} which
     *                     the maximum usable number will be queried.
     * @return the maximum usable number of the queried frontend type.
     */
    @IntRange(from = -1)
    public int getMaxNumberOfFrontends(@FrontendSettings.Type int frontendType) {
        if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_2_0, "Set maximum Frontends")) {
            return -1;
        }
        int maxNumFromHAL = nativeGetMaxNumberOfFrontends(frontendType);
        int maxNumFromTRM = mTunerResourceManager.getMaxNumberOfFrontends(frontendType);
        if (maxNumFromHAL != maxNumFromTRM) {
            Log.w(TAG, "max num of usable frontend is out-of-sync b/w " + maxNumFromHAL
                    + " != " + maxNumFromTRM);
        }
        return maxNumFromHAL;
    }

    /** @hide */
    public FrontendInfo getFrontendInfoById(int id) {
        mFrontendLock.lock();
        try {
            return nativeGetFrontendInfo(id);
        } finally {
            mFrontendLock.unlock();
        }
    }

    /**
     * Gets Demux capabilities.
     *
     * @return A {@link DemuxCapabilities} instance that represents the demux capabilities.
     *         {@code null} if the operation failed.
     */
    @Nullable
    public DemuxCapabilities getDemuxCapabilities() {
        mDemuxLock.lock();
        try {
            return nativeGetDemuxCapabilities();
        } finally {
            mDemuxLock.unlock();
        }
    }

    private void onFrontendEvent(int eventType) {
        Log.d(TAG, "Got event from tuning. Event type: " + eventType + " for " + this);
        synchronized (mOnTuneEventLock) {
            if (mOnTuneEventExecutor != null && mOnTuneEventListener != null) {
                mOnTuneEventExecutor.execute(() -> {
                    synchronized (mOnTuneEventLock) {
                        if (mOnTuneEventListener != null) {
                            mOnTuneEventListener.onTuneEvent(eventType);
                        }
                    }
                });
            }
        }

        Log.d(TAG, "Wrote Stats Log for the events from tuning.");
        if (eventType == OnTuneEventListener.SIGNAL_LOCKED) {
            FrameworkStatsLog
                    .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                        FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__LOCKED);
        } else if (eventType == OnTuneEventListener.SIGNAL_NO_SIGNAL) {
            FrameworkStatsLog
                    .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                        FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__NOT_LOCKED);
        } else if (eventType == OnTuneEventListener.SIGNAL_LOST_LOCK) {
            FrameworkStatsLog
                    .write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                        FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__SIGNAL_LOST);
        }
    }

    private void onLocked() {
        Log.d(TAG, "Wrote Stats Log for locked event from scanning.");
        FrameworkStatsLog.write(
                FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__LOCKED);

        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onLocked();
                        }
                    }
                });
            }
        }
    }

    private void onUnlocked() {
        Log.d(TAG, "Wrote Stats Log for unlocked event from scanning.");
        FrameworkStatsLog.write(FrameworkStatsLog.TV_TUNER_STATE_CHANGED, mUserId,
                FrameworkStatsLog.TV_TUNER_STATE_CHANGED__STATE__LOCKED);

        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onUnlocked();
                        }
                    }
                });
            }
        }
    }

    private void onScanStopped() {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onScanStopped();
                        }
                    }
                });
            }
        }
    }

    private void onProgress(int percent) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onProgress(percent);
                        }
                    }
                });
            }
        }
    }

    private void onFrequenciesReport(long[] frequencies) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onFrequenciesLongReported(frequencies);
                        }
                    }
                });
            }
        }
    }

    private void onSymbolRates(int[] rate) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onSymbolRatesReported(rate);
                        }
                    }
                });
            }
        }
    }

    private void onHierarchy(int hierarchy) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onHierarchyReported(hierarchy);
                        }
                    }
                });
            }
        }
    }

    private void onSignalType(int signalType) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onSignalTypeReported(signalType);
                        }
                    }
                });
            }
        }
    }

    private void onPlpIds(int[] plpIds) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onPlpIdsReported(plpIds);
                        }
                    }
                });
            }
        }
    }

    private void onGroupIds(int[] groupIds) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onGroupIdsReported(groupIds);
                        }
                    }
                });
            }
        }
    }

    private void onInputStreamIds(int[] inputStreamIds) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onInputStreamIdsReported(inputStreamIds);
                        }
                    }
                });
            }
        }
    }

    private void onDvbsStandard(int dvbsStandandard) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onDvbsStandardReported(dvbsStandandard);
                        }
                    }
                });
            }
        }
    }

    private void onDvbtStandard(int dvbtStandard) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onDvbtStandardReported(dvbtStandard);
                        }
                    }
                });
            }
        }
    }

    private void onAnalogSifStandard(int sif) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onAnalogSifStandardReported(sif);
                        }
                    }
                });
            }
        }
    }

    private void onAtsc3PlpInfos(Atsc3PlpInfo[] atsc3PlpInfos) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onAtsc3PlpInfosReported(atsc3PlpInfos);
                        }
                    }
                });
            }
        }
    }

    private void onModulationReported(int modulation) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onModulationReported(modulation);
                        }
                    }
                });
            }
        }
    }

    private void onPriorityReported(boolean isHighPriority) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onPriorityReported(isHighPriority);
                        }
                    }
                });
            }
        }
    }

    private void onDvbcAnnexReported(int dvbcAnnex) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onDvbcAnnexReported(dvbcAnnex);
                        }
                    }
                });
            }
        }
    }

    private void onDvbtCellIdsReported(int[] dvbtCellIds) {
        synchronized (mScanCallbackLock) {
            if (mScanCallbackExecutor != null && mScanCallback != null) {
                mScanCallbackExecutor.execute(() -> {
                    synchronized (mScanCallbackLock) {
                        if (mScanCallback != null) {
                            mScanCallback.onDvbtCellIdsReported(dvbtCellIds);
                        }
                    }
                });
            }
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
        mDemuxLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return null;
            }
            Filter filter = nativeOpenFilter(
                    mainType, TunerUtils.getFilterSubtype(mainType, subType), bufferSize);
            if (filter != null) {
                filter.setType(mainType, subType);
                filter.setCallback(cb, executor);
                if (mHandler == null) {
                    mHandler = createEventHandler();
                }
                synchronized (mFilters) {
                    WeakReference<Filter> weakFilter = new WeakReference<Filter>(filter);
                    mFilters.add(weakFilter);
                    if (mFilters.size() > FILTER_CLEANUP_THRESHOLD) {
                        Iterator<WeakReference<Filter>> iterator = mFilters.iterator();
                        while (iterator.hasNext()) {
                            WeakReference<Filter> wFilter = iterator.next();
                            if (wFilter.get() == null) {
                                iterator.remove();
                            }
                        }
                    }
                }
            }
            return filter;
        } finally {
            mDemuxLock.unlock();
        }
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
        mLnbLock.lock();
        try {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(cb, "LnbCallback must not be null");
            if (mLnb != null) {
                mLnb.setCallbackAndOwner(this, executor, cb);
                return mLnb;
            }
            if (checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, mLnbLock)
                    && mLnb != null) {
                mLnb.setCallbackAndOwner(this, executor, cb);
                if (mFrontendHandle != null && mFrontend != null) {
                    setLnb(mLnb);
                }
                return mLnb;
            }
            return null;
        } finally {
            mLnbLock.unlock();
        }
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
        mLnbLock.lock();
        try {
            Objects.requireNonNull(name, "LNB name must not be null");
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(cb, "LnbCallback must not be null");
            Lnb newLnb = nativeOpenLnbByName(name);
            if (newLnb != null) {
                if (mLnb != null) {
                    mLnb.close();
                    mLnbHandle = null;
                }
                mLnb = newLnb;
                mLnb.setCallbackAndOwner(this, executor, cb);
                if (mFrontendHandle != null && mFrontend != null) {
                    setLnb(mLnb);
                }
            }
            return mLnb;
        } finally {
            mLnbLock.unlock();
        }
    }

    private boolean requestLnb() {
        int[] lnbHandle = new int[1];
        TunerLnbRequest request = new TunerLnbRequest();
        request.clientId = mClientId;
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
        mDemuxLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return null;
            }
            return nativeOpenTimeFilter();
        } finally {
            mDemuxLock.unlock();
        }
    }

    /**
     * Opens a Descrambler in tuner.
     *
     * @return a {@link Descrambler} object.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_DESCRAMBLER)
    @Nullable
    public Descrambler openDescrambler() {
        mDemuxLock.lock();
        try {
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return null;
            }
            return requestDescrambler();
        } finally {
            mDemuxLock.unlock();
        }
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
        mDemuxLock.lock();
        try {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(l, "OnRecordStatusChangedListener must not be null");
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return null;
            }
            DvrRecorder dvr = nativeOpenDvrRecorder(bufferSize);
            dvr.setListener(executor, l);
            return dvr;
        } finally {
            mDemuxLock.unlock();
        }
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
        mDemuxLock.lock();
        try {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(l, "OnPlaybackStatusChangedListener must not be null");
            if (!checkResource(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, mDemuxLock)) {
                return null;
            }
            DvrPlayback dvr = nativeOpenDvrPlayback(bufferSize);
            dvr.setListener(executor, l);
            return dvr;
        } finally {
            mDemuxLock.unlock();
        }
    }

    /**
     * Open a shared filter instance.
     *
     * @param context the context of the caller.
     * @param sharedFilterToken the token of the shared filter being opened.
     * @param executor the executor on which callback will be invoked.
     * @param cb the listener to receive notifications from shared filter.
     * @return the opened shared filter object. {@code null} if the operation failed.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_SHARED_FILTER)
    @Nullable
    static public SharedFilter openSharedFilter(@NonNull Context context,
            @NonNull String sharedFilterToken, @CallbackExecutor @NonNull Executor executor,
            @NonNull SharedFilterCallback cb) {
        // TODO: check what happenes when onReclaimResources() is called and see if
        // this needs to be protected with TRMS lock
        Objects.requireNonNull(sharedFilterToken, "sharedFilterToken must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(cb, "SharedFilterCallback must not be null");

        if (context.checkCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_TV_SHARED_FILTER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have ACCESS_TV_SHAREDFILTER permission.");
        }

        SharedFilter filter = nativeOpenSharedFilter(sharedFilterToken);
        if (filter != null) {
            filter.setCallback(cb, executor);
        }
        return filter;
    }

    private boolean requestDemux() {
        int[] demuxHandle = new int[1];
        TunerDemuxRequest request = new TunerDemuxRequest();
        request.clientId = mClientId;
        boolean granted = mTunerResourceManager.requestDemux(request, demuxHandle);
        if (granted) {
            mDemuxHandle = demuxHandle[0];
            nativeOpenDemuxByhandle(mDemuxHandle);
        }
        return granted;
    }

    private Descrambler requestDescrambler() {
        int[] descramblerHandle = new int[1];
        TunerDescramblerRequest request = new TunerDescramblerRequest();
        request.clientId = mClientId;
        boolean granted = mTunerResourceManager.requestDescrambler(request, descramblerHandle);
        if (!granted) {
            return null;
        }
        int handle = descramblerHandle[0];
        Descrambler descrambler = nativeOpenDescramblerByHandle(handle);
        if (descrambler != null) {
            synchronized (mDescramblers) {
                WeakReference weakDescrambler = new WeakReference<Descrambler>(descrambler);
                mDescramblers.put(handle, weakDescrambler);
            }
        } else {
            mTunerResourceManager.releaseDescrambler(handle, mClientId);
        }
        return descrambler;
    }

    private boolean requestFrontendCiCam(int ciCamId) {
        int[] ciCamHandle = new int[1];
        TunerCiCamRequest request = new TunerCiCamRequest();
        request.clientId = mClientId;
        request.ciCamId = ciCamId;
        boolean granted = mTunerResourceManager.requestCiCam(request, ciCamHandle);
        if (granted) {
            mFrontendCiCamHandle = ciCamHandle[0];
            mFrontendCiCamId = ciCamId;
        }
        return granted;
    }

    private boolean checkResource(int resourceType, ReentrantLock localLock)  {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND: {
                if (mFrontendHandle == null && !requestResource(resourceType, localLock)) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_LNB: {
                if (mLnb == null && !requestResource(resourceType, localLock)) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX: {
                if (mDemuxHandle == null && !requestResource(resourceType, localLock)) {
                    return false;
                }
                break;
            }
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM: {
                if (mFrontendCiCamHandle == null && !requestResource(resourceType, localLock)) {
                    return false;
                }
                break;
            }
            default:
                return false;
        }
        return true;
    }

    // Expected flow of how to use this function is:
    // 1) lock the localLock and check if the resource is already held
    // 2) if yes, no need to call this function and continue with the handle with the lock held
    // 3) if no, then first release the held lock and grab the TRMS lock to avoid deadlock
    // 4) grab the local lock again and release the TRMS lock
    // If localLock is null, we'll assume the caller does not want the lock related operations
    private boolean requestResource(int resourceType, ReentrantLock localLock)  {
        boolean enableLockOperations = localLock != null;

        // release the local lock first to avoid deadlock
        if (enableLockOperations) {
            if (localLock.isLocked()) {
                localLock.unlock();
            } else {
                throw new IllegalStateException("local lock must be locked beforehand");
            }
        }

        // now safe to grab TRMS lock
        if (enableLockOperations) {
            acquireTRMSLock("requestResource:" + resourceType);
        }

        try {
            // lock the local lock
            if (enableLockOperations) {
                localLock.lock();
            }
            switch (resourceType) {
                case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND: {
                    return requestFrontend();
                }
                case TunerResourceManager.TUNER_RESOURCE_TYPE_LNB: {
                    return requestLnb();
                }
                case TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX: {
                    return requestDemux();
                }
                case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM: {
                    return requestFrontendCiCam(mRequestedCiCamId);
                }
                default:
                    return false;
            }
        } finally {
            if (enableLockOperations) {
                releaseTRMSLock();
            }
        }
    }

    /* package */ void releaseLnb() {
        acquireTRMSLock("releaseLnb()");
        mLnbLock.lock();
        try {
            if (mLnbHandle != null) {
                // LNB handle can be null if it's opened by name.
                if (DEBUG) {
                    Log.d(TAG, "releasing Lnb");
                }
                mTunerResourceManager.releaseLnb(mLnbHandle, mClientId);
                mLnbHandle = null;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "NOT releasing Lnb because mLnbHandle is null");
                }
            }
            mLnb = null;
        } finally {
            releaseTRMSLock();
            mLnbLock.unlock();
        }
    }

    /** @hide */
    public int getClientId() {
        return mClientId;
    }

    private void acquireTRMSLock(String functionNameForLog) {
        if (DEBUG) {
            Log.d(TAG, "ATTEMPT:acquireLock() in " + functionNameForLog
                    + "for clientId:" + mClientId);
        }
        if (!mTunerResourceManager.acquireLock(mClientId)) {
            Log.e(TAG, "FAILED:acquireLock() in " + functionNameForLog
                    + " for clientId:" + mClientId + " - this can cause deadlock between"
                    + " Tuner API calls and onReclaimResources()");
        }
    }

    private void releaseTRMSLock() {
        mTunerResourceManager.releaseLock(mClientId);
    }
}
