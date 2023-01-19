/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server;

import android.util.Dumpable;
import android.util.Log;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code JUnit} rule that logs (using tag {@value #TAG} the contents of
 * {@link Dumpable dumpables} in case of failure.
 */
public final class DumpableDumperRule implements TestRule {

    private static final String TAG = DumpableDumperRule.class.getSimpleName();

    private static final String[] NO_ARGS = {};

    private final List<Dumpable> mDumpables = new ArrayList<>();

    /**
     * Adds a {@link Dumpable} to be logged if the test case fails.
     */
    public void addDumpable(Dumpable dumpable) {
        mDumpables.add(dumpable);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    dumpOnFailure(description.getMethodName());
                    throw t;
                }
            }
        };
    }

    /**
     * Logs all dumpables.
     */
    public void dump(String reason) {
        if (mDumpables.isEmpty()) {
            return;
        }
        Log.w(TAG, "Dumping " + mDumpables.size() + " dumpable(s). Reason: " + reason);
        mDumpables.forEach(d -> logDumpable(d));
    }

    private void dumpOnFailure(String testName) throws IOException {
        dump("failure of " + testName);
    }

    private void logDumpable(Dumpable dumpable) {
        try {
            try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
                dumpable.dump(pw, NO_ARGS);
                String[] dump = sw.toString().split(System.lineSeparator());
                Log.w(TAG, "Dumping " + dumpable.getDumpableName() + " (" + dump.length
                        + " lines):");
                for (String line : dump) {
                    Log.w(TAG, line);
                }

            } catch (RuntimeException e) {
                Log.e(TAG, "RuntimeException dumping " + dumpable.getDumpableName(), e);
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException dumping " + dumpable.getDumpableName(), e);
        }
    }
}
