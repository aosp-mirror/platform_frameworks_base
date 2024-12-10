/*
 *
 * Copyright 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.os.CpuHeadroomParamsInternal;
import android.os.GpuHeadroomParamsInternal;
import android.os.IHintSession;
import android.os.SessionCreationConfig;

import android.hardware.power.ChannelConfig;
import android.hardware.power.CpuHeadroomResult;
import android.hardware.power.GpuHeadroomResult;
import android.hardware.power.SessionConfig;
import android.hardware.power.SessionTag;
import android.hardware.power.SupportInfo;

/** {@hide} */
interface IHintManager {
    /**
     * Creates a {@link Session} for the given set of threads and associates to a binder token.
     * Returns a config if creation is not supported, and HMS had to use the
     * legacy creation method.
     *
     * Throws UnsupportedOperationException if ADPF is not supported, and IllegalStateException
     * if creation is supported but fails.
     */
    IHintSession createHintSessionWithConfig(in IBinder token, in SessionTag tag,
            in SessionCreationConfig creationConfig, out SessionConfig config);

    void setHintSessionThreads(in IHintSession hintSession, in int[] tids);
    int[] getHintSessionThreadIds(in IHintSession hintSession);

    /**
     * Returns FMQ channel information for the caller, which it associates to a binder token.
     *
     * Throws IllegalStateException if FMQ channel creation fails.
     */
    @nullable ChannelConfig getSessionChannel(in IBinder token);
    oneway void closeSessionChannel();
    @nullable CpuHeadroomResult getCpuHeadroom(in CpuHeadroomParamsInternal params);
    long getCpuHeadroomMinIntervalMillis();
    @nullable GpuHeadroomResult getGpuHeadroom(in GpuHeadroomParamsInternal params);
    long getGpuHeadroomMinIntervalMillis();

    /**
     * Used by the JNI to pass an interface to the SessionManager;
     * for internal use only.
     */
    oneway void passSessionManagerBinder(in IBinder sessionManager);

    parcelable HintManagerClientData {
        int powerHalVersion;
        int maxGraphicsPipelineThreads;
        long preferredRateNanos;
        SupportInfo supportInfo;
    }

    interface IHintManagerClient {
        /**
        * Returns FMQ channel information for the caller, which it associates to the callback binder lifespan.
        */
        oneway void receiveChannelConfig(in ChannelConfig config);
    }

    /**
     * Set up an ADPF client, receiving a remote client binder interface and
     * passing back a bundle of support and configuration information.
     */
    HintManagerClientData registerClient(in IHintManagerClient client);
}
