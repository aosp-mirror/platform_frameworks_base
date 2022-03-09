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

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.util.Log;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.appop.AppOpsService;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.firewall.IntentFirewall;
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
import java.util.function.Supplier;

/**
 * JUnit test rule to correctly setting up system services like {@link WindowManagerService}
 * and {@link ActivityTaskManagerService} for tests.
 */
public class SystemServicesTestRule implements TestRule {

    private static final String TAG = SystemServicesTestRule.class.getSimpleName();

    static int sNextDisplayId = DEFAULT_DISPLAY + 100;
    static int sNextTaskId = 100;

    private static final int[] TEST_USER_PROFILE_IDS = {};
    /** Use a real static object so there won't be NPE in finalize() after clearInlineMocks(). */
    private static final PowerManager.WakeLock sWakeLock = getInstrumentation().getContext()
            .getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
    private PowerManager.WakeLock mStubbedWakeLock;

    private Description mDescription;
    private Context mContext;
    private StaticMockitoSession mMockitoSession;
    private ActivityManagerService mAmService;
    private ActivityTaskManagerService mAtmService;
    private WindowManagerService mWmService;
    private TestWindowManagerPolicy mWMPolicy;
    private TestDisplayWindowSettingsProvider mTestDisplayWindowSettingsProvider;
    private WindowState.PowerManagerWrapper mPowerManagerWrapper;
    private InputManagerService mImService;
    private InputChannel mInputChannel;
    private Supplier<Surface> mSurfaceFactory = () -> mock(Surface.class);
    /**
     * Spied {@link SurfaceControl.Transaction} class than can be used to verify calls.
     */
    SurfaceControl.Transaction mTransaction;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                mDescription = description;
                Throwable throwable = null;
                try {
                    runWithDexmakerShareClassLoader(SystemServicesTestRule.this::setUp);
                    base.evaluate();
                } catch (Throwable t) {
                    throwable = t;
                } finally {
                    try {
                        tearDown();
                    } catch (Throwable t) {
                        if (throwable != null) {
                            Log.e("SystemServicesTestRule", "Suppressed: ", throwable);
                            t.addSuppressed(throwable);
                        }
                        throw t;
                    }
                    if (throwable != null) throw throwable;
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

        // Prevent "WakeLock finalized while still held: SCREEN_FROZEN".
        final PowerManager pm = mock(PowerManager.class);
        doReturn(pm).when(mContext).getSystemService(eq(Context.POWER_SERVICE));
        mStubbedWakeLock = createStubbedWakeLock(false /* needVerification */);
        doReturn(mStubbedWakeLock).when(pm).newWakeLock(anyInt(), anyString());

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

        ComponentName systemServiceComponent = new ComponentName("android.test.system.service", "");
        doReturn(systemServiceComponent).when(packageManagerInternal).getSystemUiServiceComponent();

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
        // InputChannel cannot be mocked because it may pass to InputEventReceiver.
        final InputChannel[] inputChannels = InputChannel.openInputChannelPair(TAG);
        inputChannels[0].dispose();
        mInputChannel = inputChannels[1];
        doReturn(mInputChannel).when(mImService).monitorInput(anyString(), anyInt());
        doReturn(mInputChannel).when(mImService).createInputChannel(anyString());

        // StatusBarManagerInternal
        final StatusBarManagerInternal sbmi = mock(StatusBarManagerInternal.class);
        doReturn(sbmi).when(() -> LocalServices.getService(eq(StatusBarManagerInternal.class)));
    }

    private void setUpActivityTaskManagerService() {
        // ActivityManagerService
        mAmService = new ActivityManagerService(new AMTestInjector(mContext), null /* thread */);
        spyOn(mAmService);
        doReturn(mock(IPackageManager.class)).when(mAmService).getPackageManager();
        doNothing().when(mAmService).grantImplicitAccess(
                anyInt(), any(), anyInt(), anyInt());

        // ActivityManagerInternal
        final ActivityManagerInternal amInternal = mAmService.mInternal;
        spyOn(amInternal);
        doNothing().when(amInternal).trimApplications();
        doNothing().when(amInternal).scheduleAppGcs();
        doNothing().when(amInternal).updateCpuStats();
        doNothing().when(amInternal).updateOomAdj();
        doNothing().when(amInternal).updateBatteryStats(any(), anyInt(), anyInt(), anyBoolean());
        doNothing().when(amInternal).updateActivityUsageStats(
                any(), anyInt(), anyInt(), any(), any());
        doNothing().when(amInternal).startProcess(
                any(), any(), anyBoolean(), anyBoolean(), any(), any());
        doNothing().when(amInternal).updateOomLevelsForDisplay(anyInt());
        doNothing().when(amInternal).broadcastGlobalConfigurationChanged(anyInt(), anyBoolean());
        doNothing().when(amInternal).cleanUpServices(anyInt(), any(), any());
        doReturn(UserHandle.USER_SYSTEM).when(amInternal).getCurrentUserId();
        doReturn(TEST_USER_PROFILE_IDS).when(amInternal).getCurrentProfileIds();
        doReturn(true).when(amInternal).isUserRunning(anyInt(), anyInt());
        doReturn(true).when(amInternal).hasStartedUserState(anyInt());
        doReturn(false).when(amInternal).shouldConfirmCredentials(anyInt());
        doReturn(false).when(amInternal).isActivityStartsLoggingEnabled();
        LocalServices.addService(ActivityManagerInternal.class, amInternal);

        mAtmService = new TestActivityTaskManagerService(mContext, mAmService);
        LocalServices.addService(ActivityTaskManagerInternal.class, mAtmService.getAtmInternal());
    }

    private void setUpWindowManagerService() {
        mPowerManagerWrapper = mock(WindowState.PowerManagerWrapper.class);
        mWMPolicy = new TestWindowManagerPolicy(this::getWindowManagerService,
                mPowerManagerWrapper);
        mTestDisplayWindowSettingsProvider = new TestDisplayWindowSettingsProvider();
        // Suppress StrictMode violation (DisplayWindowSettings) to avoid log flood.
        DisplayThread.getHandler().post(StrictMode::allowThreadDiskWritesMask);
        mWmService = WindowManagerService.main(
                mContext, mImService, false, false, mWMPolicy, mAtmService,
                mTestDisplayWindowSettingsProvider, StubTransaction::new,
                () -> mSurfaceFactory.get(), (unused) -> new MockSurfaceControlBuilder());
        spyOn(mWmService);
        spyOn(mWmService.mRoot);
        // Invoked during {@link ActivityStack} creation.
        doNothing().when(mWmService.mRoot).updateUIDsPresentOnDisplay();
        // Always keep things awake.
        doReturn(true).when(mWmService.mRoot).hasAwakeDisplay();
        // Called when moving activity to pinned stack.
        doNothing().when(mWmService.mRoot).ensureActivitiesVisible(any(),
                anyInt(), anyBoolean(), anyBoolean());
        spyOn(mWmService.mDisplayWindowSettings);
        spyOn(mWmService.mDisplayWindowSettingsProvider);

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

        // Mock default display, and home stack.
        final DisplayContent display = mAtmService.mRootWindowContainer.getDefaultDisplay();
        // Set default display to be in fullscreen mode. Devices with PC feature may start their
        // default display in freeform mode but some of tests in WmTests have implicit assumption on
        // that the default display is in fullscreen mode.
        display.setDisplayWindowingMode(WINDOWING_MODE_FULLSCREEN);
        spyOn(display);
        final TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();

        // Set the default focused TDA.
        display.onLastFocusedTaskDisplayAreaChanged(taskDisplayArea);
        spyOn(taskDisplayArea);
        final Task homeStack = taskDisplayArea.getRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        spyOn(homeStack);
    }

    private void tearDown() {
        mWmService.mRoot.forAllDisplayPolicies(DisplayPolicy::release);

        // Unregister display listener from root to avoid issues with subsequent tests.
        mContext.getSystemService(DisplayManager.class)
                .unregisterDisplayListener(mAtmService.mRootWindowContainer);
        // The constructor of WindowManagerService registers WindowManagerConstants and
        // HighRefreshRateBlacklist with DeviceConfig. We need to undo that here to avoid
        // leaking mWmService.
        mWmService.mConstants.dispose();
        mWmService.mHighRefreshRateDenylist.dispose();

        // This makes sure the posted messages without delay are processed, e.g.
        // DisplayPolicy#release, WindowManagerService#setAnimationScale.
        waitUntilWindowManagerHandlersIdle();
        // Clear all posted messages with delay, so they don't be executed at unexpected times.
        cleanupWindowManagerHandlers();
        // Needs to explicitly dispose current static threads because there could be messages
        // scheduled at a later time, and all mocks are invalid when it's executed.
        DisplayThread.dispose();
        // Dispose SurfaceAnimationThread before AnimationThread does, so it won't create a new
        // AnimationThread after AnimationThread disposed, see {@link
        // AnimatorListenerAdapter#onAnimationEnd()}
        SurfaceAnimationThread.dispose();
        AnimationThread.dispose();
        UiThread.dispose();
        mInputChannel.dispose();

        tearDownLocalServices();
        // Reset priority booster because animation thread has been changed.
        WindowManagerService.sThreadPriorityBooster = new WindowManagerThreadPriorityBooster();

        mMockitoSession.finishMocking();
        Mockito.framework().clearInlineMocks();
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

    Description getDescription() {
        return mDescription;
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

    /** Creates a no-op wakelock object. */
    PowerManager.WakeLock createStubbedWakeLock(boolean needVerification) {
        if (needVerification) {
            return mock(PowerManager.WakeLock.class, Mockito.withSettings()
                    .spiedInstance(sWakeLock).defaultAnswer(Mockito.RETURNS_DEFAULTS));
        }
        return mock(PowerManager.WakeLock.class, Mockito.withSettings()
                .spiedInstance(sWakeLock).stubOnly());
    }

    void setSurfaceFactory(Supplier<Surface> factory) {
        mSurfaceFactory = factory;
    }

    void cleanupWindowManagerHandlers() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        wm.mH.removeCallbacksAndMessages(null);
        wm.mAnimationHandler.removeCallbacksAndMessages(null);
        // This is a different handler object than the wm.mAnimationHandler above.
        AnimationThread.getHandler().removeCallbacksAndMessages(null);
        SurfaceAnimationThread.getHandler().removeCallbacksAndMessages(null);
    }

    void waitUntilWindowManagerHandlersIdle() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        waitHandlerIdle(wm.mH);
        waitHandlerIdle(wm.mAnimationHandler);
        // This is a different handler object than the wm.mAnimationHandler above.
        waitHandlerIdle(AnimationThread.getHandler());
        waitHandlerIdle(SurfaceAnimationThread.getHandler());
    }

    static void waitHandlerIdle(Handler handler) {
        handler.runWithScissors(() -> { }, 0 /* timeout */);
    }

    void waitUntilWindowAnimatorIdle() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        // Add a message to the handler queue and make sure it is fully processed before we move on.
        // This makes sure all previous messages in the handler are fully processed vs. just popping
        // them from the message queue.
        final AtomicBoolean currentMessagesProcessed = new AtomicBoolean(false);
        wm.mAnimator.getChoreographer().postFrameCallback(time -> {
            synchronized (currentMessagesProcessed) {
                currentMessagesProcessed.set(true);
                currentMessagesProcessed.notifyAll();
            }
        });
        while (!currentMessagesProcessed.get()) {
            synchronized (currentMessagesProcessed) {
                try {
                    currentMessagesProcessed.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Throws if caller doesn't hold the given lock.
     * @param lock the lock
     */
    static void checkHoldsLock(Object lock) {
        if (!Thread.holdsLock(lock)) {
            throw new IllegalStateException("Caller doesn't hold global lock.");
        }
    }

    protected class TestActivityTaskManagerService extends ActivityTaskManagerService {
        // ActivityTaskSupervisor may be created more than once while setting up AMS and ATMS.
        // We keep the reference in order to prevent creating it twice.
        ActivityTaskSupervisor mTestTaskSupervisor;

        TestActivityTaskManagerService(Context context, ActivityManagerService ams) {
            super(context);
            spyOn(this);

            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;
            mDevEnableNonResizableMultiWindow = false;
            mMinPercentageMultiWindowSupportHeight = 0.3f;
            mMinPercentageMultiWindowSupportWidth = 0.5f;
            mLargeScreenSmallestScreenWidthDp = 600;
            mSupportsNonResizableMultiWindow = 0;
            mRespectsActivityMinWidthHeightMultiWindow = 0;
            mForceResizableActivities = false;

            doReturn(mock(IPackageManager.class)).when(this).getPackageManager();
            // allow background activity starts by default
            doReturn(true).when(this).isBackgroundActivityStartsEnabled();
            doNothing().when(this).updateCpuStats();

            // AppOpsService
            final AppOpsManager aos = mock(AppOpsManager.class);
            doReturn(aos).when(this).getAppOpsManager();
            // Make sure permission checks aren't overridden.
            doReturn(AppOpsManager.MODE_DEFAULT).when(aos).noteOpNoThrow(anyInt(), anyInt(),
                    anyString(), nullable(String.class), nullable(String.class));

            // UserManagerService
            final UserManagerService ums = mock(UserManagerService.class);
            doReturn(ums).when(this).getUserManager();
            doReturn(TEST_USER_PROFILE_IDS).when(ums).getProfileIds(anyInt(), eq(true));

            setUsageStatsManager(LocalServices.getService(UsageStatsManagerInternal.class));
            ams.mActivityTaskManager = this;
            ams.mAtmInternal = mInternal;
            onActivityManagerInternalAdded();

            final IntentFirewall intentFirewall = mock(IntentFirewall.class);
            doReturn(true).when(intentFirewall).checkStartActivity(
                    any(), anyInt(), anyInt(), nullable(String.class), any());
            initialize(intentFirewall, null /* intentController */,
                    DisplayThread.getHandler().getLooper());
            spyOn(getLifecycleManager());
            spyOn(getLockTaskController());
            spyOn(getTaskChangeNotificationController());

            AppWarnings appWarnings = getAppWarningsLocked();
            spyOn(appWarnings);
            doNothing().when(appWarnings).onStartActivity(any());
        }

        @Override
        int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
            return userId;
        }

        @Override
        protected ActivityTaskSupervisor createTaskSupervisor() {
            if (mTestTaskSupervisor == null) {
                mTestTaskSupervisor = new TestActivityTaskSupervisor(this, mH.getLooper());
            }
            return mTestTaskSupervisor;
        }
    }

    /**
     * An {@link ActivityTaskSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected class TestActivityTaskSupervisor extends ActivityTaskSupervisor {

        TestActivityTaskSupervisor(ActivityTaskManagerService service, Looper looper) {
            super(service, looper);
            spyOn(this);

            // Do not schedule idle that may touch methods outside the scope of the test.
            doNothing().when(this).scheduleIdle();
            doNothing().when(this).scheduleIdleTimeout(any());
            // unit test version does not handle launch wake lock
            doNothing().when(this).acquireLaunchWakelock();

            mLaunchingActivityWakeLock = mStubbedWakeLock;

            initialize();

            final KeyguardController controller = getKeyguardController();
            spyOn(controller);
            doReturn(true).when(controller).checkKeyguardVisibility(any());
        }
    }

    // TODO: Can we just mock this?
    private static class AMTestInjector extends ActivityManagerService.Injector {

        AMTestInjector(Context context) {
            super(context);
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
            return UiThread.getHandler();
        }

        @Override
        public boolean isNetworkRestrictedForUid(int uid) {
            return false;
        }
    }
}
