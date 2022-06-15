/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.testing.LeakCheck;
import android.testing.LeakCheck.Tracker;

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.CallbackController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class BaseLeakChecker<T> implements CallbackController<T>, Dumpable {

    private final Tracker mTracker;

    public BaseLeakChecker(LeakCheck test, String tag) {
        mTracker = test.getTracker(tag);
    }

    protected final Tracker getTracker() {
        return mTracker;
    }

    @Override
    public void addCallback(T listener) {
        mTracker.getLeakInfo(listener).addAllocation(new Throwable());
    }

    @Override
    public void removeCallback(T listener) {
        mTracker.getLeakInfo(listener).clearAllocations();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {

    }
}
