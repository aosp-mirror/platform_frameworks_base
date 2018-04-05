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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.testing.DexmakerShareClassLoaderRule;
import android.testing.LeakCheck;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Base class that does System UI specific setup.
 */
public abstract class SysuiTestCase {

    private static final String TAG = "SysuiTestCase";

    private Handler mHandler;
    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), getLeakCheck());
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();
    public TestableDependency mDependency = new TestableDependency(mContext);
    private Instrumentation mRealInstrumentation;

    @Before
    public void SysuiSetup() throws Exception {
        mContext.setTheme(R.style.Theme_SystemUI);
        SystemUIFactory.createFromConfig(mContext);

        mRealInstrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation inst = spy(mRealInstrumentation);
        when(inst.getContext()).thenAnswer(invocation -> {
            throw new RuntimeException(
                    "SysUI Tests should use SysuiTestCase#getContext or SysuiTestCase#mContext");
        });
        when(inst.getTargetContext()).thenAnswer(invocation -> {
            throw new RuntimeException(
                    "SysUI Tests should use SysuiTestCase#getContext or SysuiTestCase#mContext");
        });
        InstrumentationRegistry.registerInstance(inst, InstrumentationRegistry.getArguments());
    }

    @After
    public void SysuiTeardown() {
        InstrumentationRegistry.registerInstance(mRealInstrumentation,
                InstrumentationRegistry.getArguments());
    }

    protected LeakCheck getLeakCheck() {
        return null;
    }

    public SysuiTestableContext getContext() {
        return mContext;
    }

    protected void runShellCommand(String command) throws IOException {
        ParcelFileDescriptor pfd = mRealInstrumentation.getUiAutomation()
                .executeShellCommand(command);

        // Read the input stream fully.
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        while (fis.read() != -1);
        fis.close();
    }

    protected void waitForIdleSync() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        waitForIdleSync(mHandler);
    }

    protected void waitForUiOffloadThread() {
        Future<?> future = Dependency.get(UiOffloadThread.class).submit(() -> {});
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Failed to wait for ui offload thread.", e);
        }
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

    public static final class EmptyRunnable implements Runnable {
        public void run() {
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
