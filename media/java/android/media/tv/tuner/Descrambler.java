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
import android.media.tv.tuner.Tuner.Filter;
import android.media.tv.tuner.TunerConstants.DemuxPidType;

/**
 * This class is used to interact with descramblers.
 *
 * <p> Descrambler is a hardware component used to descramble data.
 *
 * <p> This class controls the TIS interaction with Tuner HAL.
 * @hide
 */
public class Descrambler implements AutoCloseable {
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
     * @hide
     */
    @Override
    public void close() {
        nativeClose();
    }

}
