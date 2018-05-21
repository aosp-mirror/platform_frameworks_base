/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.net.LocalSocketAddress;

/**
 * Represents a connection to a child-zygote process. A child-zygote is spawend from another
 * zygote process using {@link startChildZygote()}.
 *
 * {@hide}
 */
public class ChildZygoteProcess extends ZygoteProcess {
    /**
     * The PID of the child zygote process.
     */
    private final int mPid;

    ChildZygoteProcess(LocalSocketAddress socketAddress, int pid) {
        super(socketAddress, null);
        mPid = pid;
    }

    /**
     * Returns the PID of the child-zygote process.
     */
    public int getPid() {
        return mPid;
    }
}
