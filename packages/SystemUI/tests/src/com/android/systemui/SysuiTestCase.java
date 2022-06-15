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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.testing.DexmakerShareClassLoaderRule;
import android.testing.LeakCheck;
import android.testing.TestableLooper;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.FakeBroadcastDispatcher;
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.SmartReplyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
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
    public TestableDependency mDependency;
    private Instrumentation mRealInstrumentation;
    private FakeBroadcastDispatcher mFakeBroadcastDispatcher;

    @Before
    public void SysuiSetup() throws Exception {
        SystemUIFactory.createFromConfig(mContext, true);
        mDependency = new TestableDependency(
                SystemUIFactory.getInstance().getSysUIComponent().createDependency());
        Dependency.setInstance(mDependency);
        mFakeBroadcastDispatcher = new FakeBroadcastDispatcher(mContext, mock(Looper.class),
                mock(Executor.class), mock(DumpManager.class),
                mock(BroadcastDispatcherLogger.class), mock(UserTracker.class));

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
        // Many tests end up creating a BroadcastDispatcher. Instead, give them a fake that will
        // record receivers registered. They are not actually leaked as they are kept just as a weak
        // reference and are never sent to the Context. This will also prevent a real
        // BroadcastDispatcher from actually registering receivers.
        mDependency.injectTestDependency(BroadcastDispatcher.class, mFakeBroadcastDispatcher);
        // A lot of tests get the FalsingManager, often via several layers of indirection.
        // None of them actually need it.
        mDependency.injectTestDependency(FalsingManager.class, new FalsingManagerFake());
        mDependency.injectMockDependency(KeyguardUpdateMonitor.class);

        // A lot of tests get the LocalBluetoothManager, often via several layers of indirection.
        // None of them actually need it.
        mDependency.injectMockDependency(LocalBluetoothManager.class);

        // Notifications tests are injecting one of these, causing many classes (including
        // KeyguardUpdateMonitor to be created (injected).
        // TODO(b/1531701009) Clean up NotificationContentView creation to prevent this
        mDependency.injectMockDependency(SmartReplyController.class);
    }

    @After
    public void SysuiTeardown() {
        InstrumentationRegistry.registerInstance(mRealInstrumentation,
                InstrumentationRegistry.getArguments());
        if (TestableLooper.get(this) != null) {
            TestableLooper.get(this).processAllMessages();
        }
        disallowTestableLooperAsMainThread();
        SystemUIFactory.cleanup();
        mContext.cleanUpReceivers(this.getClass().getSimpleName());
        mFakeBroadcastDispatcher.cleanUpReceivers(this.getClass().getSimpleName());
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

    protected FakeBroadcastDispatcher getFakeBroadcastDispatcher() {
        return mFakeBroadcastDispatcher;
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
