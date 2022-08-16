/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import android.location.util.identity.CallerIdentity;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.HashMap;

/**
 * Version of AppOpsHelper for testing. All app ops are allowed until notified otherwise.
 */
public class FakeAppOpsHelper extends AppOpsHelper {

    private static class AppOp {
        AppOp() {}
        boolean mAllowed = true;
        int mStarted = 0;
        int mNoteCount = 0;
    }

    private final HashMap<String, SparseArray<AppOp>> mAppOps;

    public FakeAppOpsHelper() {
        mAppOps = new HashMap<>();
    }

    public void setAppOpAllowed(int appOp, String packageName, boolean allowed) {
        AppOp myAppOp = getOp(packageName, appOp);
        myAppOp.mAllowed = allowed;
        notifyAppOpChanged(packageName);
    }

    public boolean isAppOpStarted(int appOp, String packageName) {
        AppOp myAppOp = getOp(packageName, appOp);
        return myAppOp.mStarted > 0;
    }

    public int getAppOpNoteCount(int appOp, String packageName) {
        AppOp myAppOp = getOp(packageName, appOp);
        return myAppOp.mNoteCount;
    }

    @Override
    public boolean startOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        AppOp myAppOp = getOp(callerIdentity.getPackageName(), appOp);
        if (!myAppOp.mAllowed) {
            return false;
        }
        myAppOp.mStarted++;
        return true;
    }

    @Override
    public void finishOp(int appOp, CallerIdentity callerIdentity) {
        AppOp myAppOp = getOp(callerIdentity.getPackageName(), appOp);
        Preconditions.checkState(myAppOp.mStarted > 0);
        myAppOp.mStarted--;
    }

    @Override
    public boolean checkOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        AppOp myAppOp = getOp(callerIdentity.getPackageName(), appOp);
        return myAppOp.mAllowed;
    }

    @Override
    public boolean noteOp(int appOp, CallerIdentity callerIdentity) {
        if (!noteOpNoThrow(appOp, callerIdentity)) {
            throw new SecurityException(
                    "noteOp not allowed for op " + appOp + " and caller " + callerIdentity);
        }

        return true;
    }

    @Override
    public boolean noteOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        AppOp myAppOp = getOp(callerIdentity.getPackageName(), appOp);
        if (!myAppOp.mAllowed) {
            return false;
        }
        myAppOp.mNoteCount++;
        return true;
    }

    private AppOp getOp(String packageName, int appOp) {
        SparseArray<AppOp> ops = mAppOps.get(packageName);
        if (ops == null) {
            ops = new SparseArray<>();
            mAppOps.put(packageName, ops);
        }

        AppOp myAppOp;
        if (ops.contains(appOp)) {
            myAppOp = ops.get(appOp);
        } else {
            myAppOp = new AppOp();
            ops.put(appOp, myAppOp);
        }

        return myAppOp;
    }
}
