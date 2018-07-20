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
 * limitations under the License
 */

package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.power.V1_0.PowerHint;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.server.EventLogTags;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_NONE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.REPORT_LOSING_FOCUS;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_NONE;
import static com.android.server.wm.WindowManagerService.H.WINDOW_FREEZE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.logSurface;
import static com.android.server.wm.WindowSurfacePlacer.SET_FORCE_HIDING_CHANGED;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowSurfacePlacer.SET_TURN_ON_SCREEN;
import static com.android.server.wm.WindowSurfacePlacer.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowSurfacePlacer.SET_WALLPAPER_ACTION_PENDING;
import static com.android.server.wm.WindowSurfacePlacer.SET_WALLPAPER_MAY_CHANGE;

/** Root {@link WindowContainer} for the device. */
class RootWindowContainer extends WindowContainer<DisplayContent> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RootWindowContainer" : TAG_WM;

    private static final int SET_SCREEN_BRIGHTNESS_OVERRIDE = 1;
    private static final int SET_USER_ACTIVITY_TIMEOUT = 2;

    WindowManagerService mService;

    private boolean mWallpaperForceHidingChanged = false;
    private Object mLastWindowFreezeSource = null;
    private Session mHoldScreen = null;
    private float mScreenBrightness = -1;
    private long mUserActivityTimeout = -1;
    private boolean mUpdateRotation = false;
    // Following variables are for debugging screen wakelock only.
    // Last window that requires screen wakelock
    WindowState mHoldScreenWindow = null;
    // Last window that obscures all windows below
    WindowState mObscuringWindow = null;
    // Only set while traversing the default display based on its content.
    // Affects the behavior of mirroring on secondary displays.
    private boolean mObscureApplicationContentOnSecondaryDisplays = false;

    private boolean mSustainedPerformanceModeEnabled = false;
    private boolean mSustainedPerformanceModeCurrent = false;

    boolean mWallpaperMayChange = false;
    // During an orientation change, we track whether all windows have rendered
    // at the new orientation, and this will be false from changing orientation until that occurs.
    // For seamless rotation cases this always stays true, as the windows complete their orientation
    // changes 1 by 1 without disturbing global state.
    boolean mOrientationChangeComplete = true;
    boolean mWallpaperActionPending = false;

    private final ArrayList<Integer> mChangedStackList = new ArrayList();

    // State for the RemoteSurfaceTrace system used in testing. If this is enabled SurfaceControl
    // instances will be replaced with an instance that writes a binary representation of all
    // commands to mSurfaceTraceFd.
    boolean mSurfaceTraceEnabled;
    ParcelFileDescriptor mSurfaceTraceFd;
    RemoteEventTrace mRemoteEventTrace;

    private final WindowLayersController mLayersController;
    final WallpaperController mWallpaperController;

    private final Handler mHandler;

    private String mCloseSystemDialogsReason;
    private final Consumer<WindowState> mCloseSystemDialogsConsumer = w -> {
        if (w.mHasSurface) {
            try {
                w.mClient.closeSystemDialogs(mCloseSystemDialogsReason);
            } catch (RemoteException e) {
            }
        }
    };

    private static final Consumer<WindowState> sRemoveReplacedWindowsConsumer = w -> {
        final AppWindowToken aToken = w.mAppToken;
        if (aToken != null) {
            aToken.removeReplacedWindowIfNeeded(w);
        }
    };

    RootWindowContainer(WindowManagerService service) {
        mService = service;
        mHandler = new MyHandler(service.mH.getLooper());
        mLayersController = new WindowLayersController(mService);
        mWallpaperController = new WallpaperController(mService);
    }

    WindowState computeFocusedWindow() {
        // While the keyguard is showing, we must focus anything besides the main display.
        // Otherwise we risk input not going to the keyguard when the user expects it to.
        final boolean forceDefaultDisplay = mService.mPolicy.isKeyguardShowingAndNotOccluded();

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final DisplayContent dc = mChildren.get(i);
            final WindowState win = dc.findFocusedWindow();
            if (win != null) {
                if (forceDefaultDisplay && !dc.isDefaultDisplay) {
                    EventLog.writeEvent(0x534e4554, "71786287", win.mOwnerUid, "");
                    continue;
                }
                return win;
            }
        }
        return null;
    }

    /**
     * Get an array with display ids ordered by focus priority - last items should be given
     * focus first. Sparse array just maps position to displayId.
     */
    void getDisplaysInFocusOrder(SparseIntArray displaysInFocusOrder) {
        displaysInFocusOrder.clear();

        final int size = mChildren.size();
        for (int i = 0; i < size; ++i) {
            final DisplayContent displayContent = mChildren.get(i);
            if (displayContent.isRemovalDeferred()) {
                // Don't report displays that are going to be removed soon.
                continue;
            }
            displaysInFocusOrder.put(i, displayContent.getDisplayId());
        }
    }

    /**
     * Retrieve the DisplayContent for the specified displayId. Will create a new DisplayContent if
     * there is a Display for the displayId.
     *
     * @param displayId The display the caller is interested in.
     * @return The DisplayContent associated with displayId or null if there is no Display for it.
     */
    DisplayContent getDisplayContentOrCreate(int displayId) {
        DisplayContent dc = getDisplayContent(displayId);

        if (dc == null) {
            final Display display = mService.mDisplayManager.getDisplay(displayId);
            if (display != null) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    dc = createDisplayContent(display);
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            }
        }
        return dc;
    }

    DisplayContent getDisplayContent(int displayId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent current = mChildren.get(i);
            if (current.getDisplayId() == displayId) {
                return current;
            }
        }
        return null;
    }

    private DisplayContent createDisplayContent(final Display display) {
        final DisplayContent dc = new DisplayContent(display, mService, mLayersController,
                mWallpaperController);
        final int displayId = display.getDisplayId();

        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Adding display=" + display);

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Rect rect = new Rect();
        mService.mDisplaySettings.getOverscanLocked(displayInfo.name, displayInfo.uniqueId, rect);
        displayInfo.overscanLeft = rect.left;
        displayInfo.overscanTop = rect.top;
        displayInfo.overscanRight = rect.right;
        displayInfo.overscanBottom = rect.bottom;
        if (mService.mDisplayManagerInternal != null) {
            mService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(
                    displayId, displayInfo);
            mService.configureDisplayPolicyLocked(dc);

            // Tap Listeners are supported for:
            // 1. All physical displays (multi-display).
            // 2. VirtualDisplays that support virtual touch input. (Only VR for now)
            // TODO(multi-display): Support VirtualDisplays with no virtual touch input.
            if ((display.getType() != Display.TYPE_VIRTUAL
                    || (display.getType() == Display.TYPE_VIRTUAL
                        // Only VR VirtualDisplays
                        && displayId == mService.mVr2dDisplayId))
                    && mService.canDispatchPointerEvents()) {
                if (DEBUG_DISPLAY) {
                    Slog.d(TAG,
                            "Registering PointerEventListener for DisplayId: " + displayId);
                }
                dc.mTapDetector = new TaskTapPointerEventListener(mService, dc);
                mService.registerPointerEventListener(dc.mTapDetector);
                if (displayId == DEFAULT_DISPLAY) {
                    mService.registerPointerEventListener(mService.mMousePositionTracker);
                }
            }
        }

        return dc;
    }

    boolean isLayoutNeeded() {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                return true;
            }
        }
        return false;
    }

    void getWindowsByName(ArrayList<WindowState> output, String name) {
        int objectId = 0;
        // See if this is an object ID.
        try {
            objectId = Integer.parseInt(name, 16);
            name = null;
        } catch (RuntimeException e) {
        }

        getWindowsByName(output, name, objectId);
    }

    private void getWindowsByName(ArrayList<WindowState> output, String name, int objectId) {
        forAllWindows((w) -> {
            if (name != null) {
                if (w.mAttrs.getTitle().toString().contains(name)) {
                    output.add(w);
                }
            } else if (System.identityHashCode(w) == objectId) {
                output.add(w);
            }
        }, true /* traverseTopToBottom */);
    }

    /**
     * Returns the app window token for the input binder if it exist in the system.
     * NOTE: Only one AppWindowToken is allowed to exist in the system for a binder token, since
     * AppWindowToken represents an activity which can only exist on one display.
     */
    AppWindowToken getAppWindowToken(IBinder binder) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final AppWindowToken atoken = dc.getAppWindowToken(binder);
            if (atoken != null) {
                return atoken;
            }
        }
        return null;
    }

    /** Returns the display object the input window token is currently mapped on. */
    DisplayContent getWindowTokenDisplay(WindowToken token) {
        if (token == null) {
            return null;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final WindowToken current = dc.getWindowToken(token.token);
            if (current == token) {
                return dc;
            }
        }

        return null;
    }

    /**
     * Set new display override config and return array of ids of stacks that were changed during
     * update. If called for the default display, global configuration will also be updated.
     */
    int[] setDisplayOverrideConfigurationIfNeeded(Configuration newConfiguration, int displayId) {
        final DisplayContent displayContent = getDisplayContent(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("Display not found for id: " + displayId);
        }

        final Configuration currentConfig = displayContent.getOverrideConfiguration();
        final boolean configChanged = currentConfig.diff(newConfiguration) != 0;
        if (!configChanged) {
            return null;
        }
        displayContent.onOverrideConfigurationChanged(newConfiguration);

        if (displayId == DEFAULT_DISPLAY) {
            // Override configuration of the default display duplicates global config. In this case
            // we also want to update the global config.
            return setGlobalConfigurationIfNeeded(newConfiguration);
        } else {
            return updateStackBoundsAfterConfigChange(displayId);
        }
    }

    private int[] setGlobalConfigurationIfNeeded(Configuration newConfiguration) {
        final boolean configChanged = getConfiguration().diff(newConfiguration) != 0;
        if (!configChanged) {
            return null;
        }
        onConfigurationChanged(newConfiguration);
        return updateStackBoundsAfterConfigChange();
    }

    @Override
    void onConfigurationChanged(Configuration newParentConfig) {
        prepareFreezingTaskBounds();
        super.onConfigurationChanged(newParentConfig);

        mService.mPolicy.onConfigurationChanged();
    }

    /**
     * Callback used to trigger bounds update after configuration change and get ids of stacks whose
     * bounds were updated.
     */
    private int[] updateStackBoundsAfterConfigChange() {
        mChangedStackList.clear();

        final int numDisplays = mChildren.size();
        for (int i = 0; i < numDisplays; ++i) {
            final DisplayContent dc = mChildren.get(i);
            dc.updateStackBoundsAfterConfigChange(mChangedStackList);
        }

        return mChangedStackList.isEmpty() ? null : ArrayUtils.convertToIntArray(mChangedStackList);
    }

    /** Same as {@link #updateStackBoundsAfterConfigChange()} but only for a specific display. */
    private int[] updateStackBoundsAfterConfigChange(int displayId) {
        mChangedStackList.clear();

        final DisplayContent dc = getDisplayContent(displayId);
        dc.updateStackBoundsAfterConfigChange(mChangedStackList);

        return mChangedStackList.isEmpty() ? null : ArrayUtils.convertToIntArray(mChangedStackList);
    }

    private void prepareFreezingTaskBounds() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            mChildren.get(i).prepareFreezingTaskBounds();
        }
    }

    TaskStack getStackById(int stackId) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final DisplayContent dc = mChildren.get(i);
            final TaskStack stack = dc.getStackById(stackId);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    void setSecureSurfaceState(int userId, boolean disabled) {
        forAllWindows((w) -> {
            if (w.mHasSurface && userId == UserHandle.getUserId(w.mOwnerUid)) {
                w.mWinAnimator.setSecureLocked(disabled);
            }
        }, true /* traverseTopToBottom */);
    }

    void updateAppOpsState() {
        forAllWindows((w) -> {
            if (w.mAppOp == OP_NONE) {
                return;
            }
            final int mode = mService.mAppOps.checkOpNoThrow(w.mAppOp, w.getOwningUid(),
                    w.getOwningPackage());
            w.setAppOpVisibilityLw(mode == MODE_ALLOWED || mode == MODE_DEFAULT);
        }, false /* traverseTopToBottom */);
    }

    boolean canShowStrictModeViolation(int pid) {
        final WindowState win = getWindow((w) -> w.mSession.mPid == pid && w.isVisibleLw());
        return win != null;
    }

    void closeSystemDialogs(String reason) {
        mCloseSystemDialogsReason = reason;
        forAllWindows(mCloseSystemDialogsConsumer, false /* traverseTopToBottom */);
    }

    void removeReplacedWindows() {
        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION removeReplacedWindows");
        mService.openSurfaceTransaction();
        try {
            forAllWindows(sRemoveReplacedWindowsConsumer, true /* traverseTopToBottom */);
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION removeReplacedWindows");
        }
    }

    boolean hasPendingLayoutChanges(WindowAnimator animator) {
        boolean hasChanges = false;

        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final DisplayContent dc = mChildren.get(i);
            final int pendingChanges = animator.getPendingLayoutChanges(dc.getDisplayId());
            if ((pendingChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                animator.mBulkUpdateParams |= SET_WALLPAPER_ACTION_PENDING;
            }
            if (pendingChanges != 0) {
                hasChanges = true;
            }
        }

        return hasChanges;
    }

    boolean reclaimSomeSurfaceMemory(WindowStateAnimator winAnimator, String operation,
            boolean secure) {
        final WindowSurfaceController surfaceController = winAnimator.mSurfaceController;
        boolean leakedSurface = false;
        boolean killedApps = false;

        EventLog.writeEvent(EventLogTags.WM_NO_SURFACE_MEMORY, winAnimator.mWin.toString(),
                winAnimator.mSession.mPid, operation);

        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...first, do a sanity check of the window list to make sure
            // we haven't left any dangling surfaces around.

            Slog.i(TAG_WM, "Out of memory for surface!  Looking for leaks...");
            final int numDisplays = mChildren.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                leakedSurface |= mChildren.get(displayNdx).destroyLeakedSurfaces();
            }

            if (!leakedSurface) {
                Slog.w(TAG_WM, "No leaked surfaces; killing applications!");
                final SparseIntArray pidCandidates = new SparseIntArray();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    mChildren.get(displayNdx).forAllWindows((w) -> {
                        if (mService.mForceRemoves.contains(w)) {
                            return;
                        }
                        final WindowStateAnimator wsa = w.mWinAnimator;
                        if (wsa.mSurfaceController != null) {
                            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
                        }
                    }, false /* traverseTopToBottom */);

                    if (pidCandidates.size() > 0) {
                        int[] pids = new int[pidCandidates.size()];
                        for (int i = 0; i < pids.length; i++) {
                            pids[i] = pidCandidates.keyAt(i);
                        }
                        try {
                            if (mService.mActivityManager.killPids(pids, "Free memory", secure)) {
                                killedApps = true;
                            }
                        } catch (RemoteException e) {
                        }
                    }
                }
            }

            if (leakedSurface || killedApps) {
                // We managed to reclaim some memory, so get rid of the trouble surface and ask the
                // app to request another one.
                Slog.w(TAG_WM,
                        "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surfaceController != null) {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) logSurface(winAnimator.mWin,
                            "RECOVER DESTROY", false);
                    winAnimator.destroySurface();
                    if (winAnimator.mWin.mAppToken != null
                            && winAnimator.mWin.mAppToken.getController() != null) {
                        winAnimator.mWin.mAppToken.getController().removeStartingWindow();
                    }
                }

                try {
                    winAnimator.mWin.mClient.dispatchGetNewSurface();
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }

        return leakedSurface || killedApps;
    }

    // "Something has changed!  Let's make it correct now."
    // TODO: Super crazy long method that should be broken down...
    void performSurfacePlacement(boolean recoveringMemory) {
        if (DEBUG_WINDOW_TRACE) Slog.v(TAG, "performSurfacePlacementInner: entry. Called by "
                + Debug.getCallers(3));

        int i;
        boolean updateInputWindowsNeeded = false;

        if (mService.mFocusMayChange) {
            mService.mFocusMayChange = false;
            updateInputWindowsNeeded = mService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        }

        // Initialize state of exiting tokens.
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            displayContent.setExitingTokensHasVisible(false);
        }

        mHoldScreen = null;
        mScreenBrightness = -1;
        mUserActivityTimeout = -1;
        mObscureApplicationContentOnSecondaryDisplays = false;
        mSustainedPerformanceModeCurrent = false;
        mService.mTransactionSequence++;

        // TODO(multi-display):
        final DisplayContent defaultDisplay = mService.getDefaultDisplayContentLocked();
        final DisplayInfo defaultInfo = defaultDisplay.getDisplayInfo();
        final int defaultDw = defaultInfo.logicalWidth;
        final int defaultDh = defaultInfo.logicalHeight;

        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");
        mService.openSurfaceTransaction();
        try {
            applySurfaceChangesTransaction(recoveringMemory, defaultDw, defaultDh);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
        }

        final WindowSurfacePlacer surfacePlacer = mService.mWindowPlacerLocked;

        // If we are ready to perform an app transition, check through all of the app tokens to be
        // shown and see if they are ready to go.
        if (mService.mAppTransition.isReady()) {
            defaultDisplay.pendingLayoutChanges |=
                    surfacePlacer.handleAppTransitionReadyLocked();
            if (DEBUG_LAYOUT_REPEATS)
                surfacePlacer.debugLayoutRepeats("after handleAppTransitionReadyLocked",
                        defaultDisplay.pendingLayoutChanges);
        }

        if (!mService.mAnimator.mAppWindowAnimating && mService.mAppTransition.isRunning()) {
            // We have finished the animation of an app transition. To do this, we have delayed a
            // lot of operations like showing and hiding apps, moving apps in Z-order, etc. The app
            // token list reflects the correct Z-order, but the window list may now be out of sync
            // with it. So here we will just rebuild the entire app window list. Fun!
            defaultDisplay.pendingLayoutChanges |=
                    mService.handleAnimatingStoppedAndTransitionLocked();
            if (DEBUG_LAYOUT_REPEATS)
                surfacePlacer.debugLayoutRepeats("after handleAnimStopAndXitionLock",
                        defaultDisplay.pendingLayoutChanges);
        }

        if (mWallpaperForceHidingChanged && defaultDisplay.pendingLayoutChanges == 0
                && !mService.mAppTransition.isReady()) {
            // At this point, there was a window with a wallpaper that was force hiding other
            // windows behind it, but now it is going away. This may be simple -- just animate away
            // the wallpaper and its window -- or it may be hard -- the wallpaper now needs to be
            // shown behind something that was hidden.
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                    "after animateAwayWallpaperLocked", defaultDisplay.pendingLayoutChanges);
        }
        mWallpaperForceHidingChanged = false;

        if (mWallpaperMayChange) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "Wallpaper may change!  Adjusting");
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats("WallpaperMayChange",
                    defaultDisplay.pendingLayoutChanges);
        }

        if (mService.mFocusMayChange) {
            mService.mFocusMayChange = false;
            if (mService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                    false /*updateInputWindows*/)) {
                updateInputWindowsNeeded = true;
                defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
            }
        }

        if (isLayoutNeeded()) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats("mLayoutNeeded",
                    defaultDisplay.pendingLayoutChanges);
        }

        final ArraySet<DisplayContent> touchExcludeRegionUpdateDisplays = handleResizingWindows();

        if (DEBUG_ORIENTATION && mService.mDisplayFrozen) Slog.v(TAG,
                "With display frozen, orientationChangeComplete=" + mOrientationChangeComplete);
        if (mOrientationChangeComplete) {
            if (mService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                mService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;
                mService.mLastFinishedFreezeSource = mLastWindowFreezeSource;
                mService.mH.removeMessages(WINDOW_FREEZE_TIMEOUT);
            }
            mService.stopFreezingDisplayLocked();
        }

        // Destroy the surface of any windows that are no longer visible.
        boolean wallpaperDestroyed = false;
        i = mService.mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mService.mDestroySurface.get(i);
                win.mDestroying = false;
                if (mService.mInputMethodWindow == win) {
                    mService.setInputMethodWindowLocked(null);
                }
                if (win.getDisplayContent().mWallpaperController.isWallpaperTarget(win)) {
                    wallpaperDestroyed = true;
                }
                win.destroyOrSaveSurfaceUnchecked();
            } while (i > 0);
            mService.mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            displayContent.removeExistingTokensIfPossible();
        }

        if (wallpaperDestroyed) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            defaultDisplay.setLayoutNeeded();
        }

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.setLayoutNeeded();
            }
        }

        // Finally update all input windows now that the window changes have stabilized.
        mService.mInputMonitor.updateInputWindowsLw(true /*force*/);

        mService.setHoldScreenLocked(mHoldScreen);
        if (!mService.mDisplayFrozen) {
            final int brightness = mScreenBrightness < 0 || mScreenBrightness > 1.0f
                    ? -1 : toBrightnessOverride(mScreenBrightness);

            // Post these on a handler such that we don't call into power manager service while
            // holding the window manager lock to avoid lock contention with power manager lock.
            mHandler.obtainMessage(SET_SCREEN_BRIGHTNESS_OVERRIDE, brightness, 0).sendToTarget();
            mHandler.obtainMessage(SET_USER_ACTIVITY_TIMEOUT, mUserActivityTimeout).sendToTarget();
        }

        if (mSustainedPerformanceModeCurrent != mSustainedPerformanceModeEnabled) {
            mSustainedPerformanceModeEnabled = mSustainedPerformanceModeCurrent;
            mService.mPowerManagerInternal.powerHint(
                    PowerHint.SUSTAINED_PERFORMANCE,
                    (mSustainedPerformanceModeEnabled ? 1 : 0));
        }

        if (mService.mTurnOnScreen) {
            if (mService.mAllowTheaterModeWakeFromLayout
                    || Settings.Global.getInt(mService.mContext.getContentResolver(),
                    Settings.Global.THEATER_MODE_ON, 0) == 0) {
                if (DEBUG_VISIBILITY || DEBUG_POWER) {
                    Slog.v(TAG, "Turning screen on after layout!");
                }
                mService.mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        "android.server.wm:TURN_ON");
            }
            mService.mTurnOnScreen = false;
        }

        if (mUpdateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
            // TODO(multi-display): Update rotation for different displays separately.
            final int displayId = defaultDisplay.getDisplayId();
            if (defaultDisplay.updateRotationUnchecked(false /* inTransaction */)) {
                mService.mH.obtainMessage(SEND_NEW_CONFIGURATION, displayId).sendToTarget();
            } else {
                mUpdateRotation = false;
            }
            // Update rotation of VR virtual display separately. Currently this is the only kind of
            // secondary display that can be rotated because of the single-display limitations in
            // PhoneWindowManager.
            final DisplayContent vrDisplay = mService.mVr2dDisplayId != INVALID_DISPLAY
                    ? getDisplayContent(mService.mVr2dDisplayId) : null;
            if (vrDisplay != null && vrDisplay.updateRotationUnchecked(false /* inTransaction */)) {
                mService.mH.obtainMessage(SEND_NEW_CONFIGURATION, mService.mVr2dDisplayId)
                        .sendToTarget();
            }
        }

        if (mService.mWaitingForDrawnCallback != null ||
                (mOrientationChangeComplete && !defaultDisplay.isLayoutNeeded()
                        && !mUpdateRotation)) {
            mService.checkDrawnWindowsLocked();
        }

        final int N = mService.mPendingRemove.size();
        if (N > 0) {
            if (mService.mPendingRemoveTmp.length < N) {
                mService.mPendingRemoveTmp = new WindowState[N+10];
            }
            mService.mPendingRemove.toArray(mService.mPendingRemoveTmp);
            mService.mPendingRemove.clear();
            ArrayList<DisplayContent> displayList = new ArrayList();
            for (i = 0; i < N; i++) {
                final WindowState w = mService.mPendingRemoveTmp[i];
                w.removeImmediately();
                final DisplayContent displayContent = w.getDisplayContent();
                if (displayContent != null && !displayList.contains(displayContent)) {
                    displayList.add(displayContent);
                }
            }

            for (int j = displayList.size() - 1; j >= 0; --j) {
                final DisplayContent dc = displayList.get(j);
                dc.assignWindowLayers(true /*setLayoutNeeded*/);
            }
        }

        // Remove all deferred displays stacks, tasks, and activities.
        for (int displayNdx = mChildren.size() - 1; displayNdx >= 0; --displayNdx) {
            mChildren.get(displayNdx).checkCompleteDeferredRemoval();
        }

        if (updateInputWindowsNeeded) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
        mService.setFocusTaskRegionLocked(null);
        if (touchExcludeRegionUpdateDisplays != null) {
            final DisplayContent focusedDc = mService.mFocusedApp != null
                    ? mService.mFocusedApp.getDisplayContent() : null;
            for (DisplayContent dc : touchExcludeRegionUpdateDisplays) {
                // The focused DisplayContent was recalcuated in setFocusTaskRegionLocked
                if (focusedDc != dc) {
                    dc.setTouchExcludeRegion(null /* focusedTask */);
                }
            }
        }

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        mService.enableScreenIfNeededLocked();

        mService.scheduleAnimationLocked();
        mService.mWindowPlacerLocked.destroyPendingSurfaces();

        if (DEBUG_WINDOW_TRACE) Slog.e(TAG,
                "performSurfacePlacementInner exit: animating=" + mService.mAnimator.isAnimating());
    }

    private void applySurfaceChangesTransaction(boolean recoveringMemory, int defaultDw,
            int defaultDh) {
        mHoldScreenWindow = null;
        mObscuringWindow = null;

        // TODO(multi-display): Support these features on secondary screens.
        if (mService.mWatermark != null) {
            mService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mStrictModeFlash != null) {
            mService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mCircularDisplayMask != null) {
            mService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh,
                    mService.getDefaultDisplayRotation());
        }
        if (mService.mEmulatorDisplayOverlay != null) {
            mService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh,
                    mService.getDefaultDisplayRotation());
        }

        boolean focusDisplayed = false;

        final int count = mChildren.size();
        for (int j = 0; j < count; ++j) {
            final DisplayContent dc = mChildren.get(j);
            focusDisplayed |= dc.applySurfaceChangesTransaction(recoveringMemory);
        }

        if (focusDisplayed) {
            mService.mH.sendEmptyMessage(REPORT_LOSING_FOCUS);
        }

        // Give the display manager a chance to adjust properties like display rotation if it needs
        // to.
        mService.mDisplayManagerInternal.performTraversalInTransactionFromWindowManager();
    }

    /**
     * Handles resizing windows during surface placement.
     *
     * @return A set of any DisplayContent whose touch exclude region needs to be recalculated due
     *         to a tap-exclude window resizing, or null if no such DisplayContents were found.
     */
    private ArraySet<DisplayContent> handleResizingWindows() {
        ArraySet<DisplayContent> touchExcludeRegionUpdateSet = null;
        for (int i = mService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = mService.mResizingWindows.get(i);
            if (win.mAppFreezing) {
                // Don't remove this window until rotation has completed.
                continue;
            }
            // Discard the saved surface if window size is changed, it can't be reused.
            if (win.mAppToken != null) {
                win.mAppToken.destroySavedSurfaces();
            }
            win.reportResized();
            mService.mResizingWindows.remove(i);
            if (WindowManagerService.excludeWindowTypeFromTapOutTask(win.mAttrs.type)) {
                final DisplayContent dc = win.getDisplayContent();
                if (touchExcludeRegionUpdateSet == null) {
                    touchExcludeRegionUpdateSet = new ArraySet<>();
                }
                touchExcludeRegionUpdateSet.add(dc);
            }
        }
        return touchExcludeRegionUpdateSet;
    }

    /**
     * @param w WindowState this method is applied to.
     * @param obscured True if there is a window on top of this obscuring the display.
     * @param syswin System window?
     * @return True when the display contains content to show the user. When false, the display
     *          manager may choose to mirror or blank the display.
     */
    boolean handleNotObscuredLocked(WindowState w, boolean obscured, boolean syswin) {
        final WindowManager.LayoutParams attrs = w.mAttrs;
        final int attrFlags = attrs.flags;
        final boolean canBeSeen = w.isDisplayedLw();
        final int privateflags = attrs.privateFlags;
        boolean displayHasContent = false;

        if (w.mHasSurface && canBeSeen) {
            if ((attrFlags & FLAG_KEEP_SCREEN_ON) != 0) {
                mHoldScreen = w.mSession;
                mHoldScreenWindow = w;
            } else if (DEBUG_KEEP_SCREEN_ON && w == mService.mLastWakeLockHoldingWindow) {
                Slog.d(TAG_KEEP_SCREEN_ON, "handleNotObscuredLocked: " + w + " was holding "
                        + "screen wakelock but no longer has FLAG_KEEP_SCREEN_ON!!! called by"
                        + Debug.getCallers(10));
            }
            if (!syswin && w.mAttrs.screenBrightness >= 0 && mScreenBrightness < 0) {
                mScreenBrightness = w.mAttrs.screenBrightness;
            }
            if (!syswin && w.mAttrs.userActivityTimeout >= 0 && mUserActivityTimeout < 0) {
                mUserActivityTimeout = w.mAttrs.userActivityTimeout;
            }

            final int type = attrs.type;
            // This function assumes that the contents of the default display are processed first
            // before secondary displays.
            final DisplayContent displayContent = w.getDisplayContent();
            if (displayContent != null && displayContent.isDefaultDisplay) {
                // While a dream or keyguard is showing, obscure ordinary application content on
                // secondary displays (by forcibly enabling mirroring unless there is other content
                // we want to show) but still allow opaque keyguard dialogs to be shown.
                if (type == TYPE_DREAM || (attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                    mObscureApplicationContentOnSecondaryDisplays = true;
                }
                displayHasContent = true;
            } else if (displayContent != null &&
                    (!mObscureApplicationContentOnSecondaryDisplays
                            || (obscured && type == TYPE_KEYGUARD_DIALOG))) {
                // Allow full screen keyguard presentation dialogs to be seen.
                displayHasContent = true;
            }
            if ((privateflags & PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE) != 0) {
                mSustainedPerformanceModeCurrent = true;
            }
        }

        return displayHasContent;
    }

    boolean copyAnimToLayoutParams() {
        boolean doRequest = false;

        final int bulkUpdateParams = mService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & SET_UPDATE_ROTATION) != 0) {
            mUpdateRotation = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_WALLPAPER_MAY_CHANGE) != 0) {
            mWallpaperMayChange = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_FORCE_HIDING_CHANGED) != 0) {
            mWallpaperForceHidingChanged = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_ORIENTATION_CHANGE_COMPLETE) == 0) {
            mOrientationChangeComplete = false;
        } else {
            mOrientationChangeComplete = true;
            mLastWindowFreezeSource = mService.mAnimator.mLastWindowFreezeSource;
            if (mService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                doRequest = true;
            }
        }
        if ((bulkUpdateParams & SET_TURN_ON_SCREEN) != 0) {
            mService.mTurnOnScreen = true;
        }
        if ((bulkUpdateParams & SET_WALLPAPER_ACTION_PENDING) != 0) {
            mWallpaperActionPending = true;
        }

        return doRequest;
    }

    private static int toBrightnessOverride(float value) {
        return (int)(value * PowerManager.BRIGHTNESS_ON);
    }

    private final class MyHandler extends Handler {

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_SCREEN_BRIGHTNESS_OVERRIDE:
                    mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(
                            msg.arg1);
                    break;
                case SET_USER_ACTIVITY_TIMEOUT:
                    mService.mPowerManagerInternal.setUserActivityTimeoutOverrideFromWindowManager(
                            (Long) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    void enableSurfaceTrace(ParcelFileDescriptor pfd) {
        final FileDescriptor fd = pfd.getFileDescriptor();
        if (mSurfaceTraceEnabled) {
            disableSurfaceTrace();
        }
        mSurfaceTraceEnabled = true;
        mRemoteEventTrace = new RemoteEventTrace(mService, fd);
        mSurfaceTraceFd = pfd;
        for (int displayNdx = mChildren.size() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent dc = mChildren.get(displayNdx);
            dc.enableSurfaceTrace(fd);
        }
    }

    void disableSurfaceTrace() {
        mSurfaceTraceEnabled = false;
        mRemoteEventTrace = null;
        mSurfaceTraceFd = null;
        for (int displayNdx = mChildren.size() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent dc = mChildren.get(displayNdx);
            dc.disableSurfaceTrace();
        }
    }

    void dumpDisplayContents(PrintWriter pw) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (mService.mDisplayReady) {
            final int count = mChildren.size();
            for (int i = 0; i < count; ++i) {
                final DisplayContent displayContent = mChildren.get(i);
                displayContent.dump("  ", pw);
            }
        } else {
            pw.println("  NO DISPLAY");
        }
    }

    void dumpLayoutNeededDisplayIds(PrintWriter pw) {
        if (!isLayoutNeeded()) {
            return;
        }
        pw.print("  mLayoutNeeded on displays=");
        final int count = mChildren.size();
        for (int displayNdx = 0; displayNdx < count; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                pw.print(displayContent.getDisplayId());
            }
        }
        pw.println();
    }

    void dumpWindowsNoHeader(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        final int[] index = new int[1];
        forAllWindows((w) -> {
            if (windows == null || windows.contains(w)) {
                pw.println("  Window #" + index[0] + " " + w + ":");
                w.dump(pw, "    ", dumpAll || windows != null);
                index[0] = index[0] + 1;
            }
        }, true /* traverseTopToBottom */);
    }

    void dumpTokens(PrintWriter pw, boolean dumpAll) {
        pw.println("  All tokens:");
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).dumpTokens(pw, dumpAll);
        }
    }

    @Override
    String getName() {
        return "ROOT";
    }
}
