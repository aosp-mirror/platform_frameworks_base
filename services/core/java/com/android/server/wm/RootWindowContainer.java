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

import android.app.AppOpsManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputChannel;
import android.view.WindowManager;
import com.android.internal.util.ArrayUtils;
import com.android.server.EventLogTags;
import com.android.server.input.InputWindowHandle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_STARTING_WINDOW_DRAWN;
import static com.android.server.wm.WindowManagerService.H.REPORT_LOSING_FOCUS;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
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
// TODO: Several methods in here are accessing children of this container's children through various
// references (WindowList I am looking at you :/). See if we can delegate instead.
class RootWindowContainer extends WindowContainer<DisplayContent> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RootWindowContainer" : TAG_WM;

    WindowManagerService mService;

    private boolean mWallpaperForceHidingChanged = false;
    private Object mLastWindowFreezeSource = null;
    private Session mHoldScreen = null;
    private float mScreenBrightness = -1;
    private float mButtonBrightness = -1;
    private long mUserActivityTimeout = -1;
    private boolean mUpdateRotation = false;
    private boolean mObscured = false;
    boolean mSyswin = false;
    // Set to true when the display contains content to show the user.
    // When false, the display manager may choose to mirror or blank the display.
    private boolean mDisplayHasContent = false;
    private float mPreferredRefreshRate = 0;
    private int mPreferredModeId = 0;
    // Following variables are for debugging screen wakelock only.
    // Last window that requires screen wakelock
    WindowState mHoldScreenWindow = null;
    // Last window that obscures all windows below
    WindowState mObsuringWindow = null;
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

    RootWindowContainer(WindowManagerService service) {
        mService = service;
    }

    WindowState computeFocusedWindow() {
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            final DisplayContent dc = mChildren.get(i);
            final WindowState win = dc.findFocusedWindow();
            if (win != null) {
                return win;
            }
        }
        return null;
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
                dc = createDisplayContent(display);
            }
        }
        return dc;
    }

    private DisplayContent getDisplayContent(int displayId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent current = mChildren.get(i);
            if (current.getDisplayId() == displayId) {
                return current;
            }
        }
        return null;
    }

    private DisplayContent createDisplayContent(final Display display) {
        final DisplayContent dc = new DisplayContent(display, mService);
        final int displayId = display.getDisplayId();

        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Adding display=" + display);
        addChild(dc, null);

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

            // TODO(multi-display): Create an input channel for each display with touch capability.
            if (displayId == Display.DEFAULT_DISPLAY) {
                dc.mTapDetector = new TaskTapPointerEventListener(
                        mService, dc);
                mService.registerPointerEventListener(dc.mTapDetector);
                mService.registerPointerEventListener(mService.mMousePositionTracker);
            }
        }

        return dc;
    }

    /** Adds the input stack id to the input display id and returns the bounds of the added stack.*/
    Rect addStackToDisplay(int stackId, int displayId, boolean onTop) {
        final DisplayContent dc = getDisplayContent(displayId);
        if (dc == null) {
            Slog.w(TAG_WM, "addStackToDisplay: Trying to add stackId=" + stackId
                    + " to unknown displayId=" + displayId + " callers=" + Debug.getCallers(6));
            return null;
        }

        boolean attachedToDisplay = false;
        TaskStack stack = mService.mStackIdToStack.get(stackId);
        if (stack == null) {
            if (DEBUG_STACK) Slog.d(TAG_WM, "attachStack: stackId=" + stackId);

            stack = dc.getStackById(stackId);
            if (stack != null) {
                // It's already attached to the display...clear mDeferRemoval and move stack to
                // appropriate z-order on display as needed.
                stack.mDeferRemoval = false;
                dc.moveStack(stack, onTop);
                attachedToDisplay = true;
            } else {
                stack = new TaskStack(mService, stackId);
            }

            mService.mStackIdToStack.put(stackId, stack);
            if (stackId == DOCKED_STACK_ID) {
                mService.getDefaultDisplayContentLocked().mDividerControllerLocked
                        .notifyDockedStackExistsChanged(true);
            }
        }

        if (!attachedToDisplay) {
            stack.attachDisplayContent(dc);
            dc.attachStack(stack, onTop);
        }

        if (stack.getRawFullscreen()) {
            return null;
        }
        final Rect bounds = new Rect();
        stack.getRawBounds(bounds);
        return bounds;
    }

    boolean layoutNeeded() {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.layoutNeeded) {
                return true;
            }
        }
        return false;
    }

    void getWindows(WindowList output) {
        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final DisplayContent dc = mChildren.get(i);
            output.addAll(dc.getWindowList());
        }
    }

    void getWindows(WindowList output, boolean visibleOnly, boolean appsOnly) {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final WindowList windowList = mChildren.get(displayNdx).getWindowList();
            for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState w = windowList.get(winNdx);
                if ((!visibleOnly || w.mWinAnimator.getShown())
                        && (!appsOnly || w.mAppToken != null)) {
                    output.add(w);
                }
            }
        }
    }

    void getWindowsByName(WindowList output, String name) {
        int objectId = 0;
        // See if this is an object ID.
        try {
            objectId = Integer.parseInt(name, 16);
            name = null;
        } catch (RuntimeException e) {
        }
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final WindowList windowList = mChildren.get(displayNdx).getWindowList();
            for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState w = windowList.get(winNdx);
                if (name != null) {
                    if (w.mAttrs.getTitle().toString().contains(name)) {
                        output.add(w);
                    }
                } else if (System.identityHashCode(w) == objectId) {
                    output.add(w);
                }
            }
        }
    }

    WindowState findWindow(int hashCode) {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final WindowList windows = mChildren.get(displayNdx).getWindowList();
            final int numWindows = windows.size();
            for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                final WindowState w = windows.get(winNdx);
                if (System.identityHashCode(w) == hashCode) {
                    return w;
                }
            }
        }

        return null;
    }

    // TODO: Users would have their own window containers under the display container?
    void switchUser() {
        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final DisplayContent dc = mChildren.get(i);
            dc.switchUser();
        }
    }

    int[] onConfigurationChanged(Configuration config) {
        prepareFreezingTaskBounds();
        mService.mGlobalConfiguration = new Configuration(config);

        mService.mPolicy.onConfigurationChanged();

        mChangedStackList.clear();

        final int numDisplays = mChildren.size();
        for (int i = 0; i < numDisplays; ++i) {
            final DisplayContent dc = mChildren.get(i);
            dc.onConfigurationChanged(mChangedStackList);
        }

        return mChangedStackList.isEmpty() ? null : ArrayUtils.convertToIntArray(mChangedStackList);
    }

    private void prepareFreezingTaskBounds() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            mChildren.get(i).prepareFreezingTaskBounds();
        }
    }

    void setSecureSurfaceState(int userId, boolean disabled) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowList windows = mChildren.get(i).getWindowList();
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState win = windows.get(winNdx);
                if (win.mHasSurface && userId == UserHandle.getUserId(win.mOwnerUid)) {
                    win.mWinAnimator.setSecureLocked(disabled);
                }
            }
        }
    }

    void updateAppOpsState() {
        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final WindowList windows = mChildren.get(i).getWindowList();
            final int numWindows = windows.size();
            for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                final WindowState win = windows.get(winNdx);
                if (win.mAppOp == AppOpsManager.OP_NONE) {
                    continue;
                }
                final int mode = mService.mAppOps.checkOpNoThrow(win.mAppOp, win.getOwningUid(),
                        win.getOwningPackage());
                win.setAppOpVisibilityLw(mode == AppOpsManager.MODE_ALLOWED ||
                        mode == AppOpsManager.MODE_DEFAULT);
            }
        }
    }

    boolean canShowStrictModeViolation(int pid) {
        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final WindowList windows = mChildren.get(i).getWindowList();
            final int numWindows = windows.size();
            for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                final WindowState ws = windows.get(winNdx);
                if (ws.mSession.mPid == pid && ws.isVisibleLw()) {
                    return true;
                }
            }
        }
        return false;
    }

    void closeSystemDialogs(String reason) {
        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final WindowList windows = mChildren.get(i).getWindowList();
            final int numWindows = windows.size();
            for (int j = 0; j < numWindows; ++j) {
                final WindowState w = windows.get(j);
                if (w.mHasSurface) {
                    try {
                        w.mClient.closeSystemDialogs(reason);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    void removeReplacedWindows() {
        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION removeReplacedWindows");
        mService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                DisplayContent dc = mChildren.get(i);
                final WindowList windows = dc.getWindowList();
                for (int j = windows.size() - 1; j >= 0; j--) {
                    final WindowState win = windows.get(j);
                    final AppWindowToken aToken = win.mAppToken;
                    if (aToken != null) {
                        aToken.removeReplacedWindowIfNeeded(win);
                    }
                }
            }
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

    void updateInputWindows(InputMonitor inputMonitor, WindowState inputFocus, boolean inDrag) {
        boolean addInputConsumerHandle = mService.mInputConsumer != null;
        boolean addWallpaperInputConsumerHandle = mService.mWallpaperInputConsumer != null;
        final WallpaperController wallpaperController = mService.mWallpaperControllerLocked;
        boolean disableWallpaperTouchEvents = false;

        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final DisplayContent dc = mChildren.get(i);
            final WindowList windows = dc.getWindowList();
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState child = windows.get(winNdx);
                final InputChannel inputChannel = child.mInputChannel;
                final InputWindowHandle inputWindowHandle = child.mInputWindowHandle;
                if (inputChannel == null || inputWindowHandle == null || child.mRemoved
                        || child.isAdjustedForMinimizedDock()) {
                    // Skip this window because it cannot possibly receive input.
                    continue;
                }
                if (addInputConsumerHandle
                        && inputWindowHandle.layer <= mService.mInputConsumer.mWindowHandle.layer) {
                    inputMonitor.addInputWindowHandle(mService.mInputConsumer.mWindowHandle);
                    addInputConsumerHandle = false;
                }

                if (addWallpaperInputConsumerHandle) {
                    if (child.mAttrs.type == WindowManager.LayoutParams.TYPE_WALLPAPER &&
                            child.isVisibleLw()) {
                        // Add the wallpaper input consumer above the first visible wallpaper.
                        inputMonitor.addInputWindowHandle(
                                mService.mWallpaperInputConsumer.mWindowHandle);
                        addWallpaperInputConsumerHandle = false;
                    }
                }

                final int flags = child.mAttrs.flags;
                final int privateFlags = child.mAttrs.privateFlags;
                final int type = child.mAttrs.type;

                final boolean hasFocus = child == inputFocus;
                final boolean isVisible = child.isVisibleLw();
                if ((privateFlags
                        & WindowManager.LayoutParams.PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS)
                        != 0) {
                    disableWallpaperTouchEvents = true;
                }
                final boolean hasWallpaper = wallpaperController.isWallpaperTarget(child)
                        && (privateFlags & WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD) == 0
                        && !disableWallpaperTouchEvents;
                final boolean onDefaultDisplay = (child.getDisplayId() == Display.DEFAULT_DISPLAY);

                // If there's a drag in progress and 'child' is a potential drop target,
                // make sure it's been told about the drag
                if (inDrag && isVisible && onDefaultDisplay) {
                    mService.mDragState.sendDragStartedIfNeededLw(child);
                }

                inputMonitor.addInputWindowHandle(
                        inputWindowHandle, child, flags, type, isVisible, hasFocus, hasWallpaper);
            }
        }

        if (addWallpaperInputConsumerHandle) {
            // No visible wallpaper found, add the wallpaper input consumer at the end.
            inputMonitor.addInputWindowHandle(mService.mWallpaperInputConsumer.mWindowHandle);
        }
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
            // There was some problem...   first, do a sanity check of the window list to make sure
            // we haven't left any dangling surfaces around.

            Slog.i(TAG_WM, "Out of memory for surface!  Looking for leaks...");
            final int numDisplays = mChildren.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final WindowList windows = mChildren.get(displayNdx).getWindowList();
                final int numWindows = windows.size();
                for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                    final WindowState ws = windows.get(winNdx);
                    final WindowStateAnimator wsa = ws.mWinAnimator;
                    if (wsa.mSurfaceController == null) {
                        continue;
                    }
                    if (!mService.mSessions.contains(wsa.mSession)) {
                        Slog.w(TAG_WM, "LEAKED SURFACE (session doesn't exist): "
                                + ws + " surface=" + wsa.mSurfaceController
                                + " token=" + ws.mToken
                                + " pid=" + ws.mSession.mPid
                                + " uid=" + ws.mSession.mUid);
                        wsa.destroySurface();
                        mService.mForceRemoves.add(ws);
                        leakedSurface = true;
                    } else if (ws.mAppToken != null && ws.mAppToken.clientHidden) {
                        Slog.w(TAG_WM, "LEAKED SURFACE (app token hidden): "
                                + ws + " surface=" + wsa.mSurfaceController
                                + " token=" + ws.mAppToken
                                + " saved=" + ws.hasSavedSurface());
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", false);
                        wsa.destroySurface();
                        leakedSurface = true;
                    }
                }
            }

            if (!leakedSurface) {
                Slog.w(TAG_WM, "No leaked surfaces; killing applications!");
                SparseIntArray pidCandidates = new SparseIntArray();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final WindowList windows = mChildren.get(displayNdx).getWindowList();
                    final int numWindows = windows.size();
                    for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                        final WindowState ws = windows.get(winNdx);
                        if (mService.mForceRemoves.contains(ws)) {
                            continue;
                        }
                        WindowStateAnimator wsa = ws.mWinAnimator;
                        if (wsa.mSurfaceController != null) {
                            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
                        }
                    }
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
                    mService.scheduleRemoveStartingWindowLocked(winAnimator.mWin.mAppToken);
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
            for (i = displayContent.mExitingTokens.size() - 1; i >= 0; i--) {
                displayContent.mExitingTokens.get(i).hasVisible = false;
            }
        }

        for (int stackNdx = mService.mStackIdToStack.size() - 1; stackNdx >= 0; --stackNdx) {
            // Initialize state of exiting applications.
            final AppTokenList exitingAppTokens =
                    mService.mStackIdToStack.valueAt(stackNdx).mExitingAppTokens;
            for (int tokenNdx = exitingAppTokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                exitingAppTokens.get(tokenNdx).hasVisible = false;
            }
        }

        mHoldScreen = null;
        mScreenBrightness = -1;
        mButtonBrightness = -1;
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
            mService.mRoot.applySurfaceChangesTransaction(recoveringMemory, defaultDw, defaultDh);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
        }

        final WindowList defaultWindows = defaultDisplay.getWindowList();
        final WindowSurfacePlacer surfacePlacer = mService.mWindowPlacerLocked;

        // If we are ready to perform an app transition, check through all of the app tokens to be
        // shown and see if they are ready to go.
        if (mService.mAppTransition.isReady()) {
            defaultDisplay.pendingLayoutChanges |=
                    surfacePlacer.handleAppTransitionReadyLocked(defaultWindows);
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

        if (layoutNeeded()) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats("mLayoutNeeded",
                    defaultDisplay.pendingLayoutChanges);
        }

        for (i = mService.mResizingWindows.size() - 1; i >= 0; i--) {
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
        }

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
                    mService.mInputMethodWindow = null;
                }
                if (mService.mWallpaperControllerLocked.isWallpaperTarget(win)) {
                    wallpaperDestroyed = true;
                }
                win.destroyOrSaveSurface();
            } while (i > 0);
            mService.mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            ArrayList<WindowToken> exitingTokens = displayContent.mExitingTokens;
            for (i = exitingTokens.size() - 1; i >= 0; i--) {
                WindowToken token = exitingTokens.get(i);
                if (!token.hasVisible) {
                    exitingTokens.remove(i);
                    if (token.windowType == TYPE_WALLPAPER) {
                        mService.mWallpaperControllerLocked.removeWallpaperToken(token);
                    }
                }
            }
        }

        // Time to remove any exiting applications?
        for (int stackNdx = mService.mStackIdToStack.size() - 1; stackNdx >= 0; --stackNdx) {
            // Initialize state of exiting applications.
            final AppTokenList exitingAppTokens =
                    mService.mStackIdToStack.valueAt(stackNdx).mExitingAppTokens;
            for (i = exitingAppTokens.size() - 1; i >= 0; i--) {
                final AppWindowToken token = exitingAppTokens.get(i);
                if (!token.hasVisible && !mService.mClosingApps.contains(token) &&
                        (!token.mIsExiting || token.isEmpty())) {
                    // Make sure there is no animation running on this token, so any windows
                    // associated with it will be removed as soon as their animations are complete
                    token.mAppAnimator.clearAnimation();
                    token.mAppAnimator.animating = false;
                    if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "performLayout: App token exiting now removed" + token);
                    token.removeIfPossible();
                }
            }
        }

        if (wallpaperDestroyed) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            defaultDisplay.layoutNeeded = true;
        }

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.layoutNeeded = true;
            }
        }

        // Finally update all input windows now that the window changes have stabilized.
        mService.mInputMonitor.updateInputWindowsLw(true /*force*/);

        mService.setHoldScreenLocked(mHoldScreen);
        if (!mService.mDisplayFrozen) {
            if (mScreenBrightness < 0 || mScreenBrightness > 1.0f) {
                mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(-1);
            } else {
                mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mScreenBrightness));
            }
            if (mButtonBrightness < 0 || mButtonBrightness > 1.0f) {
                mService.mPowerManagerInternal.setButtonBrightnessOverrideFromWindowManager(-1);
            } else {
                mService.mPowerManagerInternal.setButtonBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mButtonBrightness));
            }
            mService.mPowerManagerInternal.setUserActivityTimeoutOverrideFromWindowManager(
                    mUserActivityTimeout);
        }

        if (mSustainedPerformanceModeCurrent != mSustainedPerformanceModeEnabled) {
            mSustainedPerformanceModeEnabled = mSustainedPerformanceModeCurrent;
            mService.mPowerManagerInternal.powerHint(
                    mService.mPowerManagerInternal.POWER_HINT_SUSTAINED_PERFORMANCE_MODE,
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
            if (mService.updateRotationUncheckedLocked(false)) {
                mService.mH.sendEmptyMessage(SEND_NEW_CONFIGURATION);
            } else {
                mUpdateRotation = false;
            }
        }

        if (mService.mWaitingForDrawnCallback != null ||
                (mOrientationChangeComplete && !defaultDisplay.layoutNeeded &&
                        !mUpdateRotation)) {
            mService.checkDrawnWindowsLocked();
        }

        final int N = mService.mPendingRemove.size();
        if (N > 0) {
            if (mService.mPendingRemoveTmp.length < N) {
                mService.mPendingRemoveTmp = new WindowState[N+10];
            }
            mService.mPendingRemove.toArray(mService.mPendingRemoveTmp);
            mService.mPendingRemove.clear();
            DisplayContentList displayList = new DisplayContentList();
            for (i = 0; i < N; i++) {
                final WindowState w = mService.mPendingRemoveTmp[i];
                w.removeImmediately();
                final DisplayContent displayContent = w.getDisplayContent();
                if (displayContent != null && !displayList.contains(displayContent)) {
                    displayList.add(displayContent);
                }
            }

            for (DisplayContent displayContent : displayList) {
                mService.mLayersController.assignLayersLocked(displayContent.getWindowList());
                displayContent.layoutNeeded = true;
            }
        }

        // Remove all deferred displays stacks, tasks, and activities.
        for (int displayNdx = mChildren.size() - 1; displayNdx >= 0; --displayNdx) {
            mChildren.get(displayNdx).checkCompleteDeferredRemoval();
        }

        if (updateInputWindowsNeeded) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
        mService.setFocusTaskRegionLocked();

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        mService.enableScreenIfNeededLocked();

        mService.scheduleAnimationLocked();
        mService.mWindowPlacerLocked.destroyPendingSurfaces();

        if (DEBUG_WINDOW_TRACE) Slog.e(TAG,
                "performSurfacePlacementInner exit: animating=" + mService.mAnimator.isAnimating());
    }

    // TODO: Super crazy long method that should be broken down...
    void applySurfaceChangesTransaction(boolean recoveringMemory, int defaultDw, int defaultDh) {
        mHoldScreenWindow = null;
        mObsuringWindow = null;

        if (mService.mWatermark != null) {
            mService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mStrictModeFlash != null) {
            mService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mCircularDisplayMask != null) {
            mService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh, mService.mRotation);
        }
        if (mService.mEmulatorDisplayOverlay != null) {
            mService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh,
                    mService.mRotation);
        }

        boolean focusDisplayed = false;

        final int count = mChildren.size();
        for (int j = 0; j < count; ++j) {
            final DisplayContent dc = mChildren.get(j);
            boolean updateAllDrawn = false;
            WindowList windows = dc.getWindowList();
            DisplayInfo displayInfo = dc.getDisplayInfo();
            final int displayId = dc.getDisplayId();
            final int dw = displayInfo.logicalWidth;
            final int dh = displayInfo.logicalHeight;
            final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
            final WindowSurfacePlacer surfacePlacer = mService.mWindowPlacerLocked;

            // Reset for each display.
            mDisplayHasContent = false;
            mPreferredRefreshRate = 0;
            mPreferredModeId = 0;

            int repeats = 0;
            do {
                repeats++;
                if (repeats > 6) {
                    Slog.w(TAG, "Animation repeat aborted after too many iterations");
                    dc.layoutNeeded = false;
                    break;
                }

                if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                        "On entry to LockedInner", dc.pendingLayoutChanges);

                if ((dc.pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0
                        && mService.mWallpaperControllerLocked.adjustWallpaperWindows()) {
                    mService.mLayersController.assignLayersLocked(windows);
                    dc.layoutNeeded = true;
                }

                if (isDefaultDisplay
                        && (dc.pendingLayoutChanges & FINISH_LAYOUT_REDO_CONFIG) != 0) {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                    if (mService.updateOrientationFromAppTokensLocked(true)) {
                        dc.layoutNeeded = true;
                        mService.mH.sendEmptyMessage(SEND_NEW_CONFIGURATION);
                    }
                }

                if ((dc.pendingLayoutChanges & FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                    dc.layoutNeeded = true;
                }

                // FIRST LOOP: Perform a layout, if needed.
                if (repeats < LAYOUT_REPEAT_THRESHOLD) {
                    surfacePlacer.performLayoutLockedInner(dc, repeats == 1,
                            false /* updateInputWindows */);
                } else {
                    Slog.w(TAG, "Layout repeat skipped after too many iterations");
                }

                // FIRST AND ONE HALF LOOP: Make WindowManagerPolicy think
                // it is animating.
                dc.pendingLayoutChanges = 0;

                if (isDefaultDisplay) {
                    mService.mPolicy.beginPostLayoutPolicyLw(dw, dh);
                    for (int i = windows.size() - 1; i >= 0; i--) {
                        WindowState w = windows.get(i);
                        if (w.mHasSurface) {
                            mService.mPolicy.applyPostLayoutPolicyLw(
                                    w, w.mAttrs, w.getParentWindow());
                        }
                    }
                    dc.pendingLayoutChanges |=
                            mService.mPolicy.finishPostLayoutPolicyLw();
                    if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                            "after finishPostLayoutPolicyLw", dc.pendingLayoutChanges);
                }
            } while (dc.pendingLayoutChanges != 0);

            mObscured = false;
            mSyswin = false;
            dc.resetDimming();

            // Only used if default window
            final boolean someoneLosingFocus = !mService.mLosingFocus.isEmpty();

            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState w = windows.get(i);
                final Task task = w.getTask();
                final boolean obscuredChanged = w.mObscured != mObscured;

                // Update effect.
                w.mObscured = mObscured;
                if (!mObscured) {
                    handleNotObscuredLocked(w, displayInfo);
                }

                w.applyDimLayerIfNeeded();

                if (isDefaultDisplay && obscuredChanged && w.isVisibleLw()
                        && mService.mWallpaperControllerLocked.isWallpaperTarget(w)) {
                    // This is the wallpaper target and its obscured state changed... make sure the
                    // current wallaper's visibility has been updated accordingly.
                    mService.mWallpaperControllerLocked.updateWallpaperVisibility();
                }

                final WindowStateAnimator winAnimator = w.mWinAnimator;

                // If the window has moved due to its containing content frame changing, then
                // notify the listeners and optionally animate it. Simply checking a change of
                // position is not enough, because being move due to dock divider is not a trigger
                // for animation.
                if (w.hasMoved()) {
                    // Frame has moved, containing content frame has also moved, and we're not
                    // currently animating... let's do something.
                    final int left = w.mFrame.left;
                    final int top = w.mFrame.top;
                    final boolean adjustedForMinimizedDockOrIme = task != null
                            && (task.mStack.isAdjustedForMinimizedDockedStack()
                            || task.mStack.isAdjustedForIme());
                    if (mService.okToDisplay()
                            && (w.mAttrs.privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                            && !w.isDragResizing() && !adjustedForMinimizedDockOrIme
                            && (task == null || w.getTask().mStack.hasMovementAnimations())
                            && !w.mWinAnimator.mLastHidden) {
                        winAnimator.setMoveAnimation(left, top);
                    }

                    //TODO (multidisplay): Accessibility supported only for the default display.
                    if (mService.mAccessibilityController != null
                            && displayId == Display.DEFAULT_DISPLAY) {
                        mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
                    }

                    try {
                        w.mClient.moved(left, top);
                    } catch (RemoteException e) {
                    }
                    w.mMovedByResize = false;
                }

                //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
                w.mContentChanged = false;

                // Moved from updateWindowsAndWallpaperLocked().
                if (w.mHasSurface) {
                    // Take care of the window being ready to display.
                    final boolean committed = winAnimator.commitFinishDrawingLocked();
                    if (isDefaultDisplay && committed) {
                        if (w.mAttrs.type == TYPE_DREAM) {
                            // HACK: When a dream is shown, it may at that point hide the lock
                            // screen. So we need to redo the layout to let the phone window manager
                            // make this happen.
                            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
                            if (DEBUG_LAYOUT_REPEATS) {
                                surfacePlacer.debugLayoutRepeats(
                                        "dream and commitFinishDrawingLocked true",
                                        dc.pendingLayoutChanges);
                            }
                        }
                        if ((w.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                            if (DEBUG_WALLPAPER_LIGHT)
                                Slog.v(TAG, "First draw done in potential wallpaper target " + w);
                            mWallpaperMayChange = true;
                            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                            if (DEBUG_LAYOUT_REPEATS) {
                                surfacePlacer.debugLayoutRepeats(
                                        "wallpaper and commitFinishDrawingLocked true",
                                        dc.pendingLayoutChanges);
                            }
                        }
                    }
                    if (!winAnimator.isAnimationStarting() && !winAnimator.isWaitingForOpening()) {
                        // Updates the shown frame before we set up the surface. This is needed
                        // because the resizing could change the top-left position (in addition to
                        // size) of the window. setSurfaceBoundariesLocked uses mShownPosition to
                        // position the surface.
                        //
                        // If an animation is being started, we can't call this method because the
                        // animation hasn't processed its initial transformation yet, but in general
                        // we do want to update the position if the window is animating.
                        winAnimator.computeShownFrameLocked();
                    }
                    winAnimator.setSurfaceBoundariesLocked(recoveringMemory);
                }

                final AppWindowToken atoken = w.mAppToken;
                if (DEBUG_STARTING_WINDOW && atoken != null && w == atoken.startingWindow) {
                    Slog.d(TAG, "updateWindows: starting " + w
                            + " isOnScreen=" + w.isOnScreen() + " allDrawn=" + atoken.allDrawn
                            + " freezingScreen=" + atoken.mAppAnimator.freezingScreen);
                }
                if (atoken != null && (!atoken.allDrawn || !atoken.allDrawnExcludingSaved
                        || atoken.mAppAnimator.freezingScreen)) {
                    if (atoken.lastTransactionSequence != mService.mTransactionSequence) {
                        atoken.lastTransactionSequence = mService.mTransactionSequence;
                        atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                        atoken.numInterestingWindowsExcludingSaved = 0;
                        atoken.numDrawnWindowsExcludingSaved = 0;
                        atoken.startingDisplayed = false;
                    }
                    if (!atoken.allDrawn && w.mightAffectAllDrawn(false /* visibleOnly */)) {
                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                            Slog.v(TAG, "Eval win " + w + ": isDrawn="
                                    + w.isDrawnLw()
                                    + ", isAnimationSet=" + winAnimator.isAnimationSet());
                            if (!w.isDrawnLw()) {
                                Slog.v(TAG, "Not displayed: s="
                                        + winAnimator.mSurfaceController
                                        + " pv=" + w.mPolicyVisibility
                                        + " mDrawState=" + winAnimator.drawStateToString()
                                        + " ph=" + w.isParentWindowHidden()
                                        + " th=" + atoken.hiddenRequested
                                        + " a=" + winAnimator.mAnimating);
                            }
                        }
                        if (w != atoken.startingWindow) {
                            if (w.isInteresting()) {
                                atoken.numInterestingWindows++;
                                if (w.isDrawnLw()) {
                                    atoken.numDrawnWindows++;
                                    if (DEBUG_VISIBILITY || DEBUG_ORIENTATION)
                                        Slog.v(TAG, "tokenMayBeDrawn: " + atoken
                                                + " w=" + w + " numInteresting="
                                                + atoken.numInterestingWindows
                                                + " freezingScreen="
                                                + atoken.mAppAnimator.freezingScreen
                                                + " mAppFreezing=" + w.mAppFreezing);
                                    updateAllDrawn = true;
                                }
                            }
                        } else if (w.isDrawnLw()) {
                            mService.mH.sendEmptyMessage(NOTIFY_STARTING_WINDOW_DRAWN);
                            atoken.startingDisplayed = true;
                        }
                    }
                    if (!atoken.allDrawnExcludingSaved
                            && w.mightAffectAllDrawn(true /* visibleOnly */)) {
                        if (w != atoken.startingWindow && w.isInteresting()) {
                            atoken.numInterestingWindowsExcludingSaved++;
                            if (w.isDrawnLw() && !w.isAnimatingWithSavedSurface()) {
                                atoken.numDrawnWindowsExcludingSaved++;
                                if (DEBUG_VISIBILITY || DEBUG_ORIENTATION)
                                    Slog.v(TAG, "tokenMayBeDrawnExcludingSaved: " + atoken
                                            + " w=" + w + " numInteresting="
                                            + atoken.numInterestingWindowsExcludingSaved
                                            + " freezingScreen="
                                            + atoken.mAppAnimator.freezingScreen
                                            + " mAppFreezing=" + w.mAppFreezing);
                                updateAllDrawn = true;
                            }
                        }
                    }
                }

                if (isDefaultDisplay && someoneLosingFocus && w == mService.mCurrentFocus
                        && w.isDisplayedLw()) {
                    focusDisplayed = true;
                }

                mService.updateResizingWindows(w);
            }

            mService.mDisplayManagerInternal.setDisplayProperties(displayId,
                    mDisplayHasContent,
                    mPreferredRefreshRate,
                    mPreferredModeId,
                    true /* inTraversal, must call performTraversalInTrans... below */);

            dc.stopDimmingIfNeeded();

            if (updateAllDrawn) {
                // See if any windows have been drawn, so they (and others associated with them)
                // can now be shown.
                dc.updateAllDrawn();
            }
        }

        if (focusDisplayed) {
            mService.mH.sendEmptyMessage(REPORT_LOSING_FOCUS);
        }

        // Give the display manager a chance to adjust properties
        // like display rotation if it needs to.
        mService.mDisplayManagerInternal.performTraversalInTransactionFromWindowManager();
    }

    /**
     * @param w WindowState this method is applied to.
     * @param dispInfo info of the display that the window's obscuring state is checked against.
     */
    private void handleNotObscuredLocked(final WindowState w, final DisplayInfo dispInfo) {
        final WindowManager.LayoutParams attrs = w.mAttrs;
        final int attrFlags = attrs.flags;
        final boolean canBeSeen = w.isDisplayedLw();
        final int privateflags = attrs.privateFlags;

        if (canBeSeen && w.isObscuringFullscreen(dispInfo)) {
            // This window completely covers everything behind it,
            // so we want to leave all of them as undimmed (for
            // performance reasons).
            if (!mObscured) {
                mObsuringWindow = w;
            }

            mObscured = true;
        }

        if (w.mHasSurface && canBeSeen) {
            if ((attrFlags & FLAG_KEEP_SCREEN_ON) != 0) {
                mHoldScreen = w.mSession;
                mHoldScreenWindow = w;
            } else if (DEBUG_KEEP_SCREEN_ON && w == mService.mLastWakeLockHoldingWindow) {
                Slog.d(TAG_KEEP_SCREEN_ON, "handleNotObscuredLocked: " + w + " was holding "
                        + "screen wakelock but no longer has FLAG_KEEP_SCREEN_ON!!! called by"
                        + Debug.getCallers(10));
            }
            if (!mSyswin && w.mAttrs.screenBrightness >= 0 && mScreenBrightness < 0) {
                mScreenBrightness = w.mAttrs.screenBrightness;
            }
            if (!mSyswin && w.mAttrs.buttonBrightness >= 0 && mButtonBrightness < 0) {
                mButtonBrightness = w.mAttrs.buttonBrightness;
            }
            if (!mSyswin && w.mAttrs.userActivityTimeout >= 0 && mUserActivityTimeout < 0) {
                mUserActivityTimeout = w.mAttrs.userActivityTimeout;
            }

            final int type = attrs.type;
            if (type == TYPE_SYSTEM_DIALOG || type == TYPE_SYSTEM_ERROR
                    || (attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                mSyswin = true;
            }

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
                mDisplayHasContent = true;
            } else if (displayContent != null &&
                    (!mObscureApplicationContentOnSecondaryDisplays
                            || (mObscured && type == TYPE_KEYGUARD_DIALOG))) {
                // Allow full screen keyguard presentation dialogs to be seen.
                mDisplayHasContent = true;
            }
            if (mPreferredRefreshRate == 0 && w.mAttrs.preferredRefreshRate != 0) {
                mPreferredRefreshRate = w.mAttrs.preferredRefreshRate;
            }
            if (mPreferredModeId == 0 && w.mAttrs.preferredDisplayModeId != 0) {
                mPreferredModeId = w.mAttrs.preferredDisplayModeId;
            }
            if ((privateflags & PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE) != 0) {
                mSustainedPerformanceModeCurrent = true;
            }
        }
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
        if (!layoutNeeded()) {
            return;
        }
        pw.print("  layoutNeeded on displays=");
        final int count = mChildren.size();
        for (int displayNdx = 0; displayNdx < count; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.layoutNeeded) {
                pw.print(displayContent.getDisplayId());
            }
        }
        pw.println();
    }

    void dumpWindowsNoHeader(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final WindowList windowList = mChildren.get(displayNdx).getWindowList();
            for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState w = windowList.get(winNdx);
                if (windows == null || windows.contains(w)) {
                    pw.println("  Window #" + winNdx + " " + w + ":");
                    w.dump(pw, "    ", dumpAll || windows != null);
                }
            }
        }
    }

    @Override
    String getName() {
        return "ROOT";
    }
}
