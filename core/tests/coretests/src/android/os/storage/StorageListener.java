/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os.storage;

import android.util.Log;

public class StorageListener extends StorageEventListener {
    private static final boolean localLOGV = true;

    public static final String TAG = "StorageListener";

    private String mTargetState;
    private boolean doneFlag = false;

    public StorageListener(String targetState) {
        mTargetState = targetState;
    }

    @Override
    public void onStorageStateChanged(String path, String oldState, String newState) {
        if (localLOGV) Log.i(TAG, "Storage state changed from " + oldState + " to " + newState);

        synchronized (this) {
            if (mTargetState.equals(newState)) {
                doneFlag = true;
                notifyAll();
            }
        }
    }

    public boolean isDone() {
        return doneFlag;
    }
}
