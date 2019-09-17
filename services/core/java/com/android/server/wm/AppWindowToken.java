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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_UNSET;
import static android.view.WindowManager.TRANSIT_WALLPAPER_OPEN;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.AppWindowTokenProto.ALL_DRAWN;
import static com.android.server.wm.AppWindowTokenProto.APP_STOPPED;
import static com.android.server.wm.AppWindowTokenProto.CLIENT_HIDDEN;
import static com.android.server.wm.AppWindowTokenProto.DEFER_HIDING_CLIENT;
import static com.android.server.wm.AppWindowTokenProto.FILLS_PARENT;
import static com.android.server.wm.AppWindowTokenProto.FROZEN_BOUNDS;
import static com.android.server.wm.AppWindowTokenProto.HIDDEN_REQUESTED;
import static com.android.server.wm.AppWindowTokenProto.HIDDEN_SET_FROM_TRANSFERRED_STARTING_WINDOW;
import static com.android.server.wm.AppWindowTokenProto.IS_REALLY_ANIMATING;
import static com.android.server.wm.AppWindowTokenProto.IS_WAITING_FOR_TRANSITION_START;
import static com.android.server.wm.AppWindowTokenProto.LAST_ALL_DRAWN;
import static com.android.server.wm.AppWindowTokenProto.LAST_SURFACE_SHOWING;
import static com.android.server.wm.AppWindowTokenProto.NAME;
import static com.android.server.wm.AppWindowTokenProto.NUM_DRAWN_WINDOWS;
import static com.android.server.wm.AppWindowTokenProto.NUM_INTERESTING_WINDOWS;
import static com.android.server.wm.AppWindowTokenProto.REMOVED;
import static com.android.server.wm.AppWindowTokenProto.REPORTED_DRAWN;
import static com.android.server.wm.AppWindowTokenProto.REPORTED_VISIBLE;
import static com.android.server.wm.AppWindowTokenProto.STARTING_DISPLAYED;
import static com.android.server.wm.AppWindowTokenProto.STARTING_MOVED;
import static com.android.server.wm.AppWindowTokenProto.STARTING_WINDOW;
import static com.android.server.wm.AppWindowTokenProto.THUMBNAIL;
import static com.android.server.wm.AppWindowTokenProto.WINDOW_TOKEN;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_ACTIVITY_DRAWN;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.logWithStack;
import static com.android.server.wm.WindowState.LEGACY_POLICY_VISIBILITY;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;

import android.annotation.CallSuper;
import android.annotation.Size;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.IApplicationToken;
import android.view.InputApplicationHandle;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.AttributeCache;
import com.android.server.LocalServices;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;
import com.android.server.wm.RemoteAnimationController.RemoteAnimationRecord;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

class AppTokenList extends ArrayList<AppWindowToken> {
}

/**
 * Version of WindowToken that is specifically for a particular application (or
 * really activity) that is displaying windows.
 */
class AppWindowToken extends WindowToken implements WindowManagerService.AppFreezeListener,
        ConfigurationContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowToken" : TAG_WM;

    /**
     * Value to increment the z-layer when boosting a layer during animations. BOOST in l33tsp34k.
     */
    @VisibleForTesting static final int Z_BOOST_BASE = 800570000;

    // Non-null only for application tokens.
    final IApplicationToken appToken;
    final ComponentName mActivityComponent;
    final boolean mVoiceInteraction;

    /**
     * The activity is opaque and fills the entire space of this task.
     * @see WindowContainer#fillsParent()
     */
    private boolean mOccludesParent;
    boolean mShowForAllUsers;
    int mTargetSdk;

    // Flag set while reparenting to prevent actions normally triggered by an individual parent
    // change.
    private boolean mReparenting;

    // True if we are current in the process of removing this app token from the display
    private boolean mRemovingFromDisplay = false;

    // The input dispatching timeout for this application token in nanoseconds.
    long mInputDispatchingTimeoutNanos;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    private long mLastTransactionSequence = Long.MIN_VALUE;
    private int mNumInterestingWindows;
    private int mNumDrawnWindows;
    boolean inPendingTransaction;
    boolean allDrawn;
    private boolean mLastAllDrawn;
    private boolean mUseTransferredAnimation;

    // Set to true when this app creates a surface while in the middle of an animation. In that
    // case do not clear allDrawn until the animation completes.
    boolean deferClearAllDrawn;

    // Is this window's surface needed?  This is almost like hidden, except
    // it will sometimes be true a little earlier: when the token has
    // been shown, but is still waiting for its app transition to execute
    // before making its windows shown.
    boolean hiddenRequested;

    // Have we told the window clients to hide themselves?
    private boolean mClientHidden;

    // If true we will defer setting mClientHidden to true and reporting to the client that it is
    // hidden.
    boolean mDeferHidingClient;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    // Last drawn state we reported to the app token.
    private boolean reportedDrawn;

    // Set to true when the token has been removed from the window mgr.
    boolean removed;

    // Information about an application starting window if displayed.
    // Note: these are de-referenced before the starting window animates away.
    StartingData mStartingData;
    WindowState startingWindow;
    StartingSurface startingSurface;
    boolean startingDisplayed;
    boolean startingMoved;

    // True if the hidden state of this token was forced to false due to a transferred starting
    // window.
    private boolean mHiddenSetFromTransferredStartingWindow;
    boolean firstWindowDrawn;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults =
            new WindowState.UpdateReportedVisibilityResults();

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    // TODO: Have a WindowContainer state for tracking exiting/deferred removal.
    boolean mIsExiting;

    boolean mLaunchTaskBehind;
    boolean mEnteringAnimation;

    private boolean mAlwaysFocusable;

    boolean mAppStopped;
    int mRotationAnimationHint;
    private int mPendingRelaunchCount;

    private boolean mLastContainsShowWhenLockedWindow;
    private boolean mLastContainsDismissKeyguardWindow;

    ArrayDeque<Rect> mFrozenBounds = new ArrayDeque<>();
    ArrayDeque<Configuration> mFrozenMergedConfig = new ArrayDeque<>();

    /**
     * The scale to fit at least one side of the activity to its parent. If the activity uses
     * 1920x1080, and the actually size on the screen is 960x540, then the scale is 0.5.
     */
    private float mSizeCompatScale = 1f;
    /**
     * The bounds in global coordinates for activity in size compatibility mode.
     * @see ActivityRecord#inSizeCompatMode
     */
    private Rect mSizeCompatBounds;

    private boolean mDisablePreviewScreenshots;

    private Task mLastParent;

    // TODO: Remove after unification
    ActivityRecord mActivityRecord;

    /**
     * @see #currentLaunchCanTurnScreenOn()
     */
    private boolean mCurrentLaunchCanTurnScreenOn = true;

    /**
     * If we are running an animation, this determines the transition type. Must be one of
     * AppTransition.TRANSIT_* constants.
     */
    private int mTransit;

    /**
     * If we are running an animation, this determines the flags during this animation. Must be a
     * bitwise combination of AppTransition.TRANSIT_FLAG_* constants.
     */
    private int mTransitFlags;

    /** Whether our surface was set to be showing in the last call to {@link #prepareSurfaces} */
    private boolean mLastSurfaceShowing = true;

    /**
     * This gets used during some open/close transitions as well as during a change transition
     * where it represents the starting-state snapshot.
     */
    private AppWindowThumbnail mThumbnail;
    private final Rect mTransitStartRect = new Rect();

    /**
     * This leash is used to "freeze" the app surface in place after the state change, but before
     * the animation is ready to start.
     */
    private SurfaceControl mTransitChangeLeash = null;

    /** Have we been asked to have this token keep the screen frozen? */
    private boolean mFreezingScreen;

    /** Whether this token should be boosted at the top of all app window tokens. */
    @VisibleForTesting boolean mNeedsZBoost;
    private Letterbox mLetterbox;

    private final Point mTmpPoint = new Point();
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpPrevBounds = new Rect();
    private RemoteAnimationDefinition mRemoteAnimationDefinition;
    private AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry;

    /**
     * A flag to determine if this AWT is in the process of closing or entering PIP. This is needed
     * to help AWT know that the app is in the process of closing but hasn't yet started closing on
     * the WM side.
     */
    private boolean mWillCloseOrEnterPip;

    /** Layer used to constrain the animation to a token's stack bounds. */
    SurfaceControl mAnimationBoundsLayer;

    /** Whether this token needs to create mAnimationBoundsLayer for cropping animations. */
    boolean mNeedsAnimationBoundsLayer;

    private static final int STARTING_WINDOW_TYPE_NONE = 0;
    private static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    private static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;

    private AppSaturationInfo mLastAppSaturationInfo;

    private final ColorDisplayService.ColorTransformController mColorTransformController =
            (matrix, translation) -> mWmService.mH.post(() -> {
                synchronized (mWmService.mGlobalLock) {
                    if (mLastAppSaturationInfo == null) {
                        mLastAppSaturationInfo = new AppSaturationInfo();
                    }

                    mLastAppSaturationInfo.setSaturation(matrix, translation);
                    updateColorTransform();
                }
            });

    AppWindowToken(WindowManagerService service, IApplicationToken token,
            ComponentName activityComponent, boolean voiceInteraction, DisplayContent dc,
            long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers,
            int targetSdk, int orientation, int rotationAnimationHint,
            boolean launchTaskBehind, boolean alwaysFocusable,
            ActivityRecord activityRecord) {
        this(service, token, activityComponent, voiceInteraction, dc, fullscreen);
        // TODO: remove after unification
        mActivityRecord = activityRecord;
        mActivityRecord.registerConfigurationChangeListener(this);
        mInputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
        mShowForAllUsers = showForAllUsers;
        mTargetSdk = targetSdk;
        mOrientation = orientation;
        mLaunchTaskBehind = launchTaskBehind;
        mAlwaysFocusable = alwaysFocusable;
        mRotationAnimationHint = rotationAnimationHint;

        // Application tokens start out hidden.
        setHidden(true);
        hiddenRequested = true;

        ColorDisplayService.ColorDisplayServiceInternal cds = LocalServices.getService(
                ColorDisplayService.ColorDisplayServiceInternal.class);
        cds.attachColorTransformController(activityRecord.packageName, activityRecord.mUserId,
                new WeakReference<>(mColorTransformController));
    }

    AppWindowToken(WindowManagerService service, IApplicationToken token,
            ComponentName activityComponent, boolean voiceInteraction, DisplayContent dc,
            boolean fillsParent) {
        super(service, token != null ? token.asBinder() : null, TYPE_APPLICATION, true, dc,
                false /* ownerCanManageAppTokens */);
        appToken = token;
        mActivityComponent = activityComponent;
        mVoiceInteraction = voiceInteraction;
        mOccludesParent = fillsParent;
        mInputApplicationHandle = new InputApplicationHandle(appToken.asBinder());
    }

    void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        firstWindowDrawn = true;

        // We now have a good window to show, remove dead placeholders
        removeDeadWindows();

        if (startingWindow != null) {
            if (DEBUG_STARTING_WINDOW || DEBUG_ANIM) Slog.v(TAG, "Finish starting "
                    + win.mToken + ": first real window is shown, no animation");
            // If this initial window is animating, stop it -- we will do an animation to reveal
            // it from behind the starting window, so there is no need for it to also be doing its
            // own stuff.
            win.cancelAnimation();
        }
        removeStartingWindow();
        updateReportedVisibilityLocked();
    }

    void updateReportedVisibilityLocked() {
        if (appToken == null) {
            return;
        }

        if (DEBUG_VISIBILITY) Slog.v(TAG, "Update reported visibility: " + this);
        final int count = mChildren.size();

        mReportedVisibilityResults.reset();

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            win.updateReportedVisibility(mReportedVisibilityResults);
        }

        int numInteresting = mReportedVisibilityResults.numInteresting;
        int numVisible = mReportedVisibilityResults.numVisible;
        int numDrawn = mReportedVisibilityResults.numDrawn;
        boolean nowGone = mReportedVisibilityResults.nowGone;

        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting && !isHidden();
        if (!nowGone) {
            // If the app is not yet gone, then it can only become visible/drawn.
            if (!nowDrawn) {
                nowDrawn = reportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = reportedVisible;
            }
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "VIS " + this + ": interesting="
                + numInteresting + " visible=" + numVisible);
        if (nowDrawn != reportedDrawn) {
            if (mActivityRecord != null) {
                mActivityRecord.onWindowsDrawn(nowDrawn, SystemClock.uptimeMillis());
            }
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Visibility changed in " + this + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            if (mActivityRecord != null) {
                if (nowVisible) {
                    onWindowsVisible();
                } else {
                    onWindowsGone();
                }
            }
        }
    }

    private void onWindowsGone() {
        if (mActivityRecord == null) {
            return;
        }
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_WM, "Reporting gone in " + mActivityRecord.appToken);
        }
        mActivityRecord.onWindowsGone();
    }

    private void onWindowsVisible() {
        if (mActivityRecord == null) {
            return;
        }
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_WM, "Reporting visible in " + mActivityRecord.appToken);
        }
        mActivityRecord.onWindowsVisible();
    }

    boolean isClientHidden() {
        return mClientHidden;
    }

    void setClientHidden(boolean hideClient) {
        if (mClientHidden == hideClient || (hideClient && mDeferHidingClient)) {
            return;
        }
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "setClientHidden: " + this
                + " clientHidden=" + hideClient + " Callers=" + Debug.getCallers(5));
        mClientHidden = hideClient;
        sendAppVisibilityToClients();
    }

    void setVisibility(boolean visible, boolean deferHidingClient) {
        final AppTransition appTransition = getDisplayContent().mAppTransition;

        // Don't set visibility to false if we were already not visible. This prevents WM from
        // adding the app to the closing app list which doesn't make sense for something that is
        // already not visible. However, set visibility to true even if we are already visible.
        // This makes sure the app is added to the opening apps list so that the right
        // transition can be selected.
        // TODO: Probably a good idea to separate the concept of opening/closing apps from the
        // concept of setting visibility...
        if (!visible && hiddenRequested) {

            if (!deferHidingClient && mDeferHidingClient) {
                // We previously deferred telling the client to hide itself when visibility was
                // initially set to false. Now we would like it to hide, so go ahead and set it.
                mDeferHidingClient = deferHidingClient;
                setClientHidden(true);
            }
            return;
        }

        if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) {
            Slog.v(TAG_WM, "setAppVisibility("
                    + appToken + ", visible=" + visible + "): " + appTransition
                    + " hidden=" + isHidden() + " hiddenRequested="
                    + hiddenRequested + " Callers=" + Debug.getCallers(6));
        }

        final DisplayContent displayContent = getDisplayContent();
        displayContent.mOpeningApps.remove(this);
        displayContent.mClosingApps.remove(this);
        if (isInChangeTransition()) {
            clearChangeLeash(getPendingTransaction(), true /* cancel */);
        }
        displayContent.mChangingApps.remove(this);
        waitingToShow = false;
        hiddenRequested = !visible;
        mDeferHidingClient = deferHidingClient;

        if (!visible) {
            // If the app is dead while it was visible, we kept its dead window on screen.
            // Now that the app is going invisible, we can remove it. It will be restarted
            // if made visible again.
            removeDeadWindows();
        } else {
            if (!appTransition.isTransitionSet()
                    && appTransition.isReady()) {
                // Add the app mOpeningApps if transition is unset but ready. This means
                // we're doing a screen freeze, and the unfreeze will wait for all opening
                // apps to be ready.
                displayContent.mOpeningApps.add(this);
            }
            startingMoved = false;
            // If the token is currently hidden (should be the common case), or has been
            // stopped, then we need to set up to wait for its windows to be ready.
            if (isHidden() || mAppStopped) {
                clearAllDrawn();

                // If the app was already visible, don't reset the waitingToShow state.
                if (isHidden()) {
                    waitingToShow = true;

                    // Let's reset the draw state in order to prevent the starting window to be
                    // immediately dismissed when the app still has the surface.
                    forAllWindows(w -> {
                        if (w.mWinAnimator.mDrawState == HAS_DRAWN) {
                            w.mWinAnimator.resetDrawState();

                            // Force add to mResizingWindows, so that we are guaranteed to get
                            // another reportDrawn callback.
                            w.resetLastContentInsets();
                        }
                    },  true /* traverseTopToBottom */);
                }
            }

            // In the case where we are making an app visible but holding off for a transition,
            // we still need to tell the client to make its windows visible so they get drawn.
            // Otherwise, we will wait on performing the transition until all windows have been
            // drawn, they never will be, and we are sad.
            setClientHidden(false);

            requestUpdateWallpaperIfNeeded();

            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "No longer Stopped: " + this);
            mAppStopped = false;

            transferStartingWindowFromHiddenAboveTokenIfNeeded();
        }

        // If we are preparing an app transition, then delay changing
        // the visibility of this token until we execute that transition.
        if (okToAnimate() && appTransition.isTransitionSet()) {
            inPendingTransaction = true;
            if (visible) {
                displayContent.mOpeningApps.add(this);
                mEnteringAnimation = true;
            } else {
                displayContent.mClosingApps.add(this);
                mEnteringAnimation = false;
            }
            if (appTransition.getAppTransition() == TRANSIT_TASK_OPEN_BEHIND) {
                // We're launchingBehind, add the launching activity to mOpeningApps.
                final WindowState win = getDisplayContent().findFocusedWindow();
                if (win != null) {
                    final AppWindowToken focusedToken = win.mAppToken;
                    if (focusedToken != null) {
                        if (DEBUG_APP_TRANSITIONS) {
                            Slog.d(TAG_WM, "TRANSIT_TASK_OPEN_BEHIND, "
                                    + " adding " + focusedToken + " to mOpeningApps");
                        }
                        // Force animation to be loaded.
                        displayContent.mOpeningApps.add(focusedToken);
                    }
                }
            }
            // Changes in opening apps and closing apps may cause orientation change.
            reportDescendantOrientationChangeIfNeeded();
            return;
        }

        commitVisibility(null, visible, TRANSIT_UNSET, true, mVoiceInteraction);
        updateReportedVisibilityLocked();
    }

    boolean commitVisibility(WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {

        boolean delayed = false;
        inPendingTransaction = false;
        // Reset the state of mHiddenSetFromTransferredStartingWindow since visibility is actually
        // been set by the app now.
        mHiddenSetFromTransferredStartingWindow = false;

        // Allow for state changes and animation to be applied if:
        // * token is transitioning visibility state
        // * or the token was marked as hidden and is exiting before we had a chance to play the
        // transition animation
        // * or this is an opening app and windows are being replaced
        // * or the token is the opening app and visible while opening task behind existing one.
        final DisplayContent displayContent = getDisplayContent();
        boolean visibilityChanged = false;
        if (isHidden() == visible || (isHidden() && mIsExiting)
                || (visible && waitingForReplacement())
                || (visible && displayContent.mOpeningApps.contains(this)
                && displayContent.mAppTransition.getAppTransition() == TRANSIT_TASK_OPEN_BEHIND)) {
            final AccessibilityController accessibilityController =
                    mWmService.mAccessibilityController;
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM,
                    "Changing app " + this + " hidden=" + isHidden() + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != WindowManager.TRANSIT_UNSET) {
                if (mUseTransferredAnimation) {
                    runningAppAnimation = isReallyAnimating();
                } else if (applyAnimationLocked(lp, transit, visible, isVoiceInteraction)) {
                    runningAppAnimation = true;
                }
                delayed = runningAppAnimation;
                final WindowState window = findMainWindow();
                if (window != null && accessibilityController != null) {
                    accessibilityController.onAppWindowTransitionLocked(window, transit);
                }
                changed = true;
            }

            final int windowsCount = mChildren.size();
            for (int i = 0; i < windowsCount; i++) {
                final WindowState win = mChildren.get(i);
                changed |= win.onAppVisibilityChanged(visible, runningAppAnimation);
            }

            setHidden(!visible);
            hiddenRequested = !visible;
            visibilityChanged = true;
            if (!visible) {
                stopFreezingScreen(true, true);
            } else {
                // If we are being set visible, and the starting window is not yet displayed,
                // then make sure it doesn't get displayed.
                if (startingWindow != null && !startingWindow.isDrawnLw()) {
                    startingWindow.clearPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
                    startingWindow.mLegacyPolicyVisibilityAfterAnim = false;
                }

                // We are becoming visible, so better freeze the screen with the windows that are
                // getting visible so we also wait for them.
                forAllWindows(mWmService::makeWindowFreezingScreenIfNeededLocked, true);
            }

            if (DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG_WM, "commitVisibility: " + this
                        + ": hidden=" + isHidden() + " hiddenRequested=" + hiddenRequested);
            }

            if (changed) {
                displayContent.getInputMonitor().setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    mWmService.mWindowPlacerLocked.performSurfacePlacement();
                }
                displayContent.getInputMonitor().updateInputWindowsLw(false /*force*/);
            }
        }
        mUseTransferredAnimation = false;

        if (isReallyAnimating()) {
            delayed = true;
        } else {

            // We aren't animating anything, but exiting windows rely on the animation finished
            // callback being called in case the AppWindowToken was pretending to be animating,
            // which we might have done because we were in closing/opening apps list.
            onAnimationFinished();
        }

        for (int i = mChildren.size() - 1; i >= 0 && !delayed; i--) {
            if ((mChildren.get(i)).isSelfOrChildAnimating()) {
                delayed = true;
            }
        }

        if (visibilityChanged) {
            if (visible && !delayed) {
                // The token was made immediately visible, there will be no entrance animation.
                // We need to inform the client the enter animation was finished.
                mEnteringAnimation = true;
                mWmService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(
                        token);
            }

            // If we're becoming visible, immediately change client visibility as well. there seem
            // to be some edge cases where we change our visibility but client visibility never gets
            // updated.
            // If we're becoming invisible, update the client visibility if we are not running an
            // animation. Otherwise, we'll update client visibility in onAnimationFinished.
            if (visible || !isReallyAnimating()) {
                setClientHidden(!visible);
            }

            if (!displayContent.mClosingApps.contains(this)
                    && !displayContent.mOpeningApps.contains(this)) {
                // The token is not closing nor opening, so even if there is an animation set, that
                // doesn't mean that it goes through the normal app transition cycle so we have
                // to inform the docked controller about visibility change.
                // TODO(multi-display): notify docked divider on all displays where visibility was
                // affected.
                displayContent.getDockedDividerController().notifyAppVisibilityChanged();

                // Take the screenshot before possibly hiding the WSA, otherwise the screenshot
                // will not be taken.
                mWmService.mTaskSnapshotController.notifyAppVisibilityChanged(this, visible);
            }

            // If we are hidden but there is no delay needed we immediately
            // apply the Surface transaction so that the ActivityManager
            // can have some guarantee on the Surface state following
            // setting the visibility. This captures cases like dismissing
            // the docked or pinned stack where there is no app transition.
            //
            // In the case of a "Null" animation, there will be
            // no animation but there will still be a transition set.
            // We still need to delay hiding the surface such that it
            // can be synchronized with showing the next surface in the transition.
            if (isHidden() && !delayed && !displayContent.mAppTransition.isTransitionSet()) {
                SurfaceControl.openTransaction();
                for (int i = mChildren.size() - 1; i >= 0; i--) {
                    mChildren.get(i).mWinAnimator.hide("immediately hidden");
                }
                SurfaceControl.closeTransaction();
            }

            // Visibility changes may cause orientation request change.
            reportDescendantOrientationChangeIfNeeded();
        }

        return delayed;
    }

    private void reportDescendantOrientationChangeIfNeeded() {
        // Orientation request is exposed only when we're visible. Therefore visibility change
        // will change requested orientation. Notify upward the hierarchy ladder to adjust
        // configuration. This is important to cases where activities with incompatible
        // orientations launch, or user goes back from an activity of bi-orientation to an
        // activity with specified orientation.
        if (mActivityRecord.getRequestedConfigurationOrientation() == getConfiguration().orientation
                || getOrientationIgnoreVisibility() == SCREEN_ORIENTATION_UNSET) {
            return;
        }

        final IBinder freezeToken =
                mActivityRecord.mayFreezeScreenLocked(mActivityRecord.app)
                        ? mActivityRecord.appToken : null;
        onDescendantOrientationChanged(freezeToken, mActivityRecord);
    }

    /**
     * @return The to top most child window for which {@link LayoutParams#isFullscreen()} returns
     *         true.
     */
    WindowState getTopFullscreenWindow() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win != null && win.mAttrs.isFullscreen()) {
                return win;
            }
        }
        return null;
    }

    WindowState findMainWindow() {
        return findMainWindow(true);
    }

    /**
     * Finds the main window that either has type base application or application starting if
     * requested.
     *
     * @param includeStartingApp Allow to search application-starting windows to also be returned.
     * @return The main window of type base application or application starting if requested.
     */
    WindowState findMainWindow(boolean includeStartingApp) {
        WindowState candidate = null;
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState win = mChildren.get(j);
            final int type = win.mAttrs.type;
            // No need to loop through child window as base application and starting types can't be
            // child windows.
            if (type == TYPE_BASE_APPLICATION
                    || (includeStartingApp && type == TYPE_APPLICATION_STARTING)) {
                // In cases where there are multiple windows, we prefer the non-exiting window. This
                // happens for example when replacing windows during an activity relaunch. When
                // constructing the animation, we want the new window, not the exiting one.
                if (win.mAnimatingExit) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    boolean windowsAreFocusable() {
        if (mTargetSdk < Build.VERSION_CODES.Q) {
            final int pid = mActivityRecord != null
                    ? (mActivityRecord.app != null ? mActivityRecord.app.getPid() : 0) : 0;
            final AppWindowToken topFocusedAppOfMyProcess =
                    mWmService.mRoot.mTopFocusedAppByProcess.get(pid);
            if (topFocusedAppOfMyProcess != null && topFocusedAppOfMyProcess != this) {
                // For the apps below Q, there can be only one app which has the focused window per
                // process, because legacy apps may not be ready for a multi-focus system.
                return false;
            }
        }
        return getWindowConfiguration().canReceiveKeys() || mAlwaysFocusable;
    }

    @Override
    boolean isVisible() {
        // If the app token isn't hidden then it is considered visible and there is no need to check
        // its children windows to see if they are visible.
        return !isHidden();
    }

    @Override
    void removeImmediately() {
        onRemovedFromDisplay();
        if (mActivityRecord != null) {
            mActivityRecord.unregisterConfigurationChangeListener(this);
        }
        super.removeImmediately();
    }

    @Override
    void removeIfPossible() {
        mIsExiting = false;
        removeAllWindowsIfPossible();
        removeImmediately();
    }

    @Override
    boolean checkCompleteDeferredRemoval() {
        if (mIsExiting) {
            removeIfPossible();
        }
        return super.checkCompleteDeferredRemoval();
    }

    void onRemovedFromDisplay() {
        if (mRemovingFromDisplay) {
            return;
        }
        mRemovingFromDisplay = true;

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Removing app token: " + this);

        boolean delayed = commitVisibility(null, false, TRANSIT_UNSET, true, mVoiceInteraction);

        getDisplayContent().mOpeningApps.remove(this);
        getDisplayContent().mChangingApps.remove(this);
        getDisplayContent().mUnknownAppVisibilityController.appRemovedOrHidden(this);
        mWmService.mTaskSnapshotController.onAppRemoved(this);
        waitingToShow = false;
        if (getDisplayContent().mClosingApps.contains(this)) {
            delayed = true;
        } else if (getDisplayContent().mAppTransition.isTransitionSet()) {
            getDisplayContent().mClosingApps.add(this);
            delayed = true;
        }

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Removing app " + this + " delayed=" + delayed
                + " animation=" + getAnimation() + " animating=" + isSelfAnimating());

        if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM, "removeAppToken: "
                + this + " delayed=" + delayed + " Callers=" + Debug.getCallers(4));

        if (mStartingData != null) {
            removeStartingWindow();
        }

        // If this window was animating, then we need to ensure that the app transition notifies
        // that animations have completed in DisplayContent.handleAnimatingStoppedAndTransition(),
        // so add to that list now
        if (isSelfAnimating()) {
            getDisplayContent().mNoAnimationNotifyOnTransitionFinished.add(token);
        }

        final TaskStack stack = getStack();
        if (delayed && !isEmpty()) {
            // set the token aside because it has an active animation to be finished
            if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM,
                    "removeAppToken make exiting: " + this);
            if (stack != null) {
                stack.mExitingAppTokens.add(this);
            }
            mIsExiting = true;
        } else {
            // Make sure there is no animation running on this token, so any windows associated
            // with it will be removed as soon as their animations are complete
            cancelAnimation();
            if (stack != null) {
                stack.mExitingAppTokens.remove(this);
            }
            removeIfPossible();
        }

        removed = true;
        stopFreezingScreen(true, true);

        final DisplayContent dc = getDisplayContent();
        if (dc.mFocusedApp == this) {
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Removing focused app token:" + this
                   + " displayId=" + dc.getDisplayId());
            dc.setFocusedApp(null);
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        }
        if (mLetterbox != null) {
            mLetterbox.destroy();
            mLetterbox = null;
        }

        if (!delayed) {
            updateReportedVisibilityLocked();
        }

        // Reset the last saved PiP snap fraction on removal.
        mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(mActivityComponent);

        mRemovingFromDisplay = false;
    }

    void clearAnimatingFlags() {
        boolean wallpaperMightChange = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            wallpaperMightChange |= win.clearAnimatingFlags();
        }
        if (wallpaperMightChange) {
            requestUpdateWallpaperIfNeeded();
        }
    }

    void destroySurfaces() {
        destroySurfaces(false /*cleanupOnResume*/);
    }

    /**
     * Destroy surfaces which have been marked as eligible by the animator, taking care to ensure
     * the client has finished with them.
     *
     * @param cleanupOnResume whether this is done when app is resumed without fully stopped. If
     * set to true, destroy only surfaces of removed windows, and clear relevant flags of the
     * others so that they are ready to be reused. If set to false (common case), destroy all
     * surfaces that's eligible, if the app is already stopped.
     */
    private void destroySurfaces(boolean cleanupOnResume) {
        boolean destroyedSomething = false;

        // Copying to a different list as multiple children can be removed.
        final ArrayList<WindowState> children = new ArrayList<>(mChildren);
        for (int i = children.size() - 1; i >= 0; i--) {
            final WindowState win = children.get(i);
            destroyedSomething |= win.destroySurface(cleanupOnResume, mAppStopped);
        }
        if (destroyedSomething) {
            final DisplayContent dc = getDisplayContent();
            dc.assignWindowLayers(true /*setLayoutNeeded*/);
            updateLetterboxSurface(null);
        }
    }

    /**
     * Notify that the app is now resumed, and it was not stopped before, perform a clean
     * up of the surfaces
     */
    void notifyAppResumed(boolean wasStopped) {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppResumed: wasStopped=" + wasStopped
                + " " + this);
        mAppStopped = false;
        // Allow the window to turn the screen on once the app is resumed again.
        setCurrentLaunchCanTurnScreenOn(true);
        if (!wasStopped) {
            destroySurfaces(true /*cleanupOnResume*/);
        }
    }

    /**
     * Notify that the app has stopped, and it is okay to destroy any surfaces which were
     * keeping alive in case they were still being used.
     */
    void notifyAppStopped() {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppStopped: " + this);
        mAppStopped = true;
        // Reset the last saved PiP snap fraction on app stop.
        mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(this);
        destroySurfaces();
        // Remove any starting window that was added for this app if they are still around.
        removeStartingWindow();
    }

    void clearAllDrawn() {
        allDrawn = false;
        deferClearAllDrawn = false;
    }

    Task getTask() {
        return (Task) getParent();
    }

    TaskStack getStack() {
        final Task task = getTask();
        if (task != null) {
            return task.mStack;
        } else {
            return null;
        }
    }

    @Override
    void onParentChanged() {
        super.onParentChanged();

        final Task task = getTask();

        // When the associated task is {@code null}, the {@link AppWindowToken} can no longer
        // access visual elements like the {@link DisplayContent}. We must remove any associations
        // such as animations.
        if (!mReparenting) {
            if (task == null) {
                // It is possible we have been marked as a closing app earlier. We must remove ourselves
                // from this list so we do not participate in any future animations.
                getDisplayContent().mClosingApps.remove(this);
            } else if (mLastParent != null && mLastParent.mStack != null) {
                task.mStack.mExitingAppTokens.remove(this);
            }
        }
        final TaskStack stack = getStack();

        // If we reparent, make sure to remove ourselves from the old animation registry.
        if (mAnimatingAppWindowTokenRegistry != null) {
            mAnimatingAppWindowTokenRegistry.notifyFinished(this);
        }
        mAnimatingAppWindowTokenRegistry = stack != null
                ? stack.getAnimatingAppWindowTokenRegistry()
                : null;

        mLastParent = task;

        updateColorTransform();
    }

    void postWindowRemoveStartingWindowCleanup(WindowState win) {
        // TODO: Something smells about the code below...Is there a better way?
        if (startingWindow == win) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Notify removed startingWindow " + win);
            removeStartingWindow();
        } else if (mChildren.size() == 0) {
            // If this is the last window and we had requested a starting transition window,
            // well there is no point now.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Nulling last startingData");
            mStartingData = null;
            if (mHiddenSetFromTransferredStartingWindow) {
                // We set the hidden state to false for the token from a transferred starting window.
                // We now reset it back to true since the starting window was the last window in the
                // token.
                setHidden(true);
            }
        } else if (mChildren.size() == 1 && startingSurface != null && !isRelaunching()) {
            // If this is the last window except for a starting transition window,
            // we need to get rid of the starting transition.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Last window, removing starting window "
                    + win);
            removeStartingWindow();
        }
    }

    void removeDeadWindows() {
        for (int winNdx = mChildren.size() - 1; winNdx >= 0; --winNdx) {
            WindowState win = mChildren.get(winNdx);
            if (win.mAppDied) {
                if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.w(TAG,
                        "removeDeadWindows: " + win);
                // Set mDestroying, we don't want any animation or delayed removal here.
                win.mDestroying = true;
                // Also removes child windows.
                win.removeIfPossible();
            }
        }
    }

    boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            // No need to loop through child windows as the answer should be the same as that of the
            // parent window.
            if (!(mChildren.get(i)).mAppDied) {
                return true;
            }
        }
        return false;
    }

    void setWillReplaceWindows(boolean animate) {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM,
                "Marking app token " + this + " with replacing windows.");

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceWindow(animate);
        }
    }

    void setWillReplaceChildWindows() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "Marking app token " + this
                + " with replacing child windows.");
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceChildWindows();
        }
    }

    void clearWillReplaceWindows() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM,
                "Resetting app token " + this + " of replacing window marks.");

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.clearWillReplaceWindow();
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.requestUpdateWallpaperIfNeeded();
        }
    }

    boolean isRelaunching() {
        return mPendingRelaunchCount > 0;
    }

    boolean shouldFreezeBounds() {
        final Task task = getTask();

        // For freeform windows, we can't freeze the bounds at the moment because this would make
        // the resizing unresponsive.
        if (task == null || task.inFreeformWindowingMode()) {
            return false;
        }

        // We freeze the bounds while drag resizing to deal with the time between
        // the divider/drag handle being released, and the handling it's new
        // configuration. If we are relaunched outside of the drag resizing state,
        // we need to be careful not to do this.
        return getTask().isDragResizing();
    }

    void startRelaunching() {
        if (shouldFreezeBounds()) {
            freezeBounds();
        }

        // In the process of tearing down before relaunching, the app will
        // try and clean up it's child surfaces. We need to prevent this from
        // happening, so we sever the children, transfering their ownership
        // from the client it-self to the parent surface (owned by us).
        detachChildren();

        mPendingRelaunchCount++;
    }

    void detachChildren() {
        SurfaceControl.openTransaction();
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.mWinAnimator.detachChildren();
        }
        SurfaceControl.closeTransaction();
    }

    void finishRelaunching() {
        unfreezeBounds();

        if (mPendingRelaunchCount > 0) {
            mPendingRelaunchCount--;
        } else {
            // Update keyguard flags upon finishing relaunch.
            checkKeyguardFlagsChanged();
        }
    }

    void clearRelaunching() {
        if (mPendingRelaunchCount == 0) {
            return;
        }
        unfreezeBounds();
        mPendingRelaunchCount = 0;
    }

    /**
     * Returns true if the new child window we are adding to this token is considered greater than
     * the existing child window in this token in terms of z-order.
     */
    @Override
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        final int type1 = newWindow.mAttrs.type;
        final int type2 = existingWindow.mAttrs.type;

        // Base application windows should be z-ordered BELOW all other windows in the app token.
        if (type1 == TYPE_BASE_APPLICATION && type2 != TYPE_BASE_APPLICATION) {
            return false;
        } else if (type1 != TYPE_BASE_APPLICATION && type2 == TYPE_BASE_APPLICATION) {
            return true;
        }

        // Starting windows should be z-ordered ABOVE all other windows in the app token.
        if (type1 == TYPE_APPLICATION_STARTING && type2 != TYPE_APPLICATION_STARTING) {
            return true;
        } else if (type1 != TYPE_APPLICATION_STARTING && type2 == TYPE_APPLICATION_STARTING) {
            return false;
        }

        // Otherwise the new window is greater than the existing window.
        return true;
    }

    /**
     * @return {@code true} if starting window is in app's hierarchy.
     */
    boolean hasStartingWindow() {
        if (startingDisplayed || mStartingData != null) {
            return true;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (getChildAt(i).mAttrs.type == TYPE_APPLICATION_STARTING) {
                return true;
            }
        }
        return false;
    }

    @Override
    void addWindow(WindowState w) {
        super.addWindow(w);

        boolean gotReplacementWindow = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState candidate = mChildren.get(i);
            gotReplacementWindow |= candidate.setReplacementWindowIfNeeded(w);
        }

        // if we got a replacement window, reset the timeout to give drawing more time
        if (gotReplacementWindow) {
            mWmService.scheduleWindowReplacementTimeouts(this);
        }
        checkKeyguardFlagsChanged();
    }

    @Override
    void removeChild(WindowState child) {
        if (!mChildren.contains(child)) {
            // This can be true when testing.
            return;
        }
        super.removeChild(child);
        checkKeyguardFlagsChanged();
        updateLetterboxSurface(child);
    }

    private boolean waitingForReplacement() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState candidate = mChildren.get(i);
            if (candidate.waitingForReplacement()) {
                return true;
            }
        }
        return false;
    }

    void onWindowReplacementTimeout() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            (mChildren.get(i)).onWindowReplacementTimeout();
        }
    }

    void reparent(Task task, int position) {
        if (DEBUG_ADD_REMOVE) {
            Slog.i(TAG_WM, "reparent: moving app token=" + this
                    + " to task=" + task.mTaskId + " at " + position);
        }
        if (task == null) {
            throw new IllegalArgumentException("reparent: could not find task");
        }
        final Task currentTask = getTask();
        if (task == currentTask) {
            throw new IllegalArgumentException(
                    "window token=" + this + " already child of task=" + currentTask);
        }

        if (currentTask.mStack != task.mStack) {
            throw new IllegalArgumentException(
                    "window token=" + this + " current task=" + currentTask
                        + " belongs to a different stack than " + task);
        }

        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "reParentWindowToken: removing window token=" + this
                + " from task=" + currentTask);
        final DisplayContent prevDisplayContent = getDisplayContent();

        mReparenting = true;

        getParent().removeChild(this);
        task.addChild(this, position);

        mReparenting = false;

        // Relayout display(s).
        final DisplayContent displayContent = task.getDisplayContent();
        displayContent.setLayoutNeeded();
        if (prevDisplayContent != displayContent) {
            onDisplayChanged(displayContent);
            prevDisplayContent.setLayoutNeeded();
        }
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        DisplayContent prevDc = mDisplayContent;
        super.onDisplayChanged(dc);
        if (prevDc == null || prevDc == mDisplayContent) {
            return;
        }

        if (prevDc.mOpeningApps.remove(this)) {
            // Transfer opening transition to new display.
            mDisplayContent.mOpeningApps.add(this);
            mDisplayContent.prepareAppTransition(prevDc.mAppTransition.getAppTransition(), true);
            mDisplayContent.executeAppTransition();
        }

        if (prevDc.mChangingApps.remove(this)) {
            // This gets called *after* the AppWindowToken has been reparented to the new display.
            // That reparenting resulted in this window changing modes (eg. FREEFORM -> FULLSCREEN),
            // so this token is now "frozen" while waiting for the animation to start on prevDc
            // (which will be cancelled since the window is no-longer a child). However, since this
            // is no longer a child of prevDc, this won't be notified of the cancelled animation,
            // so we need to cancel the change transition here.
            clearChangeLeash(getPendingTransaction(), true /* cancel */);
        }
        prevDc.mClosingApps.remove(this);

        if (prevDc.mFocusedApp == this) {
            prevDc.setFocusedApp(null);
            final TaskStack stack = dc.getTopStack();
            if (stack != null) {
                final Task task = stack.getTopChild();
                if (task != null && task.getTopChild() == this) {
                    dc.setFocusedApp(this);
                }
            }
        }

        if (mLetterbox != null) {
            mLetterbox.onMovedToDisplay(mDisplayContent.getDisplayId());
        }
    }

    /**
     * Freezes the task bounds. The size of this task reported the app will be fixed to the bounds
     * freezed by {@link Task#prepareFreezingBounds} until {@link #unfreezeBounds} gets called, even
     * if they change in the meantime. If the bounds are already frozen, the bounds will be frozen
     * with a queue.
     */
    private void freezeBounds() {
        final Task task = getTask();
        mFrozenBounds.offer(new Rect(task.mPreparedFrozenBounds));

        if (task.mPreparedFrozenMergedConfig.equals(Configuration.EMPTY)) {
            // We didn't call prepareFreezingBounds on the task, so use the current value.
            mFrozenMergedConfig.offer(new Configuration(task.getConfiguration()));
        } else {
            mFrozenMergedConfig.offer(new Configuration(task.mPreparedFrozenMergedConfig));
        }
        // Calling unset() to make it equal to Configuration.EMPTY.
        task.mPreparedFrozenMergedConfig.unset();
    }

    /**
     * Unfreezes the previously frozen bounds. See {@link #freezeBounds}.
     */
    private void unfreezeBounds() {
        if (mFrozenBounds.isEmpty()) {
            return;
        }
        mFrozenBounds.remove();
        if (!mFrozenMergedConfig.isEmpty()) {
            mFrozenMergedConfig.remove();
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            win.onUnfreezeBounds();
        }
        mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!mChildren.isEmpty()) {
            final DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (DEBUG_LAYOUT_REPEATS) {
                mWmService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
            }
        }
    }

    void removeReplacedWindowIfNeeded(WindowState replacement) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win.removeReplacedWindowIfNeeded(replacement)) {
                return;
            }
        }
    }

    void startFreezingScreen() {
        if (DEBUG_ORIENTATION) logWithStack(TAG, "Set freezing of " + appToken + ": hidden="
                + isHidden() + " freezing=" + mFreezingScreen + " hiddenRequested="
                + hiddenRequested);
        if (!hiddenRequested) {
            if (!mFreezingScreen) {
                mFreezingScreen = true;
                mWmService.registerAppFreezeListener(this);
                mWmService.mAppsFreezingScreen++;
                if (mWmService.mAppsFreezingScreen == 1) {
                    mWmService.startFreezingDisplayLocked(0, 0, getDisplayContent());
                    mWmService.mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                    mWmService.mH.sendEmptyMessageDelayed(H.APP_FREEZE_TIMEOUT, 2000);
                }
            }
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final WindowState w = mChildren.get(i);
                w.onStartFreezingScreen();
            }
        }
    }

    void stopFreezingScreen(boolean unfreezeSurfaceNow, boolean force) {
        if (!mFreezingScreen) {
            return;
        }
        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Clear freezing of " + this + " force=" + force);
        final int count = mChildren.size();
        boolean unfrozeWindows = false;
        for (int i = 0; i < count; i++) {
            final WindowState w = mChildren.get(i);
            unfrozeWindows |= w.onStopFreezingScreen();
        }
        if (force || unfrozeWindows) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "No longer freezing: " + this);
            mFreezingScreen = false;
            mWmService.unregisterAppFreezeListener(this);
            mWmService.mAppsFreezingScreen--;
            mWmService.mLastFinishedFreezeSource = this;
        }
        if (unfreezeSurfaceNow) {
            if (unfrozeWindows) {
                mWmService.mWindowPlacerLocked.performSurfacePlacement();
            }
            mWmService.stopFreezingDisplayLocked();
        }
    }

    @Override
    public void onAppFreezeTimeout() {
        Slog.w(TAG_WM, "Force clearing freeze: " + this);
        stopFreezingScreen(true, true);
    }

    /**
     * Tries to transfer the starting window from a token that's above ourselves in the task but
     * not visible anymore. This is a common scenario apps use: Trampoline activity T start main
     * activity M in the same task. Now, when reopening the task, T starts on top of M but then
     * immediately finishes after, so we have to transfer T to M.
     */
    void transferStartingWindowFromHiddenAboveTokenIfNeeded() {
        final Task task = getTask();
        for (int i = task.mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken fromToken = task.mChildren.get(i);
            if (fromToken == this) {
                return;
            }
            if (fromToken.hiddenRequested && transferStartingWindow(fromToken.token)) {
                return;
            }
        }
    }

    boolean transferStartingWindow(IBinder transferFrom) {
        final AppWindowToken fromToken = getDisplayContent().getAppWindowToken(transferFrom);
        if (fromToken == null) {
            return false;
        }

        final WindowState tStartingWindow = fromToken.startingWindow;
        if (tStartingWindow != null && fromToken.startingSurface != null) {
            // In this case, the starting icon has already been displayed, so start
            // letting windows get shown immediately without any more transitions.
            getDisplayContent().mSkipAppTransitionAnimation = true;

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Moving existing starting " + tStartingWindow
                    + " from " + fromToken + " to " + this);

            final long origId = Binder.clearCallingIdentity();
            try {
                // Transfer the starting window over to the new token.
                mStartingData = fromToken.mStartingData;
                startingSurface = fromToken.startingSurface;
                startingDisplayed = fromToken.startingDisplayed;
                fromToken.startingDisplayed = false;
                startingWindow = tStartingWindow;
                reportedVisible = fromToken.reportedVisible;
                fromToken.mStartingData = null;
                fromToken.startingSurface = null;
                fromToken.startingWindow = null;
                fromToken.startingMoved = true;
                tStartingWindow.mToken = this;
                tStartingWindow.mAppToken = this;

                if (DEBUG_ADD_REMOVE || DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                        "Removing starting " + tStartingWindow + " from " + fromToken);
                fromToken.removeChild(tStartingWindow);
                fromToken.postWindowRemoveStartingWindowCleanup(tStartingWindow);
                fromToken.mHiddenSetFromTransferredStartingWindow = false;
                addWindow(tStartingWindow);

                // Propagate other interesting state between the tokens. If the old token is displayed,
                // we should immediately force the new one to be displayed. If it is animating, we need
                // to move that animation to the new one.
                if (fromToken.allDrawn) {
                    allDrawn = true;
                    deferClearAllDrawn = fromToken.deferClearAllDrawn;
                }
                if (fromToken.firstWindowDrawn) {
                    firstWindowDrawn = true;
                }
                if (!fromToken.isHidden()) {
                    setHidden(false);
                    hiddenRequested = false;
                    mHiddenSetFromTransferredStartingWindow = true;
                }
                setClientHidden(fromToken.mClientHidden);

                transferAnimation(fromToken);

                // When transferring an animation, we no longer need to apply an animation to the
                // the token we transfer the animation over. Thus, set this flag to indicate we've
                // transferred the animation.
                mUseTransferredAnimation = true;

                mWmService.updateFocusedWindowLocked(
                        UPDATE_FOCUS_WILL_PLACE_SURFACES, true /*updateInputWindows*/);
                getDisplayContent().setLayoutNeeded();
                mWmService.mWindowPlacerLocked.performSurfacePlacement();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            return true;
        } else if (fromToken.mStartingData != null) {
            // The previous app was getting ready to show a
            // starting window, but hasn't yet done so.  Steal it!
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                    "Moving pending starting from " + fromToken + " to " + this);
            mStartingData = fromToken.mStartingData;
            fromToken.mStartingData = null;
            fromToken.startingMoved = true;
            scheduleAddStartingWindow();
            return true;
        }

        // TODO: Transfer thumbnail

        return false;
    }

    boolean isLastWindow(WindowState win) {
        return mChildren.size() == 1 && mChildren.get(0) == win;
    }

    @Override
    void onAppTransitionDone() {
        sendingToBottom = false;
    }

    /**
     * We override because this class doesn't want its children affecting its reported orientation
     * in anyway.
     */
    @Override
    int getOrientation(int candidate) {
        if (candidate == SCREEN_ORIENTATION_BEHIND) {
            // Allow app to specify orientation regardless of its visibility state if the current
            // candidate want us to use orientation behind. I.e. the visible app on-top of this one
            // wants us to use the orientation of the app behind it.
            return mOrientation;
        }

        // The {@link AppWindowToken} should only specify an orientation when it is not closing or
        // going to the bottom. Allowing closing {@link AppWindowToken} to participate can lead to
        // an Activity in another task being started in the wrong orientation during the transition.
        if (!(sendingToBottom || getDisplayContent().mClosingApps.contains(this))
                && (isVisible() || getDisplayContent().mOpeningApps.contains(this))) {
            return mOrientation;
        }

        return SCREEN_ORIENTATION_UNSET;
    }

    /** Returns the app's preferred orientation regardless of its currently visibility state. */
    int getOrientationIgnoreVisibility() {
        return mOrientation;
    }

    /** @return {@code true} if the compatibility bounds is taking effect. */
    boolean inSizeCompatMode() {
        return mSizeCompatBounds != null;
    }

    @Override
    float getSizeCompatScale() {
        return inSizeCompatMode() ? mSizeCompatScale : super.getSizeCompatScale();
    }

    /**
     * @return Non-empty bounds if the activity has override bounds.
     * @see ActivityRecord#resolveOverrideConfiguration(Configuration)
     */
    Rect getResolvedOverrideBounds() {
        // Get bounds from resolved override configuration because it is computed with orientation.
        return getResolvedOverrideConfiguration().windowConfiguration.getBounds();
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        final int prevWinMode = getWindowingMode();
        mTmpPrevBounds.set(getBounds());
        super.onConfigurationChanged(newParentConfig);

        final Task task = getTask();
        final Rect overrideBounds = getResolvedOverrideBounds();
        if (task != null && !overrideBounds.isEmpty()
                // If the changes come from change-listener, the incoming parent configuration is
                // still the old one. Make sure their orientations are the same to reduce computing
                // the compatibility bounds for the intermediate state.
                && (task.mTaskRecord == null || task.mTaskRecord
                        .getConfiguration().orientation == newParentConfig.orientation)) {
            final Rect taskBounds = task.getBounds();
            // Since we only center the activity horizontally, if only the fixed height is smaller
            // than its container, the override bounds don't need to take effect.
            if ((overrideBounds.width() != taskBounds.width()
                    || overrideBounds.height() > taskBounds.height())) {
                calculateCompatBoundsTransformation(newParentConfig);
                updateSurfacePosition();
            } else if (mSizeCompatBounds != null) {
                mSizeCompatBounds = null;
                mSizeCompatScale = 1f;
                updateSurfacePosition();
            }
        }

        final int winMode = getWindowingMode();

        if (prevWinMode == winMode) {
            return;
        }

        if (prevWinMode != WINDOWING_MODE_UNDEFINED && winMode == WINDOWING_MODE_PINNED) {
            // Entering PiP from fullscreen, reset the snap fraction
            mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(this);
        } else if (prevWinMode == WINDOWING_MODE_PINNED && winMode != WINDOWING_MODE_UNDEFINED
                && !isHidden()) {
            // Leaving PiP to fullscreen, save the snap fraction based on the pre-animation bounds
            // for the next re-entry into PiP (assuming the activity is not hidden or destroyed)
            final TaskStack pinnedStack = mDisplayContent.getPinnedStack();
            if (pinnedStack != null) {
                final Rect stackBounds;
                if (pinnedStack.lastAnimatingBoundsWasToFullscreen()) {
                    // We are animating the bounds, use the pre-animation bounds to save the snap
                    // fraction
                    stackBounds = pinnedStack.mPreAnimationBounds;
                } else {
                    // We skip the animation if the fullscreen configuration is not compatible, so
                    // use the current bounds to calculate the saved snap fraction instead
                    // (see PinnedActivityStack.skipResizeAnimation())
                    stackBounds = mTmpRect;
                    pinnedStack.getBounds(stackBounds);
                }
                mDisplayContent.mPinnedStackControllerLocked.saveReentrySnapFraction(this,
                        stackBounds);
            }
        } else if (shouldStartChangeTransition(prevWinMode, winMode)) {
            initializeChangeTransition(mTmpPrevBounds);
        }
    }

    private boolean shouldStartChangeTransition(int prevWinMode, int newWinMode) {
        if (mWmService.mDisableTransitionAnimation
                || !isVisible()
                || getDisplayContent().mAppTransition.isTransitionSet()
                || getSurfaceControl() == null) {
            return false;
        }
        // Only do an animation into and out-of freeform mode for now. Other mode
        // transition animations are currently handled by system-ui.
        return (prevWinMode == WINDOWING_MODE_FREEFORM) != (newWinMode == WINDOWING_MODE_FREEFORM);
    }

    /**
     * Initializes a change transition. Because the app is visible already, there is a small period
     * of time where the user can see the app content/window update before the transition starts.
     * To prevent this, we immediately take a snapshot and place the app/snapshot into a leash which
     * "freezes" the location/crop until the transition starts.
     * <p>
     * Here's a walk-through of the process:
     * 1. Create a temporary leash ("interim-change-leash") and reparent the app to it.
     * 2. Set the temporary leash's position/crop to the current state.
     * 3. Create a snapshot and place that at the top of the leash to cover up content changes.
     * 4. Once the transition is ready, it will reparent the app to the animation leash.
     * 5. Detach the interim-change-leash.
     */
    private void initializeChangeTransition(Rect startBounds) {
        mDisplayContent.prepareAppTransition(TRANSIT_TASK_CHANGE_WINDOWING_MODE,
                false /* alwaysKeepCurrent */, 0, false /* forceOverride */);
        mDisplayContent.mChangingApps.add(this);
        mTransitStartRect.set(startBounds);

        final SurfaceControl.Builder builder = makeAnimationLeash()
                .setParent(getAnimationLeashParent())
                .setName(getSurfaceControl() + " - interim-change-leash");
        mTransitChangeLeash = builder.build();
        Transaction t = getPendingTransaction();
        t.setWindowCrop(mTransitChangeLeash, startBounds.width(), startBounds.height());
        t.setPosition(mTransitChangeLeash, startBounds.left, startBounds.top);
        t.show(mTransitChangeLeash);
        t.reparent(getSurfaceControl(), mTransitChangeLeash);
        onAnimationLeashCreated(t, mTransitChangeLeash);

        // Skip creating snapshot if this transition is controlled by a remote animator which
        // doesn't need it.
        ArraySet<Integer> activityTypes = new ArraySet<>();
        activityTypes.add(getActivityType());
        RemoteAnimationAdapter adapter =
                mDisplayContent.mAppTransitionController.getRemoteAnimationOverride(
                        this, TRANSIT_TASK_CHANGE_WINDOWING_MODE, activityTypes);
        if (adapter != null && !adapter.getChangeNeedsSnapshot()) {
            return;
        }

        Task task = getTask();
        if (mThumbnail == null && task != null && !hasCommittedReparentToAnimationLeash()) {
            SurfaceControl.ScreenshotGraphicBuffer snapshot =
                    mWmService.mTaskSnapshotController.createTaskSnapshot(
                            task, 1 /* scaleFraction */);
            if (snapshot != null) {
                mThumbnail = new AppWindowThumbnail(mWmService.mSurfaceFactory, t, this,
                        snapshot.getGraphicBuffer(), true /* relative */);
            }
        }
    }

    boolean isInChangeTransition() {
        return mTransitChangeLeash != null || AppTransition.isChangeTransit(mTransit);
    }

    @VisibleForTesting
    AppWindowThumbnail getThumbnail() {
        return mThumbnail;
    }

    /**
     * Calculates the scale and offset to horizontal center the size compatibility bounds into the
     * region which is available to application.
     */
    private void calculateCompatBoundsTransformation(Configuration newParentConfig) {
        final Rect parentAppBounds = newParentConfig.windowConfiguration.getAppBounds();
        final Rect parentBounds = newParentConfig.windowConfiguration.getBounds();
        final Rect viewportBounds = parentAppBounds != null ? parentAppBounds : parentBounds;
        final Rect appBounds = getWindowConfiguration().getAppBounds();
        final Rect contentBounds = appBounds != null ? appBounds : getResolvedOverrideBounds();
        final float contentW = contentBounds.width();
        final float contentH = contentBounds.height();
        final float viewportW = viewportBounds.width();
        final float viewportH = viewportBounds.height();
        // Only allow to scale down.
        mSizeCompatScale = (contentW <= viewportW && contentH <= viewportH)
                ? 1 : Math.min(viewportW / contentW, viewportH / contentH);
        final int offsetX = (int) ((viewportW - contentW * mSizeCompatScale + 1) * 0.5f)
                + viewportBounds.left;

        if (mSizeCompatBounds == null) {
            mSizeCompatBounds = new Rect();
        }
        mSizeCompatBounds.set(contentBounds);
        mSizeCompatBounds.offsetTo(0, 0);
        mSizeCompatBounds.scale(mSizeCompatScale);
        // Ensure to align the top with the parent.
        mSizeCompatBounds.top = parentBounds.top;
        // The decor inset is included in height.
        mSizeCompatBounds.bottom += viewportBounds.top;
        mSizeCompatBounds.left += offsetX;
        mSizeCompatBounds.right += offsetX;
    }

    @Override
    public Rect getBounds() {
        if (mSizeCompatBounds != null) {
            return mSizeCompatBounds;
        }
        return super.getBounds();
    }

    @Override
    public boolean matchParentBounds() {
        if (super.matchParentBounds()) {
            return true;
        }
        // An activity in size compatibility mode may have override bounds which equals to its
        // parent bounds, so the exact bounds should also be checked.
        final WindowContainer parent = getParent();
        return parent == null || parent.getBounds().equals(getResolvedOverrideBounds());
    }

    @Override
    void checkAppWindowsReadyToShow() {
        if (allDrawn == mLastAllDrawn) {
            return;
        }

        mLastAllDrawn = allDrawn;
        if (!allDrawn) {
            return;
        }

        // The token has now changed state to having all windows shown...  what to do, what to do?
        if (mFreezingScreen) {
            showAllWindowsLocked();
            stopFreezingScreen(false, true);
            if (DEBUG_ORIENTATION) Slog.i(TAG,
                    "Setting mOrientationChangeComplete=true because wtoken " + this
                    + " numInteresting=" + mNumInterestingWindows + " numDrawn=" + mNumDrawnWindows);
            // This will set mOrientationChangeComplete and cause a pass through layout.
            setAppLayoutChanges(FINISH_LAYOUT_REDO_WALLPAPER,
                    "checkAppWindowsReadyToShow: freezingScreen");
        } else {
            setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM, "checkAppWindowsReadyToShow");

            // We can now show all of the drawn windows!
            if (!getDisplayContent().mOpeningApps.contains(this) && canShowWindows()) {
                showAllWindowsLocked();
            }
        }
    }

    /**
     * Returns whether the drawn window states of this {@link AppWindowToken} has considered every
     * child {@link WindowState}. A child is considered if it has been passed into
     * {@link #updateDrawnWindowStates(WindowState)} after being added. This is used to determine
     * whether states, such as {@code allDrawn}, can be set, which relies on state variables such as
     * {@code mNumInterestingWindows}, which depend on all {@link WindowState}s being considered.
     *
     * @return {@code true} If all children have been considered, {@code false}.
     */
    private boolean allDrawnStatesConsidered() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState child = mChildren.get(i);
            if (child.mightAffectAllDrawn() && !child.getDrawnStateEvaluated()) {
                return false;
            }
        }
        return true;
    }

    /**
     *  Determines if the token has finished drawing. This should only be called from
     *  {@link DisplayContent#applySurfaceChangesTransaction}
     */
    void updateAllDrawn() {
        if (!allDrawn) {
            // Number of drawn windows can be less when a window is being relaunched, wait for
            // all windows to be launched and drawn for this token be considered all drawn.
            final int numInteresting = mNumInterestingWindows;

            // We must make sure that all present children have been considered (determined by
            // {@link #allDrawnStatesConsidered}) before evaluating whether everything has been
            // drawn.
            if (numInteresting > 0 && allDrawnStatesConsidered()
                    && mNumDrawnWindows >= numInteresting && !isRelaunching()) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawn: " + this
                        + " interesting=" + numInteresting + " drawn=" + mNumDrawnWindows);
                allDrawn = true;
                // Force an additional layout pass where
                // WindowStateAnimator#commitFinishDrawingLocked() will call performShowLocked().
                if (mDisplayContent != null) {
                    mDisplayContent.setLayoutNeeded();
                }
                mWmService.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();

                // Notify the pinned stack upon all windows drawn. If there was an animation in
                // progress then this signal will resume that animation.
                final TaskStack pinnedStack = mDisplayContent.getPinnedStack();
                if (pinnedStack != null) {
                    pinnedStack.onAllWindowsDrawn();
                }
            }
        }
    }

    boolean keyDispatchingTimedOut(String reason, int windowPid) {
        return mActivityRecord != null && mActivityRecord.keyDispatchingTimedOut(reason, windowPid);
    }

    /**
     * Updated this app token tracking states for interesting and drawn windows based on the window.
     *
     * @return Returns true if the input window is considered interesting and drawn while all the
     *         windows in this app token where not considered drawn as of the last pass.
     */
    boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true /*evaluated*/);

        if (DEBUG_STARTING_WINDOW_VERBOSE && w == startingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen()
                    + " allDrawn=" + allDrawn + " freezingScreen=" + mFreezingScreen);
        }

        if (allDrawn && !mFreezingScreen) {
            return false;
        }

        if (mLastTransactionSequence != mWmService.mTransactionSequence) {
            mLastTransactionSequence = mWmService.mTransactionSequence;
            mNumDrawnWindows = 0;
            startingDisplayed = false;

            // There is the main base application window, even if it is exiting, wait for it
            mNumInterestingWindows = findMainWindow(false /* includeStartingApp */) != null ? 1 : 0;
        }

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        boolean isInterestingAndDrawn = false;

        if (!allDrawn && w.mightAffectAllDrawn()) {
            if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawnLw()
                        + ", isAnimationSet=" + isSelfAnimating());
                if (!w.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceController
                            + " pv=" + w.isVisibleByPolicy()
                            + " mDrawState=" + winAnimator.drawStateToString()
                            + " ph=" + w.isParentWindowHidden() + " th=" + hiddenRequested
                            + " a=" + isSelfAnimating());
                }
            }

            if (w != startingWindow) {
                if (w.isInteresting()) {
                    // Add non-main window as interesting since the main app has already been added
                    if (findMainWindow(false /* includeStartingApp */) != w) {
                        mNumInterestingWindows++;
                    }
                    if (w.isDrawnLw()) {
                        mNumDrawnWindows++;

                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) Slog.v(TAG, "tokenMayBeDrawn: "
                                + this + " w=" + w + " numInteresting=" + mNumInterestingWindows
                                + " freezingScreen=" + mFreezingScreen
                                + " mAppFreezing=" + w.mAppFreezing);

                        isInterestingAndDrawn = true;
                    }
                }
            } else if (w.isDrawnLw()) {
                if (mActivityRecord != null) {
                    mActivityRecord.onStartingWindowDrawn(SystemClock.uptimeMillis());
                }
                startingDisplayed = true;
            }
        }

        return isInterestingAndDrawn;
    }

    void layoutLetterbox(WindowState winHint) {
        final WindowState w = findMainWindow();
        if (w == null || winHint != null && w != winHint) {
            return;
        }
        final boolean surfaceReady = w.isDrawnLw()  // Regular case
                || w.mWinAnimator.mSurfaceDestroyDeferred  // The preserved surface is still ready.
                || w.isDragResizeChanged();  // Waiting for relayoutWindow to call preserveSurface.
        final boolean needsLetterbox = surfaceReady && w.isLetterboxedAppWindow() && fillsParent();
        if (needsLetterbox) {
            if (mLetterbox == null) {
                mLetterbox = new Letterbox(() -> makeChildSurface(null),
                        mWmService.mTransactionFactory);
                mLetterbox.attachInput(w);
            }
            getPosition(mTmpPoint);
            // Get the bounds of the "space-to-fill". In multi-window mode, the task-level
            // represents this. In fullscreen-mode, the stack does (since the orientation letterbox
            // is also applied to the task).
            Rect spaceToFill = (inMultiWindowMode() || getStack() == null)
                    ? getTask().getDisplayedBounds() : getStack().getDisplayedBounds();
            mLetterbox.layout(spaceToFill, w.getFrameLw(), mTmpPoint);
        } else if (mLetterbox != null) {
            mLetterbox.hide();
        }
    }

    void updateLetterboxSurface(WindowState winHint) {
        final WindowState w = findMainWindow();
        if (w != winHint && winHint != null && w != null) {
            return;
        }
        layoutLetterbox(winHint);
        if (mLetterbox != null && mLetterbox.needsApplySurfaceChanges()) {
            mLetterbox.applySurfaceChanges(getPendingTransaction());
        }
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        // For legacy reasons we process the TaskStack.mExitingAppTokens first in DisplayContent
        // before the non-exiting app tokens. So, we skip the exiting app tokens here.
        // TODO: Investigate if we need to continue to do this or if we can just process them
        // in-order.
        if (mIsExiting && !waitingForReplacement()) {
            return false;
        }
        return forAllWindowsUnchecked(callback, traverseTopToBottom);
    }

    @Override
    void forAllAppWindows(Consumer<AppWindowToken> callback) {
        callback.accept(this);
    }

    boolean forAllWindowsUnchecked(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        return super.forAllWindows(callback, traverseTopToBottom);
    }

    @Override
    AppWindowToken asAppWindowToken() {
        // I am an app window token!
        return this;
    }

    boolean addStartingWindow(String pkg, int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags,
            IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents) {
        // If the display is frozen, we won't do anything until the actual window is
        // displayed so there is no reason to put in the starting window.
        if (!okToDisplay()) {
            return false;
        }

        if (mStartingData != null) {
            return false;
        }

        final WindowState mainWin = findMainWindow();
        if (mainWin != null && mainWin.mWinAnimator.getShown()) {
            // App already has a visible window...why would you want a starting window?
            return false;
        }

        final ActivityManager.TaskSnapshot snapshot =
                mWmService.mTaskSnapshotController.getSnapshot(
                        getTask().mTaskId, getTask().mUserId,
                        false /* restoreFromDisk */, false /* reducedResolution */);
        final int type = getStartingWindowType(newTask, taskSwitch, processRunning,
                allowTaskSnapshot, activityCreated, fromRecents, snapshot);

        if (type == STARTING_WINDOW_TYPE_SNAPSHOT) {
            return createSnapshot(snapshot);
        }

        // If this is a translucent window, then don't show a starting window -- the current
        // effect (a full-screen opaque starting window that fades away to the real contents
        // when it is ready) does not work for this.
        if (DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Checking theme of starting window: 0x" + Integer.toHexString(theme));
        }
        if (theme != 0) {
            AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                    com.android.internal.R.styleable.Window,
                    mWmService.mCurrentUserId);
            if (ent == null) {
                // Whoops!  App doesn't exist. Um. Okay. We'll just pretend like we didn't
                // see that.
                return false;
            }
            final boolean windowIsTranslucent = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsTranslucent, false);
            final boolean windowIsFloating = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsFloating, false);
            final boolean windowShowWallpaper = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowShowWallpaper, false);
            final boolean windowDisableStarting = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowDisablePreview, false);
            if (DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Translucent=" + windowIsTranslucent
                        + " Floating=" + windowIsFloating
                        + " ShowWallpaper=" + windowShowWallpaper);
            }
            if (windowIsTranslucent) {
                return false;
            }
            if (windowIsFloating || windowDisableStarting) {
                return false;
            }
            if (windowShowWallpaper) {
                if (getDisplayContent().mWallpaperController
                        .getWallpaperTarget() == null) {
                    // If this theme is requesting a wallpaper, and the wallpaper
                    // is not currently visible, then this effectively serves as
                    // an opaque window and our starting window transition animation
                    // can still work.  We just need to make sure the starting window
                    // is also showing the wallpaper.
                    windowFlags |= FLAG_SHOW_WALLPAPER;
                } else {
                    return false;
                }
            }
        }

        if (transferStartingWindow(transferFrom)) {
            return true;
        }

        // There is no existing starting window, and we don't want to create a splash screen, so
        // that's it!
        if (type != STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
            return false;
        }

        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating SplashScreenStartingData");
        mStartingData = new SplashScreenStartingData(mWmService, pkg,
                theme, compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                getMergedOverrideConfiguration());
        scheduleAddStartingWindow();
        return true;
    }


    private boolean createSnapshot(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating SnapshotStartingData");
        mStartingData = new SnapshotStartingData(mWmService, snapshot);
        scheduleAddStartingWindow();
        return true;
    }

    void scheduleAddStartingWindow() {
        // Note: we really want to do sendMessageAtFrontOfQueue() because we
        // want to process the message ASAP, before any other queued
        // messages.
        if (!mWmService.mAnimationHandler.hasCallbacks(mAddStartingWindow)) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Enqueueing ADD_STARTING");
            mWmService.mAnimationHandler.postAtFrontOfQueue(mAddStartingWindow);
        }
    }

    private final Runnable mAddStartingWindow = new Runnable() {

        @Override
        public void run() {
            // Can be accessed without holding the global lock
            final StartingData startingData;
            synchronized (mWmService.mGlobalLock) {
                // There can only be one adding request, silly caller!
                mWmService.mAnimationHandler.removeCallbacks(this);

                if (mStartingData == null) {
                    // Animation has been canceled... do nothing.
                    if (DEBUG_STARTING_WINDOW) {
                        Slog.v(TAG, "startingData was nulled out before handling"
                                + " mAddStartingWindow: " + AppWindowToken.this);
                    }
                    return;
                }
                startingData = mStartingData;
            }

            if (DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Add starting " + this + ": startingData=" + startingData);
            }

            WindowManagerPolicy.StartingSurface surface = null;
            try {
                surface = startingData.createStartingSurface(AppWindowToken.this);
            } catch (Exception e) {
                Slog.w(TAG, "Exception when adding starting window", e);
            }
            if (surface != null) {
                boolean abort = false;
                synchronized (mWmService.mGlobalLock) {
                    // If the window was successfully added, then
                    // we need to remove it.
                    if (removed || mStartingData == null) {
                        if (DEBUG_STARTING_WINDOW) {
                            Slog.v(TAG, "Aborted starting " + AppWindowToken.this
                                    + ": removed=" + removed + " startingData=" + mStartingData);
                        }
                        startingWindow = null;
                        mStartingData = null;
                        abort = true;
                    } else {
                        startingSurface = surface;
                    }
                    if (DEBUG_STARTING_WINDOW && !abort) {
                        Slog.v(TAG,
                                "Added starting " + AppWindowToken.this + ": startingWindow="
                                        + startingWindow + " startingView=" + startingSurface);
                    }
                }
                if (abort) {
                    surface.remove();
                }
            } else if (DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Surface returned was null: " + AppWindowToken.this);
            }
        }
    };

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents,
            ActivityManager.TaskSnapshot snapshot) {
        if (getDisplayContent().mAppTransition.getAppTransition()
                == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            // TODO(b/34099271): Remove this statement to add back the starting window and figure
            // out why it causes flickering, the starting window appears over the thumbnail while
            // the docked from recents transition occurs
            return STARTING_WINDOW_TYPE_NONE;
        } else if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
            return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else if (taskSwitch && allowTaskSnapshot) {
            if (mWmService.mLowRamTaskSnapshotsAndRecents) {
                // For low RAM devices, we use the splash screen starting window instead of the
                // task snapshot starting window.
                return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
            return snapshot == null ? STARTING_WINDOW_TYPE_NONE
                    : snapshotOrientationSameAsTask(snapshot) || fromRecents
                            ? STARTING_WINDOW_TYPE_SNAPSHOT : STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else {
            return STARTING_WINDOW_TYPE_NONE;
        }
    }


    private boolean snapshotOrientationSameAsTask(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return getTask().getConfiguration().orientation == snapshot.getOrientation();
    }

    void removeStartingWindow() {
        if (startingWindow == null) {
            if (mStartingData != null) {
                // Starting window has not been added yet, but it is scheduled to be added.
                // Go ahead and cancel the request.
                if (DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG_WM, "Clearing startingData for token=" + this);
                }
                mStartingData = null;
            }
            return;
        }

        final WindowManagerPolicy.StartingSurface surface;
        if (mStartingData != null) {
            surface = startingSurface;
            mStartingData = null;
            startingSurface = null;
            startingWindow = null;
            startingDisplayed = false;
            if (surface == null) {
                if (DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG_WM, "startingWindow was set but startingSurface==null, couldn't "
                            + "remove");
                }
                return;
            }
        } else {
            if (DEBUG_STARTING_WINDOW) {
                Slog.v(TAG_WM, "Tried to remove starting window but startingWindow was null:"
                        + this);
            }
            return;
        }

        if (DEBUG_STARTING_WINDOW) {
            Slog.v(TAG_WM, "Schedule remove starting " + this
                    + " startingWindow=" + startingWindow
                    + " startingView=" + startingSurface
                    + " Callers=" + Debug.getCallers(5));
        }

        // Use the same thread to remove the window as we used to add it, as otherwise we end up
        // with things in the view hierarchy being called from different threads.
        mWmService.mAnimationHandler.post(() -> {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Removing startingView=" + surface);
            try {
                surface.remove();
            } catch (Exception e) {
                Slog.w(TAG_WM, "Exception when removing starting window", e);
            }
        });
    }

    @Override
    boolean fillsParent() {
        return occludesParent();
    }

    /** Returns true if this activity is opaque and fills the entire space of this task. */
    boolean occludesParent() {
        return mOccludesParent;
    }

    boolean setOccludesParent(boolean occludesParent) {
        final boolean changed = occludesParent != mOccludesParent;
        mOccludesParent = occludesParent;
        setMainWindowOpaque(occludesParent);
        mWmService.mWindowPlacerLocked.requestTraversal();
        return changed;
    }

    void setMainWindowOpaque(boolean isOpaque) {
        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        isOpaque = isOpaque & !PixelFormat.formatHasAlpha(win.getAttrs().format);
        win.mWinAnimator.setOpaqueLocked(isOpaque);
    }

    boolean containsDismissKeyguardWindow() {
        // Window state is transient during relaunch. We are not guaranteed to be frozen during the
        // entirety of the relaunch.
        if (isRelaunching()) {
            return mLastContainsDismissKeyguardWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_DISMISS_KEYGUARD) != 0) {
                return true;
            }
        }
        return false;
    }

    boolean containsShowWhenLockedWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsShowWhenLockedWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0) {
                return true;
            }
        }

        return false;
    }

    void checkKeyguardFlagsChanged() {
        final boolean containsDismissKeyguard = containsDismissKeyguardWindow();
        final boolean containsShowWhenLocked = containsShowWhenLockedWindow();
        if (containsDismissKeyguard != mLastContainsDismissKeyguardWindow
                || containsShowWhenLocked != mLastContainsShowWhenLockedWindow) {
            mWmService.notifyKeyguardFlagsChanged(null /* callback */,
                    getDisplayContent().getDisplayId());
        }
        mLastContainsDismissKeyguardWindow = containsDismissKeyguard;
        mLastContainsShowWhenLockedWindow = containsShowWhenLocked;
    }

    WindowState getImeTargetBelowWindow(WindowState w) {
        final int index = mChildren.indexOf(w);
        if (index > 0) {
            final WindowState target = mChildren.get(index - 1);
            if (target.canBeImeTarget()) {
                return target;
            }
        }
        return null;
    }

    WindowState getHighestAnimLayerWindow(WindowState currentTarget) {
        WindowState candidate = null;
        for (int i = mChildren.indexOf(currentTarget); i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            if (w.mRemoved) {
                continue;
            }
            if (candidate == null) {
                candidate = w;
            }
        }
        return candidate;
    }

    /**
     * See {@link Activity#setDisablePreviewScreenshots}.
     */
    void setDisablePreviewScreenshots(boolean disable) {
        mDisablePreviewScreenshots = disable;
    }

    /**
     * Sets whether the current launch can turn the screen on.
     * @see #currentLaunchCanTurnScreenOn()
     */
    void setCurrentLaunchCanTurnScreenOn(boolean currentLaunchCanTurnScreenOn) {
        mCurrentLaunchCanTurnScreenOn = currentLaunchCanTurnScreenOn;
    }

    /**
     * Indicates whether the current launch can turn the screen on. This is to prevent multiple
     * relayouts from turning the screen back on. The screen should only turn on at most
     * once per activity resume.
     * <p>
     * Note this flag is only meaningful when {@link WindowManager.LayoutParams#FLAG_TURN_SCREEN_ON}
     * or {@link ActivityRecord#canTurnScreenOn} is set.
     *
     * @return {@code true} if the activity is ready to turn on the screen.
     */
    boolean currentLaunchCanTurnScreenOn() {
        return mCurrentLaunchCanTurnScreenOn;
    }

    /**
     * Retrieves whether we'd like to generate a snapshot that's based solely on the theme. This is
     * the case when preview screenshots are disabled {@link #setDisablePreviewScreenshots} or when
     * we can't take a snapshot for other reasons, for example, if we have a secure window.
     *
     * @return True if we need to generate an app theme snapshot, false if we'd like to take a real
     *         screenshot.
     */
    boolean shouldUseAppThemeSnapshot() {
        return mDisablePreviewScreenshots || forAllWindows(w -> (w.mAttrs.flags & FLAG_SECURE) != 0,
                true /* topToBottom */);
    }

    SurfaceControl getAppAnimationLayer() {
        return getAppAnimationLayer(isActivityTypeHome() ? ANIMATION_LAYER_HOME
                : needsZBoost() ? ANIMATION_LAYER_BOOSTED
                : ANIMATION_LAYER_STANDARD);
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        // All normal app transitions take place in an animation layer which is below the pinned
        // stack but may be above the parent stacks of the given animating apps.
        // For transitions in the pinned stack (menu activity) we just let them occur as a child
        // of the pinned stack.
        if (!inPinnedWindowingMode()) {
            return getAppAnimationLayer();
        } else {
            return getStack().getSurfaceControl();
        }
    }


    @VisibleForTesting
    boolean shouldAnimate(int transit) {
        final boolean isSplitScreenPrimary =
                getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
        final boolean allowSplitScreenPrimaryAnimation = transit != TRANSIT_WALLPAPER_OPEN;

        // Don't animate while the task runs recents animation but only if we are in the mode
        // where we cancel with deferred screenshot, which means that the controller has
        // transformed the task.
        final RecentsAnimationController controller = mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(getTask())
                && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }

        // We animate always if it's not split screen primary, and only some special cases in split
        // screen primary because it causes issues with stack clipping when we run an un-minimize
        // animation at the same time.
        return !isSplitScreenPrimary || allowSplitScreenPrimaryAnimation;
    }

    /**
     * Creates a layer to apply crop to an animation.
     */
    private SurfaceControl createAnimationBoundsLayer(Transaction t) {
        if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.i(TAG, "Creating animation bounds layer");
        final SurfaceControl.Builder builder = makeAnimationLeash()
                .setParent(getAnimationLeashParent())
                .setName(getSurfaceControl() + " - animation-bounds");
        final SurfaceControl boundsLayer = builder.build();
        t.show(boundsLayer);
        return boundsLayer;
    }

    @Override
    Rect getDisplayedBounds() {
        final Task task = getTask();
        if (task != null) {
            final Rect overrideDisplayedBounds = task.getOverrideDisplayedBounds();
            if (!overrideDisplayedBounds.isEmpty()) {
                return overrideDisplayedBounds;
            }
        }
        return getBounds();
    }

    @VisibleForTesting
    Rect getAnimationBounds(int appStackClipMode) {
        if (appStackClipMode == STACK_CLIP_BEFORE_ANIM && getStack() != null) {
            // Using the stack bounds here effectively applies the clipping before animation.
            return getStack().getBounds();
        }
        // Use task-bounds if available so that activity-level letterbox (maxAspectRatio) is
        // included in the animation.
        return getTask() != null ? getTask().getBounds() : getBounds();
    }

    boolean applyAnimationLocked(WindowManager.LayoutParams lp, int transit, boolean enter,
            boolean isVoiceInteraction) {

        if (mWmService.mDisableTransitionAnimation || !shouldAnimate(transit)) {
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                Slog.v(TAG_WM, "applyAnimation: transition animation is disabled or skipped."
                        + " atoken=" + this);
            }
            cancelAnimation();
            return false;
        }

        // Only apply an animation if the display isn't frozen. If it is frozen, there is no reason
        // to animate and it can cause strange artifacts when we unfreeze the display if some
        // different animation is running.
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AWT#applyAnimationLocked");
        if (okToAnimate()) {
            final AnimationAdapter adapter;
            AnimationAdapter thumbnailAdapter = null;

            final int appStackClipMode =
                    getDisplayContent().mAppTransition.getAppStackClipMode();

            // Separate position and size for use in animators.
            mTmpRect.set(getAnimationBounds(appStackClipMode));
            mTmpPoint.set(mTmpRect.left, mTmpRect.top);
            mTmpRect.offsetTo(0, 0);

            final boolean isChanging = AppTransition.isChangeTransit(transit) && enter
                    && getDisplayContent().mChangingApps.contains(this);

            // Delaying animation start isn't compatible with remote animations at all.
            if (getDisplayContent().mAppTransition.getRemoteAnimationController() != null
                    && !mSurfaceAnimator.isAnimationStartDelayed()) {
                RemoteAnimationRecord adapters =
                        getDisplayContent().mAppTransition.getRemoteAnimationController()
                                .createRemoteAnimationRecord(this, mTmpPoint, mTmpRect,
                                        (isChanging ? mTransitStartRect : null));
                adapter = adapters.mAdapter;
                thumbnailAdapter = adapters.mThumbnailAdapter;
            } else if (isChanging) {
                final float durationScale = mWmService.getTransitionAnimationScaleLocked();
                mTmpRect.offsetTo(mTmpPoint.x, mTmpPoint.y);
                adapter = new LocalAnimationAdapter(
                        new WindowChangeAnimationSpec(mTransitStartRect, mTmpRect,
                                getDisplayContent().getDisplayInfo(), durationScale,
                                true /* isAppAnimation */, false /* isThumbnail */),
                        mWmService.mSurfaceAnimationRunner);
                if (mThumbnail != null) {
                    thumbnailAdapter = new LocalAnimationAdapter(
                            new WindowChangeAnimationSpec(mTransitStartRect, mTmpRect,
                                    getDisplayContent().getDisplayInfo(), durationScale,
                                    true /* isAppAnimation */, true /* isThumbnail */),
                            mWmService.mSurfaceAnimationRunner);
                }
                mTransit = transit;
                mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
            } else {
                mNeedsAnimationBoundsLayer = (appStackClipMode == STACK_CLIP_AFTER_ANIM);

                final Animation a = loadAnimation(lp, transit, enter, isVoiceInteraction);
                if (a != null) {
                    // Only apply corner radius to animation if we're not in multi window mode.
                    // We don't want rounded corners when in pip or split screen.
                    final float windowCornerRadius = !inMultiWindowMode()
                            ? getDisplayContent().getWindowCornerRadius()
                            : 0;
                    adapter = new LocalAnimationAdapter(
                            new WindowAnimationSpec(a, mTmpPoint, mTmpRect,
                                    getDisplayContent().mAppTransition.canSkipFirstFrame(),
                                    appStackClipMode,
                                    true /* isAppAnimation */,
                                    windowCornerRadius),
                            mWmService.mSurfaceAnimationRunner);
                    if (a.getZAdjustment() == Animation.ZORDER_TOP) {
                        mNeedsZBoost = true;
                    }
                    mTransit = transit;
                    mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
                } else {
                    adapter = null;
                }
            }
            if (adapter != null) {
                startAnimation(getPendingTransaction(), adapter, !isVisible());
                if (adapter.getShowWallpaper()) {
                    mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                }
                if (thumbnailAdapter != null) {
                    mThumbnail.startAnimation(
                            getPendingTransaction(), thumbnailAdapter, !isVisible());
                }
            }
        } else {
            cancelAnimation();
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

        return isReallyAnimating();
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter,
            boolean isVoiceInteraction) {
        final DisplayContent displayContent = getTask().getDisplayContent();
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int width = displayInfo.appWidth;
        final int height = displayInfo.appHeight;
        if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG_WM,
                "applyAnimation: atoken=" + this);

        // Determine the visible rect to calculate the thumbnail clip
        final WindowState win = findMainWindow();
        final Rect frame = new Rect(0, 0, width, height);
        final Rect displayFrame = new Rect(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        final Rect insets = new Rect();
        final Rect stableInsets = new Rect();
        Rect surfaceInsets = null;
        final boolean freeform = win != null && win.inFreeformWindowingMode();
        if (win != null) {
            // Containing frame will usually cover the whole screen, including dialog windows.
            // For freeform workspace windows it will not cover the whole screen and it also
            // won't exactly match the final freeform window frame (e.g. when overlapping with
            // the status bar). In that case we need to use the final frame.
            if (freeform) {
                frame.set(win.getFrameLw());
            } else if (win.isLetterboxedAppWindow()) {
                frame.set(getTask().getBounds());
            } else if (win.isDockedResizing()) {
                // If we are animating while docked resizing, then use the stack bounds as the
                // animation target (which will be different than the task bounds)
                frame.set(getTask().getParent().getBounds());
            } else {
                frame.set(win.getContainingFrame());
            }
            surfaceInsets = win.getAttrs().surfaceInsets;
            // XXX(b/72757033): These are insets relative to the window frame, but we're really
            // interested in the insets relative to the frame we chose in the if-blocks above.
            win.getContentInsets(insets);
            win.getStableInsets(stableInsets);
        }

        if (mLaunchTaskBehind) {
            // Differentiate the two animations. This one which is briefly on the screen
            // gets the !enter animation, and the other activity which remains on the
            // screen gets the enter animation. Both appear in the mOpeningApps set.
            enter = false;
        }
        if (DEBUG_APP_TRANSITIONS) Slog.d(TAG_WM, "Loading animation for app transition."
                + " transit=" + AppTransition.appTransitionToString(transit) + " enter=" + enter
                + " frame=" + frame + " insets=" + insets + " surfaceInsets=" + surfaceInsets);
        final Configuration displayConfig = displayContent.getConfiguration();
        final Animation a = getDisplayContent().mAppTransition.loadAnimation(lp, transit, enter,
                displayConfig.uiMode, displayConfig.orientation, frame, displayFrame, insets,
                surfaceInsets, stableInsets, isVoiceInteraction, freeform, getTask().mTaskId);
        if (a != null) {
            if (DEBUG_ANIM) logWithStack(TAG, "Loaded animation " + a + " for " + this);
            final int containingWidth = frame.width();
            final int containingHeight = frame.height();
            a.initialize(containingWidth, containingHeight, width, height);
            a.scaleCurrentDuration(mWmService.getTransitionAnimationScaleLocked());
        }
        return a;
    }

    @Override
    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        return mAnimatingAppWindowTokenRegistry != null
                && mAnimatingAppWindowTokenRegistry.notifyAboutToFinish(
                        this, endDeferFinishCallback);
    }

    @Override
    public void onAnimationLeashLost(Transaction t) {
        super.onAnimationLeashLost(t);
        if (mAnimationBoundsLayer != null) {
            t.remove(mAnimationBoundsLayer);
            mAnimationBoundsLayer = null;
        }

        if (mAnimatingAppWindowTokenRegistry != null) {
            mAnimatingAppWindowTokenRegistry.notifyFinished(this);
        }
    }

    @Override
    protected void setLayer(Transaction t, int layer) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.setLayer(mSurfaceControl, layer);
        }
    }

    @Override
    protected void setRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.setRelativeLayer(mSurfaceControl, relativeTo, layer);
        }
    }

    @Override
    protected void reparentSurfaceControl(Transaction t, SurfaceControl newParent) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.reparent(mSurfaceControl, newParent);
        }
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        // The leash is parented to the animation layer. We need to preserve the z-order by using
        // the prefix order index, but we boost if necessary.
        int layer = 0;
        if (!inPinnedWindowingMode()) {
            layer = getPrefixOrderIndex();
        } else {
            // Pinned stacks have animations take place within themselves rather than an animation
            // layer so we need to preserve the order relative to the stack (e.g. the order of our
            // task/parent).
            layer = getParent().getPrefixOrderIndex();
        }

        if (mNeedsZBoost) {
            layer += Z_BOOST_BASE;
        }
        if (!mNeedsAnimationBoundsLayer) {
            leash.setLayer(layer);
        }

        final DisplayContent dc = getDisplayContent();
        dc.assignStackOrdering();

        if (leash == mTransitChangeLeash) {
            // This is a temporary state so skip any animation notifications
            return;
        } else if (mTransitChangeLeash != null) {
            // unparent mTransitChangeLeash for clean-up
            clearChangeLeash(t, false /* cancel */);
        }

        if (mAnimatingAppWindowTokenRegistry != null) {
            mAnimatingAppWindowTokenRegistry.notifyStarting(this);
        }

        // If the animation needs to be cropped then an animation bounds layer is created as a child
        // of the pinned stack or animation layer. The leash is then reparented to this new layer.
        if (mNeedsAnimationBoundsLayer) {
            mTmpRect.setEmpty();
            final Task task = getTask();
            if (getDisplayContent().mAppTransitionController.isTransitWithinTask(
                    getTransit(), task)) {
                task.getBounds(mTmpRect);
            } else {
                final TaskStack stack = getStack();
                if (stack == null) {
                    return;
                }
                // Set clip rect to stack bounds.
                stack.getBounds(mTmpRect);
            }
            mAnimationBoundsLayer = createAnimationBoundsLayer(t);

            // Crop to stack bounds.
            t.setWindowCrop(mAnimationBoundsLayer, mTmpRect);
            t.setLayer(mAnimationBoundsLayer, layer);

            // Reparent leash to animation bounds layer.
            t.reparent(leash, mAnimationBoundsLayer);
        }
    }

    /**
     * This must be called while inside a transaction.
     */
    void showAllWindowsLocked() {
        forAllWindows(windowState -> {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "performing show on: " + windowState);
            windowState.performShowLocked();
        }, false /* traverseTopToBottom */);
    }

    @Override
    protected void onAnimationFinished() {
        super.onAnimationFinished();

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AWT#onAnimationFinished");
        mTransit = TRANSIT_UNSET;
        mTransitFlags = 0;
        mNeedsZBoost = false;
        mNeedsAnimationBoundsLayer = false;

        setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM | FINISH_LAYOUT_REDO_WALLPAPER,
                "AppWindowToken");

        clearThumbnail();
        setClientHidden(isHidden() && hiddenRequested);

        getDisplayContent().computeImeTargetIfNeeded(this);

        if (DEBUG_ANIM) Slog.v(TAG, "Animation done in " + this
                + ": reportedVisible=" + reportedVisible
                + " okToDisplay=" + okToDisplay()
                + " okToAnimate=" + okToAnimate()
                + " startingDisplayed=" + startingDisplayed);

        // clean up thumbnail window
        if (mThumbnail != null) {
            mThumbnail.destroy();
            mThumbnail = null;
        }

        // WindowState.onExitAnimationDone might modify the children list, so make a copy and then
        // traverse the copy.
        final ArrayList<WindowState> children = new ArrayList<>(mChildren);
        children.forEach(WindowState::onExitAnimationDone);

        getDisplayContent().mAppTransition.notifyAppTransitionFinishedLocked(token);
        scheduleAnimation();

        mActivityRecord.onAnimationFinished();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @Override
    boolean isAppAnimating() {
        return isSelfAnimating();
    }

    @Override
    boolean isSelfAnimating() {
        // If we are about to start a transition, we also need to be considered animating.
        return isWaitingForTransitionStart() || isReallyAnimating();
    }

    /**
     * @return True if and only if we are actually running an animation. Note that
     *         {@link #isSelfAnimating} also returns true if we are waiting for an animation to
     *         start.
     */
    private boolean isReallyAnimating() {
        return super.isSelfAnimating();
    }

    /**
     * @param cancel {@code true} if clearing the leash due to cancelling instead of transferring
     *                            to another leash.
     */
    private void clearChangeLeash(Transaction t, boolean cancel) {
        if (mTransitChangeLeash == null) {
            return;
        }
        if (cancel) {
            clearThumbnail();
            SurfaceControl sc = getSurfaceControl();
            SurfaceControl parentSc = getParentSurfaceControl();
            // Don't reparent if surface is getting destroyed
            if (parentSc != null && sc != null) {
                t.reparent(sc, getParentSurfaceControl());
            }
        }
        t.hide(mTransitChangeLeash);
        t.remove(mTransitChangeLeash);
        mTransitChangeLeash = null;
        if (cancel) {
            onAnimationLeashLost(t);
        }
    }

    @Override
    void cancelAnimation() {
        cancelAnimationOnly();
        clearThumbnail();
        clearChangeLeash(getPendingTransaction(), true /* cancel */);
    }

    /**
     * This only cancels the animation. It doesn't do other teardown like cleaning-up thumbnail
     * or interim leashes.
     * <p>
     * Used when canceling in preparation for starting a new animation.
     */
    void cancelAnimationOnly() {
        super.cancelAnimation();
    }

    boolean isWaitingForTransitionStart() {
        return getDisplayContent().mAppTransition.isTransitionSet()
                && (getDisplayContent().mOpeningApps.contains(this)
                    || getDisplayContent().mClosingApps.contains(this)
                    || getDisplayContent().mChangingApps.contains(this));
    }

    public int getTransit() {
        return mTransit;
    }

    int getTransitFlags() {
        return mTransitFlags;
    }

    void attachThumbnailAnimation() {
        if (!isReallyAnimating()) {
            return;
        }
        final int taskId = getTask().mTaskId;
        final GraphicBuffer thumbnailHeader =
                getDisplayContent().mAppTransition.getAppTransitionThumbnailHeader(taskId);
        if (thumbnailHeader == null) {
            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG, "No thumbnail header bitmap for: " + taskId);
            return;
        }
        clearThumbnail();
        mThumbnail = new AppWindowThumbnail(mWmService.mSurfaceFactory, getPendingTransaction(),
                this, thumbnailHeader);
        mThumbnail.startAnimation(getPendingTransaction(), loadThumbnailAnimation(thumbnailHeader));
    }

    /**
     * Attaches a surface with a thumbnail for the
     * {@link android.app.ActivityOptions#ANIM_OPEN_CROSS_PROFILE_APPS} animation.
     */
    void attachCrossProfileAppsThumbnailAnimation() {
        if (!isReallyAnimating()) {
            return;
        }
        clearThumbnail();

        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        final Rect frame = win.getFrameLw();
        final int thumbnailDrawableRes = getTask().mUserId == mWmService.mCurrentUserId
                ? R.drawable.ic_account_circle
                : R.drawable.ic_corp_badge;
        final GraphicBuffer thumbnail =
                getDisplayContent().mAppTransition
                        .createCrossProfileAppsThumbnail(thumbnailDrawableRes, frame);
        if (thumbnail == null) {
            return;
        }
        mThumbnail = new AppWindowThumbnail(mWmService.mSurfaceFactory,
                getPendingTransaction(), this, thumbnail);
        final Animation animation =
                getDisplayContent().mAppTransition.createCrossProfileAppsThumbnailAnimationLocked(
                        win.getFrameLw());
        mThumbnail.startAnimation(getPendingTransaction(), animation, new Point(frame.left,
                frame.top));
    }

    private Animation loadThumbnailAnimation(GraphicBuffer thumbnailHeader) {
        final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();

        // If this is a multi-window scenario, we use the windows frame as
        // destination of the thumbnail header animation. If this is a full screen
        // window scenario, we use the whole display as the target.
        WindowState win = findMainWindow();
        Rect appRect = win != null ? win.getContentFrameLw() :
                new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        final Rect insets = win != null ? win.getContentInsets() : null;
        final Configuration displayConfig = mDisplayContent.getConfiguration();
        return getDisplayContent().mAppTransition.createThumbnailAspectScaleAnimationLocked(
                appRect, insets, thumbnailHeader, getTask().mTaskId, displayConfig.uiMode,
                displayConfig.orientation);
    }

    private void clearThumbnail() {
        if (mThumbnail == null) {
            return;
        }
        mThumbnail.destroy();
        mThumbnail = null;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
    }

    RemoteAnimationDefinition getRemoteAnimationDefinition() {
        return mRemoteAnimationDefinition;
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        if (appToken != null) {
            pw.println(prefix + "app=true mVoiceInteraction=" + mVoiceInteraction);
        }
        pw.println(prefix + "component=" + mActivityComponent.flattenToShortString());
        pw.print(prefix); pw.print("task="); pw.println(getTask());
        pw.print(prefix); pw.print(" mOccludesParent="); pw.print(mOccludesParent);
                pw.print(" mOrientation="); pw.println(mOrientation);
        pw.println(prefix + "hiddenRequested=" + hiddenRequested + " mClientHidden=" + mClientHidden
            + ((mDeferHidingClient) ? " mDeferHidingClient=" + mDeferHidingClient : "")
            + " reportedDrawn=" + reportedDrawn + " reportedVisible=" + reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (mAppStopped) {
            pw.print(prefix); pw.print("mAppStopped="); pw.println(mAppStopped);
        }
        if (mNumInterestingWindows != 0 || mNumDrawnWindows != 0
                || allDrawn || mLastAllDrawn) {
            pw.print(prefix); pw.print("mNumInterestingWindows=");
                    pw.print(mNumInterestingWindows);
                    pw.print(" mNumDrawnWindows="); pw.print(mNumDrawnWindows);
                    pw.print(" inPendingTransaction="); pw.print(inPendingTransaction);
                    pw.print(" allDrawn="); pw.print(allDrawn);
                    pw.print(" lastAllDrawn="); pw.print(mLastAllDrawn);
                    pw.println(")");
        }
        if (inPendingTransaction) {
            pw.print(prefix); pw.print("inPendingTransaction=");
                    pw.println(inPendingTransaction);
        }
        if (mStartingData != null || removed || firstWindowDrawn || mIsExiting) {
            pw.print(prefix); pw.print("startingData="); pw.print(mStartingData);
                    pw.print(" removed="); pw.print(removed);
                    pw.print(" firstWindowDrawn="); pw.print(firstWindowDrawn);
                    pw.print(" mIsExiting="); pw.println(mIsExiting);
        }
        if (startingWindow != null || startingSurface != null
                || startingDisplayed || startingMoved || mHiddenSetFromTransferredStartingWindow) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
                    pw.print(" startingSurface="); pw.print(startingSurface);
                    pw.print(" startingDisplayed="); pw.print(startingDisplayed);
                    pw.print(" startingMoved="); pw.print(startingMoved);
                    pw.println(" mHiddenSetFromTransferredStartingWindow="
                            + mHiddenSetFromTransferredStartingWindow);
        }
        if (!mFrozenBounds.isEmpty()) {
            pw.print(prefix); pw.print("mFrozenBounds="); pw.println(mFrozenBounds);
            pw.print(prefix); pw.print("mFrozenMergedConfig="); pw.println(mFrozenMergedConfig);
        }
        if (mPendingRelaunchCount != 0) {
            pw.print(prefix); pw.print("mPendingRelaunchCount="); pw.println(mPendingRelaunchCount);
        }
        if (mSizeCompatScale != 1f || mSizeCompatBounds != null) {
            pw.println(prefix + "mSizeCompatScale=" + mSizeCompatScale + " mSizeCompatBounds="
                    + mSizeCompatBounds);
        }
        if (mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + mRemovingFromDisplay);
        }
    }

    @Override
    void setHidden(boolean hidden) {
        super.setHidden(hidden);
        scheduleAnimation();
    }

    @Override
    void prepareSurfaces() {
        // isSelfAnimating also returns true when we are about to start a transition, so we need
        // to check super here.
        final boolean reallyAnimating = super.isSelfAnimating();
        final boolean show = !isHidden() || reallyAnimating;

        if (mSurfaceControl != null) {
            if (show && !mLastSurfaceShowing) {
                getPendingTransaction().show(mSurfaceControl);
            } else if (!show && mLastSurfaceShowing) {
                getPendingTransaction().hide(mSurfaceControl);
            }
        }
        if (mThumbnail != null) {
            mThumbnail.setShowing(getPendingTransaction(), show);
        }
        mLastSurfaceShowing = show;
        super.prepareSurfaces();
    }

    /**
     * @return Whether our {@link #getSurfaceControl} is currently showing.
     */
    boolean isSurfaceShowing() {
        return mLastSurfaceShowing;
    }

    boolean isFreezingScreen() {
        return mFreezingScreen;
    }

    @Override
    boolean needsZBoost() {
        return mNeedsZBoost || super.needsZBoost();
    }

    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        writeNameToProto(proto, NAME);
        super.writeToProto(proto, WINDOW_TOKEN, logLevel);
        proto.write(LAST_SURFACE_SHOWING, mLastSurfaceShowing);
        proto.write(IS_WAITING_FOR_TRANSITION_START, isWaitingForTransitionStart());
        proto.write(IS_REALLY_ANIMATING, isReallyAnimating());
        if (mThumbnail != null){
            mThumbnail.writeToProto(proto, THUMBNAIL);
        }
        proto.write(FILLS_PARENT, mOccludesParent);
        proto.write(APP_STOPPED, mAppStopped);
        proto.write(HIDDEN_REQUESTED, hiddenRequested);
        proto.write(CLIENT_HIDDEN, mClientHidden);
        proto.write(DEFER_HIDING_CLIENT, mDeferHidingClient);
        proto.write(REPORTED_DRAWN, reportedDrawn);
        proto.write(REPORTED_VISIBLE, reportedVisible);
        proto.write(NUM_INTERESTING_WINDOWS, mNumInterestingWindows);
        proto.write(NUM_DRAWN_WINDOWS, mNumDrawnWindows);
        proto.write(ALL_DRAWN, allDrawn);
        proto.write(LAST_ALL_DRAWN, mLastAllDrawn);
        proto.write(REMOVED, removed);
        if (startingWindow != null) {
            startingWindow.writeIdentifierToProto(proto, STARTING_WINDOW);
        }
        proto.write(STARTING_DISPLAYED, startingDisplayed);
        proto.write(STARTING_MOVED, startingMoved);
        proto.write(HIDDEN_SET_FROM_TRANSFERRED_STARTING_WINDOW,
                mHiddenSetFromTransferredStartingWindow);
        for (Rect bounds : mFrozenBounds) {
            bounds.writeToProto(proto, FROZEN_BOUNDS);
        }
        proto.end(token);
    }

    void writeNameToProto(ProtoOutputStream proto, long fieldId) {
        if (appToken == null) {
            return;
        }
        try {
            proto.write(fieldId, appToken.getName());
        } catch (RemoteException e) {
            // This shouldn't happen, but in this case fall back to outputting nothing
            Slog.e(TAG, e.toString());
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("AppWindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" token="); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName + ((mIsExiting) ? " mIsExiting=" : "");
    }

    Rect getLetterboxInsets() {
        if (mLetterbox != null) {
            return mLetterbox.getInsets();
        } else {
            return new Rect();
        }
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getInnerFrame());
        } else {
            outBounds.setEmpty();
        }
    }

    /**
     * @return {@code true} if there is a letterbox and any part of that letterbox overlaps with
     * the given {@code rect}.
     */
    boolean isLetterboxOverlappingWith(Rect rect) {
        return mLetterbox != null && mLetterbox.isOverlappingWith(rect);
    }

    /**
     * Sets if this AWT is in the process of closing or entering PIP.
     * {@link #mWillCloseOrEnterPip}}
     */
    void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        mWillCloseOrEnterPip = willCloseOrEnterPip;
    }

    /**
     * Returns whether this AWT is considered closing. Conditions are either
     * 1. Is this app animating and was requested to be hidden
     * 2. App is delayed closing since it might enter PIP.
     */
    boolean isClosingOrEnteringPip() {
        return (isAnimating() && hiddenRequested) || mWillCloseOrEnterPip;
    }

    /**
     * @return Whether we are allowed to show non-starting windows at the moment. We disallow
     *         showing windows during transitions in case we have windows that have wide-color-gamut
     *         color mode set to avoid jank in the middle of the transition.
     */
    boolean canShowWindows() {
        return allDrawn && !(isReallyAnimating() && hasNonDefaultColorWindow());
    }

    /**
     * @return true if we have a window that has a non-default color mode set; false otherwise.
     */
    private boolean hasNonDefaultColorWindow() {
        return forAllWindows(ws -> ws.mAttrs.getColorMode() != COLOR_MODE_DEFAULT,
                true /* topToBottom */);
    }

    private void updateColorTransform() {
        if (mSurfaceControl != null && mLastAppSaturationInfo != null) {
            getPendingTransaction().setColorTransform(mSurfaceControl,
                    mLastAppSaturationInfo.mMatrix, mLastAppSaturationInfo.mTranslation);
            mWmService.scheduleAnimationLocked();
        }
    }

    private static class AppSaturationInfo {
        float[] mMatrix = new float[9];
        float[] mTranslation = new float[3];

        void setSaturation(@Size(9) float[] matrix, @Size(3) float[] translation) {
            System.arraycopy(matrix, 0, mMatrix, 0, mMatrix.length);
            System.arraycopy(translation, 0, mTranslation, 0, mTranslation.length);
        }
    }
}
