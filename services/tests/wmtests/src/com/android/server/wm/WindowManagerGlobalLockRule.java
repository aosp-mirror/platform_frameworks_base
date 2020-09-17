/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import android.os.AsyncTask;
import android.os.Handler;

import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds {@link WindowManagerGlobalLock} for test methods (including before and after) to prevent
 * the race condition with other threads (e.g. {@link com.android.server.DisplayThread},
 * {@link com.android.server.AnimationThread}, {@link com.android.server.UiThread}).
 */
class WindowManagerGlobalLockRule implements WindowTestRunner.MethodWrapper {

    private final SystemServicesTestRule mSystemServicesTestRule;
    private volatile boolean mIsLocked;

    WindowManagerGlobalLockRule(SystemServicesTestRule systemServiceTestRule) {
        mSystemServicesTestRule = systemServiceTestRule;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        if (description.getAnnotation(NoGlobalLock.class) == null) {
            return new StatementWrapper(base);
        }
        return base;
    }

    @Override
    public FrameworkMethod apply(FrameworkMethod base) {
        if (base.getAnnotation(NoGlobalLock.class) == null) {
            return new FrameworkMethodWrapper(base);
        }
        return base;
    }

    boolean runWithScissors(Handler handler, Runnable r, long timeout) {
        return waitForLocked(() -> handler.runWithScissors(r, timeout));
    }

    void waitForLocked(Runnable r) {
        waitForLocked(() -> {
            r.run();
            return null;
        });
    }

    /**
     * If the test holds the lock, we need to invoke {@link Object#wait} to release it so other
     * threads won't be blocked when we are waiting.
     */
    <T> T waitForLocked(Callable<T> callable) {
        if (!mIsLocked) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        final Object lock = mSystemServicesTestRule.getWindowManagerService().mGlobalLock;
        final AtomicBoolean done = new AtomicBoolean(false);
        final List<T> result = Arrays.asList((T) null);
        final Exception[] exception = { null };

        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            try {
                result.set(0, callable.call());
            } catch (Exception e) {
                exception[0] = e;
            }
            synchronized (lock) {
                lock.notifyAll();
                done.set(true);
            }
        });

        synchronized (lock) {
            if (!done.get()) {
                try {
                    lock.wait();
                } catch (InterruptedException impossible) {
                }
            }
        }
        if (exception[0] != null) {
            throw new RuntimeException(exception[0]);
        }

        return result.get(0);
    }

    /** Wraps methods annotated with {@link org.junit.Test}. */
    private class StatementWrapper extends Statement {
        final Statement mBase;

        StatementWrapper(Statement base) {
            mBase = base;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                synchronized (mSystemServicesTestRule.getWindowManagerService().mGlobalLock) {
                    mIsLocked = true;
                    mBase.evaluate();
                }
            } finally {
                mIsLocked = false;
            }
        }
    }

    /** Wraps methods annotated with {@link org.junit.Before} or {@link org.junit.After}. */
    private class FrameworkMethodWrapper extends FrameworkMethod {

        FrameworkMethodWrapper(FrameworkMethod base) {
            super(base.getMethod());
        }

        @Override
        public Object invokeExplosively(Object target, Object... params) throws Throwable {
            try {
                synchronized (mSystemServicesTestRule.getWindowManagerService().mGlobalLock) {
                    mIsLocked = true;
                    return super.invokeExplosively(target, params);
                }
            } finally {
                mIsLocked = false;
            }
        }
    }

    /**
     * If the test method is annotated with {@link NoGlobalLock}, the rule
     * {@link WindowManagerGlobalLockRule} won't apply to the method.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface NoGlobalLock {
        String reason() default "";
    }
}
