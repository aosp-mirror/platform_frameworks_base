/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import android.util.ArraySet;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.Map;

final class WindowProcessControllerMap {

    /** All processes we currently have running mapped by pid */
    private final SparseArray<WindowProcessController> mPidMap = new SparseArray<>();
    /** All processes we currently have running mapped by uid */
    private final Map<Integer, ArraySet<WindowProcessController>> mUidMap = new HashMap<>();

    /** Retrieves a currently running process for pid. */
    WindowProcessController getProcess(int pid) {
        return mPidMap.get(pid);
    }

    /** Retrieves all currently running processes for uid. */
    ArraySet<WindowProcessController> getProcesses(int uid) {
        return mUidMap.get(uid);
    }

    SparseArray<WindowProcessController> getPidMap() {
        return mPidMap;
    }

    void put(int pid, WindowProcessController proc) {
        // if there is a process for this pid already in mPidMap it'll get replaced automagically,
        // but we actually need to remove it from mUidMap too before adding the new one
        final WindowProcessController prevProc = mPidMap.get(pid);
        if (prevProc != null) {
            removeProcessFromUidMap(prevProc);
        }
        // put process into mPidMap
        mPidMap.put(pid, proc);
        // put process into mUidMap
        final int uid = proc.mUid;
        ArraySet<WindowProcessController> procSet = mUidMap.getOrDefault(uid,
                new ArraySet<WindowProcessController>());
        procSet.add(proc);
        mUidMap.put(uid, procSet);
    }

    void remove(int pid) {
        final WindowProcessController proc = mPidMap.get(pid);
        if (proc != null) {
            // remove process from mPidMap
            mPidMap.remove(pid);
            // remove process from mUidMap
            removeProcessFromUidMap(proc);
            proc.destroy();
        }
    }

    private void removeProcessFromUidMap(WindowProcessController proc) {
        if (proc == null) {
            return;
        }
        final int uid = proc.mUid;
        ArraySet<WindowProcessController> procSet = mUidMap.get(uid);
        if (procSet != null) {
            procSet.remove(proc);
            if (procSet.isEmpty()) {
                mUidMap.remove(uid);
            }
        }
    }
}
