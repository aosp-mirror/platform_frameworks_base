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

package com.android.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A class to count the number of notifications received.
 */
public class WatchableTester extends Watcher {

    // The count of changes.
    public int mChanges = 0;

    // The change count at the last verifyChangeReported() call.
    public int mLastChangeCount = 0;

    // The single Watchable that this monitors.
    public final Watchable mWatched;

    // The key, used for messages
    public String mKey;

    // Clear the changes count, for when the tester is reused.
    public void clear() {
        mChanges = 0;
    }

    /**
     * Create the WatchableTester with a Watcher and a key.  The key is used for logging
     * test failures.
     * @param w The {@link Watchable} under test
     * @param k A key that is prefixed to any test failures.
     **/
    public WatchableTester(Watchable w, String k) {
        mWatched = w;
        mKey = k;
    }

    // Listen for events
    public void register() {
        mWatched.registerObserver(this);
    }

    // Stop listening for events
    public void unregister() {
        mWatched.unregisterObserver(this);
    }

    // Count the number of notifications received.
    @Override
    public void onChange(Watchable what) {
        mChanges++;
    }

    // Verify the count.
    public void verify(int want, String msg) {
        assertEquals(mKey + " " + msg, want, mChanges);
    }

    // Verify that at least one change was reported since the last verify.  The actual
    // number of changes is not important.  This resets the count of changes.
    public void verifyChangeReported(String msg) {
        assertTrue(mKey + " " + msg, mLastChangeCount < mChanges);
        mLastChangeCount = mChanges;
    }

    // Verify that no change was reported since the last verify.
    public void verifyNoChangeReported(String msg) {
        assertTrue(mKey + " " + msg, mLastChangeCount == mChanges);
    }
}
