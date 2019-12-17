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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

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
    }

    /**
     * Frontend Callback.
     *
     * @hide
     */
    public interface FilterCallback {
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
        private native boolean nativeStartFilter();
        private native boolean nativeStopFilter();
        private native boolean nativeFlushFilter();
        private native int nativeRead(byte[] buffer, int offset, int size);

        private Filter(int id) {
            mId = id;
        }

        private void onFilterStatus(int status) {
            if (mHandler != null) {
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_ON_FILTER_STATUS, status, 0, this));
            }
        }

        public int configure(FilterSettings settings) {
            int subType = -1;
            if (settings.mSettings != null) {
                subType = settings.mSettings.getType();
            }
            return nativeConfigureFilter(settings.getType(), subType, settings);
        }

        public boolean start() {
            return nativeStartFilter();
        }

        public boolean stop() {
            return nativeStopFilter();
        }

        public boolean flush() {
            return nativeFlushFilter();
        }

        public int read(@NonNull byte[] buffer, int offset, int size) {
            size = Math.min(size, buffer.length - offset);
            return nativeRead(buffer, offset, size);
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
     *
     */
    public class Descrambler {
        private long mNativeContext;

        private native boolean nativeAddPid(int pidType, int pid, Filter filter);
        private native boolean nativeRemovePid(int pidType, int pid, Filter filter);

        private Descrambler() {}

        /** @hide */
        public boolean addPid(@DemuxPidType int pidType, int pid, Filter filter) {
            return nativeAddPid(pidType, pid, filter);
        }

        /** @hide */
        public boolean removePid(@DemuxPidType int pidType, int pid, Filter filter) {
            return nativeRemovePid(pidType, pid, filter);
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

        private native boolean nativeAttachFilter(Filter filter);
        private native boolean nativeDetachFilter(Filter filter);
        private native int nativeConfigureDvr(DvrSettings settings);
        private native boolean nativeStartDvr();
        private native boolean nativeStopDvr();
        private native boolean nativeFlushDvr();

        private Dvr() {}

        public boolean attachFilter(Filter filter) {
            return nativeAttachFilter(filter);
        }
        public boolean detachFilter(Filter filter) {
            return nativeDetachFilter(filter);
        }
        public int configure(DvrSettings settings) {
            return nativeConfigureDvr(settings);
        }
        public boolean start() {
            return nativeStartDvr();
        }
        public boolean stop() {
            return nativeStopDvr();
        }
        public boolean flush() {
            return nativeFlushDvr();
        }
    }

    private Dvr openDvr(int type, int bufferSize) {
        Dvr dvr = nativeOpenDvr(type, bufferSize);
        return dvr;
    }
}
