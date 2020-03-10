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

import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.ProtoLogGroup.WM_ERROR;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.WINDOW_TOKEN;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowTokenProto.HASH_CODE;
import static com.android.server.wm.WindowTokenProto.PAUSED;
import static com.android.server.wm.WindowTokenProto.WAITING_TO_SHOW;
import static com.android.server.wm.WindowTokenProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.app.IWindowToken;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.ArrayList;
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

    // Temporary for finding which tokens no longer have visible windows.
    boolean hasVisible;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    /** The owner has {@link android.Manifest.permission#MANAGE_APP_TOKENS} */
    final boolean mOwnerCanManageAppTokens;

    private FixedRotationTransformState mFixedRotationTransformState;

    private Configuration mLastReportedConfig;
    private int mLastReportedDisplay = INVALID_DISPLAY;

    @VisibleForTesting
    final boolean mFromClientToken;

    /**
     * Used to fix the transform of the token to be rotated to a rotation different than it's
     * display. The window frames and surfaces corresponding to this token will be layouted and
     * rotated by the given rotated display info, frames and insets.
     */
    private static class FixedRotationTransformState {
        final WindowToken mOwner;
        final DisplayInfo mDisplayInfo;
        final DisplayFrames mDisplayFrames;
        final InsetsState mInsetsState;
        final Configuration mRotatedOverrideConfiguration;
        final SeamlessRotator mRotator;
        /**
         * The tokens that share the same transform. Their end time of transform are the same as
         * {@link #mOwner}.
         */
        final ArrayList<WindowToken> mAssociatedTokens = new ArrayList<>(1);
        final ArrayList<WindowContainer<?>> mRotatedContainers = new ArrayList<>(3);
        boolean mIsTransforming = true;

        FixedRotationTransformState(WindowToken owner, DisplayInfo rotatedDisplayInfo,
                DisplayFrames rotatedDisplayFrames, InsetsState rotatedInsetsState,
                Configuration rotatedConfig, int currentRotation) {
            mOwner = owner;
            mDisplayInfo = rotatedDisplayInfo;
            mDisplayFrames = rotatedDisplayFrames;
            mInsetsState = rotatedInsetsState;
            mRotatedOverrideConfiguration = rotatedConfig;
            // This will use unrotate as rotate, so the new and old rotation are inverted.
            mRotator = new SeamlessRotator(rotatedDisplayInfo.rotation, currentRotation,
                    rotatedDisplayInfo);
        }

        /**
         * Transforms the window container from the next rotation to the current rotation for
         * showing the window in a display with different rotation.
         */
        void transform(WindowContainer<?> container) {
            mRotator.unrotate(container.getPendingTransaction(), container);
            if (!mRotatedContainers.contains(container)) {
                mRotatedContainers.add(container);
            }
        }

        /**
         * Resets the transformation of the window containers which have been rotated. This should
         * be called when the window has the same rotation as display.
         */
        void resetTransform() {
            for (int i = mRotatedContainers.size() - 1; i >= 0; i--) {
                final WindowContainer<?> c = mRotatedContainers.get(i);
                mRotator.finish(c.getPendingTransaction(), c);
            }
        }
    }

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
                false /* roundedCornerOverlay */);
    }

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty,
            DisplayContent dc, boolean ownerCanManageAppTokens, boolean roundedCornerOverlay) {
        this(service, _token, type, persistOnEmpty, dc, ownerCanManageAppTokens,
                roundedCornerOverlay, false /* fromClientToken */);
    }

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty,
            DisplayContent dc, boolean ownerCanManageAppTokens, boolean roundedCornerOverlay,
            boolean fromClientToken) {
        super(service);
        token = _token;
        windowType = type;
        mPersistOnEmpty = persistOnEmpty;
        mOwnerCanManageAppTokens = ownerCanManageAppTokens;
        mRoundedCornerOverlay = roundedCornerOverlay;
        mFromClientToken = fromClientToken;
        if (dc != null) {
            dc.addWindowToken(token, this);
        }
    }

    void removeAllWindowsIfPossible() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState win = mChildren.get(i);
            ProtoLog.w(WM_DEBUG_WINDOW_MOVEMENT,
                    "removeAllWindowsIfPossible: removing win=%s", win);
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

        if (!isVisible()) {
            return;
        }

        final int count = mChildren.size();
        boolean changed = false;
        final boolean delayed = isAnimating(TRANSITION | PARENTS | CHILDREN);

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            changed |= win.onSetAppExiting();
        }

        final ActivityRecord app = asActivityRecord();
        if (app != null) {
            app.setVisible(false);
        }

        if (changed) {
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /*updateInputWindows*/);
        }

        if (delayed) {
            mDisplayContent.mExitingTokens.add(this);
        }
    }

    /**
     * @return The scale for applications running in compatibility mode. Multiply the size in the
     *         application by this scale will be the size in the screen.
     */
    float getSizeCompatScale() {
        return mDisplayContent.mCompatibleScreenScale;
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
        ProtoLog.d(WM_DEBUG_FOCUS,
                "addWindow: win=%s Callers=%s", win, Debug.getCallers(5));

        if (win.isChildWindow()) {
            // Child windows are added to their parent windows.
            return;
        }
        if (!mChildren.contains(win)) {
            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "Adding %s to %s", win, this);
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

        // TODO(b/36740756): One day this should perhaps be hooked
        // up with goodToGo, so we don't move a window
        // to another display before the window behind
        // it is ready.
        super.onDisplayChanged(dc);
        reportConfigToWindowTokenClient();
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        reportConfigToWindowTokenClient();
    }

    void reportConfigToWindowTokenClient() {
        if (asActivityRecord() != null) {
            // Activities are updated through ATM callbacks.
            return;
        }

        // Unfortunately, this WindowToken is not from WindowContext so it cannot handle
        // its own configuration changes.
        if (!mFromClientToken) {
            return;
        }

        final Configuration config = getConfiguration();
        final int displayId = getDisplayContent().getDisplayId();
        if (config.equals(mLastReportedConfig) && displayId == mLastReportedDisplay) {
            // No changes since last reported time.
            return;
        }

        mLastReportedConfig = config;
        mLastReportedDisplay = displayId;

        IWindowToken windowTokenClient = IWindowToken.Stub.asInterface(token);
        if (windowTokenClient != null) {
            try {
                windowTokenClient.onConfigurationChanged(config, displayId);
            } catch (RemoteException e) {
                ProtoLog.w(WM_ERROR,
                        "Could not report config changes to the window token client.");
            }
        }
    }

    @Override
    void assignLayer(SurfaceControl.Transaction t, int layer) {
        if (windowType == TYPE_DOCK_DIVIDER) {
            // See {@link DisplayContent#mSplitScreenDividerAnchor}
            super.assignRelativeLayer(t, mDisplayContent.getSplitScreenDividerAnchor(), 1);
        } else if (mRoundedCornerOverlay) {
            super.assignLayer(t, WindowManagerPolicy.COLOR_FADE_LAYER + 1);
        } else {
            super.assignLayer(t, layer);
        }
    }

    @Override
    SurfaceControl.Builder makeSurface() {
        final SurfaceControl.Builder builder = super.makeSurface();
        if (mRoundedCornerOverlay) {
            builder.setParent(null);
        }
        return builder;
    }

    boolean hasFixedRotationTransform() {
        return mFixedRotationTransformState != null;
    }

    boolean isFinishingFixedRotationTransform() {
        return mFixedRotationTransformState != null
                && !mFixedRotationTransformState.mIsTransforming;
    }

    boolean isFixedRotationTransforming() {
        return mFixedRotationTransformState != null
                && mFixedRotationTransformState.mIsTransforming;
    }

    DisplayInfo getFixedRotationTransformDisplayInfo() {
        return isFixedRotationTransforming() ? mFixedRotationTransformState.mDisplayInfo : null;
    }

    DisplayFrames getFixedRotationTransformDisplayFrames() {
        return isFixedRotationTransforming() ? mFixedRotationTransformState.mDisplayFrames : null;
    }

    Rect getFixedRotationTransformDisplayBounds() {
        return isFixedRotationTransforming()
                ? mFixedRotationTransformState.mRotatedOverrideConfiguration.windowConfiguration
                        .getBounds()
                : null;
    }

    InsetsState getFixedRotationTransformInsetsState() {
        return isFixedRotationTransforming() ? mFixedRotationTransformState.mInsetsState : null;
    }

    /** Applies the rotated layout environment to this token in the simulated rotated display. */
    void applyFixedRotationTransform(DisplayInfo info, DisplayFrames displayFrames,
            Configuration config) {
        if (mFixedRotationTransformState != null) {
            return;
        }
        final InsetsState insetsState = new InsetsState();
        mDisplayContent.getDisplayPolicy().simulateLayoutDisplay(displayFrames, insetsState,
                mDisplayContent.getConfiguration().uiMode);
        mFixedRotationTransformState = new FixedRotationTransformState(this, info, displayFrames,
                insetsState, new Configuration(config), mDisplayContent.getRotation());
        onConfigurationChanged(getParent().getConfiguration());
    }

    /**
     * Reuses the {@link FixedRotationTransformState} (if any) from the other WindowToken to this
     * one. This takes the same effect as {@link #applyFixedRotationTransform}, but the linked state
     * can only be cleared by the state owner.
     */
    void linkFixedRotationTransform(WindowToken other) {
        if (mFixedRotationTransformState != null) {
            return;
        }
        final FixedRotationTransformState fixedRotationState = other.mFixedRotationTransformState;
        if (fixedRotationState == null) {
            return;
        }
        mFixedRotationTransformState = fixedRotationState;
        fixedRotationState.mAssociatedTokens.add(this);
        onConfigurationChanged(getParent().getConfiguration());
    }

    /**
     * Clears the transformation and continue updating the orientation change of display. Only the
     * state owner can clear the transform state.
     */
    void clearFixedRotationTransform() {
        final FixedRotationTransformState state = mFixedRotationTransformState;
        if (state == null || state.mOwner != this) {
            return;
        }
        state.resetTransform();
        // Clear the flag so if the display will be updated to the same orientation, the transform
        // won't take effect. The state is cleared at the end, because it is used to indicate that
        // other windows can use seamless rotation when applying rotation to display.
        state.mIsTransforming = false;
        final boolean changed =
                mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp(this);
        // If it is not the launching app or the display is not rotated, make sure the merged
        // override configuration is restored from parent.
        if (!changed) {
            onMergedOverrideConfigurationChanged();
        }
        for (int i = state.mAssociatedTokens.size() - 1; i >= 0; i--) {
            state.mAssociatedTokens.get(i).mFixedRotationTransformState = null;
        }
        mFixedRotationTransformState = null;
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        super.resolveOverrideConfiguration(newParentConfig);
        if (isFixedRotationTransforming()) {
            // Apply the rotated configuration to current resolved configuration, so the merged
            // override configuration can update to the same state.
            getResolvedOverrideConfiguration().updateFrom(
                    mFixedRotationTransformState.mRotatedOverrideConfiguration);
        }
    }

    @Override
    void updateSurfacePosition() {
        super.updateSurfacePosition();
        if (isFixedRotationTransforming()) {
            // The window is layouted in a simulated rotated display but the real display hasn't
            // rotated, so here transforms its surface to fit in the real display.
            mFixedRotationTransformState.transform(this);
        }
    }

    /**
     * Converts the rotated animation frames and insets back to display space for local animation.
     * It should only be called when {@link #hasFixedRotationTransform} is true.
     */
    void unrotateAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final SeamlessRotator rotator = mFixedRotationTransformState.mRotator;
        rotator.unrotateFrame(outFrame);
        rotator.unrotateInsets(outInsets);
        rotator.unrotateInsets(outStableInsets);
        rotator.unrotateInsets(outSurfaceInsets);
    }

    /**
     * Gives a chance to this {@link WindowToken} to adjust the {@link
     * android.view.WindowManager.LayoutParams} of its windows.
     */
    void adjustWindowParams(WindowState win, WindowManager.LayoutParams attrs) {
    }


    @CallSuper
    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(WAITING_TO_SHOW, waitingToShow);
        proto.write(PAUSED, paused);
        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return WINDOW_TOKEN;
    }

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix); pw.print("windows="); pw.println(mChildren);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                pw.print(" hasVisible="); pw.print(hasVisible);
        if (waitingToShow) {
            pw.print(" waitingToShow=true");
        }
        pw.println();
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

    int getWindowLayerFromType() {
        return mWmService.mPolicy.getWindowLayerFromTypeLw(windowType, mOwnerCanManageAppTokens);
    }
}
