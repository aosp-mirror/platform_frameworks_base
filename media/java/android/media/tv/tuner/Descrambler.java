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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.tv.tuner.Tuner.Result;
import android.media.tv.tuner.filter.Filter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * This class is used to interact with descramblers.
 *
 * <p> Descrambler is a hardware component used to descramble data.
 *
 * <p> This class controls the TIS interaction with Tuner HAL.
 *
 * @hide
 */
@SystemApi
public class Descrambler implements AutoCloseable {
    /** @hide */
    @IntDef(prefix = "PID_TYPE_", value = {PID_TYPE_T, PID_TYPE_MMTP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PidType {}

    /**
     * Packet ID is used to specify packets in transport stream.
     */
    public static final int PID_TYPE_T = 1;
    /**
     * Packet ID is used to specify packets in MMTP.
     */
    public static final int PID_TYPE_MMTP = 2;

    private static final String TAG = "Descrambler";


    private long mNativeContext;
    private boolean mIsClosed = false;
    private final Object mLock = new Object();

    private native int nativeAddPid(int pidType, int pid, Filter filter);
    private native int nativeRemovePid(int pidType, int pid, Filter filter);
    private native int nativeSetKeyToken(byte[] keyToken);
    private native int nativeClose();

    // Called by JNI code
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
     */
    @Result
    public int addPid(@PidType int pidType, int pid, @Nullable Filter filter) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeAddPid(pidType, pid, filter);
        }
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
     */
    @Result
    public int removePid(@PidType int pidType, int pid, @Nullable Filter filter) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeRemovePid(pidType, pid, filter);
        }
    }

    /**
     * Set a key token to link descrambler to a key slot
     *
     * A descrambler instance can have only one key slot to link, but a key slot can hold a few
     * keys for different purposes.
     *
     * @param keyToken the token to be used to link the key slot.
     * @return result status of the operation.
     */
    @Result
    public int setKeyToken(@NonNull byte[] keyToken) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            Objects.requireNonNull(keyToken, "key token must not be null");
            return nativeSetKeyToken(keyToken);
        }
    }

    /**
     * Release the descrambler instance.
     */
    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) {
                return;
            }
            int res = nativeClose();
            if (res != Tuner.RESULT_SUCCESS) {
                TunerUtils.throwExceptionForResult(res, "Failed to close descrambler");
            } else {
                mIsClosed = true;
            }
        }
    }

}
