/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.systemui;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.support.test.InstrumentationRegistry;
import android.util.ArrayMap;
import android.util.Log;

import com.android.systemui.utils.TestableContext;
import com.android.systemui.utils.leaks.Tracker;

import org.junit.After;
import org.junit.Before;

import java.lang.Thread.UncaughtExceptionHandler;

/**
 * Base class that does System UI specific setup.
 */
public abstract class SysuiTestCase {

    private Throwable mException;
    private Handler mHandler;
    protected TestableContext mContext;
    protected TestDependency mDependency;

    @Before
    public void SysuiSetup() throws Exception {
        mException = null;
        System.setProperty("dexmaker.share_classloader", "true");
        mContext = new TestableContext(InstrumentationRegistry.getTargetContext(), this);
        SystemUIFactory.createFromConfig(mContext);
        mDependency = new TestDependency();
        mDependency.mContext = mContext;
        mDependency.start();
    }

    @After
    public void cleanup() throws Exception {
        mContext.getSettingsProvider().clearOverrides(this);
    }

    protected Context getContext() {
        return mContext;
    }

    protected void waitForIdleSync() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        waitForIdleSync(mHandler);
    }

    public static void waitForIdleSync(Handler h) {
        validateThread(h.getLooper());
        Idler idler = new Idler(null);
        h.getLooper().getQueue().addIdleHandler(idler);
        // Ensure we are non-idle, so the idle handler can run.
        h.post(new EmptyRunnable());
        idler.waitForIdle();
    }

    private static final void validateThread(Looper l) {
        if (Looper.myLooper() == l) {
            throw new RuntimeException(
                "This method can not be called from the looper being synced");
        }
    }

    // Used for leak tracking, returns null to indicate no leak tracking by default.
    public Tracker getTracker(String tag) {
        return null;
    }

    public void injectMockDependency(Class<?> cls) {
        mDependency.injectTestDependency(cls.getName(), mock(cls));
    }

    public void injectTestDependency(Class<?> cls, Object obj) {
        mDependency.injectTestDependency(cls.getName(), obj);
    }

    public void injectTestDependency(String key, Object obj) {
        mDependency.injectTestDependency(key, obj);
    }

    public static final class EmptyRunnable implements Runnable {
        public void run() {
        }
    }

    public static class TestDependency extends Dependency {
        private final ArrayMap<String, Object> mObjs = new ArrayMap<>();

        private void injectTestDependency(String key, Object obj) {
            mObjs.put(key, obj);
        }

        @Override
        protected <T> T createDependency(String cls) {
            if (mObjs.containsKey(cls)) return (T) mObjs.get(cls);
            return super.createDependency(cls);
        }
    }

    public static final class Idler implements MessageQueue.IdleHandler {
        private final Runnable mCallback;
        private boolean mIdle;

        public Idler(Runnable callback) {
            mCallback = callback;
            mIdle = false;
        }

        @Override
        public boolean queueIdle() {
            if (mCallback != null) {
                mCallback.run();
            }
            synchronized (this) {
                mIdle = true;
                notifyAll();
            }
            return false;
        }

        public void waitForIdle() {
            synchronized (this) {
                while (!mIdle) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }
}
