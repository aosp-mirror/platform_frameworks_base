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
import android.system.ErrnoException;
import android.system.Os;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * The UID of the child zygote process.
     */
    private final int mUid;


    /**
     * If this zygote process was dead;
     */
    private AtomicBoolean mDead;


    ChildZygoteProcess(LocalSocketAddress socketAddress, int pid, int uid) {
        super(socketAddress, null);
        mPid = pid;
        mUid = uid;
        mDead = new AtomicBoolean(false);
    }

    /**
     * Returns the PID of the child-zygote process.
     */
    public int getPid() {
        return mPid;
    }

    /**
     * Check if child-zygote process is dead
     */
    public boolean isDead() {
        if (mDead.get()) {
            return true;
        }
        StrictMode.ThreadPolicy oldStrictModeThreadPolicy = StrictMode.allowThreadDiskReads();
        try {
            if (Os.stat("/proc/" + mPid).st_uid == mUid) {
                return false;
            }
        } catch (ErrnoException e) {
            // Do nothing, it's dead.
        } finally {
            StrictMode.setThreadPolicy(oldStrictModeThreadPolicy);
        }
        mDead.set(true);
        return true;
    }
}
