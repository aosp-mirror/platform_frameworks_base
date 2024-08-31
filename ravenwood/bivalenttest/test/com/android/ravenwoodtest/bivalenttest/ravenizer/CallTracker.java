/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalenttest.ravenizer;

import static org.junit.Assert.fail;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;

import android.util.Log;

import java.lang.StackWalker.StackFrame;
import java.util.HashMap;

/**
 * Used to keep track of and count the number of calls.
 */
public class CallTracker {
    public static final String TAG = "CallTracker";

    private final HashMap<String, Integer> mNumCalled = new HashMap<>();

    /**
     * Call it when a method is called. It increments the count for the calling method.
     */
    public void incrementMethodCallCount() {
        var methodName = getCallingMethodName(1);

        Log.i(TAG, "Method called: " + methodName);

        mNumCalled.put(methodName, getNumCalled(methodName) + 1);
    }

    /**
     * Return the number of calls of a method.
     */
    public int getNumCalled(String methodName) {
        return mNumCalled.getOrDefault(methodName, 0);
    }

    /**
     * Return the current method name. (with the class name.)
     */
    private static String getCallingMethodName(int frameOffset) {
        var walker = StackWalker.getInstance(RETAIN_CLASS_REFERENCE);
        var caller = walker.walk(frames ->
                frames.skip(1 + frameOffset).findFirst().map(StackFrame::getMethodName)
        );
        return caller.get();
    }

    /**
     * Check the number of calls stored in {@link #mNumCalled}.
     */
    public void assertCalls(Object... methodNameAndCountPairs) {
        // Create a local copy
        HashMap<String, Integer> counts = new HashMap<>(mNumCalled);
        for (int i = 0; i < methodNameAndCountPairs.length - 1; i += 2) {
            String methodName = (String) methodNameAndCountPairs[i];
            int expectedCount = (Integer) methodNameAndCountPairs[i + 1];

            if (getNumCalled(methodName) != expectedCount) {
                fail(String.format("Method %s: expected call count=%d, actual=%d",
                        methodName, expectedCount, getNumCalled(methodName)));
            }
            counts.remove(methodName);
        }
        // All other entries are expected to be 0.
        var sb = new StringBuilder();
        for (var e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            sb.append(String.format("Method %s: expected call count=0, actual=%d",
                    e.getKey(), e.getValue()));
        }
        if (sb.length() > 0) {
            fail(sb.toString());
        }
    }

    /**
     * Same as {@link #assertCalls(Object...)} but it kills the process if it fails.
     * Only use in @AfterClass.
     */
    public void assertCallsOrDie(Object... methodNameAndCountPairs) {
        try {
            assertCalls(methodNameAndCountPairs);
        } catch (Throwable th) {
            // TODO: I don't think it's by spec, but the exception here would be ignored both on
            // ravenwood and on the device side. Look into it.
            Log.e(TAG, "*** Failure detected in @AfterClass! ***", th);
            Log.e(TAG, "JUnit seems to ignore exceptions from @AfterClass, so killing self.");
            System.exit(7);
        }
    }

}
