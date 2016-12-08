/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.util.Log;

import org.junit.Assert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class LeakInfo {
    private static final String TAG = "LeakInfo";
    private List<Throwable> mThrowables = new ArrayList<>();

    LeakInfo() {
    }

    public void addAllocation(Throwable t) {
        // TODO: Drop off the first element in the stack trace here to have a cleaner stack.
        mThrowables.add(t);
    }

    public void clearAllocations() {
        mThrowables.clear();
    }

    void verify() {
        if (mThrowables.size() == 0) return;
        Log.e(TAG, "Listener or binding not properly released");
        for (Throwable t : mThrowables) {
            Log.e(TAG, "Allocation found", t);
        }
        StringWriter writer = new StringWriter();
        mThrowables.get(0).printStackTrace(new PrintWriter(writer));
        Assert.fail("Listener or binding not properly released\n"
                + writer.toString());
    }
}
