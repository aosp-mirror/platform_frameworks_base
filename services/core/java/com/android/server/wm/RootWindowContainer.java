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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.RootWindowContainerProto.DISPLAYS;
import static com.android.server.wm.RootWindowContainerProto.WINDOWS;
import static com.android.server.wm.RootWindowContainerProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WINDOW_FREEZE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_NONE;
import static com.android.server.wm.WindowManagerService.logSurface;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowSurfacePlacer.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowSurfacePlacer.SET_WALLPAPER_ACTION_PENDING;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.hardware.power.V1_0.PowerHint;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

/** Root {@link WindowContainer} for the device. */
class RootWindowContainer extends WindowContainer<DisplayContent>
        implements ConfigurationContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RootWindowContainer" : TAG_WM;

    private static final int SET_SCREEN_BRIGHTNESS_OVERRIDE = 1;
    private static final int SET_USER_ACTIVITY_TIMEOUT = 2;

    // TODO: Remove after object merge with RootActivityContainer.
    private RootActivityContainer mRootActivityContainer;

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

    // During an orientation change, we track whether all windows have rendered
    // at the new orientation, and this will be false from changing orientation until that occurs.
    // For seamless rotation cases this always stays true, as the windows complete their orientation
    // changes 1 by 1 without disturbing global state.
    boolean mOrientationChangeComplete = true;
    boolean mWallpaperActionPending = false;

    private final Handler mHandler;

    private String mCloseSystemDialogsReason;

    // The ID of the display which is responsible for receiving display-unspecified key and pointer
    // events.
    private int mTopFocusedDisplayId = INVALID_DISPLAY;

    // Map from the PID to the top most app which has a focused window of the process.
    final HashMap<Integer, AppWindowToken> mTopFocusedAppByProcess = new HashMap<>();

    // Only a separate transaction until we separate the apply surface changes
    // transaction from the global transaction.
    private final SurfaceControl.Transaction mDisplayTransaction;

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
        super(service);
        mDisplayTransaction = service.mTransactionFactory.get();
        mHandler = new MyHandler(service.mH.getLooper());
    }

    void setRootActivityContainer(RootActivityContainer container) {
        mRootActivityContainer = container;
        if (container != null) {
            container.registerConfigurationChangeListener(this);
        }
    }

    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        mTopFocusedAppByProcess.clear();
        boolean changed = false;
        int topFocusedDisplayId = INVALID_DISPLAY;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            changed |= dc.updateFocusedWindowLocked(mode, updateInputWindows, topFocusedDisplayId);
            final WindowState newFocus = dc.mCurrentFocus;
            if (newFocus != null) {
                final int pidOfNewFocus = newFocus.mSession.mPid;
                if (mTopFocusedAppByProcess.get(pidOfNewFocus) == null) {
                    mTopFocusedAppByProcess.put(pidOfNewFocus, newFocus.mAppToken);
                }
                if (topFocusedDisplayId == INVALID_DISPLAY) {
                    topFocusedDisplayId = dc.getDisplayId();
                }
            } else if (topFocusedDisplayId == INVALID_DISPLAY && dc.mFocusedApp != null) {
                // The top-most display that has a focused app should still be the top focused
                // display even when the app window is not ready yet (process not attached or
                // window not added yet).
                topFocusedDisplayId = dc.getDisplayId();
            }
        }
        if (topFocusedDisplayId == INVALID_DISPLAY) {
            topFocusedDisplayId = DEFAULT_DISPLAY;
        }
        if (mTopFocusedDisplayId != topFocusedDisplayId) {
            mTopFocusedDisplayId = topFocusedDisplayId;
            mWmService.mInputManager.setFocusedDisplay(topFocusedDisplayId);
            mWmService.mPolicy.setTopFocusedDisplay(topFocusedDisplayId);
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "New topFocusedDisplayId="
                    + topFocusedDisplayId);
        }
        return changed;
    }

    DisplayContent getTopFocusedDisplayContent() {
        final DisplayContent dc = getDisplayContent(mTopFocusedDisplayId);
        return dc != null ? dc : getDisplayContent(DEFAULT_DISPLAY);
    }

    @Override
    void onChildPositionChanged() {
        mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                !mWmService.mPerDisplayFocusEnabled /* updateInputWindows */);
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

    DisplayContent createDisplayContent(final Display display, ActivityDisplay activityDisplay) {
        final int displayId = display.getDisplayId();

        // In select scenarios, it is possible that a DisplayContent will be created on demand
        // rather than waiting for the controller. In this case, associate the controller and return
        // the existing display.
        final DisplayContent existing = getDisplayContent(displayId);

        if (existing != null) {
            existing.mAcitvityDisplay = activityDisplay;
            existing.initializeDisplayOverrideConfiguration();
            return existing;
        }

        final DisplayContent dc = new DisplayContent(display, mWmService, activityDisplay);

        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Adding display=" + display);

        mWmService.mDisplayWindowSettings.applySettingsToDisplayLocked(dc);
        dc.initializeDisplayOverrideConfiguration();

        if (mWmService.mDisplayManagerInternal != null) {
            mWmService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(
                    displayId, dc.getDisplayInfo());
            dc.configureDisplayPolicy();
        }

        return dc;
    }

    /**
     * Called when DisplayWindowSettings values may change.
     */
    void onSettingsRetrieved() {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            final boolean changed = mWmService.mDisplayWindowSettings.updateSettingsForDisplay(
                    displayContent);
            if (!changed) {
                continue;
            }

            displayContent.initializeDisplayOverrideConfiguration();
            displayContent.reconfigureDisplayLocked();

            // We need to update global configuration as well if config of default display has
            // changed. Do it inline because ATMS#retrieveSettings() will soon update the
            // configuration inline, which will overwrite the new windowing mode.
            if (displayContent.isDefaultDisplay) {
                final Configuration newConfig = mWmService.computeNewConfiguration(
                        displayContent.getDisplayId());
                mWmService.mAtmService.updateConfigurationLocked(newConfig, null /* starting */,
                        false /* initLocale */);
            }
        }
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
     * Returns true if the callingUid has any non-toast window currently visible to the user.
     * Also ignores TYPE_APPLICATION_STARTING, since those windows don't belong to apps.
     */
    boolean isAnyNonToastWindowVisibleForUid(int callingUid) {
        return forAllWindows(w ->
                        w.getOwningUid() == callingUid && w.mAttrs.type != TYPE_TOAST
                        && w.mAttrs.type != TYPE_APPLICATION_STARTING && w.isVisibleNow(),
                true /* traverseTopToBottom */);
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

    /** Returns the window token for the input binder if it exist in the system. */
    WindowToken getWindowToken(IBinder binder) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final WindowToken wtoken = dc.getWindowToken(binder);
            if (wtoken != null) {
                return wtoken;
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
     * Set new display override config. If called for the default display, global configuration
     * will also be updated.
     */
    void setDisplayOverrideConfigurationIfNeeded(Configuration newConfiguration,
            @NonNull DisplayContent displayContent) {

        final Configuration currentConfig = displayContent.getRequestedOverrideConfiguration();
        final boolean configChanged = currentConfig.diff(newConfiguration) != 0;
        if (!configChanged) {
            return;
        }

        displayContent.onRequestedOverrideConfigurationChanged(newConfiguration);

        if (displayContent.getDisplayId() == DEFAULT_DISPLAY) {
            // Override configuration of the default display duplicates global config. In this case
            // we also want to update the global config.
            setGlobalConfigurationIfNeeded(newConfiguration);
        }
    }

    private void setGlobalConfigurationIfNeeded(Configuration newConfiguration) {
        final boolean configChanged = getConfiguration().diff(newConfiguration) != 0;
        if (!configChanged) {
            return;
        }
        onConfigurationChanged(newConfiguration);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        prepareFreezingTaskBounds();
        super.onConfigurationChanged(newParentConfig);
    }

    private void prepareFreezingTaskBounds() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            mChildren.get(i).prepareFreezingTaskBounds();
        }
    }

    TaskStack getStack(int windowingMode, int activityType) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final DisplayContent dc = mChildren.get(i);
            final TaskStack stack = dc.getStack(windowingMode, activityType);
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

    void updateHiddenWhileSuspendedState(final ArraySet<String> packages, final boolean suspended) {
        forAllWindows((w) -> {
            if (packages.contains(w.getOwningPackage())) {
                w.setHiddenWhileSuspended(suspended);
            }
        }, false);
    }

    void updateAppOpsState() {
        forAllWindows((w) -> {
            w.updateAppOpsState();
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
        mWmService.openSurfaceTransaction();
        try {
            forAllWindows(sRemoveReplacedWindowsConsumer, true /* traverseTopToBottom */);
        } finally {
            mWmService.closeSurfaceTransaction("removeReplacedWindows");
            if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION removeReplacedWindows");
        }
    }

    boolean hasPendingLayoutChanges(WindowAnimator animator) {
        boolean hasChanges = false;

        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final int pendingChanges = mChildren.get(i).pendingLayoutChanges;
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
                        if (mWmService.mForceRemoves.contains(w)) {
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
                            if (mWmService.mActivityManager.killPids(pids, "Free memory", secure)) {
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
                    if (winAnimator.mWin.mAppToken != null) {
                        winAnimator.mWin.mAppToken.removeStartingWindow();
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

    void performSurfacePlacement(boolean recoveringMemory) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "performSurfacePlacement");
        try {
            performSurfacePlacementNoTrace(recoveringMemory);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    // "Something has changed!  Let's make it correct now."
    // TODO: Super crazy long method that should be broken down...
    void performSurfacePlacementNoTrace(boolean recoveringMemory) {
        if (DEBUG_WINDOW_TRACE) Slog.v(TAG, "performSurfacePlacementInner: entry. Called by "
                + Debug.getCallers(3));

        int i;

        if (mWmService.mFocusMayChange) {
            mWmService.mFocusMayChange = false;
            mWmService.updateFocusedWindowLocked(
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
        mWmService.mTransactionSequence++;

        // TODO(multi-display): recents animation & wallpaper need support multi-display.
        final DisplayContent defaultDisplay = mWmService.getDefaultDisplayContentLocked();
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;

        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "applySurfaceChanges");
        mWmService.openSurfaceTransaction();
        try {
            applySurfaceChangesTransaction(recoveringMemory);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            mWmService.closeSurfaceTransaction("performLayoutAndPlaceSurfaces");
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
        }
        mWmService.mAnimator.executeAfterPrepareSurfacesRunnables();

        checkAppTransitionReady(surfacePlacer);

        // Defer starting the recents animation until the wallpaper has drawn
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();
        if (recentsAnimationController != null) {
            recentsAnimationController.checkAnimationReady(defaultDisplay.mWallpaperController);
        }

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.mWallpaperMayChange) {
                if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "Wallpaper may change!  Adjusting");
                displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("WallpaperMayChange",
                            displayContent.pendingLayoutChanges);
                }
            }
        }

        if (mWmService.mFocusMayChange) {
            mWmService.mFocusMayChange = false;
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                    false /*updateInputWindows*/);
        }

        if (isLayoutNeeded()) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats("mLayoutNeeded",
                    defaultDisplay.pendingLayoutChanges);
        }

        handleResizingWindows();

        if (DEBUG_ORIENTATION && mWmService.mDisplayFrozen) Slog.v(TAG,
                "With display frozen, orientationChangeComplete=" + mOrientationChangeComplete);
        if (mOrientationChangeComplete) {
            if (mWmService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                mWmService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;
                mWmService.mLastFinishedFreezeSource = mLastWindowFreezeSource;
                mWmService.mH.removeMessages(WINDOW_FREEZE_TIMEOUT);
            }
            mWmService.stopFreezingDisplayLocked();
        }

        // Destroy the surface of any windows that are no longer visible.
        i = mWmService.mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mWmService.mDestroySurface.get(i);
                win.mDestroying = false;
                final DisplayContent displayContent = win.getDisplayContent();
                if (displayContent.mInputMethodWindow == win) {
                    displayContent.setInputMethodWindowLocked(null);
                }
                if (displayContent.mWallpaperController.isWallpaperTarget(win)) {
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                }
                win.destroySurfaceUnchecked();
                win.mWinAnimator.destroyPreservedSurfaceLocked();
            } while (i > 0);
            mWmService.mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            displayContent.removeExistingTokensIfPossible();
        }

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.setLayoutNeeded();
            }
        }

        mWmService.setHoldScreenLocked(mHoldScreen);
        if (!mWmService.mDisplayFrozen) {
            final int brightness = mScreenBrightness < 0 || mScreenBrightness > 1.0f
                    ? -1 : toBrightnessOverride(mScreenBrightness);

            // Post these on a handler such that we don't call into power manager service while
            // holding the window manager lock to avoid lock contention with power manager lock.
            mHandler.obtainMessage(SET_SCREEN_BRIGHTNESS_OVERRIDE, brightness, 0).sendToTarget();
            mHandler.obtainMessage(SET_USER_ACTIVITY_TIMEOUT, mUserActivityTimeout).sendToTarget();
        }

        if (mSustainedPerformanceModeCurrent != mSustainedPerformanceModeEnabled) {
            mSustainedPerformanceModeEnabled = mSustainedPerformanceModeCurrent;
            mWmService.mPowerManagerInternal.powerHint(
                    PowerHint.SUSTAINED_PERFORMANCE,
                    (mSustainedPerformanceModeEnabled ? 1 : 0));
        }

        if (mUpdateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
            mUpdateRotation = updateRotationUnchecked();
        }

        if (mWmService.mWaitingForDrawnCallback != null
                || (mOrientationChangeComplete && !isLayoutNeeded()
                && !mUpdateRotation)) {
            mWmService.checkDrawnWindowsLocked();
        }

        final int N = mWmService.mPendingRemove.size();
        if (N > 0) {
            if (mWmService.mPendingRemoveTmp.length < N) {
                mWmService.mPendingRemoveTmp = new WindowState[N + 10];
            }
            mWmService.mPendingRemove.toArray(mWmService.mPendingRemoveTmp);
            mWmService.mPendingRemove.clear();
            ArrayList<DisplayContent> displayList = new ArrayList();
            for (i = 0; i < N; i++) {
                final WindowState w = mWmService.mPendingRemoveTmp[i];
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

        forAllDisplays(dc -> {
            dc.getInputMonitor().updateInputWindowsLw(true /*force*/);
            dc.updateSystemGestureExclusion();
            dc.updateTouchExcludeRegion();
        });

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        mWmService.enableScreenIfNeededLocked();

        mWmService.scheduleAnimationLocked();

        if (DEBUG_WINDOW_TRACE) Slog.e(TAG,
                "performSurfacePlacementInner exit: animating="
                        + mWmService.mAnimator.isAnimating());
    }

    private void checkAppTransitionReady(WindowSurfacePlacer surfacePlacer) {
        // Trace all displays app transition by Z-order for pending layout change.
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent curDisplay = mChildren.get(i);

            // If we are ready to perform an app transition, check through all of the app tokens
            // to be shown and see if they are ready to go.
            if (curDisplay.mAppTransition.isReady()) {
                // handleAppTransitionReady may modify curDisplay.pendingLayoutChanges.
                curDisplay.mAppTransitionController.handleAppTransitionReady();
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAppTransitionReady",
                            curDisplay.pendingLayoutChanges);
                }
            }

            if (curDisplay.mAppTransition.isRunning() && !curDisplay.isAppAnimating()) {
                // We have finished the animation of an app transition. To do this, we have
                // delayed a lot of operations like showing and hiding apps, moving apps in
                // Z-order, etc.
                // The app token list reflects the correct Z-order, but the window list may now
                // be out of sync with it. So here we will just rebuild the entire app window
                // list. Fun!
                curDisplay.handleAnimatingStoppedAndTransition();
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAnimStopAndXitionLock",
                            curDisplay.pendingLayoutChanges);
                }
            }
        }
    }

    private void applySurfaceChangesTransaction(boolean recoveringMemory) {
        mHoldScreenWindow = null;
        mObscuringWindow = null;

        // TODO(multi-display): Support these features on secondary screens.
        final DisplayContent defaultDc = mWmService.getDefaultDisplayContentLocked();
        final DisplayInfo defaultInfo = defaultDc.getDisplayInfo();
        final int defaultDw = defaultInfo.logicalWidth;
        final int defaultDh = defaultInfo.logicalHeight;
        if (mWmService.mWatermark != null) {
            mWmService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (mWmService.mStrictModeFlash != null) {
            mWmService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (mWmService.mCircularDisplayMask != null) {
            mWmService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh,
                    mWmService.getDefaultDisplayRotation());
        }
        if (mWmService.mEmulatorDisplayOverlay != null) {
            mWmService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh,
                    mWmService.getDefaultDisplayRotation());
        }

        final int count = mChildren.size();
        for (int j = 0; j < count; ++j) {
            final DisplayContent dc = mChildren.get(j);
            dc.applySurfaceChangesTransaction(recoveringMemory);
        }

        // Give the display manager a chance to adjust properties like display rotation if it needs
        // to.
        mWmService.mDisplayManagerInternal.performTraversal(mDisplayTransaction);
        SurfaceControl.mergeToGlobalTransaction(mDisplayTransaction);
    }

    /**
     * Handles resizing windows during surface placement.
     */
    private void handleResizingWindows() {
        for (int i = mWmService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = mWmService.mResizingWindows.get(i);
            if (win.mAppFreezing || win.getDisplayContent().mWaitingForConfig) {
                // Don't remove this window until rotation has completed and is not waiting for the
                // complete configuration.
                continue;
            }
            win.reportResized();
            mWmService.mResizingWindows.remove(i);
        }
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
        final boolean onScreen = w.isOnScreen();
        final boolean canBeSeen = w.isDisplayedLw();
        final int privateflags = attrs.privateFlags;
        boolean displayHasContent = false;

        if (DEBUG_KEEP_SCREEN_ON) {
            Slog.d(TAG_KEEP_SCREEN_ON, "handleNotObscuredLocked w: " + w
                + ", w.mHasSurface: " + w.mHasSurface
                + ", w.isOnScreen(): " + onScreen
                + ", w.isDisplayedLw(): " + w.isDisplayedLw()
                + ", w.mAttrs.userActivityTimeout: " + w.mAttrs.userActivityTimeout);
        }
        if (w.mHasSurface && onScreen) {
            if (!syswin && w.mAttrs.userActivityTimeout >= 0 && mUserActivityTimeout < 0) {
                mUserActivityTimeout = w.mAttrs.userActivityTimeout;
                if (DEBUG_KEEP_SCREEN_ON) {
                    Slog.d(TAG, "mUserActivityTimeout set to " + mUserActivityTimeout);
                }
            }
        }
        if (w.mHasSurface && canBeSeen) {
            if ((attrFlags & FLAG_KEEP_SCREEN_ON) != 0) {
                mHoldScreen = w.mSession;
                mHoldScreenWindow = w;
            } else if (DEBUG_KEEP_SCREEN_ON && w == mWmService.mLastWakeLockHoldingWindow) {
                Slog.d(TAG_KEEP_SCREEN_ON, "handleNotObscuredLocked: " + w + " was holding "
                        + "screen wakelock but no longer has FLAG_KEEP_SCREEN_ON!!! called by"
                        + Debug.getCallers(10));
            }
            if (!syswin && w.mAttrs.screenBrightness >= 0 && mScreenBrightness < 0) {
                mScreenBrightness = w.mAttrs.screenBrightness;
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

    boolean updateRotationUnchecked() {
        boolean changed = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).getDisplayRotation().updateRotationAndSendNewConfigIfChanged()) {
                changed = true;
            }
        }
        return changed;
    }

    boolean copyAnimToLayoutParams() {
        boolean doRequest = false;

        final int bulkUpdateParams = mWmService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & SET_UPDATE_ROTATION) != 0) {
            mUpdateRotation = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_ORIENTATION_CHANGE_COMPLETE) == 0) {
            mOrientationChangeComplete = false;
        } else {
            mOrientationChangeComplete = true;
            mLastWindowFreezeSource = mWmService.mAnimator.mLastWindowFreezeSource;
            if (mWmService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                doRequest = true;
            }
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
                    mWmService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(
                            msg.arg1);
                    break;
                case SET_USER_ACTIVITY_TIMEOUT:
                    mWmService.mPowerManagerInternal.
                            setUserActivityTimeoutOverrideFromWindowManager((Long) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    void dumpDisplayContents(PrintWriter pw) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (mWmService.mDisplayReady) {
            final int count = mChildren.size();
            for (int i = 0; i < count; ++i) {
                final DisplayContent displayContent = mChildren.get(i);
                displayContent.dump(pw, "  ", true /* dumpAll */);
            }
        } else {
            pw.println("  NO DISPLAY");
        }
    }

    void dumpTopFocusedDisplayId(PrintWriter pw) {
        pw.print("  mTopFocusedDisplayId="); pw.println(mTopFocusedDisplayId);
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

    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, logLevel);
        if (mWmService.mDisplayReady) {
            final int count = mChildren.size();
            for (int i = 0; i < count; ++i) {
                final DisplayContent displayContent = mChildren.get(i);
                displayContent.writeToProto(proto, DISPLAYS, logLevel);
            }
        }
        if (logLevel == WindowTraceLogLevel.ALL) {
            forAllWindows((w) -> {
                w.writeIdentifierToProto(proto, WINDOWS);
            }, true);
        }
        proto.end(token);
    }

    @Override
    String getName() {
        return "ROOT";
    }

    @Override
    void positionChildAt(int position, DisplayContent child, boolean includingParents) {
        super.positionChildAt(position, child, includingParents);
        if (mRootActivityContainer != null) {
            mRootActivityContainer.onChildPositionChanged(child.mAcitvityDisplay, position);
        }
    }

    void positionChildAt(int position, DisplayContent child) {
        // Only called from controller so no need to notify the change to controller.
        super.positionChildAt(position, child, false /* includingParents */);
    }

    @Override
    void scheduleAnimation() {
        mWmService.scheduleAnimationLocked();
    }

    @Override
    protected void removeChild(DisplayContent dc) {
        super.removeChild(dc);
        if (mTopFocusedDisplayId == dc.getDisplayId()) {
            mWmService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_NORMAL, true /* updateInputWindows */);
        }
    }

    /**
     * For all display at or below this call the callback.
     *
     * @param callback Callback to be called for every display.
     */
    void forAllDisplays(Consumer<DisplayContent> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            callback.accept(mChildren.get(i));
        }
    }

    void forAllDisplayPolicies(Consumer<DisplayPolicy> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            callback.accept(mChildren.get(i).getDisplayPolicy());
        }
    }

    /**
     * Get current topmost focused IME window in system.
     * Will look on all displays in current Z-order.
     */
    WindowState getCurrentInputMethodWindow() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent displayContent = mChildren.get(i);
            if (displayContent.mInputMethodWindow != null) {
                return displayContent.mInputMethodWindow;
            }
        }
        return null;
    }
}
