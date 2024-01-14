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

import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.DexmakerShareClassLoaderRule;
import android.testing.LeakCheck;
import android.testing.TestWithLooperRule;
import android.testing.TestableLooper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.animation.AndroidXAnimatorIsolationRule;
import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.systemui.broadcast.FakeBroadcastDispatcher;
import com.android.systemui.flags.SceneContainerRule;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mockito;

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

    // set the lowest order so it's the outermost rule
    @Rule(order = Integer.MIN_VALUE)
    public AndroidXAnimatorIsolationRule mAndroidXAnimatorIsolationRule =
            new AndroidXAnimatorIsolationRule();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Rule(order = 10)
    public final SceneContainerRule mSceneContainerRule = new SceneContainerRule();

    @Rule
    public SysuiTestableContext mContext = createTestableContext();

    @NonNull
    private SysuiTestableContext createTestableContext() {
        SysuiTestableContext context = new SysuiTestableContext(
                InstrumentationRegistry.getContext(), getLeakCheck());
        if (isRobolectricTest()) {
            // Manually associate a Display to context for Robolectric test. Similar to b/214297409
            return context.createDefaultDisplayContext();
        } else {
            return context;
        }
    }

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    // set the highest order so it's the innermost rule
    @Rule(order = Integer.MAX_VALUE)
    public TestWithLooperRule mlooperRule = new TestWithLooperRule();

    public TestableDependency mDependency;
    private Instrumentation mRealInstrumentation;
    private SysuiTestDependency mSysuiDependency;

    @Before
    public void SysuiSetup() throws Exception {
        mSysuiDependency = new SysuiTestDependency(mContext, shouldFailOnLeakedReceiver());
        mDependency = mSysuiDependency.install();
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

    protected boolean shouldFailOnLeakedReceiver() {
        return false;
    }

    @After
    public void SysuiTeardown() {
        if (mRealInstrumentation != null) {
            InstrumentationRegistry.registerInstance(mRealInstrumentation,
                    InstrumentationRegistry.getArguments());
        }
        if (TestableLooper.get(this) != null) {
            TestableLooper.get(this).processAllMessages();
            // Must remove static reference to this test object to prevent leak (b/261039202)
            TestableLooper.remove(this);
        }
        disallowTestableLooperAsMainThread();
        mContext.cleanUpReceivers(this.getClass().getSimpleName());
        FakeBroadcastDispatcher dispatcher = getFakeBroadcastDispatcher();
        if (dispatcher != null) {
            dispatcher.cleanUpReceivers(this.getClass().getSimpleName());
        }
    }

    @AfterClass
    public static void mockitoTearDown() {
        Mockito.framework().clearInlineMocks();
    }

    /**
     * Tests are run on the TestableLooper; however, there are parts of SystemUI that assert that
     * the code is run from the main looper. Therefore, we allow the TestableLooper to pass these
     * assertions since in a test, the TestableLooper is essentially the MainLooper.
     */
    protected void allowTestableLooperAsMainThread() {
        com.android.systemui.util.Assert.setTestableLooper(TestableLooper.get(this).getLooper());
    }

    protected void disallowTestableLooperAsMainThread() {
        com.android.systemui.util.Assert.setTestableLooper(null);
    }

    protected LeakCheck getLeakCheck() {
        return null;
    }

    public FakeBroadcastDispatcher getFakeBroadcastDispatcher() {
        return mSysuiDependency.getFakeBroadcastDispatcher();
    }

    public SysuiTestableContext getContext() {
        return mContext;
    }

    protected UiDevice getUiDevice() {
        return UiDevice.getInstance(mRealInstrumentation);
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
        Future<?> future = Dependency.get(UiOffloadThread.class).execute(() -> { });
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

    public static boolean isRobolectricTest() {
        return Build.FINGERPRINT.contains("robolectric");
    }

    private static final void validateThread(Looper l) {
        if (Looper.myLooper() == l) {
            throw new RuntimeException(
                "This method can not be called from the looper being synced");
        }
    }

    /** Delegates to {@link android.testing.TestableResources#addOverride(int, Object)}. */
    protected void overrideResource(int resourceId, Object value) {
        mContext.getOrCreateTestableResources().addOverride(resourceId, value);
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
