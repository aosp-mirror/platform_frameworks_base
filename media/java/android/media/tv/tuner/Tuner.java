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

    private FrontendCallback mFrontendCallback;
    private List<Integer> mFrontendIds;

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


    /**
     * Frontend Callback.
     */
    public interface FrontendCallback {

        /**
         * Invoked when there is a frontend event.
         */
        void onEvent(int frontendEventType);
    }

    protected static class Frontend {
        int mId;
        private Frontend(int id) {
            mId = id;
        }
    }

    private List<Integer> getFrontendIds() {
        mFrontendIds = nativeGetFrontendIds();
        return mFrontendIds;
    }

    private Frontend openFrontendById(int id) {
        if (mFrontendIds == null) {
            getFrontendIds();
        }
        if (!mFrontendIds.contains(id)) {
            return null;
        }
        return nativeOpenFrontendById(id);
    }
}
