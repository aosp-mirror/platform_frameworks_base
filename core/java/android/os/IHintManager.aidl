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

import android.os.IHintSession;
import android.hardware.power.ChannelConfig;
import android.hardware.power.SessionConfig;
import android.hardware.power.SessionTag;

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
    IHintSession createHintSessionWithConfig(in IBinder token, in int[] threadIds,
            in long durationNanos, in SessionTag tag, out @nullable SessionConfig config);

    /**
     * Get preferred rate limit in nanoseconds.
     */
    long getHintSessionPreferredRate();

    void setHintSessionThreads(in IHintSession hintSession, in int[] tids);
    int[] getHintSessionThreadIds(in IHintSession hintSession);

    /**
     * Returns FMQ channel information for the caller, which it associates to a binder token.
     *
     * Throws IllegalStateException if FMQ channel creation fails.
     */
    ChannelConfig getSessionChannel(in IBinder token);
    oneway void closeSessionChannel();
}
