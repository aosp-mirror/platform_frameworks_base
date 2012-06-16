/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media.libaah;

import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/*
 * The implementation reads data from libaah_rtp
 */
public class MetaDataServiceRtp extends MetaDataService {

    static {
        System.loadLibrary("aah_rtp");
    }

    private final static String TAG = "AAHMetaData-JAVA";

    /**
     * State of a MetaDataService object that was not successfully
     * initialized upon creation
     */
    private static final int STATE_UNINITIALIZED = 0;
    /**
     * State of a MetaDataService object that is ready to be used.
     */
    private static final int STATE_INITIALIZED = 1;

    // -------------------------------------------------------------------------
    // Member variables
    // --------------------
    /**
     * Indicates the state of the MetaDataService instance
     */
    private int mState = STATE_UNINITIALIZED;

    private int mCookie;
    /*
     * Successful operation.
     */
    private static final int SUCCESS = 0;
    /*
     * Unspecified error.
     */
    private static final int ERROR = -1;
    /*
     * Internal operation status. Not returned by any method.
     */
    private static final int ALREADY_EXISTS = -2;
    /*
     * Operation failed due to bad object initialization.
     */
    private static final int ERROR_NO_INIT = -3;
    /*
     * Operation failed due to bad parameter value.
     */
    private static final int ERROR_BAD_VALUE = -4;
    /*
     * Operation failed because it was requested in wrong state.
     */
    private static final int ERROR_INVALID_OPERATION = -5;
    /*
     * Operation failed due to lack of memory.
     */
    private static final int ERROR_NO_MEMORY = -6;
    /*
     * Operation failed due to dead remote object.
     */
    private static final int ERROR_DEAD_OBJECT = -7;

    /*
     * only called by MetaDataService.create()
     */
    MetaDataServiceRtp() {
        mState = STATE_UNINITIALIZED;
        // native initialization
        int result = native_setup(new WeakReference<MetaDataServiceRtp>(this));
        if (result != SUCCESS) {
            Log.e(TAG, "Error code " + result + " when initializing.");
        }
        mState = STATE_INITIALIZED;
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    @Override
    public void release() {
        native_finalize();
    }

    @Override
    public void enable() {
        native_enable();
    }

    @Override
    public void disable() {
        native_disable();
    }

    private native final int native_setup(Object metadataservice_this);

    private native final void native_enable();

    private native final void native_disable();

    private native final void native_finalize();

    // ---------------------------------------------------------
    // Java methods called from the native side
    // --------------------
    @SuppressWarnings("unused")
    private static void postMetaDataFromNative(Object s_ref,
            short type, int item_len, byte[] buffer) {
        MetaDataService service =
                (MetaDataService) ((WeakReference) s_ref).get();
        if (service == null) {
            return;
        }
        switch (type) {
            case TYPEID_BEAT:
                service.processBeat(item_len, buffer);
                break;
            default:
                Log.w(TAG, "unknown type metadata type " + type);
                break;
        }

    }

    @SuppressWarnings("unused")
    private static void flushFromNative(Object s_ref) {
        MetaDataService service =
                (MetaDataService) ((WeakReference) s_ref).get();
        if (service == null) {
            return;
        }
        service.flush();
    }
}
