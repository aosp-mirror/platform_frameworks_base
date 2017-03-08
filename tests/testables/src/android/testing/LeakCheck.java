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

package android.testing;

import android.util.ArrayMap;
import android.util.Log;

import org.junit.Assert;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LeakCheck extends TestWatcher {

    private final Map<String, Tracker> mTrackers = new HashMap<>();

    public LeakCheck() {
    }

    @Override
    protected void succeeded(Description description) {
        verify();
    }

    public Tracker getTracker(String tag) {
        Tracker t = mTrackers.get(tag);
        if (t == null) {
            t = new Tracker();
            mTrackers.put(tag, t);
        }
        return t;
    }

    public void verify() {
        mTrackers.values().forEach(Tracker::verify);
    }

    public static class LeakInfo {
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

    public static class Tracker {
        private Map<Object, LeakInfo> mObjects = new ArrayMap<>();

        public LeakInfo getLeakInfo(Object object) {
            LeakInfo leakInfo = mObjects.get(object);
            if (leakInfo == null) {
                leakInfo = new LeakInfo();
                mObjects.put(object, leakInfo);
            }
            return leakInfo;
        }

        void verify() {
            mObjects.values().forEach(LeakInfo::verify);
        }
    }
}
