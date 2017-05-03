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

import android.content.Context;
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

/**
 * Utility for dealing with the facts of Lifecycle. Creates trackers to check that for every
 * call to registerX, addX, bindX, a corresponding call to unregisterX, removeX, and unbindX
 * is performed. This should be applied to a test as a {@link org.junit.rules.TestRule}
 * and will only check for leaks on successful tests.
 * <p>
 * Example that will catch an allocation and fail:
 * <pre class="prettyprint">
 * public class LeakCheckTest {
 *    &#064;Rule public LeakCheck mLeakChecker = new LeakCheck();
 *
 *    &#064;Test
 *    public void testLeak() {
 *        Context context = new ContextWrapper(...) {
 *            public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
 *                mLeakChecker.getTracker("receivers").addAllocation(new Throwable());
 *            }
 *            public void unregisterReceiver(BroadcastReceiver receiver) {
 *                mLeakChecker.getTracker("receivers").clearAllocations();
 *            }
 *        };
 *        context.registerReceiver(...);
 *    }
 *  }
 * </pre>
 *
 * Note: {@link TestableContext} supports leak tracking when using
 * {@link TestableContext#TestableContext(Context, LeakCheck)}.
 */
public class LeakCheck extends TestWatcher {

    private final Map<String, Tracker> mTrackers = new HashMap<>();

    public LeakCheck() {
    }

    @Override
    protected void succeeded(Description description) {
        verify();
    }

    /**
     * Acquire a {@link Tracker}. Gets a tracker for the specified tag, creating one if necessary.
     * There should be one tracker for each pair of add/remove callbacks (e.g. one tracker for
     * registerReceiver/unregisterReceiver).
     *
     * @param tag Unique tag to use for this set of allocation tracking.
     */
    public Tracker getTracker(String tag) {
        Tracker t = mTrackers.get(tag);
        if (t == null) {
            t = new Tracker();
            mTrackers.put(tag, t);
        }
        return t;
    }

    private void verify() {
        mTrackers.values().forEach(Tracker::verify);
    }

    /**
     * Holds allocations associated with a specific callback (such as a BroadcastReceiver).
     */
    public static class LeakInfo {
        private static final String TAG = "LeakInfo";
        private List<Throwable> mThrowables = new ArrayList<>();

        LeakInfo() {
        }

        /**
         * Should be called once for each callback/listener added. addAllocation may be
         * called several times, but it only takes one clearAllocations call to remove all
         * of them.
         */
        public void addAllocation(Throwable t) {
            // TODO: Drop off the first element in the stack trace here to have a cleaner stack.
            mThrowables.add(t);
        }

        /**
         * Should be called when the callback/listener has been removed. One call to
         * clearAllocations will counteract any number of calls to addAllocation.
         */
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

    /**
     * Tracks allocations related to a specific tag or method(s).
     * @see #getTracker(String)
     */
    public static class Tracker {
        private Map<Object, LeakInfo> mObjects = new ArrayMap<>();

        private Tracker() {
        }

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
