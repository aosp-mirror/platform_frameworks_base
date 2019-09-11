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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.nullable;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.StrictMode;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.ServiceThread;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.appop.AppOpsService;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.input.InputManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JUnit test rule to correctly setting up system services like {@link WindowManagerService}
 * and {@link ActivityTaskManagerService} for tests.
 */
public class SystemServicesTestRule implements TestRule {

    private static final String TAG = SystemServicesTestRule.class.getSimpleName();

    static int sNextDisplayId = DEFAULT_DISPLAY + 100;
    static int sNextTaskId = 100;

    private final AtomicBoolean mCurrentMessagesProcessed = new AtomicBoolean(false);
    private static final int[] TEST_USER_PROFILE_IDS = {};

    private Context mContext;
    private StaticMockitoSession mMockitoSession;
    ServiceThread mHandlerThread;
    private ActivityManagerService mAmService;
    private ActivityTaskManagerService mAtmService;
    private WindowManagerService mWmService;
    private TestWindowManagerPolicy mWMPolicy;
    private WindowState.PowerManagerWrapper mPowerManagerWrapper;
    private InputManagerService mImService;
    /**
     * Spied {@link SurfaceControl.Transaction} class than can be used to verify calls.
     */
    SurfaceControl.Transaction mTransaction;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    runWithDexmakerShareClassLoader(SystemServicesTestRule.this::setUp);
                    base.evaluate();
                } finally {
                    tearDown();
                }
            }
        };
    }

    private void setUp() {
        mMockitoSession = mockitoSession()
                .spyStatic(LocalServices.class)
                .mockStatic(LockGuard.class)
                .mockStatic(Watchdog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        setUpSystemCore();
        setUpLocalServices();
        setUpActivityTaskManagerService();
        setUpWindowManagerService();
    }

    private void setUpSystemCore() {
        mHandlerThread = new ServiceThread(
                "WmTestsThread", THREAD_PRIORITY_DEFAULT, true /* allowIo */);
        mHandlerThread.start();

        doReturn(mock(Watchdog.class)).when(Watchdog::getInstance);

        mContext = getInstrumentation().getTargetContext();
        spyOn(mContext);

        doReturn(null).when(mContext)
                .registerReceiver(nullable(BroadcastReceiver.class), any(IntentFilter.class));
        doReturn(null).when(mContext)
                .registerReceiverAsUser(any(BroadcastReceiver.class), any(UserHandle.class),
                        any(IntentFilter.class), nullable(String.class), nullable(Handler.class));

        final ContentResolver contentResolver = mContext.getContentResolver();
        spyOn(contentResolver);
        doNothing().when(contentResolver)
                .registerContentObserver(any(Uri.class), anyBoolean(), any(ContentObserver.class),
                        anyInt());
    }

    private void setUpLocalServices() {
        // Tear down any local services just in case.
        tearDownLocalServices();

        // UriGrantsManagerInternal
        final UriGrantsManagerInternal ugmi = mock(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, ugmi);

        // AppOpsManager
        final AppOpsManager aom = mock(AppOpsManager.class);
        doReturn(aom).when(mContext).getSystemService(eq(Context.APP_OPS_SERVICE));

        // DisplayManagerInternal
        final DisplayManagerInternal dmi = mock(DisplayManagerInternal.class);
        doReturn(dmi).when(() -> LocalServices.getService(eq(DisplayManagerInternal.class)));

        // ColorDisplayServiceInternal
        final ColorDisplayService.ColorDisplayServiceInternal cds =
                mock(ColorDisplayService.ColorDisplayServiceInternal.class);
        doReturn(cds).when(() -> LocalServices.getService(
                eq(ColorDisplayService.ColorDisplayServiceInternal.class)));

        final UsageStatsManagerInternal usmi = mock(UsageStatsManagerInternal.class);
        LocalServices.addService(UsageStatsManagerInternal.class, usmi);

        // PackageManagerInternal
        final PackageManagerInternal packageManagerInternal = mock(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, packageManagerInternal);
        doReturn(false).when(packageManagerInternal).isPermissionsReviewRequired(
                anyString(), anyInt());
        doReturn(null).when(packageManagerInternal).getDefaultHomeActivity(anyInt());

        // PowerManagerInternal
        final PowerManagerInternal pmi = mock(PowerManagerInternal.class);
        final PowerSaveState state = new PowerSaveState.Builder().build();
        doReturn(state).when(pmi).getLowPowerState(anyInt());
        doReturn(pmi).when(() -> LocalServices.getService(eq(PowerManagerInternal.class)));

        // PermissionPolicyInternal
        final PermissionPolicyInternal ppi = mock(PermissionPolicyInternal.class);
        LocalServices.addService(PermissionPolicyInternal.class, ppi);
        doReturn(true).when(ppi).checkStartActivity(any(), anyInt(), any());

        // InputManagerService
        mImService = mock(InputManagerService.class);
        // InputChannel is final and can't be mocked.
        final InputChannel[] input = InputChannel.openInputChannelPair(TAG_WM);
        if (input != null && input.length > 1) {
            doReturn(input[1]).when(mImService).monitorInput(anyString(), anyInt());
        }

        // StatusBarManagerInternal
        final StatusBarManagerInternal sbmi = mock(StatusBarManagerInternal.class);
        doReturn(sbmi).when(() -> LocalServices.getService(eq(StatusBarManagerInternal.class)));
    }

    private void setUpActivityTaskManagerService() {
        // ActivityManagerService
        mAmService = new ActivityManagerService(
                new AMTestInjector(mContext, mHandlerThread), mHandlerThread);
        spyOn(mAmService);
        doReturn(mock(IPackageManager.class)).when(mAmService).getPackageManager();
        doNothing().when(mAmService).grantEphemeralAccessLocked(
                anyInt(), any(), anyInt(), anyInt());

        // ActivityManagerInternal
        final ActivityManagerInternal amInternal = mAmService.mInternal;
        spyOn(amInternal);
        doNothing().when(amInternal).trimApplications();
        doNothing().when(amInternal).updateCpuStats();
        doNothing().when(amInternal).updateOomAdj();
        doNothing().when(amInternal).updateBatteryStats(any(), anyInt(), anyInt(), anyBoolean());
        doNothing().when(amInternal).updateActivityUsageStats(
                any(), anyInt(), anyInt(), any(), any());
        doNothing().when(amInternal).startProcess(
                any(), any(), anyBoolean(), anyBoolean(), any(), any());
        doNothing().when(amInternal).updateOomLevelsForDisplay(anyInt());
        LocalServices.addService(ActivityManagerInternal.class, amInternal);

        mAtmService = new TestActivityTaskManagerService(mContext, mAmService);
        LocalServices.addService(ActivityTaskManagerInternal.class, mAtmService.getAtmInternal());
    }

    private void setUpWindowManagerService() {
        mPowerManagerWrapper = mock(WindowState.PowerManagerWrapper.class);
        mWMPolicy = new TestWindowManagerPolicy(this::getWindowManagerService,
                mPowerManagerWrapper);
        // Suppress StrictMode violation (DisplayWindowSettings) to avoid log flood.
        DisplayThread.getHandler().post(StrictMode::allowThreadDiskWritesMask);
        mWmService = WindowManagerService.main(
                mContext, mImService, false, false, mWMPolicy, mAtmService, StubTransaction::new,
                () -> mock(Surface.class), (unused) -> new MockSurfaceControlBuilder());
        spyOn(mWmService);

        // Setup factory classes to prevent calls to native code.
        mTransaction = spy(StubTransaction.class);
        // Return a spied Transaction class than can be used to verify calls.
        mWmService.mTransactionFactory = () -> mTransaction;
        mWmService.mSurfaceAnimationRunner = new SurfaceAnimationRunner(
                null, null, mTransaction, mWmService.mPowerManagerInternal);

        mWmService.onInitReady();
        mAmService.setWindowManager(mWmService);
        mWmService.mDisplayEnabled = true;
        mWmService.mDisplayReady = true;
        // Set configuration for default display
        mWmService.getDefaultDisplayContentLocked().reconfigureDisplayLocked();

        // Mock root, some default display, and home stack.
        spyOn(mWmService.mRoot);
        final ActivityDisplay display = mAtmService.mRootActivityContainer.getDefaultDisplay();
        spyOn(display);
        spyOn(display.mDisplayContent);
        final ActivityStack homeStack = display.getStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        spyOn(homeStack);
        spyOn(homeStack.mTaskStack);
    }

    private void tearDown() {
        waitUntilWindowManagerHandlersIdle();
        // Unregister display listener from root to avoid issues with subsequent tests.
        mContext.getSystemService(DisplayManager.class)
                .unregisterDisplayListener(mAtmService.mRootActivityContainer);
        // ProptertiesChangesListener is registered in the constructor of WindowManagerService to
        // a static object, so we need to clean it up in tearDown(), even though we didn't set up
        // in tests.
        DeviceConfig.removeOnPropertiesChangedListener(mWmService.mPropertiesChangedListener);
        mWmService = null;
        mWMPolicy = null;
        mPowerManagerWrapper = null;

        tearDownLocalServices();
        tearDownSystemCore();

        // Needs to explicitly dispose current static threads because there could be messages
        // scheduled at a later time, and all mocks are invalid when it's executed.
        DisplayThread.dispose();
        AnimationThread.dispose();
        // Reset priority booster because animation thread has been changed.
        WindowManagerService.sThreadPriorityBooster = new WindowManagerThreadPriorityBooster();

        Mockito.framework().clearInlineMocks();
    }

    private void tearDownSystemCore() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
            mMockitoSession = null;
        }

        if (mHandlerThread != null) {
            // Make sure there are no running messages and then quit the thread so the next test
            // won't be affected.
            mHandlerThread.getThreadHandler().runWithScissors(mHandlerThread::quit,
                    0 /* timeout */);
        }
    }

    private static void tearDownLocalServices() {
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.removeServiceForTest(PermissionPolicyInternal.class);
        LocalServices.removeServiceForTest(ColorDisplayService.ColorDisplayServiceInternal.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
    }

    WindowManagerService getWindowManagerService() {
        return mWmService;
    }

    ActivityTaskManagerService getActivityTaskManagerService() {
        return mAtmService;
    }

    WindowState.PowerManagerWrapper getPowerManagerWrapper() {
        return mPowerManagerWrapper;
    }

    void cleanupWindowManagerHandlers() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        wm.mH.removeCallbacksAndMessages(null);
        wm.mAnimationHandler.removeCallbacksAndMessages(null);
        SurfaceAnimationThread.getHandler().removeCallbacksAndMessages(null);
    }

    void waitUntilWindowManagerHandlersIdle() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        // Removing delayed FORCE_GC message decreases time for waiting idle.
        wm.mH.removeMessages(WindowManagerService.H.FORCE_GC);
        waitHandlerIdle(wm.mH);
        waitHandlerIdle(wm.mAnimationHandler);
        waitHandlerIdle(SurfaceAnimationThread.getHandler());
        waitHandlerIdle(mHandlerThread.getThreadHandler());
    }

    private void waitHandlerIdle(Handler handler) {
        synchronized (mCurrentMessagesProcessed) {
            // Add a message to the handler queue and make sure it is fully processed before we move
            // on. This makes sure all previous messages in the handler are fully processed vs. just
            // popping them from the message queue.
            mCurrentMessagesProcessed.set(false);
            handler.post(() -> {
                synchronized (mCurrentMessagesProcessed) {
                    mCurrentMessagesProcessed.set(true);
                    mCurrentMessagesProcessed.notifyAll();
                }
            });
            while (!mCurrentMessagesProcessed.get()) {
                try {
                    mCurrentMessagesProcessed.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    protected class TestActivityTaskManagerService extends ActivityTaskManagerService {
        // ActivityStackSupervisor may be created more than once while setting up AMS and ATMS.
        // We keep the reference in order to prevent creating it twice.
        ActivityStackSupervisor mTestStackSupervisor;

        TestActivityTaskManagerService(Context context, ActivityManagerService ams) {
            super(context);
            spyOn(this);

            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;

            doReturn(mock(IPackageManager.class)).when(this).getPackageManager();
            // allow background activity starts by default
            doReturn(true).when(this).isBackgroundActivityStartsEnabled();
            doNothing().when(this).updateCpuStats();

            // AppOpsService
            final AppOpsService aos = mock(AppOpsService.class);
            doReturn(aos).when(this).getAppOpsService();
            // Make sure permission checks aren't overridden.
            doReturn(AppOpsManager.MODE_DEFAULT)
                    .when(aos).noteOperation(anyInt(), anyInt(), anyString());

            // UserManagerService
            final UserManagerService ums = mock(UserManagerService.class);
            doReturn(ums).when(this).getUserManager();
            doReturn(TEST_USER_PROFILE_IDS).when(ums).getProfileIds(anyInt(), eq(true));

            setUsageStatsManager(LocalServices.getService(UsageStatsManagerInternal.class));
            ams.mActivityTaskManager = this;
            ams.mAtmInternal = mInternal;
            onActivityManagerInternalAdded();
            initialize(
                    ams.mIntentFirewall, ams.mPendingIntentController, mHandlerThread.getLooper());
            spyOn(getLifecycleManager());
            spyOn(getLockTaskController());
            spyOn(getTaskChangeNotificationController());
            initRootActivityContainerMocks();
        }

        void initRootActivityContainerMocks() {
            spyOn(mRootActivityContainer);
            // Invoked during {@link ActivityStack} creation.
            doNothing().when(mRootActivityContainer).updateUIDsPresentOnDisplay();
            // Always keep things awake.
            doReturn(true).when(mRootActivityContainer).hasAwakeDisplay();
            // Called when moving activity to pinned stack.
            doNothing().when(mRootActivityContainer).ensureActivitiesVisible(any(), anyInt(),
                    anyBoolean());
        }

        @Override
        int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
            return userId;
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            if (mTestStackSupervisor == null) {
                mTestStackSupervisor = new TestActivityStackSupervisor(this, mH.getLooper());
            }
            return mTestStackSupervisor;
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected class TestActivityStackSupervisor extends ActivityStackSupervisor {

        TestActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
            super(service, looper);
            spyOn(this);

            // Do not schedule idle that may touch methods outside the scope of the test.
            doNothing().when(this).scheduleIdleLocked();
            doNothing().when(this).scheduleIdleTimeoutLocked(any());
            // unit test version does not handle launch wake lock
            doNothing().when(this).acquireLaunchWakelock();
            doReturn(mock(KeyguardController.class)).when(this).getKeyguardController();

            mLaunchingActivityWakeLock = mock(PowerManager.WakeLock.class);

            initialize();
        }
    }

    // TODO: Can we just mock this?
    private static class AMTestInjector extends ActivityManagerService.Injector {
        private ServiceThread mHandlerThread;

        AMTestInjector(Context context, ServiceThread handlerThread) {
            super(context);
            mHandlerThread = handlerThread;
        }

        @Override
        public Context getContext() {
            return getInstrumentation().getTargetContext();
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return null;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandlerThread.getThreadHandler();
        }

        @Override
        public boolean isNetworkRestrictedForUid(int uid) {
            return false;
        }
    }
}
