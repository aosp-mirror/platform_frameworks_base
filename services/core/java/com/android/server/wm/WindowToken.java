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

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowTokenProto.HASH_CODE;
import static com.android.server.wm.WindowTokenProto.HIDDEN;
import static com.android.server.wm.WindowTokenProto.PAUSED;
import static com.android.server.wm.WindowTokenProto.WAITING_TO_SHOW;
import static com.android.server.wm.WindowTokenProto.WINDOWS;
import static com.android.server.wm.WindowTokenProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.os.Debug;
import android.os.IBinder;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.Comparator;

/**
 * Container of a set of related windows in the window manager. Often this is an AppWindowToken,
 * which is the handle for an Activity that it uses to display windows. For nested windows, there is
 * a WindowToken created for the parent window to manage its children.
 */
class WindowToken extends WindowContainer<WindowState> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowToken" : TAG_WM;

    // The actual token.
    final IBinder token;

    // The type of window this token is for, as per WindowManager.LayoutParams.
    final int windowType;

    /** {@code true} if this holds the rounded corner overlay */
    final boolean mRoundedCornerOverlay;

    // Set if this token was explicitly added by a client, so should
    // persist (not be removed) when all windows are removed.
    boolean mPersistOnEmpty;

    // For printing.
    String stringName;

    // Is key dispatching paused for this token?
    boolean paused = false;

    // Should this token's windows be hidden?
    private boolean mHidden;

    // Temporary for finding which tokens no longer have visible windows.
    boolean hasVisible;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    // Set to true when this token is in a pending transaction where its
    // windows will be put to the bottom of the list.
    boolean sendingToBottom;

    // The display this token is on.
    protected DisplayContent mDisplayContent;

    /** The owner has {@link android.Manifest.permission#MANAGE_APP_TOKENS} */
    final boolean mOwnerCanManageAppTokens;

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

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty,
            DisplayContent dc, boolean ownerCanManageAppTokens) {
        this(service, _token, type, persistOnEmpty, dc, ownerCanManageAppTokens,
                false /* roundedCornersOverlay */);
    }

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty,
            DisplayContent dc, boolean ownerCanManageAppTokens, boolean roundedCornerOverlay) {
        super(service);
        token = _token;
        windowType = type;
        mPersistOnEmpty = persistOnEmpty;
        mOwnerCanManageAppTokens = ownerCanManageAppTokens;
        mRoundedCornerOverlay = roundedCornerOverlay;
        onDisplayChanged(dc);
    }

    void setHidden(boolean hidden) {
        if (hidden != mHidden) {
            mHidden = hidden;
        }
    }

    boolean isHidden() {
        return mHidden;
    }

    void removeAllWindowsIfPossible() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState win = mChildren.get(i);
            if (DEBUG_WINDOW_MOVEMENT) Slog.w(TAG_WM,
                    "removeAllWindowsIfPossible: removing win=" + win);
            win.removeIfPossible();
        }
    }

    void setExiting() {
        if (mChildren.size() == 0) {
            super.removeImmediately();
            return;
        }

        // This token is exiting, so allow it to be removed when it no longer contains any windows.
        mPersistOnEmpty = false;

        if (mHidden) {
            return;
        }

        final int count = mChildren.size();
        boolean changed = false;
        boolean delayed = false;

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            if (win.isAnimating()) {
                delayed = true;
            }
            changed |= win.onSetAppExiting();
        }

        setHidden(true);

        if (changed) {
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /*updateInputWindows*/);
        }

        if (delayed) {
            mDisplayContent.mExitingTokens.add(this);
        }
    }

    /**
     * Returns true if the new window is considered greater than the existing window in terms of
     * z-order.
     */
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        // New window is considered greater if it has a higher or equal base layer.
        return newWindow.mBaseLayer >= existingWindow.mBaseLayer;
    }

    void addWindow(final WindowState win) {
        if (DEBUG_FOCUS) Slog.d(TAG_WM,
                "addWindow: win=" + win + " Callers=" + Debug.getCallers(5));

        if (win.isChildWindow()) {
            // Child windows are added to their parent windows.
            return;
        }
        if (!mChildren.contains(win)) {
            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + this);
            addChild(win, mWindowComparator);
            mWmService.mWindowsChanged = true;
            // TODO: Should we also be setting layout needed here and other places?
        }
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

    @Override
    void onDisplayChanged(DisplayContent dc) {
        dc.reParentWindowToken(this);
        mDisplayContent = dc;

        // TODO(b/36740756): One day this should perhaps be hooked
        // up with goodToGo, so we don't move a window
        // to another display before the window behind
        // it is ready.

        super.onDisplayChanged(dc);
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
        proto.write(HASH_CODE, System.identityHashCode(this));
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowState w = mChildren.get(i);
            w.writeToProto(proto, WINDOWS, logLevel);
        }
        proto.write(HIDDEN, mHidden);
        proto.write(WAITING_TO_SHOW, waitingToShow);
        proto.write(PAUSED, paused);
        proto.end(token);
    }

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix); pw.print("windows="); pw.println(mChildren);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                pw.print(" hidden="); pw.print(mHidden);
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

    boolean okToDisplay() {
        return mDisplayContent != null && mDisplayContent.okToDisplay();
    }

    boolean okToAnimate() {
        return mDisplayContent != null && mDisplayContent.okToAnimate();
    }

    /**
     * Return whether windows from this token can layer above the
     * system bars, or in other words extend outside of the "Decor Frame"
     */
    boolean canLayerAboveSystemBars() {
        int layer = mWmService.mPolicy.getWindowLayerFromTypeLw(windowType,
                mOwnerCanManageAppTokens);
        int navLayer = mWmService.mPolicy.getWindowLayerFromTypeLw(TYPE_NAVIGATION_BAR,
                mOwnerCanManageAppTokens);
        return mOwnerCanManageAppTokens && (layer > navLayer);
    }
}
