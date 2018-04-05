/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.testing;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ConcurrentModificationException;


/**
 * Runs the test such that mocks created in it don't use a dedicated classloader.
 *
 * This allows mocking package-private methods.
 *
 * WARNING: This is absolutely incompatible with running tests in parallel!
 */
public class DexmakerShareClassLoaderRule implements TestRule {

    private static final String TAG = "ShareClassloaderRule";
    @VisibleForTesting
    static final String DEXMAKER_SHARE_CLASSLOADER_PROPERTY = "dexmaker.share_classloader";

    private static Thread sOwningThread = null;

    @Override
    public Statement apply(Statement base, Description description) {
        return apply(base::evaluate).toStatement();
    }

    /**
     * Runs the runnable such that mocks created in it don't use a dedicated classloader.
     *
     * This allows mocking package-private methods.
     *
     * WARNING: This is absolutely incompatible with running tests in parallel!
     */
    public static void runWithDexmakerShareClassLoader(Runnable r) {
        apply(r::run).run();
    }

    /**
     * Returns a statement that first makes sure that only one thread at the time is modifying
     * the property. Then actually sets the property, and runs the statement.
     */
    private static <T extends Throwable> ThrowingRunnable<T> apply(ThrowingRunnable<T> r) {
        return wrapInMutex(wrapInSetAndClearProperty(r));
    }

    private static <T extends Throwable> ThrowingRunnable<T> wrapInSetAndClearProperty(
            ThrowingRunnable<T> r) {
        return () -> {
            final String previousValue = System.getProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY);
            try {
                System.setProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY, "true");
                r.run();
            } finally {
                if (previousValue != null) {
                    System.setProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY, previousValue);
                } else {
                    System.clearProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY);
                }
            }
        };
    }

    /**
     * Runs the given statement, and while doing so prevents other threads from running statements.
     */
    private static <T extends Throwable> ThrowingRunnable<T> wrapInMutex(ThrowingRunnable<T> r) {
        return () -> {
            final boolean isOwner;
            synchronized (DexmakerShareClassLoaderRule.class) {
                isOwner = (sOwningThread == null);
                if (isOwner) {
                    sOwningThread = Thread.currentThread();
                } else if (sOwningThread != Thread.currentThread()) {
                    final RuntimeException e = new ConcurrentModificationException(
                            "Tried to set dexmaker.share_classloader from " + Thread.currentThread()
                                    + ", but was already set from " + sOwningThread);
                    // Also log in case exception gets swallowed.
                    Log.e(TAG, e.getMessage(), e);
                    throw e;
                }
            }
            try {
                r.run();
            } finally {
                synchronized (DexmakerShareClassLoaderRule.class) {
                    if (isOwner) {
                        sOwningThread = null;
                    }
                }
            }
        };
    }

    private interface ThrowingRunnable<T extends Throwable> {
        void run() throws T;

        default Statement toStatement() {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    ThrowingRunnable.this.run();
                }
            };
        }
    }
}
