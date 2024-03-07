/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.os.Process.SYSTEM_UID;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.MergedConfiguration;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindow;
import android.view.IWindowSessionCallback;
import android.view.InsetsFrameProvider;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.inputmethod.ImeTracker;
import android.window.ClientWindowFrames;
import android.window.ITransitionPlayer;
import android.window.ScreenCapture;
import android.window.StartingWindowInfo;
import android.window.StartingWindowRemovalInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

import com.android.internal.policy.AttributeCache;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.wm.DisplayWindowSettings.SettingsProvider.SettingsEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.Description;
import org.mockito.Mockito;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;

/** Common base class for window manager unit test classes. */
class WindowTestsBase extends SystemServiceTestsBase {
    final Context mContext = getInstrumentation().getTargetContext();

    // Default package name
    static final String DEFAULT_COMPONENT_PACKAGE_NAME = "com.foo";

    static final int DEFAULT_TASK_FRAGMENT_ORGANIZER_UID = 10000;
    static final String DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME = "Test:TaskFragmentOrganizer";

    // Default base activity name
    private static final String DEFAULT_COMPONENT_CLASS_NAME = ".BarActivity";

    // An id appended to the end of the component name to make it unique
    static int sCurrentActivityId = 0;

    ActivityTaskManagerService mAtm;
    RootWindowContainer mRootWindowContainer;
    ActivityTaskSupervisor mSupervisor;
    ClientLifecycleManager mClientLifecycleManager;
    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mTestSession;
    private boolean mUseFakeSettingsProvider;

    DisplayInfo mDisplayInfo = new DisplayInfo();
    DisplayContent mDefaultDisplay;

    static final int STATUS_BAR_HEIGHT = 10;
    static final int NAV_BAR_HEIGHT = 15;

    /**
     * It is {@link #mDefaultDisplay} by default. If the test class or method is annotated with
     * {@link UseTestDisplay}, it will be an additional display.
     */
    DisplayContent mDisplayContent;

    // The following fields are only available depending on the usage of annotation UseTestDisplay
    // and UseCommonWindows.
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mNotificationShadeWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;

    /**
     * Spied {@link Transaction} class than can be used to verify calls.
     */
    Transaction mTransaction;

    /**
     * Whether device-specific global overrides have already been checked in
     * {@link WindowTestsBase#setUpBase()}.
     */
    private static boolean sGlobalOverridesChecked;
    /**
     * Whether device-specific overrides have already been checked in
     * {@link WindowTestsBase#setUpBase()} when the default display is used.
     */
    private static boolean sOverridesCheckedDefaultDisplay;
    /**
     * Whether device-specific overrides have already been checked in
     * {@link WindowTestsBase#setUpBase()} when a {@link TestDisplayContent} is used.
     */
    private static boolean sOverridesCheckedTestDisplay;

    private boolean mOriginalPerDisplayFocusEnabled;

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        mAtm = mSystemServicesTestRule.getActivityTaskManagerService();
        mSupervisor = mAtm.mTaskSupervisor;
        mRootWindowContainer = mAtm.mRootWindowContainer;
        mClientLifecycleManager = mAtm.getLifecycleManager();
        mWm = mSystemServicesTestRule.getWindowManagerService();
        mOriginalPerDisplayFocusEnabled = mWm.mPerDisplayFocusEnabled;
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        mDefaultDisplay = mWm.mRoot.getDefaultDisplay();
        // Update the display policy to make the screen fully turned on so animation is allowed
        final DisplayPolicy displayPolicy = mDefaultDisplay.getDisplayPolicy();
        displayPolicy.screenTurnedOn(null /* screenOnListener */);
        displayPolicy.finishKeyguardDrawn();
        displayPolicy.finishWindowsDrawn();
        displayPolicy.finishScreenTurningOn();

        final InsetsPolicy insetsPolicy = mDefaultDisplay.getInsetsPolicy();
        suppressInsetsAnimation(insetsPolicy.getTransientControlTarget());
        suppressInsetsAnimation(insetsPolicy.getPermanentControlTarget());

        mTransaction = mSystemServicesTestRule.mTransaction;

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getDisplayInfo(mDisplayInfo);

        // Only create an additional test display for annotated test class/method because it may
        // significantly increase the execution time.
        final Description description = mSystemServicesTestRule.getDescription();
        final UseTestDisplay useTestDisplay = getAnnotation(description, UseTestDisplay.class);
        if (useTestDisplay != null) {
            createTestDisplay(useTestDisplay);
        } else {
            mDisplayContent = mDefaultDisplay;
            final SetupWindows setupWindows = getAnnotation(description, SetupWindows.class);
            if (setupWindows != null) {
                addCommonWindows(setupWindows.addAllCommonWindows(), setupWindows.addWindows());
            }
        }

        // Ensure letterbox aspect ratio is not overridden on any device target.
        // {@link com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio}, is set
        // on some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(0);
        // Ensure letterbox horizontal position multiplier is not overridden on any device target.
        // {@link com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier},
        // may be set on some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(0.5f);
        // Ensure letterbox vertical position multiplier is not overridden on any device target.
        // {@link com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier},
        // may be set on some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.0f);
        // Ensure letterbox horizontal reachability treatment isn't overridden on any device target.
        // {@link com.android.internal.R.bool.config_letterboxIsHorizontalReachabilityEnabled},
        // may be set on some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(false);
        // Ensure letterbox vertical reachability treatment isn't overridden on any device target.
        // {@link com.android.internal.R.bool.config_letterboxIsVerticalReachabilityEnabled},
        // may be set on some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(false);
        // Ensure aspect ratio for unresizable apps isn't overridden on any device target.
        // {@link com.android.internal.R.bool
        // .config_letterboxIsSplitScreenAspectRatioForUnresizableAppsEnabled}, may be set on some
        // device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration
                .setIsSplitScreenAspectRatioForUnresizableAppsEnabled(false);
        // Ensure aspect ratio for al apps isn't overridden on any device target.
        // {@link com.android.internal.R.bool
        // .config_letterboxIsDisplayAspectRatioForFixedOrientationLetterboxEnabled}, may be set on
        // some device form factors.
        mAtm.mWindowManager.mLetterboxConfiguration
                .setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(false);

        checkDeviceSpecificOverridesNotApplied();
    }

    /**
     * The test doesn't create real SurfaceControls, but mocked ones. This prevents the target from
     * controlling them, or it will cause {@link NullPointerException}.
     */
    static void suppressInsetsAnimation(InsetsControlTarget target) {
        spyOn(target);
        Mockito.doNothing().when(target).notifyInsetsControlChanged();
    }

    @After
    public void tearDown() throws Exception {
        if (mUseFakeSettingsProvider) {
            FakeSettingsProvider.clearSettingsProvider();
        }
        mWm.mPerDisplayFocusEnabled = mOriginalPerDisplayFocusEnabled;
    }

    /**
     * Check that device-specific overrides are not applied. Only need to check once during entire
     * test run for each case: global overrides, default display, and test display.
     */
    private void checkDeviceSpecificOverridesNotApplied() {
        // Check global overrides
        if (!sGlobalOverridesChecked) {
            assertEquals(0, mWm.mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio(),
                    0 /* delta */);
            sGlobalOverridesChecked = true;
        }
        // Check display-specific overrides
        if (!sOverridesCheckedDefaultDisplay && mDisplayContent == mDefaultDisplay) {
            assertFalse(mDisplayContent.getIgnoreOrientationRequest());
            sOverridesCheckedDefaultDisplay = true;
        } else if (!sOverridesCheckedTestDisplay && mDisplayContent instanceof TestDisplayContent) {
            assertFalse(mDisplayContent.getIgnoreOrientationRequest());
            sOverridesCheckedTestDisplay = true;
        }
    }

    private void createTestDisplay(UseTestDisplay annotation) {
        beforeCreateTestDisplay();
        mDisplayContent = createNewDisplayWithImeSupport(DISPLAY_IME_POLICY_LOCAL);
        addCommonWindows(annotation.addAllCommonWindows(), annotation.addWindows());
        mDisplayContent.getDisplayPolicy().setRemoteInsetsControllerControlsSystemBars(false);

        // Adding a display will cause freezing the display. Make sure to wait until it's
        // unfrozen to not run into race conditions with the tests.
        waitUntilHandlersIdle();
    }

    private void addCommonWindows(boolean addAll, @CommonTypes int[] requestedWindows) {
        if (addAll || ArrayUtils.contains(requestedWindows, W_WALLPAPER)) {
            mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD)) {
            mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
            mDisplayContent.mInputMethodWindow = mImeWindow;
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD_DIALOG)) {
            mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                    "mImeDialogWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_STATUS_BAR)) {
            mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
            mStatusBarWindow.mAttrs.height = STATUS_BAR_HEIGHT;
            mStatusBarWindow.mAttrs.gravity = Gravity.TOP;
            mStatusBarWindow.mAttrs.layoutInDisplayCutoutMode =
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            mStatusBarWindow.mAttrs.setFitInsetsTypes(0);
            final IBinder owner = new Binder();
            mStatusBarWindow.mAttrs.providedInsets = new InsetsFrameProvider[] {
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.statusBars()),
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
            };
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NOTIFICATION_SHADE)) {
            mNotificationShadeWindow = createCommonWindow(null, TYPE_NOTIFICATION_SHADE,
                    "mNotificationShadeWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NAVIGATION_BAR)) {
            mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
            mNavBarWindow.mAttrs.height = NAV_BAR_HEIGHT;
            mNavBarWindow.mAttrs.gravity = Gravity.BOTTOM;
            mNavBarWindow.mAttrs.paramsForRotation = new WindowManager.LayoutParams[4];
            mNavBarWindow.mAttrs.setFitInsetsTypes(0);
            mNavBarWindow.mAttrs.layoutInDisplayCutoutMode =
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            mNavBarWindow.mAttrs.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
            final IBinder owner = new Binder();
            mNavBarWindow.mAttrs.providedInsets = new InsetsFrameProvider[] {
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars()),
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                    new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
            };
            // If the navigation bar cannot move then it is always at the bottom.
            if (mDisplayContent.getDisplayPolicy().navigationBarCanMove()) {
                for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
                    mNavBarWindow.mAttrs.paramsForRotation[rot] =
                            getNavBarLayoutParamsForRotation(rot, owner);
                }
            }
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_DOCK_DIVIDER)) {
            mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                    "mDockedDividerWindow");
        }
        final boolean addAboveApp = ArrayUtils.contains(requestedWindows, W_ABOVE_ACTIVITY);
        final boolean addBelowApp = ArrayUtils.contains(requestedWindows, W_BELOW_ACTIVITY);
        if (addAll || addAboveApp || addBelowApp
                || ArrayUtils.contains(requestedWindows, W_ACTIVITY)) {
            mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
        }
        if (addAll || addAboveApp) {
            mChildAppWindowAbove = createCommonWindow(mAppWindow, TYPE_APPLICATION_ATTACHED_DIALOG,
                    "mChildAppWindowAbove");
        }
        if (addAll || addBelowApp) {
            mChildAppWindowBelow = createCommonWindow(mAppWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                    "mChildAppWindowBelow");
        }
    }

    private WindowManager.LayoutParams getNavBarLayoutParamsForRotation(
            int rotation, IBinder owner) {
        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.MATCH_PARENT;
        int gravity = Gravity.BOTTOM;
        switch (rotation) {
            case ROTATION_UNDEFINED:
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                height = NAV_BAR_HEIGHT;
                break;
            case Surface.ROTATION_90:
                gravity = Gravity.RIGHT;
                width = NAV_BAR_HEIGHT;
                break;
            case Surface.ROTATION_270:
                gravity = Gravity.LEFT;
                width = NAV_BAR_HEIGHT;
                break;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR);
        lp.width = width;
        lp.height = height;
        lp.gravity = gravity;
        lp.setFitInsetsTypes(0);
        lp.privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
        };
        return lp;
    }

    void beforeCreateTestDisplay() {
        // Called before display is created.
    }

    /** Avoid writing values to real Settings. */
    ContentResolver useFakeSettingsProvider() {
        mUseFakeSettingsProvider = true;
        FakeSettingsProvider.clearSettingsProvider();
        final FakeSettingsProvider provider = new FakeSettingsProvider();
        // SystemServicesTestRule#setUpSystemCore has called spyOn for the ContentResolver.
        final ContentResolver resolver = mContext.getContentResolver();
        doReturn(provider.getIContentProvider()).when(resolver).acquireProvider(Settings.AUTHORITY);
        return resolver;
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        final WindowState win = createWindow(parent, type, name);
        // Prevent common windows from been IME targets.
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        if (type == TYPE_WALLPAPER) {
            return createWallpaperToken(dc);
        }
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return createTestWindowToken(type, dc);
        }

        return createActivityRecord(dc, windowingMode, activityType);
    }

    private WindowToken createWallpaperToken(DisplayContent dc) {
        return new WallpaperWindowToken(mWm, mock(IBinder.class), true /* explicit */, dc,
                true /* ownerCanManageAppTokens */);
    }

    WindowState createNavBarWithProvidedInsets(DisplayContent dc) {
        final WindowState navbar = createWindow(null, TYPE_NAVIGATION_BAR, dc, "navbar");
        final Binder owner = new Binder();
        navbar.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars())
                        .setInsetsSize(Insets.of(0, 0, 0, NAV_BAR_HEIGHT))
        };
        dc.getDisplayPolicy().addWindowLw(navbar, navbar.mAttrs);
        return navbar;
    }

    WindowState createStatusBarWithProvidedInsets(DisplayContent dc) {
        final WindowState statusBar = createWindow(null, TYPE_STATUS_BAR, dc, "statusBar");
        final Binder owner = new Binder();
        statusBar.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.statusBars())
                        .setInsetsSize(Insets.of(0, STATUS_BAR_HEIGHT, 0, 0))
        };
        statusBar.mAttrs.setFitInsetsTypes(0);
        dc.getDisplayPolicy().addWindowLw(statusBar, statusBar.mAttrs);
        return statusBar;
    }

    Session getTestSession() {
        if (mTestSession != null) {
            return mTestSession;
        }
        mTestSession = createTestSession(mAtm);
        return mTestSession;
    }

    private Session getTestSession(WindowToken token) {
        final ActivityRecord r = token.asActivityRecord();
        if (r == null || r.app == null) {
            return getTestSession();
        }
        // If the activity has a process, let the window session belonging to activity use the
        // process of the activity.
        int pid = r.app.getPid();
        if (pid == 0) {
            // See SystemServicesTestRule#addProcess, pid 0 isn't added to the map. So generate
            // a non-zero pid to initialize it.
            final int numPid = mAtm.mProcessMap.getPidMap().size();
            pid = numPid > 0 ? mAtm.mProcessMap.getPidMap().keyAt(numPid - 1) + 1 : 1;
            r.app.setPid(pid);
            mAtm.mProcessMap.put(pid, r.app);
        } else {
            final WindowState win = mRootWindowContainer.getWindow(w -> w.getProcess() == r.app);
            if (win != null) {
                // Reuse the same Session if there is a window uses the same process.
                return win.mSession;
            }
        }
        return createTestSession(mAtm, pid, r.getUid());
    }

    static Session createTestSession(ActivityTaskManagerService atms) {
        return createTestSession(atms, WindowManagerService.MY_PID, WindowManagerService.MY_UID);
    }

    static Session createTestSession(ActivityTaskManagerService atms, int pid, int uid) {
        if (atms.mProcessMap.getProcess(pid) == null) {
            SystemServicesTestRule.addProcess(atms, "testPkg", "testProc", pid, uid);
        }
        return new Session(atms.mWindowManager, new IWindowSessionCallback.Stub() {
            @Override
            public void onAnimatorScaleChanged(float scale) {
            }
        }, pid, uid);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final ActivityRecord activity = createNonAttachedActivityRecord(task.getDisplayContent());
        task.addChild(activity, 0);
        return createWindow(null, type, activity, name);
    }

    WindowState createDreamWindow(WindowState parent, int type, String name) {
        final WindowToken token = createWindowToken(
                mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_DREAM, type);
        return createWindow(parent, type, token, name);
    }

    // TODO: Move these calls to a builder?
    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            IWindow iwindow) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                false /* ownerCanAddInternalSystemWindow */, iwindow);
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    WindowState createWindow(WindowState parent, int type, String name, int ownerId) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name, ownerId)
                : createWindow(parent, type, parent.mToken, name, ownerId);
    }

    WindowState createWindow(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        return createWindow(
                parent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type, dc, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            int ownerId) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, ownerId);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                ownerCanAddInternalSystemWindow);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId) {
        return createWindow(parent, type, token, name, ownerId,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, ownerCanAddInternalSystemWindow,
                mIWindow);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow, IWindow iwindow) {
        return createWindow(parent, type, token, name, ownerId, UserHandle.getUserId(ownerId),
                ownerCanAddInternalSystemWindow, mWm, getTestSession(token), iwindow,
                mSystemServicesTestRule.getPowerManagerWrapper());
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, int userId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow,
            WindowState.PowerManagerWrapper powerManagerWrapper) {
        SystemServicesTestRule.checkHoldsLock(service.mGlobalLock);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);
        attrs.packageName = "test";

        final WindowState w = new WindowState(service, session, iWindow, token, parent,
                OP_NONE, attrs, VISIBLE, ownerId, userId,
                ownerCanAddInternalSystemWindow,
                powerManagerWrapper);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    static void makeWindowVisible(WindowState... windows) {
        for (WindowState win : windows) {
            win.mViewVisibility = View.VISIBLE;
            win.mRelayoutCalled = true;
            win.mHasSurface = true;
            win.mHidden = false;
            win.show(false /* doAnimation */, false /* requestAnim */);
        }
    }

    static void makeWindowVisibleAndDrawn(WindowState... windows) {
        makeWindowVisible(windows);
        for (WindowState win : windows) {
            win.mWinAnimator.mDrawState = HAS_DRAWN;
        }
    }

    static void makeLastConfigReportedToClient(WindowState w, boolean visible) {
        w.fillClientWindowFramesAndConfiguration(new ClientWindowFrames(),
                new MergedConfiguration(), true /* useLatestConfig */, visible);
    }

    /**
     * Gets the order of the given {@link Task} as its z-order in the hierarchy below this TDA.
     * The Task can be a direct child of a child TaskDisplayArea. {@code -1} if not found.
     */
    static int getTaskIndexOf(TaskDisplayArea taskDisplayArea, Task task) {
        int index = 0;
        final int childCount = taskDisplayArea.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final WindowContainer wc = taskDisplayArea.getChildAt(i);
            if (wc.asTask() != null) {
                if (wc.asTask() == task) {
                    return index;
                }
                index++;
            } else {
                final TaskDisplayArea tda = wc.asTaskDisplayArea();
                final int subIndex = getTaskIndexOf(tda, task);
                if (subIndex > -1) {
                    return index + subIndex;
                } else {
                    index += tda.getRootTaskCount();
                }
            }
        }
        return -1;
    }

    /** Creates a {@link TaskDisplayArea} right above the default one. */
    static TaskDisplayArea createTaskDisplayArea(DisplayContent displayContent,
            WindowManagerService service, String name, int displayAreaFeature) {
        final TaskDisplayArea newTaskDisplayArea = new TaskDisplayArea(
                displayContent, service, name, displayAreaFeature);
        final TaskDisplayArea defaultTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();

        // Insert the new TDA to the correct position.
        defaultTaskDisplayArea.getParent().addChild(newTaskDisplayArea,
                defaultTaskDisplayArea.getParent().mChildren.indexOf(defaultTaskDisplayArea)
                        + 1);
        return newTaskDisplayArea;
    }

    /**
     *  Creates a {@link Task} with a simple {@link ActivityRecord} and adds to the given
     *  {@link TaskDisplayArea}.
     */
    Task createTaskWithActivity(TaskDisplayArea taskDisplayArea,
            int windowingMode, int activityType, boolean onTop, boolean twoLevelTask) {
        return createTask(taskDisplayArea, windowingMode, activityType,
                onTop, true /* createActivity */, twoLevelTask);
    }

    /** Creates a {@link Task} and adds to the given {@link DisplayContent}. */
    Task createTask(DisplayContent dc) {
        return createTask(dc.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
    }

    Task createTask(DisplayContent dc, int windowingMode, int activityType) {
        return createTask(dc.getDefaultTaskDisplayArea(), windowingMode, activityType);
    }

    Task createTask(TaskDisplayArea taskDisplayArea, int windowingMode, int activityType) {
        return createTask(taskDisplayArea, windowingMode, activityType,
                true /* onTop */, false /* createActivity */, false /* twoLevelTask */);
    }

    /** Creates a {@link Task} and adds to the given {@link TaskDisplayArea}. */
    Task createTask(TaskDisplayArea taskDisplayArea, int windowingMode, int activityType,
            boolean onTop, boolean createActivity, boolean twoLevelTask) {
        final TaskBuilder builder = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(taskDisplayArea)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setOnTop(onTop)
                .setCreateActivity(createActivity);
        if (twoLevelTask) {
            return builder
                    .setCreateParentTask(true)
                    .build()
                    .getRootTask();
        } else {
            return builder.build();
        }
    }

    /** Creates a {@link Task} and adds to the given root {@link Task}. */
    Task createTaskInRootTask(Task rootTask, int userId) {
        final Task task = new TaskBuilder(rootTask.mTaskSupervisor)
                .setUserId(userId)
                .setParentTask(rootTask)
                .build();
        return task;
    }

    /** Creates an {@link ActivityRecord}. */
    static ActivityRecord createNonAttachedActivityRecord(DisplayContent dc) {
        final ActivityRecord activity = new ActivityBuilder(dc.mWmService.mAtmService)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, dc);
        return activity;
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * [Task] - [ActivityRecord]
     */
    ActivityRecord createActivityRecord(DisplayContent dc) {
        return createActivityRecord(dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * [Task] - [ActivityRecord]
     */
    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode,
            int activityType) {
        final Task task = createTask(dc, windowingMode, activityType);
        return createActivityRecord(dc, task);
    }

    /**
     *  Creates an {@link ActivityRecord} and adds it to the specified {@link Task}.
     * [Task] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecord(Task task) {
        return createActivityRecord(task.getDisplayContent(), task);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to the specified {@link Task}.
     * [Task] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecord(DisplayContent dc, Task task) {
        final ActivityRecord activity = new ActivityBuilder(dc.mWmService.mAtmService)
                .setTask(task)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, dc);
        return activity;
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * Then adds the new created {@link Task} to a new created parent {@link Task}
     * [Task1] - [Task2] - [ActivityRecord]
     */
    ActivityRecord createActivityRecordWithParentTask(DisplayContent dc, int windowingMode,
            int activityType) {
        final Task task = createTask(dc, windowingMode, activityType);
        return createActivityRecordWithParentTask(task);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * Then adds the new created {@link Task} to the specified parent {@link Task}
     * [Task1] - [Task2] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecordWithParentTask(Task parentTask) {
        final ActivityRecord activity = new ActivityBuilder(parentTask.mAtmService)
                .setParentTask(parentTask)
                .setCreateTask(true)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, parentTask.getDisplayContent());
        return activity;
    }

    private static void postCreateActivitySetup(ActivityRecord activity, DisplayContent dc) {
        activity.onDisplayChanged(dc);
        activity.setOccludesParent(true);
        activity.setVisible(true);
        activity.setVisibleRequested(true);
    }

    /**
     * Creates a {@link TaskFragment} with {@link ActivityRecord}, and attaches it to the
     * {@code parentTask}.
     *
     * @param parentTask the {@link Task} this {@link TaskFragment} is going to be attached.
     * @return the created {@link TaskFragment}
     */
    static TaskFragment createTaskFragmentWithActivity(@NonNull Task parentTask) {
        return new TaskFragmentBuilder(parentTask.mAtmService)
                .setParentTask(parentTask)
                .createActivityCount(1)
                .build();
    }

    /**
     * Creates an embedded {@link TaskFragment} organized by {@code organizer} with
     * {@link ActivityRecord}, and attaches it to the {@code parentTask}.
     *
     * @param parentTask the {@link Task} this {@link TaskFragment} is going to be attached.
     * @param organizer  the {@link TaskFragmentOrganizer} this {@link TaskFragment} is going to be
     *                   organized by.
     * @return the created {@link TaskFragment}
     */
    static TaskFragment createTaskFragmentWithEmbeddedActivity(@NonNull Task parentTask,
            @NonNull TaskFragmentOrganizer organizer) {
        final IBinder fragmentToken = new Binder();
        final TaskFragment taskFragment = new TaskFragmentBuilder(parentTask.mAtmService)
                .setParentTask(parentTask)
                .createActivityCount(1)
                .setOrganizer(organizer)
                .setFragmentToken(fragmentToken)
                .build();
        parentTask.mAtmService.mWindowOrganizerController.mLaunchTaskFragments
                .put(fragmentToken, taskFragment);
        return taskFragment;
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplayWithImeSupport(DISPLAY_IME_POLICY_LOCAL);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplayWithImeSupport(@DisplayImePolicy int imePolicy) {
        return createNewDisplay(mDisplayInfo, imePolicy, /* overrideSettings */ null);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo info) {
        return createNewDisplay(info, DISPLAY_IME_POLICY_LOCAL, /* overrideSettings */ null);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(DisplayInfo info, @DisplayImePolicy int imePolicy,
            @Nullable SettingsEntry overrideSettings) {
        final DisplayContent display =
                new TestDisplayContent.Builder(mAtm, info)
                        .setOverrideSettings(overrideSettings)
                        .build();
        final DisplayContent dc = display.mDisplayContent;
        // this display can show IME.
        dc.mWmService.mDisplayWindowSettings.setDisplayImePolicy(dc, imePolicy);
        return dc;
    }

    /**
     * Creates a {@link DisplayContent} with given display state and adds it to the system.
     *
     * @param displayState For initializing the state of the display. See
     *                     {@link Display#getState()}.
     */
    DisplayContent createNewDisplay(int displayState) {
        // Leverage main display info & initialize it with display state for given displayId.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.state = displayState;
        return createNewDisplay(displayInfo, DISPLAY_IME_POLICY_LOCAL, /* overrideSettings */ null);
    }

    /** Creates a {@link TestWindowState} */
    TestWindowState createWindowState(WindowManager.LayoutParams attrs, WindowToken token) {
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        return new TestWindowState(mWm, getTestSession(), mIWindow, attrs, token);
    }

    /** Creates a {@link DisplayContent} as parts of simulate display info for test. */
    DisplayContent createMockSimulatedDisplay() {
        return createMockSimulatedDisplay(/* overrideSettings */ null);
    }

    DisplayContent createMockSimulatedDisplay(@Nullable SettingsEntry overrideSettings) {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.state = Display.STATE_ON;
        displayInfo.ownerUid = SYSTEM_UID;
        return createNewDisplay(displayInfo, DISPLAY_IME_POLICY_FALLBACK_DISPLAY, overrideSettings);
    }

    IDisplayWindowInsetsController createDisplayWindowInsetsController() {
        return new IDisplayWindowInsetsController.Stub() {

            @Override
            public void insetsChanged(InsetsState insetsState) throws RemoteException {
            }

            @Override
            public void insetsControlChanged(InsetsState insetsState,
                    InsetsSourceControl[] insetsSourceControls) throws RemoteException {
            }

            @Override
            public void showInsets(int i, boolean b, @Nullable ImeTracker.Token t)
                    throws RemoteException {
            }

            @Override
            public void hideInsets(int i, boolean b, @Nullable ImeTracker.Token t)
                    throws RemoteException {
            }

            @Override
            public void topFocusedWindowChanged(ComponentName component,
                    int requestedVisibleTypes) {
            }
        };
    }

    BLASTSyncEngine createTestBLASTSyncEngine() {
        return createTestBLASTSyncEngine(mWm.mH);
    }

    BLASTSyncEngine createTestBLASTSyncEngine(Handler handler) {
        return new BLASTSyncEngine(mWm, handler) {
            @Override
            void scheduleTimeout(SyncGroup s, long timeoutMs) {
                // Disable timeout.
            }
        };
    }

    /** Sets up a simple implementation of transition player for shell transitions. */
    TestTransitionPlayer registerTestTransitionPlayer() {
        final TestTransitionPlayer testPlayer = new TestTransitionPlayer(
                mAtm.getTransitionController(), mAtm.mWindowOrganizerController);
        testPlayer.mController.registerTransitionPlayer(testPlayer, null /* playerProc */);
        return testPlayer;
    }

    /** Overrides the behavior of config_reverseDefaultRotation for the given display. */
    void setReverseDefaultRotation(DisplayContent dc, boolean reverse) {
        final DisplayRotation displayRotation = dc.getDisplayRotation();
        if (!Mockito.mockingDetails(displayRotation).isSpy()) {
            spyOn(displayRotation);
        }
        doAnswer(invocation -> {
            invocation.callRealMethod();
            final int w = invocation.getArgument(0);
            final int h = invocation.getArgument(1);
            if (w > h) {
                if (reverse) {
                    displayRotation.mPortraitRotation = Surface.ROTATION_90;
                    displayRotation.mUpsideDownRotation = Surface.ROTATION_270;
                } else {
                    displayRotation.mPortraitRotation = Surface.ROTATION_270;
                    displayRotation.mUpsideDownRotation = Surface.ROTATION_90;
                }
            } else {
                if (reverse) {
                    displayRotation.mLandscapeRotation = Surface.ROTATION_270;
                    displayRotation.mSeascapeRotation = Surface.ROTATION_90;
                } else {
                    displayRotation.mLandscapeRotation = Surface.ROTATION_90;
                    displayRotation.mSeascapeRotation = Surface.ROTATION_270;
                }
            }
            return null;
        }).when(displayRotation).configure(anyInt(), anyInt());
        displayRotation.configure(dc.mBaseDisplayWidth, dc.mBaseDisplayHeight);
    }

    /**
     * Avoids rotating screen disturbed by some conditions. It is usually used for the default
     * display that is not the instance of {@link TestDisplayContent} (it bypasses the conditions).
     *
     * @see DisplayRotation#updateRotationUnchecked
     */
    void unblockDisplayRotation(DisplayContent dc) {
        dc.mOpeningApps.clear();
        mWm.mAppsFreezingScreen = 0;
        mWm.stopFreezingDisplayLocked();
        // The rotation animation won't actually play, it needs to be cleared manually.
        dc.setRotationAnimation(null);
    }

    static void resizeDisplay(DisplayContent displayContent, int width, int height) {
        displayContent.updateBaseDisplayMetrics(width, height, displayContent.mBaseDisplayDensity,
                displayContent.mBaseDisplayPhysicalXDpi, displayContent.mBaseDisplayPhysicalYDpi);
        displayContent.getDisplayRotation().configure(width, height);
        final Configuration c = new Configuration();
        displayContent.computeScreenConfiguration(c);
        displayContent.onRequestedOverrideConfigurationChanged(c);
    }

    /** Used for the tests that assume the display is portrait by default. */
    static void makeDisplayPortrait(DisplayContent displayContent) {
        if (displayContent.mBaseDisplayHeight <= displayContent.mBaseDisplayWidth) {
            resizeDisplay(displayContent, 500, 1000);
        }
    }

    // The window definition for UseTestDisplay#addWindows. The test can declare to add only
    // necessary windows, that avoids adding unnecessary overhead of unused windows.
    static final int W_NOTIFICATION_SHADE = TYPE_NOTIFICATION_SHADE;
    static final int W_STATUS_BAR = TYPE_STATUS_BAR;
    static final int W_NAVIGATION_BAR = TYPE_NAVIGATION_BAR;
    static final int W_INPUT_METHOD_DIALOG = TYPE_INPUT_METHOD_DIALOG;
    static final int W_INPUT_METHOD = TYPE_INPUT_METHOD;
    static final int W_DOCK_DIVIDER = TYPE_DOCK_DIVIDER;
    static final int W_ABOVE_ACTIVITY = TYPE_APPLICATION_ATTACHED_DIALOG;
    static final int W_ACTIVITY = TYPE_BASE_APPLICATION;
    static final int W_BELOW_ACTIVITY = TYPE_APPLICATION_MEDIA_OVERLAY;
    static final int W_WALLPAPER = TYPE_WALLPAPER;

    /** The common window types supported by {@link UseTestDisplay}. */
    @Retention(RetentionPolicy.RUNTIME)
    @IntDef(value = {
            W_NOTIFICATION_SHADE,
            W_STATUS_BAR,
            W_NAVIGATION_BAR,
            W_INPUT_METHOD_DIALOG,
            W_INPUT_METHOD,
            W_DOCK_DIVIDER,
            W_ABOVE_ACTIVITY,
            W_ACTIVITY,
            W_BELOW_ACTIVITY,
            W_WALLPAPER,
    })
    @interface CommonTypes {
    }

    /**
     * The annotation to provide common windows on default display. This is mutually exclusive
     * with {@link UseTestDisplay}.
     */
    @Target({ ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @interface SetupWindows {
        boolean addAllCommonWindows() default false;
        @CommonTypes int[] addWindows() default {};
    }

    /**
     * The annotation for class and method (higher priority) to create a non-default display that
     * will be assigned to {@link #mDisplayContent}. It is used if the test needs
     * <ul>
     * <li>Pure empty display.</li>
     * <li>Independent and customizable orientation.</li>
     * <li>Cross display operation.</li>
     * </ul>
     *
     * @see TestDisplayContent
     * @see #createTestDisplay
     **/
    @Target({ ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @interface UseTestDisplay {
        boolean addAllCommonWindows() default false;
        @CommonTypes int[] addWindows() default {};
    }

    static <T extends Annotation> T getAnnotation(Description desc, Class<T> type) {
        final T annotation = desc.getAnnotation(type);
        if (annotation != null) return annotation;
        return desc.getTestClass().getAnnotation(type);
    }

    /** Creates and adds a {@link TestDisplayContent} to supervisor at the given position. */
    TestDisplayContent addNewDisplayContentAt(int position) {
        return new TestDisplayContent.Builder(mAtm, 1000, 1500).setPosition(position).build();
    }

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    public void removeGlobalMinSizeRestriction() {
        mAtm.mRootWindowContainer.forAllDisplays(
                displayContent -> displayContent.mMinSizeOfResizeableTaskDp = 1);
    }

    /** Mocks the behavior of taking a snapshot. */
    void mockSurfaceFreezerSnapshot(SurfaceFreezer surfaceFreezer) {
        final ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer =
                mock(ScreenCapture.ScreenshotHardwareBuffer.class);
        final HardwareBuffer hardwareBuffer = mock(HardwareBuffer.class);
        spyOn(surfaceFreezer);
        doReturn(screenshotBuffer).when(surfaceFreezer)
                .createSnapshotBufferInner(any(), any());
        doReturn(null).when(surfaceFreezer)
                .createFromHardwareBufferInner(any());
        doReturn(hardwareBuffer).when(screenshotBuffer).getHardwareBuffer();
        doReturn(100).when(hardwareBuffer).getWidth();
        doReturn(100).when(hardwareBuffer).getHeight();
    }

    static ComponentName getUniqueComponentName() {
        return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                DEFAULT_COMPONENT_CLASS_NAME + sCurrentActivityId++);
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        static final int DEFAULT_FAKE_UID = 12345;
        static final String DEFAULT_PROCESS_NAME = "procName";
        static int sProcNameSeq;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private String mTargetActivity;
        private Task mTask;
        private String mProcessName = DEFAULT_PROCESS_NAME;
        private String mAffinity;
        private int mUid = DEFAULT_FAKE_UID;
        private boolean mCreateTask = false;
        private Task mParentTask;
        private int mActivityFlags;
        private int mActivityTheme;
        private int mLaunchMode;
        private int mResizeMode = RESIZE_MODE_RESIZEABLE;
        private float mMaxAspectRatio;
        private float mMinAspectRatio;
        private boolean mSupportsSizeChanges;
        private int mScreenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        private boolean mLaunchTaskBehind = false;
        private int mConfigChanges;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;
        private String mLaunchedFromPackage;
        private WindowProcessController mWpc;
        private Bundle mIntentExtras;
        private boolean mOnTop = false;
        private ActivityInfo.WindowLayout mWindowLayout;
        private boolean mVisible = true;
        private String mRequiredDisplayCategory;
        private ActivityOptions mActivityOpts;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTargetActivity(String targetActivity) {
            mTargetActivity = targetActivity;
            return this;
        }

        ActivityBuilder setIntentExtras(Bundle extras) {
            mIntentExtras = extras;
            return this;
        }

        static ComponentName getDefaultComponent() {
            return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                    DEFAULT_COMPONENT_PACKAGE_NAME);
        }

        ActivityBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        ActivityBuilder setActivityTheme(int theme) {
            mActivityTheme = theme;
            // Use the real package of test so it can get a valid context for theme.
            mComponent = ComponentName.createRelative(mService.mContext.getPackageName(),
                    DEFAULT_COMPONENT_CLASS_NAME + sCurrentActivityId++);
            return this;
        }

        ActivityBuilder setActivityFlags(int flags) {
            mActivityFlags = flags;
            return this;
        }

        ActivityBuilder setLaunchMode(int launchMode) {
            mLaunchMode = launchMode;
            return this;
        }

        ActivityBuilder setParentTask(Task parentTask) {
            mParentTask = parentTask;
            return this;
        }

        ActivityBuilder setCreateTask(boolean createTask) {
            mCreateTask = createTask;
            return this;
        }

        ActivityBuilder setProcessName(String name) {
            mProcessName = name;
            return this;
        }

        ActivityBuilder setUid(int uid) {
            mUid = uid;
            return this;
        }

        ActivityBuilder setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
            return this;
        }

        ActivityBuilder setMaxAspectRatio(float maxAspectRatio) {
            mMaxAspectRatio = maxAspectRatio;
            return this;
        }

        ActivityBuilder setMinAspectRatio(float minAspectRatio) {
            mMinAspectRatio = minAspectRatio;
            return this;
        }

        ActivityBuilder setSupportsSizeChanges(boolean supportsSizeChanges) {
            mSupportsSizeChanges = supportsSizeChanges;
            return this;
        }

        ActivityBuilder setScreenOrientation(int screenOrientation) {
            mScreenOrientation = screenOrientation;
            return this;
        }

        ActivityBuilder setLaunchTaskBehind(boolean launchTaskBehind) {
            mLaunchTaskBehind = launchTaskBehind;
            return this;
        }

        ActivityBuilder setConfigChanges(int configChanges) {
            mConfigChanges = configChanges;
            return this;
        }

        ActivityBuilder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        ActivityBuilder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
            return this;
        }

        ActivityBuilder setLaunchedFromPackage(String packageName) {
            mLaunchedFromPackage = packageName;
            return this;
        }

        ActivityBuilder setUseProcess(WindowProcessController wpc) {
            mWpc = wpc;
            return this;
        }

        ActivityBuilder setAffinity(String affinity) {
            mAffinity = affinity;
            return this;
        }

        ActivityBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        ActivityBuilder setWindowLayout(ActivityInfo.WindowLayout windowLayout) {
            mWindowLayout = windowLayout;
            return this;
        }

        ActivityBuilder setVisible(boolean visible) {
            mVisible = visible;
            return this;
        }

        ActivityBuilder setActivityOptions(ActivityOptions opts) {
            mActivityOpts = opts;
            return this;
        }

        ActivityBuilder setRequiredDisplayCategory(String requiredDisplayCategory) {
            mRequiredDisplayCategory = requiredDisplayCategory;
            return this;
        }

        ActivityRecord build() {
            SystemServicesTestRule.checkHoldsLock(mService.mGlobalLock);
            try {
                mService.deferWindowLayout();
                return buildInner();
            } finally {
                mService.continueWindowLayout();
            }
        }

        ActivityRecord buildInner() {
            if (mComponent == null) {
                mComponent = getUniqueComponentName();
            }

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            if (mIntentExtras != null) {
                intent.putExtras(mIntentExtras);
            }
            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
            aInfo.applicationInfo.packageName = mComponent.getPackageName();
            aInfo.applicationInfo.uid = mUid;
            if (DEFAULT_PROCESS_NAME.equals(mProcessName)) {
                mProcessName += ++sProcNameSeq;
            }
            aInfo.processName = mProcessName;
            aInfo.packageName = mComponent.getPackageName();
            aInfo.name = mComponent.getClassName();
            if (mTargetActivity != null) {
                aInfo.targetActivity = mTargetActivity;
            }
            if (mActivityTheme != 0) {
                aInfo.theme = mActivityTheme;
            }
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;
            aInfo.resizeMode = mResizeMode;
            aInfo.setMaxAspectRatio(mMaxAspectRatio);
            aInfo.setMinAspectRatio(mMinAspectRatio);
            aInfo.supportsSizeChanges = mSupportsSizeChanges;
            aInfo.screenOrientation = mScreenOrientation;
            aInfo.configChanges |= mConfigChanges;
            aInfo.taskAffinity = mAffinity;
            aInfo.windowLayout = mWindowLayout;
            if (mRequiredDisplayCategory != null) {
                aInfo.requiredDisplayCategory = mRequiredDisplayCategory;
            }

            if (mCreateTask) {
                mTask = new TaskBuilder(mService.mTaskSupervisor)
                        .setComponent(mComponent)
                        // Apply the root activity info and intent
                        .setActivityInfo(aInfo)
                        .setIntent(intent)
                        .setParentTask(mParentTask).build();
            } else if (mTask == null && mParentTask != null && DisplayContent.alwaysCreateRootTask(
                    mParentTask.getWindowingMode(), mParentTask.getActivityType())) {
                // The parent task can be the task root.
                mTask = mParentTask;
            }

            ActivityOptions options = null;
            if (mActivityOpts != null) {
                options = mActivityOpts;
            } else if (mLaunchTaskBehind) {
                options = ActivityOptions.makeTaskLaunchBehind();
            }
            final ActivityRecord activity = new ActivityRecord.Builder(mService)
                    .setLaunchedFromPid(mLaunchedFromPid)
                    .setLaunchedFromUid(mLaunchedFromUid)
                    .setLaunchedFromPackage(mLaunchedFromPackage)
                    .setIntent(intent)
                    .setActivityInfo(aInfo)
                    .setActivityOptions(options)
                    .build();

            spyOn(activity);
            if (mTask != null) {
                mTask.addChild(activity);
                if (mOnTop) {
                    // Move the task to front after activity is added.
                    // Or {@link TaskDisplayArea#mPreferredTopFocusableRootTask} could be other
                    // root tasks (e.g. home root task).
                    mTask.moveToFront("createActivity");
                }
                if (mVisible) {
                    activity.setVisibleRequested(true);
                    activity.setVisible(true);
                }
            }

            final WindowProcessController wpc;
            if (mWpc != null) {
                wpc = mWpc;
            } else {
                final WindowProcessController p = mService.getProcessController(mProcessName, mUid);
                wpc = p != null ? p : SystemServicesTestRule.addProcess(
                        mService, aInfo.applicationInfo, mProcessName, 0 /* pid */);
            }
            activity.setProcess(wpc);

            // Resume top activities to make sure all other signals in the system are connected.
            mService.mRootWindowContainer.resumeFocusedTasksTopActivities();
            return activity;
        }
    }

    static class TaskFragmentBuilder {
        private final ActivityTaskManagerService mAtm;
        private Task mParentTask;
        private boolean mCreateParentTask;
        private int mCreateActivityCount = 0;
        @Nullable
        private TaskFragmentOrganizer mOrganizer;
        private IBinder mFragmentToken;
        private Rect mBounds;

        TaskFragmentBuilder(ActivityTaskManagerService service) {
            mAtm = service;
        }

        TaskFragmentBuilder setCreateParentTask() {
            mCreateParentTask = true;
            return this;
        }

        TaskFragmentBuilder setParentTask(Task task) {
            mParentTask = task;
            return this;
        }

        TaskFragmentBuilder createActivityCount(int count) {
            mCreateActivityCount = count;
            return this;
        }

        TaskFragmentBuilder setOrganizer(@Nullable TaskFragmentOrganizer organizer) {
            mOrganizer = organizer;
            return this;
        }

        TaskFragmentBuilder setFragmentToken(@Nullable IBinder fragmentToken) {
            mFragmentToken = fragmentToken;
            return this;
        }

        TaskFragmentBuilder setBounds(@Nullable Rect bounds) {
            mBounds = bounds;
            return this;
        }

        TaskFragment build() {
            SystemServicesTestRule.checkHoldsLock(mAtm.mGlobalLock);

            final TaskFragment taskFragment = new TaskFragment(mAtm, mFragmentToken,
                    mOrganizer != null);
            if (mParentTask == null && mCreateParentTask) {
                mParentTask = new TaskBuilder(mAtm.mTaskSupervisor).build();
            }
            if (mParentTask != null) {
                mParentTask.addChild(taskFragment, POSITION_TOP);
            }
            while (mCreateActivityCount > 0) {
                final ActivityRecord activity = new ActivityBuilder(mAtm).build();
                taskFragment.addChild(activity);
                mCreateActivityCount--;
            }
            if (mOrganizer != null) {
                taskFragment.setTaskFragmentOrganizer(
                        mOrganizer.getOrganizerToken(), DEFAULT_TASK_FRAGMENT_ORGANIZER_UID,
                        DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME);
            }
            if (mBounds != null && !mBounds.isEmpty()) {
                taskFragment.setBounds(mBounds);
            }
            spyOn(taskFragment);
            return taskFragment;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        private final ActivityTaskSupervisor mSupervisor;

        private TaskDisplayArea mTaskDisplayArea;
        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        private int mTaskId = -1;
        private int mUserId = 0;
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private ActivityInfo mActivityInfo;
        private Intent mIntent;
        private boolean mOnTop = true;
        private IVoiceInteractionSession mVoiceSession;

        private boolean mCreateParentTask = false;
        private Task mParentTask;

        private boolean mCreateActivity = false;
        private boolean mCreatedByOrganizer = false;

        TaskBuilder(ActivityTaskSupervisor supervisor) {
            mSupervisor = supervisor;
            mTaskDisplayArea = mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
        }

        /**
         * Set the parent {@link DisplayContent} and use the default task display area. Overrides
         * the task display area, if was set before.
         */
        TaskBuilder setDisplay(DisplayContent display) {
            mTaskDisplayArea = display.getDefaultTaskDisplayArea();
            return this;
        }

        /** Set the parent {@link TaskDisplayArea}. Overrides the display, if was set before. */
        TaskBuilder setTaskDisplayArea(TaskDisplayArea taskDisplayArea) {
            mTaskDisplayArea = taskDisplayArea;
            return this;
        }

        TaskBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        TaskBuilder setPackage(String packageName) {
            mPackage = packageName;
            return this;
        }

        TaskBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        TaskBuilder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        TaskBuilder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        TaskBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        TaskBuilder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        TaskBuilder setActivityInfo(ActivityInfo info) {
            mActivityInfo = info;
            return this;
        }

        TaskBuilder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        TaskBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        TaskBuilder setVoiceSession(IVoiceInteractionSession session) {
            mVoiceSession = session;
            return this;
        }

        TaskBuilder setCreateParentTask(boolean createParentTask) {
            mCreateParentTask = createParentTask;
            return this;
        }

        TaskBuilder setParentTask(Task parentTask) {
            mParentTask = parentTask;
            return this;
        }

        TaskBuilder setCreateActivity(boolean createActivity) {
            mCreateActivity = createActivity;
            return this;
        }

        TaskBuilder setCreatedByOrganizer(boolean createdByOrganizer) {
            mCreatedByOrganizer = createdByOrganizer;
            return this;
        }

        Task build() {
            SystemServicesTestRule.checkHoldsLock(mSupervisor.mService.mGlobalLock);

            // Create parent task.
            if (mParentTask == null && mCreateParentTask) {
                mParentTask = mTaskDisplayArea.createRootTask(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
            }
            if (mParentTask != null && !Mockito.mockingDetails(mParentTask).isSpy()) {
                spyOn(mParentTask);
            }

            // Create task.
            if (mActivityInfo == null) {
                mActivityInfo = new ActivityInfo();
                mActivityInfo.applicationInfo = new ApplicationInfo();
                mActivityInfo.applicationInfo.packageName = mPackage;
            }

            if (mIntent == null) {
                mIntent = new Intent();
                if (mComponent == null) {
                    mComponent = getUniqueComponentName();
                }
                mIntent.setComponent(mComponent);
                mIntent.setFlags(mFlags);
            }

            final Task.Builder builder = new Task.Builder(mSupervisor.mService)
                    .setTaskId(mTaskId >= 0 ? mTaskId : mTaskDisplayArea.getNextRootTaskId())
                    .setWindowingMode(mWindowingMode)
                    .setActivityInfo(mActivityInfo)
                    .setIntent(mIntent)
                    .setOnTop(mOnTop)
                    .setVoiceSession(mVoiceSession)
                    .setCreatedByOrganizer(mCreatedByOrganizer);
            final Task task;
            if (mParentTask == null) {
                task = builder.setActivityType(mActivityType)
                        .setParent(mTaskDisplayArea)
                        .build();
            } else {
                task = builder.setParent(mParentTask).build();
                mParentTask.moveToFront("build-task");
            }
            spyOn(task);
            task.mUserId = mUserId;
            final Task rootTask = task.getRootTask();
            if (task != rootTask && !Mockito.mockingDetails(rootTask).isSpy()) {
                spyOn(rootTask);
            }
            doNothing().when(rootTask).startActivityLocked(
                    any(), any(), anyBoolean(), anyBoolean(), any(), any());

            // Create child activity.
            if (mCreateActivity) {
                new ActivityBuilder(mSupervisor.mService)
                        .setTask(task)
                        .setComponent(mComponent)
                        .build();
                if (mOnTop) {
                    // We move the task to front again in order to regain focus after activity
                    // is added. Or {@link TaskDisplayArea#mPreferredTopFocusableRootTask} could be
                    // other root tasks (e.g. home root task).
                    task.moveToFront("createActivityTask");
                } else {
                    task.moveToBack("createActivityTask", null);
                }
            }

            return task;
        }
    }

    static class TestStartingWindowOrganizer extends WindowOrganizerTests.StubOrganizer {
        private final ActivityTaskManagerService mAtm;
        private final WindowManagerService mWMService;
        private final WindowState.PowerManagerWrapper mPowerManagerWrapper;

        private Runnable mRunnableWhenAddingSplashScreen;
        private final SparseArray<IBinder> mTaskAppMap = new SparseArray<>();
        private final HashMap<IBinder, WindowState> mAppWindowMap = new HashMap<>();

        TestStartingWindowOrganizer(ActivityTaskManagerService service,
                WindowState.PowerManagerWrapper powerManagerWrapper) {
            mAtm = service;
            mWMService = mAtm.mWindowManager;
            mPowerManagerWrapper = powerManagerWrapper;
            mAtm.mTaskOrganizerController.setDeferTaskOrgCallbacksConsumer(Runnable::run);
            mAtm.mTaskOrganizerController.registerTaskOrganizer(this);
        }

        void setRunnableWhenAddingSplashScreen(Runnable r) {
            mRunnableWhenAddingSplashScreen = r;
        }

        @Override
        public void addStartingWindow(StartingWindowInfo info) {
            synchronized (mWMService.mGlobalLock) {
                final ActivityRecord activity = mWMService.mRoot.getActivityRecord(
                        info.appToken);
                IWindow iWindow = mock(IWindow.class);
                doReturn(mock(IBinder.class)).when(iWindow).asBinder();
                final WindowState window = WindowTestsBase.createWindow(null,
                        TYPE_APPLICATION_STARTING, activity,
                        "Starting window", 0 /* ownerId */, 0 /* userId*/,
                        false /* internalWindows */, mWMService, createTestSession(mAtm), iWindow,
                        mPowerManagerWrapper);
                activity.mStartingWindow = window;
                mAppWindowMap.put(info.appToken, window);
                mTaskAppMap.put(info.taskInfo.taskId, info.appToken);
            }
            if (mRunnableWhenAddingSplashScreen != null) {
                mRunnableWhenAddingSplashScreen.run();
                mRunnableWhenAddingSplashScreen = null;
            }
        }
        @Override
        public void removeStartingWindow(StartingWindowRemovalInfo removalInfo) {
            synchronized (mWMService.mGlobalLock) {
                final IBinder appToken = mTaskAppMap.get(removalInfo.taskId);
                if (appToken != null) {
                    mTaskAppMap.remove(removalInfo.taskId);
                    final ActivityRecord activity = mWMService.mRoot.getActivityRecord(
                            appToken);
                    WindowState win = mAppWindowMap.remove(appToken);
                    activity.removeChild(win);
                    activity.mStartingWindow = null;
                }
            }
        }
    }

    static class TestSplitOrganizer extends WindowOrganizerTests.StubOrganizer {
        final ActivityTaskManagerService mService;
        final TaskDisplayArea mDefaultTDA;
        Task mPrimary;
        Task mSecondary;
        int mDisplayId;

        TestSplitOrganizer(ActivityTaskManagerService service, DisplayContent display) {
            mService = service;
            mDefaultTDA = display.getDefaultTaskDisplayArea();
            mDisplayId = display.mDisplayId;
            mService.mTaskOrganizerController.registerTaskOrganizer(this);
            mPrimary = mService.mTaskOrganizerController.createRootTask(
                    display, WINDOWING_MODE_MULTI_WINDOW, null);
            mSecondary = mService.mTaskOrganizerController.createRootTask(
                    display, WINDOWING_MODE_MULTI_WINDOW, null);

            mPrimary.setAdjacentTaskFragment(mSecondary);
            display.getDefaultTaskDisplayArea().setLaunchAdjacentFlagRootTask(mSecondary);

            final Rect primaryBounds = new Rect();
            final Rect secondaryBounds = new Rect();
            if (display.getConfiguration().orientation == ORIENTATION_LANDSCAPE) {
                display.getBounds().splitVertically(primaryBounds, secondaryBounds);
            } else {
                display.getBounds().splitHorizontally(primaryBounds, secondaryBounds);
            }
            mPrimary.setBounds(primaryBounds);
            mSecondary.setBounds(secondaryBounds);

            spyOn(mPrimary);
            spyOn(mSecondary);
        }

        TestSplitOrganizer(ActivityTaskManagerService service) {
            this(service, service.mTaskSupervisor.mRootWindowContainer.getDefaultDisplay());
        }

        public Task createTaskToPrimary(boolean onTop) {
            final Task primaryTask = mDefaultTDA.createRootTask(
                    WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, onTop);
            putTaskToPrimary(primaryTask, onTop);
            return primaryTask;
        }

        public Task createTaskToSecondary(boolean onTop) {
            final Task secondaryTask = mDefaultTDA.createRootTask(
                    WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, onTop);
            putTaskToSecondary(secondaryTask, onTop);
            return secondaryTask;
        }

        public void putTaskToPrimary(Task task, boolean onTop) {
            task.reparent(mPrimary, onTop ? POSITION_TOP : POSITION_BOTTOM);
        }

        public void putTaskToSecondary(Task task, boolean onTop) {
            task.reparent(mSecondary, onTop ? POSITION_TOP : POSITION_BOTTOM);
        }
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc) {
        return createTestWindowToken(type, dc, false /* persistOnEmpty */);
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc,
            boolean persistOnEmpty) {
        SystemServicesTestRule.checkHoldsLock(dc.mWmService.mGlobalLock);

        return new TestWindowToken(type, dc, persistOnEmpty);
    }

    /** Used so we can gain access to some protected members of the {@link WindowToken} class */
    static class TestWindowToken extends WindowToken {

        private TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(dc.mWmService, mock(IBinder.class), type, persistOnEmpty, dc,
                    false /* ownerCanManageAppTokens */);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }

    /** Used to track resize reports. */
    static class TestWindowState extends WindowState {
        boolean mResizeReported;

        TestWindowState(WindowManagerService service, Session session, IWindow window,
                WindowManager.LayoutParams attrs, WindowToken token) {
            super(service, session, window, token, null, OP_NONE, attrs, 0, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            mResizeReported = true;
        }

        @Override
        public boolean isGoneForLayout() {
            return false;
        }

        @Override
        void updateResizingWindowIfNeeded() {
            // Used in AppWindowTokenTests#testLandscapeSeascapeRotationRelayout to deceive
            // the system that it can actually update the window.
            boolean hadSurface = mHasSurface;
            mHasSurface = true;

            super.updateResizingWindowIfNeeded();

            mHasSurface = hadSurface;
        }
    }

    static class TestTransitionController extends TransitionController {
        TestTransitionController(ActivityTaskManagerService atms) {
            super(atms);
            doReturn(this).when(atms).getTransitionController();
            mSnapshotController = mock(SnapshotController.class);
            mTransitionTracer = mock(TransitionTracer.class);
        }
    }

    static class TestTransitionPlayer extends ITransitionPlayer.Stub {
        final TransitionController mController;
        final WindowOrganizerController mOrganizer;
        Transition mLastTransit = null;
        TransitionRequestInfo mLastRequest = null;
        TransitionInfo mLastReady = null;

        TestTransitionPlayer(@NonNull TransitionController controller,
                @NonNull WindowOrganizerController organizer) {
            mController = controller;
            mOrganizer = organizer;
        }

        void clear() {
            mLastTransit = null;
            mLastReady = null;
            mLastRequest = null;
        }

        @Override
        public void onTransitionReady(IBinder transitToken, TransitionInfo transitionInfo,
                SurfaceControl.Transaction transaction, SurfaceControl.Transaction finishT)
                throws RemoteException {
            mLastTransit = Transition.fromBinder(transitToken);
            mLastReady = transitionInfo;
        }

        @Override
        public void requestStartTransition(IBinder transitToken,
                TransitionRequestInfo request) throws RemoteException {
            mLastTransit = Transition.fromBinder(transitToken);
            mLastRequest = request;
        }

        void startTransition() {
            mOrganizer.startTransition(mLastTransit.getToken(), null);
        }

        void onTransactionReady(SurfaceControl.Transaction t) {
            mLastTransit.onTransactionReady(mLastTransit.getSyncId(), t);
        }

        void start() {
            startTransition();
            onTransactionReady(mock(SurfaceControl.Transaction.class));
        }

        public void finish() {
            mController.finishTransition(mLastTransit);
        }
    }
}
