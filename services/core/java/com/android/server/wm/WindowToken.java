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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowContainerChildProto.WINDOW_TOKEN;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowTokenProto.HASH_CODE;
import static com.android.server.wm.WindowTokenProto.PAUSED;
import static com.android.server.wm.WindowTokenProto.WAITING_TO_SHOW;
import static com.android.server.wm.WindowTokenProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.window.WindowContext;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

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

    /** The actual token */
    final IBinder token;

    /** The type of window this token is for, as per {@link WindowManager.LayoutParams} */
    final int windowType;

    /**
     * Options that will be used to determine which {@link RootDisplayArea} this window should be
     * attached to.
     */
    @Nullable
    final Bundle mOptions;

    /** {@code true} if this holds the rounded corner overlay */
    final boolean mRoundedCornerOverlay;

    /**
     * Set if this token was explicitly added by a client, so should persist (not be removed)
     * when all windows are removed.
     */
    boolean mPersistOnEmpty;

    // For printing.
    String stringName;

    // Is key dispatching paused for this token?
    boolean paused = false;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    /** The owner has {@link android.Manifest.permission#MANAGE_APP_TOKENS} */
    final boolean mOwnerCanManageAppTokens;

    private FixedRotationTransformState mFixedRotationTransformState;
    private SurfaceControl mFixedRotationTransformLeash;

    /**
     * When set to {@code true}, this window token is created from {@link WindowContext}
     */
    private final boolean mFromClientToken;

    /** Have we told the window clients to show themselves? */
    private boolean mClientVisible;

    /**
     * Used to fix the transform of the token to be rotated to a rotation different than it's
     * display. The window frames and surfaces corresponding to this token will be layouted and
     * rotated by the given rotated display info, frames and insets.
     */
    private static class FixedRotationTransformState {
        final DisplayInfo mDisplayInfo;
        final DisplayFrames mDisplayFrames;
        final Configuration mRotatedOverrideConfiguration;
        final SeamlessRotator mRotator;
        /**
         * The tokens that share the same transform. Their end time of transform are the same. The
         * list should at least contain the token who creates this state.
         */
        final ArrayList<WindowToken> mAssociatedTokens = new ArrayList<>(3);
        final ArrayList<WindowContainer<?>> mRotatedContainers = new ArrayList<>(3);
        boolean mIsTransforming = true;

        FixedRotationTransformState(DisplayInfo rotatedDisplayInfo,
                DisplayFrames rotatedDisplayFrames, Configuration rotatedConfig,
                int currentRotation) {
            mDisplayInfo = rotatedDisplayInfo;
            mDisplayFrames = rotatedDisplayFrames;
            mRotatedOverrideConfiguration = rotatedConfig;
            // This will use unrotate as rotate, so the new and old rotation are inverted.
            mRotator = new SeamlessRotator(rotatedDisplayInfo.rotation, currentRotation,
                    rotatedDisplayInfo, true /* applyFixedTransformationHint */);
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
                // If the window is detached (no parent), its surface may have been released.
                if (c.getParent() != null) {
                    mRotator.finish(c.getPendingTransaction(), c);
                }
            }
        }

        /** The state may not only be used by self. Make sure to leave the influence by others. */
        void disassociate(WindowToken token) {
            mAssociatedTokens.remove(token);
            mRotatedContainers.remove(token);
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

    protected WindowToken(WindowManagerService service, IBinder _token, int type,
            boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens) {
        this(service, _token, type, persistOnEmpty, dc, ownerCanManageAppTokens,
                false /* roundedCornerOverlay */, false /* fromClientToken */, null /* options */);
    }

    protected WindowToken(WindowManagerService service, IBinder _token, int type,
            boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens,
            boolean roundedCornerOverlay, boolean fromClientToken, @Nullable Bundle options) {
        super(service);
        token = _token;
        windowType = type;
        mOptions = options;
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
            if (i > mChildren.size()) {
                // It's possible for removeIfPossible to delete siblings (for example if it is a
                // starting window, it will perform operations on the ActivityRecord).
                i = mChildren.size();
            }
        }
    }

    /** Starts exit animation or hides windows if needed. It is only used for non-activity token. */
    void setExiting(boolean animateExit) {
        if (isEmpty()) {
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
        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            changed |= win.onSetAppExiting(animateExit);
        }

        if (changed) {
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /*updateInputWindows*/);
        }
    }

    /**
     * @return The scale for applications running in compatibility mode. Multiply the size in the
     *         application by this scale will be the size in the screen.
     */
    float getCompatScale() {
        return mDisplayContent.mCompatibleScreenScale;
    }

    /**
     * @return {@code true} if this window token has bounds for size compatibility mode.
     */
    boolean hasSizeCompatBounds() {
        return false;
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
        // This token is created from WindowContext and the client requests to addView now, create a
        // surface for this token.
        if (mSurfaceControl == null) {
            createSurfaceControl(true /* force */);

            // Layers could have been assigned before the surface was created, update them again
            reassignLayer(getSyncTransaction());
        }
        if (!mChildren.contains(win)) {
            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "Adding %s to %s", win, this);
            addChild(win, mWindowComparator);
            mWmService.mWindowsChanged = true;
            // TODO: Should we also be setting layout needed here and other places?
        }
    }

    @Override
    void createSurfaceControl(boolean force) {
        if (!mFromClientToken || force) {
            super.createSurfaceControl(force);
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
            if (w.hasWallpaper()) {
                return true;
            }
        }

        return false;
    }

    @Override
    void removeImmediately() {
        if (mDisplayContent != null) {
            mDisplayContent.removeWindowToken(token, true /* animateExit */);
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
    }

    @Override
    void assignLayer(SurfaceControl.Transaction t, int layer) {
        if (mRoundedCornerOverlay) {
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

    boolean isClientVisible() {
        return mClientVisible;
    }

    void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible) {
            return;
        }
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "setClientVisible: %s clientVisible=%b Callers=%s", this, clientVisible,
                Debug.getCallers(5));
        mClientVisible = clientVisible;
        sendAppVisibilityToClients();
    }

    boolean hasFixedRotationTransform() {
        return mFixedRotationTransformState != null;
    }

    /** Returns {@code true} if the given token shares the same transform. */
    boolean hasFixedRotationTransform(WindowToken token) {
        if (mFixedRotationTransformState == null || token == null) {
            return false;
        }
        return this == token || mFixedRotationTransformState == token.mFixedRotationTransformState;
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

    Rect getFixedRotationTransformMaxBounds() {
        return isFixedRotationTransforming()
                ? mFixedRotationTransformState.mRotatedOverrideConfiguration.windowConfiguration
                .getMaxBounds()
                : null;
    }

    Rect getFixedRotationTransformDisplayBounds() {
        return isFixedRotationTransforming()
                ? mFixedRotationTransformState.mRotatedOverrideConfiguration.windowConfiguration
                        .getBounds()
                : null;
    }

    InsetsState getFixedRotationTransformInsetsState() {
        return isFixedRotationTransforming()
                ? mFixedRotationTransformState.mDisplayFrames.mInsetsState
                : null;
    }

    /** Applies the rotated layout environment to this token in the simulated rotated display. */
    void applyFixedRotationTransform(DisplayInfo info, DisplayFrames displayFrames,
            Configuration config) {
        if (mFixedRotationTransformState != null) {
            mFixedRotationTransformState.disassociate(this);
        }
        // TODO(b/233855302): Remove TaskFragment override if the DisplayContent uses the same
        //  bounds for screenLayout calculation.
        final Configuration overrideConfig = new Configuration(config);
        overrideConfig.screenLayout = TaskFragment.computeScreenLayoutOverride(
                overrideConfig.screenLayout, overrideConfig.screenWidthDp,
                overrideConfig.screenHeightDp);
        mFixedRotationTransformState = new FixedRotationTransformState(info, displayFrames,
                overrideConfig, mDisplayContent.getRotation());
        mFixedRotationTransformState.mAssociatedTokens.add(this);
        mDisplayContent.getDisplayPolicy().simulateLayoutDisplay(displayFrames);
        onFixedRotationStatePrepared();
    }

    /**
     * Reuses the {@link FixedRotationTransformState} (if any) from the other WindowToken to this
     * one. This takes the same effect as {@link #applyFixedRotationTransform}.
     */
    void linkFixedRotationTransform(WindowToken other) {
        final FixedRotationTransformState fixedRotationState = other.mFixedRotationTransformState;
        if (fixedRotationState == null || mFixedRotationTransformState == fixedRotationState) {
            return;
        }
        if (mFixedRotationTransformState != null) {
            mFixedRotationTransformState.disassociate(this);
        }
        mFixedRotationTransformState = fixedRotationState;
        fixedRotationState.mAssociatedTokens.add(this);
        onFixedRotationStatePrepared();
    }

    /**
     * Makes the rotated states take effect for this window container and its client process.
     * This should only be called when {@link #mFixedRotationTransformState} is non-null.
     */
    private void onFixedRotationStatePrepared() {
        // Resolve the rotated configuration.
        onConfigurationChanged(getParent().getConfiguration());
        final ActivityRecord r = asActivityRecord();
        if (r != null && r.hasProcess()) {
            // The application needs to be configured as in a rotated environment for compatibility.
            // This registration will send the rotated configuration to its process.
            r.app.registerActivityConfigurationListener(r);
        }
    }

    /**
     * Return {@code true} if one of the associated activity is still animating. Otherwise,
     * return {@code false}.
     */
    boolean hasAnimatingFixedRotationTransition() {
        if (mFixedRotationTransformState == null) {
            return false;
        }

        for (int i = mFixedRotationTransformState.mAssociatedTokens.size() - 1; i >= 0; i--) {
            final ActivityRecord r =
                    mFixedRotationTransformState.mAssociatedTokens.get(i).asActivityRecord();
            if (r != null && r.isInTransition()) {
                return true;
            }
        }
        return false;
    }

    void finishFixedRotationTransform() {
        finishFixedRotationTransform(null /* applyDisplayRotation */);
    }

    /**
     * Finishes the transform and apply display rotation if the action is given. If the display will
     * not rotate, the transformed containers are restored to their original states.
     */
    void finishFixedRotationTransform(Runnable applyDisplayRotation) {
        final FixedRotationTransformState state = mFixedRotationTransformState;
        if (state == null) {
            return;
        }
        if (!mTransitionController.isShellTransitionsEnabled()) {
            state.resetTransform();
        } else {
            // Remove all the leashes
            for (int i = state.mAssociatedTokens.size() - 1; i >= 0; --i) {
                state.mAssociatedTokens.get(i).removeFixedRotationLeash();
            }
        }
        // Clear the flag so if the display will be updated to the same orientation, the transform
        // won't take effect.
        state.mIsTransforming = false;
        if (applyDisplayRotation != null) {
            applyDisplayRotation.run();
        }
        // The state is cleared at the end, because it is used to indicate that other windows can
        // use seamless rotation when applying rotation to display.
        for (int i = state.mAssociatedTokens.size() - 1; i >= 0; i--) {
            final WindowToken token = state.mAssociatedTokens.get(i);
            token.mFixedRotationTransformState = null;
            if (applyDisplayRotation == null) {
                // Notify cancellation because the display does not change rotation.
                token.cancelFixedRotationTransform();
            }
        }
    }

    /** Restores the changes that applies to this container. */
    private void cancelFixedRotationTransform() {
        final WindowContainer<?> parent = getParent();
        if (parent == null) {
            // The window may be detached or detaching.
            return;
        }
        if (mTransitionController.isShellTransitionsEnabled()
                && asActivityRecord() != null && isVisible()) {
            // Trigger an activity level rotation transition.
            mTransitionController.requestTransitionIfNeeded(WindowManager.TRANSIT_CHANGE, this);
            mTransitionController.setReady(this);
        }
        final int originalRotation = getWindowConfiguration().getRotation();
        onConfigurationChanged(parent.getConfiguration());
        onCancelFixedRotationTransform(originalRotation);
    }

    /**
     * Gets or creates a leash which can be treated as if this window is not-rotated. This is
     * used to adapt mismatched-rotation surfaces into code that expects all windows to share
     * the same rotation.
     */
    @Nullable
    SurfaceControl getOrCreateFixedRotationLeash(@NonNull SurfaceControl.Transaction t) {
        if (!mTransitionController.isShellTransitionsEnabled()) return null;
        final int rotation = getRelativeDisplayRotation();
        if (rotation == Surface.ROTATION_0) return mFixedRotationTransformLeash;
        if (mFixedRotationTransformLeash != null) return mFixedRotationTransformLeash;

        final SurfaceControl leash = makeSurface().setContainerLayer()
                .setParent(getParentSurfaceControl())
                .setName(getSurfaceControl() + " - rotation-leash")
                .setHidden(false)
                .setCallsite("WindowToken.getOrCreateFixedRotationLeash")
                .build();
        t.setPosition(leash, mLastSurfacePosition.x, mLastSurfacePosition.y);
        t.reparent(getSurfaceControl(), leash);
        mFixedRotationTransformLeash = leash;
        updateSurfaceRotation(t, rotation, mFixedRotationTransformLeash);
        return mFixedRotationTransformLeash;
    }

    /**
     * @return the leash which represents this window as if it was non-rotated. Will be null if
     *         there isn't one.
     */
    @Nullable
    SurfaceControl getFixedRotationLeash() {
        return mFixedRotationTransformLeash;
    }

    void removeFixedRotationLeash() {
        if (mFixedRotationTransformLeash == null) return;
        final SurfaceControl.Transaction t = getSyncTransaction();
        t.reparent(getSurfaceControl(), getParentSurfaceControl());
        t.remove(mFixedRotationTransformLeash);
        mFixedRotationTransformLeash = null;
    }

    /**
     * It is called when the window is using fixed rotation transform, and before display applies
     * the same rotation, the rotation change for display is canceled, e.g. the orientation from
     * sensor is updated to previous direction.
     */
    void onCancelFixedRotationTransform(int originalDisplayRotation) {
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
    void updateSurfacePosition(SurfaceControl.Transaction t) {
        super.updateSurfacePosition(t);
        if (!mTransitionController.isShellTransitionsEnabled() && isFixedRotationTransforming()) {
            final ActivityRecord r = asActivityRecord();
            final Task rootTask = r != null ? r.getRootTask() : null;
            // Don't transform the activity in PiP because the PiP task organizer will handle it.
            if (rootTask == null || !rootTask.inPinnedWindowingMode()) {
                // The window is laid out in a simulated rotated display but the real display hasn't
                // rotated, so here transforms its surface to fit in the real display.
                mFixedRotationTransformState.transform(this);
            }
        }
    }

    @Override
    protected void updateSurfaceRotation(SurfaceControl.Transaction t,
            @Surface.Rotation int deltaRotation, SurfaceControl positionLeash) {
        final ActivityRecord r = asActivityRecord();
        if (r != null) {
            final Task rootTask = r.getRootTask();
            // Don't transform the activity exiting PiP because the PiP task organizer will handle
            // it.
            if (rootTask != null && mTransitionController.getWindowingModeAtStart(rootTask)
                    == WINDOWING_MODE_PINNED) {
                return;
            }
        }
        super.updateSurfaceRotation(t, deltaRotation, positionLeash);
    }

    @Override
    void resetSurfacePositionForAnimationLeash(SurfaceControl.Transaction t) {
        // Keep the transformed position to animate because the surface will show in different
        // rotation than the animator of leash.
        if (!isFixedRotationTransforming()) {
            super.resetSurfacePositionForAnimationLeash(t);
        }
    }

    @Override
    boolean prepareSync() {
        if (mDisplayContent != null && mDisplayContent.isRotationChanging()
                && AsyncRotationController.canBeAsync(this)) {
            return false;
        }
        return super.prepareSync();
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
        if (waitingToShow) {
            pw.print(" waitingToShow=true");
        }
        pw.println();
        if (hasFixedRotationTransform()) {
            pw.print(prefix);
            pw.print("fixedRotationConfig=");
            pw.println(mFixedRotationTransformState.mRotatedOverrideConfiguration);
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            stringName = "WindowToken{" + Integer.toHexString(System.identityHashCode(this))
                    + " type=" + windowType + " " + token + "}";
        }
        return stringName;
    }

    @Override
    String getName() {
        return toString();
    }

    @Override
    WindowToken asWindowToken() {
        return this;
    }

    /**
     * Return whether windows from this token can layer above the
     * system bars, or in other words extend outside of the "Decor Frame"
     */
    boolean canLayerAboveSystemBars() {
        int layer = getWindowLayerFromType();
        int navLayer = mWmService.mPolicy.getWindowLayerFromTypeLw(TYPE_NAVIGATION_BAR,
                mOwnerCanManageAppTokens);
        return mOwnerCanManageAppTokens && (layer > navLayer);
    }

    int getWindowLayerFromType() {
        return mWmService.mPolicy.getWindowLayerFromTypeLw(windowType, mOwnerCanManageAppTokens,
                mRoundedCornerOverlay);
    }

    boolean isFromClient() {
        return mFromClientToken;
    }

    /** @see WindowState#freezeInsetsState() */
    void setInsetsFrozen(boolean freeze) {
        forAllWindows(w -> {
            if (w.mToken == this) {
                if (freeze) {
                    w.freezeInsetsState();
                } else {
                    w.clearFrozenInsetsState();
                }
            }
        },  true /* traverseTopToBottom */);
    }

    @Override
    @WindowType int getWindowType() {
        return windowType;
    }

    static class Builder {
        private final WindowManagerService mService;
        private final IBinder mToken;
        @WindowType
        private final int mType;

        private boolean mPersistOnEmpty;
        private DisplayContent mDisplayContent;
        private boolean mOwnerCanManageAppTokens;
        private boolean mRoundedCornerOverlay;
        private boolean mFromClientToken;
        @Nullable
        private Bundle mOptions;

        Builder(WindowManagerService service, IBinder token, int type) {
            mService = service;
            mToken = token;
            mType = type;
        }

        /** @see WindowToken#mPersistOnEmpty */
        Builder setPersistOnEmpty(boolean persistOnEmpty) {
            mPersistOnEmpty = persistOnEmpty;
            return this;
        }

        /** Sets the {@link DisplayContent} to be associated. */
        Builder setDisplayContent(DisplayContent dc) {
            mDisplayContent = dc;
            return this;
        }

        /** @see WindowToken#mOwnerCanManageAppTokens */
        Builder setOwnerCanManageAppTokens(boolean ownerCanManageAppTokens) {
            mOwnerCanManageAppTokens = ownerCanManageAppTokens;
            return this;
        }

        /** @see WindowToken#mRoundedCornerOverlay */
        Builder setRoundedCornerOverlay(boolean roundedCornerOverlay) {
            mRoundedCornerOverlay = roundedCornerOverlay;
            return this;
        }

        /** @see WindowToken#mFromClientToken */
        Builder setFromClientToken(boolean fromClientToken) {
            mFromClientToken = fromClientToken;
            return this;
        }

        /** @see WindowToken#mOptions */
        Builder setOptions(Bundle options) {
            mOptions = options;
            return this;
        }

        WindowToken build() {
            return new WindowToken(mService, mToken, mType, mPersistOnEmpty, mDisplayContent,
                    mOwnerCanManageAppTokens, mRoundedCornerOverlay, mFromClientToken, mOptions);
        }
    }
}
