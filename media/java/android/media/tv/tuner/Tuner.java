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

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.List;

/**
 * Tuner is used to interact with tuner devices.
 *
 * @hide
 */
public final class Tuner implements AutoCloseable  {
    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = false;

    private static final int MSG_ON_FRONTEND_EVENT = 1;

    static {
        System.loadLibrary("media_tv_tuner");
        nativeInit();
    }

    private List<Integer> mFrontendIds;
    private Frontend mFrontend;
    private EventHandler mHandler;

    public Tuner() {
        nativeSetup();
    }

    private long mNativeContext; // used by native jMediaTuner

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

    private native Filter nativeOpenFilter(int type, int subType, int bufferSize);


    /**
     * Frontend Callback.
     */
    public interface FrontendCallback {

        /**
         * Invoked when there is a frontend event.
         */
        void onEvent(int frontendEventType);
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
                default:
                    // fall through
            }
        }
    }

    protected class Frontend {
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

    protected class Filter {
        int mId;
        private Filter(int id) {
            mId = id;
        }
    }

    private Filter openFilter(int type, int subType, int bufferSize) {
        return nativeOpenFilter(type, subType, bufferSize);
    }
}
