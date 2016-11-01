/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.util.Comparator;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.animation.Animation;

import java.io.PrintWriter;

/**
 * Container of a set of related windows in the window manager. Often this is an AppWindowToken,
 * which is the handle for an Activity that it uses to display windows. For nested windows, there is
 * a WindowToken created for the parent window to manage its children.
 */
class WindowToken extends WindowContainer<WindowState> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowToken" : TAG_WM;

    // The window manager!
    protected final WindowManagerService mService;

    // The actual token.
    final IBinder token;

    // The type of window this token is for, as per WindowManager.LayoutParams.
    final int windowType;

    // Set if this token was explicitly added by a client, so should
    // not be removed when all windows are removed.
    final boolean explicit;

    // For printing.
    String stringName;

    // Is key dispatching paused for this token?
    boolean paused = false;

    // Should this token's windows be hidden?
    boolean hidden;

    // Temporary for finding which tokens no longer have visible windows.
    boolean hasVisible;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    // Set to true when this token is in a pending transaction where its
    // windows will be put to the bottom of the list.
    boolean sendingToBottom;

    // The display this token is on.
    private DisplayContent mDisplayContent;

    /**
     * Compares two child window of this token and returns -1 if the first is lesser than the
     * second in terms of z-order and 1 otherwise.
     */
    private final Comparator<WindowState> mWindowComparator =
            (WindowState newWindow, WindowState existingWindow) -> {
        final WindowToken token = WindowToken.this;
        if (newWindow.mToken != token) {
            throw new IllegalArgumentException("newWindow=" + newWindow
                    + " is not a child of token=" + token);
        }

        if (existingWindow.mToken != token) {
            throw new IllegalArgumentException("existingWindow=" + existingWindow
                    + " is not a child of token=" + token);
        }

        return isFirstChildWindowGreaterThanSecond(newWindow, existingWindow) ? 1 : -1;
    };

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean _explicit,
            DisplayContent dc) {
        mService = service;
        token = _token;
        windowType = type;
        explicit = _explicit;
        onDisplayChanged(dc);
    }

    void removeAllWindows() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState win = mChildren.get(i);
            if (DEBUG_WINDOW_MOVEMENT) Slog.w(TAG_WM, "removeAllWindows: removing win=" + win);
            win.removeIfPossible();
            if (mChildren.contains(win)) {
                removeChild(win);
            }
        }
    }

    void setExiting() {
        if (hidden) {
            return;
        }

        final int count = mChildren.size();
        boolean changed = false;
        boolean delayed = false;

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            if (win.mWinAnimator.isAnimationSet()) {
                delayed = true;
            }
            changed |= win.onSetAppExiting();
        }

        hidden = true;

        if (changed) {
            mService.mWindowPlacerLocked.performSurfacePlacement();
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /*updateInputWindows*/);
        }

        if (delayed) {
            mDisplayContent.mExitingTokens.add(this);
        }
    }

    int adjustAnimLayer(int adj) {
        int highestAnimLayer = -1;
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowState w = mChildren.get(j);
            final int winHighestAnimLayer = w.adjustAnimLayer(adj);
            if (winHighestAnimLayer > highestAnimLayer) {
                highestAnimLayer = winHighestAnimLayer;
            }
            if (w == mService.mInputMethodTarget && !mService.mInputMethodTargetWaitingAnim) {
                mDisplayContent.setInputMethodAnimLayerAdjustment(adj);
            }
        }
        return highestAnimLayer;
    }

    WindowState getTopWindow() {
        if (mChildren.isEmpty()) {
            return null;
        }
        return (WindowState) mChildren.get(mChildren.size() - 1).getTop();
    }

    /**
     * Recursive search through a WindowList and all of its windows' children.
     * @param target The window to search for.
     * @return The index of win in windows or of the window that is an ancestor of win.
     */
    int getWindowIndex(WindowState target) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState w = mChildren.get(i);
            if (w == target || w.hasChild(target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns true if the new window is considered greater than the existing window in terms of
     * z-order.
     */
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        // By default the first window isn't greater than the second to preserve existing logic of
        // how new windows are added to the token
        return false;
    }

    void addWindow(final WindowState win) {
        if (DEBUG_FOCUS) Slog.d(TAG_WM,
                "addWindow: win=" + win + " Callers=" + Debug.getCallers(5));

        if (!win.isChildWindow()) {
            if (asAppWindowToken() != null) {
                mDisplayContent.addAppWindowToWindowList(win);
            } else {
                mDisplayContent.addNonAppWindowToWindowList(win);
            }

            if (!mChildren.contains(win)) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + this);
                addChild(win, mWindowComparator);
            }
        } else {
            mDisplayContent.addChildWindowToWindowList(win);
        }
    }

    void addImeWindow(WindowState win) {
        int pos = mDisplayContent.findDesiredInputMethodWindowIndex(true);

        if (pos < 0) {
            addWindow(win);
            mDisplayContent.moveInputMethodDialogs(pos);
            return;
        }

        if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Adding input method window " + win + " at " + pos);
        mDisplayContent.addToWindowList(win, pos);
        if (!mChildren.contains(win)) {
            addChild(win, null);
        }
        mDisplayContent.moveInputMethodDialogs(pos + 1);
    }

    /** Return the first window in the token window list that isn't a starting window or null. */
    WindowState getFirstNonStartingWindow() {
        final int count = mChildren.size();
        // We only care about parent windows so no need to loop through child windows.
        for (int i = 0; i < count; i++) {
            final WindowState w = mChildren.get(i);
            if (w.mAttrs.type != TYPE_APPLICATION_STARTING) {
                return w;
            }
        }
        return null;
    }

    /** Returns true if the token windows list is empty. */
    boolean isEmpty() {
        return mChildren.isEmpty();
    }

    WindowState getReplacingWindow() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            final WindowState replacing = win.getReplacingWindow();
            if (replacing != null) {
                return replacing;
            }
        }
        return null;
    }

    /** Return true if this token has a window that wants the wallpaper displayed behind it. */
    boolean windowsCanBeWallpaperTarget() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowState w = mChildren.get(j);
            if ((w.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                return true;
            }
        }

        return false;
    }

    void hideWallpaperToken(boolean wasDeferred, String reason) {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowState wallpaper = mChildren.get(j);
            wallpaper.hideWallpaperWindow(wasDeferred, reason);
        }
        hidden = true;
    }

    void sendWindowWallpaperCommand(
            String action, int x, int y, int z, Bundle extras, boolean sync) {
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);
            try {
                wallpaper.mClient.dispatchWallpaperCommand(action, x, y, z, extras, sync);
                // We only want to be synchronous with one wallpaper.
                sync = false;
            } catch (RemoteException e) {
            }
        }
    }

    void updateWallpaperOffset(int dw, int dh, boolean sync) {
        final WallpaperController wallpaperController = mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);
            if (wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, sync)) {
                final WindowStateAnimator winAnimator = wallpaper.mWinAnimator;
                winAnimator.computeShownFrameLocked();
                // No need to lay out the windows - we can just set the wallpaper position directly.
                winAnimator.setWallpaperOffset(wallpaper.mShownPosition);
                // We only want to be synchronous with one wallpaper.
                sync = false;
            }
        }
    }

    void updateWallpaperVisibility(boolean visible) {
        final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();
        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;

        if (hidden == visible) {
            hidden = !visible;
            // Need to do a layout to ensure the wallpaper now has the correct size.
            mDisplayContent.setLayoutNeeded();
        }

        final WallpaperController wallpaperController = mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);
            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }

            wallpaper.dispatchWallpaperVisibility(visible);
        }
    }

    /**
     * Starts {@param anim} on all children.
     */
    void startAnimation(Animation anim) {
        for (int ndx = mChildren.size() - 1; ndx >= 0; ndx--) {
            final WindowState windowState = mChildren.get(ndx);
            windowState.mWinAnimator.setAnimation(anim);
        }
    }

    boolean updateWallpaperWindowsPlacement(ReadOnlyWindowList windowList,
            WindowState wallpaperTarget, int wallpaperTargetIndex, boolean visible, int dw, int dh,
            int wallpaperAnimLayerAdj) {

        boolean changed = false;
        if (hidden == visible) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.d(TAG,
                    "Wallpaper token " + token + " hidden=" + !visible);
            hidden = !visible;
            // Need to do a layout to ensure the wallpaper now has the correct size.
            mDisplayContent.setLayoutNeeded();
        }

        final WallpaperController wallpaperController = mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);

            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }

            // First, make sure the client has the current visibility state.
            wallpaper.dispatchWallpaperVisibility(visible);
            wallpaper.adjustAnimLayer(wallpaperAnimLayerAdj);

            if (DEBUG_LAYERS || DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "adjustWallpaper win "
                    + wallpaper + " anim layer: " + wallpaper.mWinAnimator.mAnimLayer);

            // First, if this window is at the current index, then all is well.
            if (wallpaper == wallpaperTarget) {
                wallpaperTargetIndex--;
                wallpaperTarget = wallpaperTargetIndex > 0
                        ? windowList.get(wallpaperTargetIndex - 1) : null;
                continue;
            }

            // The window didn't match...  the current wallpaper window,
            // wherever it is, is in the wrong place, so make sure it is not in the list.
            int oldIndex = windowList.indexOf(wallpaper);
            if (oldIndex >= 0) {
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Wallpaper removing at " + oldIndex + ": " + wallpaper);
                mDisplayContent.removeFromWindowList(wallpaper);
                if (oldIndex < wallpaperTargetIndex) {
                    wallpaperTargetIndex--;
                }
            }

            // Now stick it in. For apps over wallpaper keep the wallpaper at the bottommost
            // layer. For keyguard over wallpaper put the wallpaper under the lowest window that
            // is currently on screen, i.e. not hidden by policy.
            int insertionIndex = 0;
            if (visible && wallpaperTarget != null) {
                final int privateFlags = wallpaperTarget.mAttrs.privateFlags;
                if ((privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                    insertionIndex = Math.min(windowList.indexOf(wallpaperTarget),
                            findLowestWindowOnScreen(windowList));
                }
            }
            if (DEBUG_WALLPAPER_LIGHT || DEBUG_WINDOW_MOVEMENT
                    || (DEBUG_ADD_REMOVE && oldIndex != insertionIndex)) Slog.v(TAG,
                    "Moving wallpaper " + wallpaper + " from " + oldIndex + " to " + insertionIndex);

            mDisplayContent.addToWindowList(wallpaper, insertionIndex);
            changed = true;
        }

        return changed;
    }

    /**
     * @return The index in {@param windows} of the lowest window that is currently on screen and
     *         not hidden by the policy.
     */
    private int findLowestWindowOnScreen(ReadOnlyWindowList windowList) {
        final int size = windowList.size();
        for (int index = 0; index < size; index++) {
            final WindowState win = windowList.get(index);
            if (win.isOnScreen()) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    boolean hasVisibleNotDrawnWallpaper() {
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState wallpaper = mChildren.get(j);
            if (wallpaper.hasVisibleNotDrawnWallpaper()) {
                return true;
            }
        }
        return false;
    }

    int getHighestAnimLayer() {
        int highest = -1;
        for (int j = 0; j < mChildren.size(); j++) {
            final WindowState w = mChildren.get(j);
            final int wLayer = w.getHighestAnimLayer();
            if (wLayer > highest) {
                highest = wLayer;
            }
        }
        return highest;
    }

    AppWindowToken asAppWindowToken() {
        // TODO: Not sure if this is the best way to handle this vs. using instanceof and casting.
        // I am not an app window token!
        return null;
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    @Override
    void removeImmediately() {
        if (mDisplayContent != null) {
            mDisplayContent.removeWindowToken(token);
        }
        // Needs to occur after the token is removed from the display above to avoid attempt at
        // duplicate removal of this window container from it's parent.
        super.removeImmediately();
    }

    void onDisplayChanged(DisplayContent dc) {
        if (mDisplayContent == dc) {
            return;
        }

        if (mDisplayContent != null) {
            mDisplayContent.removeWindowToken(token);
        }
        mDisplayContent = dc;
        mDisplayContent.setWindowToken(token, this);

        super.onDisplayChanged(dc);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("windows="); pw.println(mChildren);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                pw.print(" hidden="); pw.print(hidden);
                pw.print(" hasVisible="); pw.println(hasVisible);
        if (waitingToShow || sendingToBottom) {
            pw.print(prefix); pw.print("waitingToShow="); pw.print(waitingToShow);
                    pw.print(" sendingToBottom="); pw.print(sendingToBottom);
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("WindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" "); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }

    @Override
    String getName() {
        return toString();
    }
}
