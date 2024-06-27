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

import static android.view.WindowManager.LayoutParams;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_RELAUNCH;
import static android.view.WindowManager.TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_DREAM_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_DREAM_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_UNSET;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_OPEN;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_dreamActivityCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_dreamActivityOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_dreamActivityOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_launchTaskBehindSourceAnimation;
import static com.android.internal.R.styleable.WindowAnimation_launchTaskBehindTargetAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS_ANIM;
import static com.android.server.wm.AppTransitionProto.APP_TRANSITION_STATE;
import static com.android.server.wm.AppTransitionProto.LAST_USED_APP_TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_NONE;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManager.TransitionFlags;
import android.view.WindowManager.TransitionOldType;
import android.view.WindowManager.TransitionType;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.LogLevel;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.DumpUtils.Dump;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.wm.ActivityRecord.CustomAppTransition;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// State management of app transitions.  When we are preparing for a
// transition, mNextAppTransition will be the kind of transition to
// perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
// mOpeningApps and mClosingApps are the lists of tokens that will be
// made visible or hidden at the next transition.
public class AppTransition implements Dump {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppTransition" : TAG_WM;

    static final int DEFAULT_APP_TRANSITION_DURATION = 336;

    /**
     * Maximum duration for the clip reveal animation. This is used when there is a lot of movement
     * involved, to make it more understandable.
     */
    private static final long APP_TRANSITION_TIMEOUT_MS = 5000;
    static final int MAX_APP_TRANSITION_DURATION = 3 * 1000; // 3 secs.

    private final Context mContext;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;

    @VisibleForTesting
    final TransitionAnimation mTransitionAnimation;

    private @TransitionFlags int mNextAppTransitionFlags = 0;
    private final ArrayList<Integer> mNextAppTransitionRequests = new ArrayList<>();
    private @TransitionOldType int mLastUsedAppTransition = TRANSIT_OLD_UNSET;
    private String mLastOpeningApp;
    private String mLastClosingApp;
    private String mLastChangingApp;

    private static final int NEXT_TRANSIT_TYPE_NONE = 0;
    private static final int NEXT_TRANSIT_TYPE_CUSTOM = 1;
    private static final int NEXT_TRANSIT_TYPE_SCALE_UP = 2;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP = 3;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN = 4;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP = 5;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN = 6;
    private static final int NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE = 7;
    private static final int NEXT_TRANSIT_TYPE_CLIP_REVEAL = 8;

    /**
     * Refers to the transition to activity started by using {@link
     * android.content.pm.crossprofile.CrossProfileApps#startMainActivity(ComponentName, UserHandle)
     * }.
     */
    private static final int NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS = 9;
    private static final int NEXT_TRANSIT_TYPE_REMOTE = 10;

    private int mNextAppTransitionType = NEXT_TRANSIT_TYPE_NONE;
    private boolean mNextAppTransitionOverrideRequested;

    private String mNextAppTransitionPackage;
    // Used for thumbnail transitions. True if we're scaling up, false if scaling down
    private boolean mNextAppTransitionScaleUp;
    private IRemoteCallback mNextAppTransitionCallback;
    private IRemoteCallback mNextAppTransitionFutureCallback;
    private IRemoteCallback mAnimationFinishedCallback;
    private int mNextAppTransitionEnter;
    private int mNextAppTransitionExit;
    private @ColorInt int mNextAppTransitionBackgroundColor;
    private int mNextAppTransitionInPlace;
    private boolean mNextAppTransitionIsSync;

    // Keyed by WindowContainer hashCode.
    private final SparseArray<AppTransitionAnimationSpec> mNextAppTransitionAnimationsSpecs
            = new SparseArray<>();
    private IAppTransitionAnimationSpecsFuture mNextAppTransitionAnimationsSpecsFuture;
    private boolean mNextAppTransitionAnimationsSpecsPending;
    private AppTransitionAnimationSpec mDefaultNextAppTransitionAnimationSpec;

    private final Rect mTmpRect = new Rect();

    private final static int APP_STATE_IDLE = 0;
    private final static int APP_STATE_READY = 1;
    private final static int APP_STATE_RUNNING = 2;
    private final static int APP_STATE_TIMEOUT = 3;
    private int mAppTransitionState = APP_STATE_IDLE;

    private final ArrayList<AppTransitionListener> mListeners = new ArrayList<>();
    private final ExecutorService mDefaultExecutor = Executors.newSingleThreadExecutor();

    private final int mDefaultWindowAnimationStyleResId;
    private boolean mOverrideTaskTransition;

    private RemoteAnimationController mRemoteAnimationController;

    final Handler mHandler;
    final Runnable mHandleAppTransitionTimeoutRunnable = () -> handleAppTransitionTimeout();

    AppTransition(Context context, WindowManagerService service, DisplayContent displayContent) {
        mContext = context;
        mService = service;
        mHandler = new Handler(service.mH.getLooper());
        mDisplayContent = displayContent;
        mTransitionAnimation = new TransitionAnimation(
                context, ProtoLog.isEnabled(WM_DEBUG_ANIM, LogLevel.DEBUG), TAG);

        final TypedArray windowStyle = mContext.getTheme().obtainStyledAttributes(
                com.android.internal.R.styleable.Window);
        mDefaultWindowAnimationStyleResId = windowStyle.getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        windowStyle.recycle();
    }

    boolean isTransitionSet() {
        return !mNextAppTransitionRequests.isEmpty();
    }

    boolean isUnoccluding() {
        return mNextAppTransitionRequests.contains(TRANSIT_KEYGUARD_UNOCCLUDE);
    }

    boolean transferFrom(AppTransition other) {
        mNextAppTransitionRequests.addAll(other.mNextAppTransitionRequests);
        return prepare();
    }

    void setLastAppTransition(@TransitionOldType int transit, ActivityRecord openingApp,
            ActivityRecord closingApp, ActivityRecord changingApp) {
        mLastUsedAppTransition = transit;
        mLastOpeningApp = "" + openingApp;
        mLastClosingApp = "" + closingApp;
        mLastChangingApp = "" + changingApp;
    }

    boolean isReady() {
        return mAppTransitionState == APP_STATE_READY
                || mAppTransitionState == APP_STATE_TIMEOUT;
    }

    void setReady() {
        setAppTransitionState(APP_STATE_READY);
        fetchAppTransitionSpecsFromFuture();
    }

    boolean isRunning() {
        return mAppTransitionState == APP_STATE_RUNNING;
    }

    void setIdle() {
        setAppTransitionState(APP_STATE_IDLE);
    }

    boolean isIdle() {
        return mAppTransitionState == APP_STATE_IDLE;
    }

    boolean isTimeout() {
        return mAppTransitionState == APP_STATE_TIMEOUT;
    }

    void setTimeout() {
        setAppTransitionState(APP_STATE_TIMEOUT);
    }

    /**
     * Gets the animation overridden by app via {@link #overridePendingAppTransition}.
     */
    @Nullable
    Animation getNextAppRequestedAnimation(boolean enter) {
        final Animation a = mTransitionAnimation.loadAppTransitionAnimation(
                mNextAppTransitionPackage,
                enter ? mNextAppTransitionEnter : mNextAppTransitionExit);
        if (mNextAppTransitionBackgroundColor != 0 && a != null) {
            a.setBackdropColor(mNextAppTransitionBackgroundColor);
        }
        return a;
    }

    /**
     * Gets the animation background color overridden by app via
     * {@link #overridePendingAppTransition}.
     */
    @ColorInt int getNextAppTransitionBackgroundColor() {
        return mNextAppTransitionBackgroundColor;
    }

    @VisibleForTesting
    boolean isNextAppTransitionOverrideRequested() {
        return mNextAppTransitionOverrideRequested;
    }

    HardwareBuffer getAppTransitionThumbnailHeader(WindowContainer container) {
        AppTransitionAnimationSpec spec = mNextAppTransitionAnimationsSpecs.get(
                container.hashCode());
        if (spec == null) {
            spec = mDefaultNextAppTransitionAnimationSpec;
        }
        return spec != null ? spec.buffer : null;
    }

    /** Returns whether the next thumbnail transition is aspect scaled up. */
    boolean isNextThumbnailTransitionAspectScaled() {
        return mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN;
    }

    /** Returns whether the next thumbnail transition is scaling up. */
    boolean isNextThumbnailTransitionScaleUp() {
        return mNextAppTransitionScaleUp;
    }

    boolean isNextAppTransitionThumbnailUp() {
        return mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP;
    }

    boolean isNextAppTransitionThumbnailDown() {
        return mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN;
    }

    boolean isNextAppTransitionOpenCrossProfileApps() {
        return mNextAppTransitionType == NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS;
    }

    /**
     * @return true if and only if we are currently fetching app transition specs from the future
     *         passed into {@link #overridePendingAppTransitionMultiThumbFuture}
     */
    boolean isFetchingAppTransitionsSpecs() {
        return mNextAppTransitionAnimationsSpecsPending;
    }

    private boolean prepare() {
        if (!isRunning()) {
            setAppTransitionState(APP_STATE_IDLE);
            notifyAppTransitionPendingLocked();
            return true;
        }
        return false;
    }

    /**
     * @return bit-map of WindowManagerPolicy#FINISH_LAYOUT_REDO_* to indicate whether another
     *         layout pass needs to be done
     */
    int goodToGo(@TransitionOldType int transit, ActivityRecord topOpeningApp) {
        mNextAppTransitionFlags = 0;
        mNextAppTransitionRequests.clear();
        setAppTransitionState(APP_STATE_RUNNING);
        final WindowContainer wc =
                topOpeningApp != null ? topOpeningApp.getAnimatingContainer() : null;
        final AnimationAdapter topOpeningAnim = wc != null ? wc.getAnimation() : null;

        int redoLayout = notifyAppTransitionStartingLocked(
                topOpeningAnim != null
                        ? topOpeningAnim.getStatusBarTransitionsStartTime()
                        : SystemClock.uptimeMillis(),
                AnimationAdapter.STATUS_BAR_TRANSITION_DURATION);

        if (mRemoteAnimationController != null) {
            mRemoteAnimationController.goodToGo(transit);
        } else if ((isTaskOpenTransitOld(transit) || transit == TRANSIT_OLD_WALLPAPER_CLOSE)
                && topOpeningAnim != null) {
            if (mDisplayContent.getDisplayPolicy().shouldAttachNavBarToAppDuringTransition()
                    && mService.getRecentsAnimationController() == null) {
                final NavBarFadeAnimationController controller =
                        new NavBarFadeAnimationController(mDisplayContent);
                // For remote animation case, the nav bar fades out and in is controlled by the
                // remote side. For non-remote animation case, we play the fade out/in animation
                // here. We play the nav bar fade-out animation when the app transition animation
                // starts and play the fade-in animation sequentially once the fade-out is finished.
                controller.fadeOutAndInSequentially(topOpeningAnim.getDurationHint(),
                        null /* fadeOutParent */, topOpeningApp.getSurfaceControl());
            }
        }
        return redoLayout;
    }

    void clear() {
        clear(true /* clearAppOverride */);
    }

    private void clear(boolean clearAppOverride) {
        mNextAppTransitionType = NEXT_TRANSIT_TYPE_NONE;
        mNextAppTransitionOverrideRequested = false;
        mNextAppTransitionAnimationsSpecs.clear();
        mRemoteAnimationController = null;
        mNextAppTransitionAnimationsSpecsFuture = null;
        mDefaultNextAppTransitionAnimationSpec = null;
        mAnimationFinishedCallback = null;
        mOverrideTaskTransition = false;
        mNextAppTransitionIsSync = false;
        if (clearAppOverride) {
            mNextAppTransitionPackage = null;
            mNextAppTransitionEnter = 0;
            mNextAppTransitionExit = 0;
            mNextAppTransitionBackgroundColor = 0;
        }
    }

    void freeze() {
        final boolean keyguardGoingAwayCancelled = mNextAppTransitionRequests.contains(
                TRANSIT_KEYGUARD_GOING_AWAY);

        // The RemoteAnimationControl didn't register AppTransitionListener and
        // only initialized the finish and timeout callback when goodToGo().
        // So cancel the remote animation here to prevent the animation can't do
        // finish after transition state cleared.
        if (mRemoteAnimationController != null) {
            mRemoteAnimationController.cancelAnimation("freeze");
        }
        mNextAppTransitionRequests.clear();
        clear();
        setReady();
        notifyAppTransitionCancelledLocked(keyguardGoingAwayCancelled);
    }

    private void setAppTransitionState(int state) {
        mAppTransitionState = state;
        updateBooster();
    }

    /**
     * Updates whether we currently boost wm locked sections and the animation thread. We want to
     * boost the priorities to a more important value whenever an app transition is going to happen
     * soon or an app transition is running.
     */
    void updateBooster() {
        WindowManagerService.sThreadPriorityBooster.setAppTransitionRunning(needsBoosting());
    }

    private boolean needsBoosting() {
        final boolean recentsAnimRunning = mService.getRecentsAnimationController() != null;
        return !mNextAppTransitionRequests.isEmpty()
                || mAppTransitionState == APP_STATE_READY
                || mAppTransitionState == APP_STATE_RUNNING
                || recentsAnimRunning;
    }

    void registerListenerLocked(AppTransitionListener listener) {
        mListeners.add(listener);
    }

    void unregisterListener(AppTransitionListener listener) {
        mListeners.remove(listener);
    }

    public void notifyAppTransitionFinishedLocked(IBinder token) {
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onAppTransitionFinishedLocked(token);
        }
    }

    private void notifyAppTransitionPendingLocked() {
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onAppTransitionPendingLocked();
        }
    }

    private void notifyAppTransitionCancelledLocked(boolean keyguardGoingAwayCancelled) {
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onAppTransitionCancelledLocked(keyguardGoingAwayCancelled);
        }
    }

    private void notifyAppTransitionTimeoutLocked() {
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onAppTransitionTimeoutLocked();
        }
    }

    private int notifyAppTransitionStartingLocked(long statusBarAnimationStartTime,
            long statusBarAnimationDuration) {
        int redoLayout = 0;
        for (int i = 0; i < mListeners.size(); i++) {
            redoLayout |= mListeners.get(i).onAppTransitionStartingLocked(
                    statusBarAnimationStartTime, statusBarAnimationDuration);
        }
        return redoLayout;
    }

    @VisibleForTesting
    int getDefaultWindowAnimationStyleResId() {
        return mDefaultWindowAnimationStyleResId;
    }

    /** Returns window animation style ID from {@link LayoutParams} or from system in some cases */
    @VisibleForTesting
    int getAnimationStyleResId(@NonNull LayoutParams lp) {
        return mTransitionAnimation.getAnimationStyleResId(lp);
    }

    @VisibleForTesting
    @Nullable
    Animation loadAnimationSafely(Context context, int resId) {
        return TransitionAnimation.loadAnimationSafely(context, resId, TAG);
    }

    private static int mapOpenCloseTransitTypes(int transit, boolean enter) {
        int animAttr = 0;
        switch (transit) {
            case TRANSIT_OLD_ACTIVITY_OPEN:
            case TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN:
                animAttr = enter
                        ? WindowAnimation_activityOpenEnterAnimation
                        : WindowAnimation_activityOpenExitAnimation;
                break;
            case TRANSIT_OLD_ACTIVITY_CLOSE:
            case TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE:
                animAttr = enter
                        ? WindowAnimation_activityCloseEnterAnimation
                        : WindowAnimation_activityCloseExitAnimation;
                break;
            case TRANSIT_OLD_TASK_OPEN:
                animAttr = enter
                        ? WindowAnimation_taskOpenEnterAnimation
                        : WindowAnimation_taskOpenExitAnimation;
                break;
            case TRANSIT_OLD_TASK_CLOSE:
                animAttr = enter
                        ? WindowAnimation_taskCloseEnterAnimation
                        : WindowAnimation_taskCloseExitAnimation;
                break;
            case TRANSIT_OLD_TASK_TO_FRONT:
                animAttr = enter
                        ? WindowAnimation_taskToFrontEnterAnimation
                        : WindowAnimation_taskToFrontExitAnimation;
                break;
            case TRANSIT_OLD_TASK_TO_BACK:
                animAttr = enter
                        ? WindowAnimation_taskToBackEnterAnimation
                        : WindowAnimation_taskToBackExitAnimation;
                break;
            case TRANSIT_OLD_WALLPAPER_OPEN:
                animAttr = enter
                        ? WindowAnimation_wallpaperOpenEnterAnimation
                        : WindowAnimation_wallpaperOpenExitAnimation;
                break;
            case TRANSIT_OLD_WALLPAPER_CLOSE:
                animAttr = enter
                        ? WindowAnimation_wallpaperCloseEnterAnimation
                        : WindowAnimation_wallpaperCloseExitAnimation;
                break;
            case TRANSIT_OLD_WALLPAPER_INTRA_OPEN:
                animAttr = enter
                        ? WindowAnimation_wallpaperIntraOpenEnterAnimation
                        : WindowAnimation_wallpaperIntraOpenExitAnimation;
                break;
            case TRANSIT_OLD_WALLPAPER_INTRA_CLOSE:
                animAttr = enter
                        ? WindowAnimation_wallpaperIntraCloseEnterAnimation
                        : WindowAnimation_wallpaperIntraCloseExitAnimation;
                break;
            case TRANSIT_OLD_TASK_OPEN_BEHIND:
                animAttr = enter
                        ? WindowAnimation_launchTaskBehindSourceAnimation
                        : WindowAnimation_launchTaskBehindTargetAnimation;
                break;
            // TODO(b/189386466): Use activity transition as the fallback. Investigate if we
            //  need new TaskFragment transition.
            case TRANSIT_OLD_TASK_FRAGMENT_OPEN:
                animAttr = enter
                        ? WindowAnimation_activityOpenEnterAnimation
                        : WindowAnimation_activityOpenExitAnimation;
                break;
            // TODO(b/189386466): Use activity transition as the fallback. Investigate if we
            //  need new TaskFragment transition.
            case TRANSIT_OLD_TASK_FRAGMENT_CLOSE:
                animAttr = enter
                        ? WindowAnimation_activityCloseEnterAnimation
                        : WindowAnimation_activityCloseExitAnimation;
                break;
            case TRANSIT_OLD_DREAM_ACTIVITY_OPEN:
                animAttr = enter
                        ? WindowAnimation_dreamActivityOpenEnterAnimation
                        : WindowAnimation_dreamActivityOpenExitAnimation;
                break;
            case TRANSIT_OLD_DREAM_ACTIVITY_CLOSE:
                animAttr = enter
                        ? 0
                        : WindowAnimation_dreamActivityCloseExitAnimation;
                break;
        }

        return animAttr;
    }

    @Nullable
    Animation loadAnimationAttr(LayoutParams lp, int animAttr, int transit) {
        return mTransitionAnimation.loadAnimationAttr(lp, animAttr, transit);
    }

    private void getDefaultNextAppTransitionStartRect(Rect rect) {
        if (mDefaultNextAppTransitionAnimationSpec == null ||
                mDefaultNextAppTransitionAnimationSpec.rect == null) {
            Slog.e(TAG, "Starting rect for app requested, but none available", new Throwable());
            rect.setEmpty();
        } else {
            rect.set(mDefaultNextAppTransitionAnimationSpec.rect);
        }
    }

    private void putDefaultNextAppTransitionCoordinates(int left, int top, int width, int height,
            HardwareBuffer buffer) {
        mDefaultNextAppTransitionAnimationSpec = new AppTransitionAnimationSpec(-1 /* taskId */,
                buffer, new Rect(left, top, left + width, top + height));
    }

    /**
     * Creates an overlay with a background color and a thumbnail for the cross profile apps
     * animation.
     */
    HardwareBuffer createCrossProfileAppsThumbnail(
            Drawable thumbnailDrawable, Rect frame) {
        return mTransitionAnimation.createCrossProfileAppsThumbnail(thumbnailDrawable, frame);
    }

    Animation createCrossProfileAppsThumbnailAnimationLocked(Rect appRect) {
        return mTransitionAnimation.createCrossProfileAppsThumbnailAnimationLocked(appRect);
    }

    /**
     * This animation runs for the thumbnail that gets cross faded with the enter/exit activity
     * when a thumbnail is specified with the pending animation override.
     */
    Animation createThumbnailAspectScaleAnimationLocked(Rect appRect, @Nullable Rect contentInsets,
            HardwareBuffer thumbnailHeader, WindowContainer container, int orientation) {
        AppTransitionAnimationSpec spec = mNextAppTransitionAnimationsSpecs.get(
                container.hashCode());
        return mTransitionAnimation.createThumbnailAspectScaleAnimationLocked(appRect,
                contentInsets, thumbnailHeader, orientation, spec != null ? spec.rect : null,
                mDefaultNextAppTransitionAnimationSpec != null
                        ? mDefaultNextAppTransitionAnimationSpec.rect : null,
                mNextAppTransitionScaleUp);
    }

    private AnimationSet createAspectScaledThumbnailFreeformAnimationLocked(Rect sourceFrame,
            Rect destFrame, @Nullable Rect surfaceInsets, boolean enter) {
        final float sourceWidth = sourceFrame.width();
        final float sourceHeight = sourceFrame.height();
        final float destWidth = destFrame.width();
        final float destHeight = destFrame.height();
        final float scaleH = enter ? sourceWidth / destWidth : destWidth / sourceWidth;
        final float scaleV = enter ? sourceHeight / destHeight : destHeight / sourceHeight;
        AnimationSet set = new AnimationSet(true);
        final int surfaceInsetsH = surfaceInsets == null
                ? 0 : surfaceInsets.left + surfaceInsets.right;
        final int surfaceInsetsV = surfaceInsets == null
                ? 0 : surfaceInsets.top + surfaceInsets.bottom;
        // We want the scaling to happen from the center of the surface. In order to achieve that,
        // we need to account for surface insets that will be used to enlarge the surface.
        final float scaleHCenter = ((enter ? destWidth : sourceWidth) + surfaceInsetsH) / 2;
        final float scaleVCenter = ((enter ? destHeight : sourceHeight) + surfaceInsetsV) / 2;
        final ScaleAnimation scale = enter ?
                new ScaleAnimation(scaleH, 1, scaleV, 1, scaleHCenter, scaleVCenter)
                : new ScaleAnimation(1, scaleH, 1, scaleV, scaleHCenter, scaleVCenter);
        final int sourceHCenter = sourceFrame.left + sourceFrame.width() / 2;
        final int sourceVCenter = sourceFrame.top + sourceFrame.height() / 2;
        final int destHCenter = destFrame.left + destFrame.width() / 2;
        final int destVCenter = destFrame.top + destFrame.height() / 2;
        final int fromX = enter ? sourceHCenter - destHCenter : destHCenter - sourceHCenter;
        final int fromY = enter ? sourceVCenter - destVCenter : destVCenter - sourceVCenter;
        final TranslateAnimation translation = enter ? new TranslateAnimation(fromX, 0, fromY, 0)
                : new TranslateAnimation(0, fromX, 0, fromY);
        set.addAnimation(scale);
        set.addAnimation(translation);
        setAppTransitionFinishedCallbackIfNeeded(set);
        return set;
    }

    /**
     * @return true if and only if the first frame of the transition can be skipped, i.e. the first
     *         frame of the transition doesn't change the visuals on screen, so we can start
     *         directly with the second one
     */
    boolean canSkipFirstFrame() {
        return mNextAppTransitionType != NEXT_TRANSIT_TYPE_CUSTOM
                && !mNextAppTransitionOverrideRequested
                && mNextAppTransitionType != NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE
                && mNextAppTransitionType != NEXT_TRANSIT_TYPE_CLIP_REVEAL
                && !mNextAppTransitionRequests.contains(TRANSIT_KEYGUARD_GOING_AWAY);
    }

    RemoteAnimationController getRemoteAnimationController() {
        return mRemoteAnimationController;
    }

    /**
     *
     * @param frame These are the bounds of the window when it finishes the animation. This is where
     *              the animation must usually finish in entrance animation, as the next frame will
     *              display the window at these coordinates. In case of exit animation, this is
     *              where the animation must start, as the frame before the animation is displaying
     *              the window at these bounds.
     * @param insets Knowing where the window will be positioned is not enough. Some parts of the
     *               window might be obscured, usually by the system windows (status bar and
     *               navigation bar) and we use content insets to convey that information. This
     *               usually affects the animation aspects vertically, as the system decoration is
     *               at the top and the bottom. For example when we animate from full screen to
     *               recents, we want to exclude the covered parts, because they won't match the
     *               thumbnail after the last frame is executed.
     * @param surfaceInsets In rare situation the surface is larger than the content and we need to
     *                      know about this to make the animation frames match. We currently use
     *                      this for freeform windows, which have larger surfaces to display
     *                      shadows. When we animate them from recents, we want to match the content
     *                      to the recents thumbnail and hence need to account for the surface being
     *                      bigger.
     */
    @Nullable
    Animation loadAnimation(LayoutParams lp, int transit, boolean enter, int uiMode,
            int orientation, Rect frame, Rect displayFrame, Rect insets,
            @Nullable Rect surfaceInsets, @Nullable Rect stableInsets, boolean isVoiceInteraction,
            boolean freeform, WindowContainer container) {

        final boolean canCustomizeAppTransition = container.canCustomizeAppTransition();

        if (mNextAppTransitionOverrideRequested) {
            if (canCustomizeAppTransition || mOverrideTaskTransition) {
                mNextAppTransitionType = NEXT_TRANSIT_TYPE_CUSTOM;
            } else {
                ProtoLog.e(WM_DEBUG_APP_TRANSITIONS_ANIM, "applyAnimation: "
                        + " override requested, but it is prohibited by policy.");
            }
        }

        Animation a;
        if (isKeyguardGoingAwayTransitOld(transit) && enter) {
            a = mTransitionAnimation.loadKeyguardExitAnimation(mNextAppTransitionFlags,
                    transit == TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER);
        } else if (transit == TRANSIT_OLD_KEYGUARD_OCCLUDE
                || transit == TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM) {
            a = null;
        } else if (transit == TRANSIT_OLD_KEYGUARD_UNOCCLUDE && !enter) {
            a = mTransitionAnimation.loadKeyguardUnoccludeAnimation();
        } else if (transit == TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE) {
            a = null;
        } else if (isVoiceInteraction && (transit == TRANSIT_OLD_ACTIVITY_OPEN
                || transit == TRANSIT_OLD_TASK_OPEN
                || transit == TRANSIT_OLD_TASK_TO_FRONT)) {
            a = mTransitionAnimation.loadVoiceActivityOpenAnimation(enter);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation voice: anim=%s transit=%s isEntrance=%b Callers=%s", a,
                    appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (isVoiceInteraction && (transit == TRANSIT_OLD_ACTIVITY_CLOSE
                || transit == TRANSIT_OLD_TASK_CLOSE
                || transit == TRANSIT_OLD_TASK_TO_BACK)) {
            a = mTransitionAnimation.loadVoiceActivityExitAnimation(enter);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation voice: anim=%s transit=%s isEntrance=%b Callers=%s", a,
                    appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (transit == TRANSIT_OLD_ACTIVITY_RELAUNCH) {
            a = mTransitionAnimation.createRelaunchAnimation(frame, insets,
                    mDefaultNextAppTransitionAnimationSpec != null
                            ? mDefaultNextAppTransitionAnimationSpec.rect : null);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s transit=%s Callers=%s", a,
                    appTransitionOldToString(transit), Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM) {
            a = getNextAppRequestedAnimation(enter);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=ANIM_CUSTOM transit=%s "
                            + "isEntrance=%b Callers=%s",
                    a, appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE) {
            a = mTransitionAnimation.loadAppTransitionAnimation(
                    mNextAppTransitionPackage, mNextAppTransitionInPlace);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=ANIM_CUSTOM_IN_PLACE "
                            + "transit=%s Callers=%s",
                    a, appTransitionOldToString(transit), Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CLIP_REVEAL) {
            a = mTransitionAnimation.createClipRevealAnimationLockedCompat(
                    transit, enter, frame, displayFrame,
                    mDefaultNextAppTransitionAnimationSpec != null
                            ? mDefaultNextAppTransitionAnimationSpec.rect : null);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=ANIM_CLIP_REVEAL "
                            + "transit=%s Callers=%s",
                    a, appTransitionOldToString(transit), Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_SCALE_UP) {
            a = mTransitionAnimation.createScaleUpAnimationLockedCompat(transit, enter, frame,
                    mDefaultNextAppTransitionAnimationSpec != null
                            ? mDefaultNextAppTransitionAnimationSpec.rect : null);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=ANIM_SCALE_UP transit=%s "
                            + "isEntrance=%s Callers=%s",
                    a, appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP);
            final HardwareBuffer thumbnailHeader = getAppTransitionThumbnailHeader(container);
            a = mTransitionAnimation.createThumbnailEnterExitAnimationLockedCompat(enter,
                    mNextAppTransitionScaleUp, frame, transit, thumbnailHeader,
                    mDefaultNextAppTransitionAnimationSpec != null
                            ? mDefaultNextAppTransitionAnimationSpec.rect : null);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=%s transit=%s isEntrance=%b "
                            + "Callers=%s",
                    a,  mNextAppTransitionScaleUp
                            ? "ANIM_THUMBNAIL_SCALE_UP" : "ANIM_THUMBNAIL_SCALE_DOWN",
                    appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP);
            AppTransitionAnimationSpec spec = mNextAppTransitionAnimationsSpecs.get(
                    container.hashCode());
            a = mTransitionAnimation.createAspectScaledThumbnailEnterExitAnimationLocked(enter,
                    mNextAppTransitionScaleUp, orientation, transit, frame, insets, surfaceInsets,
                    stableInsets, freeform, spec != null ? spec.rect : null,
                    mDefaultNextAppTransitionAnimationSpec != null
                            ? mDefaultNextAppTransitionAnimationSpec.rect : null);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s nextAppTransition=%s transit=%s isEntrance=%b "
                            + "Callers=%s",
                    a, mNextAppTransitionScaleUp
                            ? "ANIM_THUMBNAIL_ASPECT_SCALE_UP"
                        : "ANIM_THUMBNAIL_ASPECT_SCALE_DOWN",
                    appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS && enter) {
            a = mTransitionAnimation.loadCrossProfileAppEnterAnimation();
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS: "
                            + "anim=%s transit=%s isEntrance=true Callers=%s",
                    a, appTransitionOldToString(transit), Debug.getCallers(3));
        } else if (isChangeTransitOld(transit)) {
            // In the absence of a specific adapter, we just want to keep everything stationary.
            a = new AlphaAnimation(1.f, 1.f);
            a.setDuration(WindowChangeAnimationSpec.ANIMATION_DURATION);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s transit=%s isEntrance=%b Callers=%s",
                    a, appTransitionOldToString(transit), enter, Debug.getCallers(3));
        } else {
            int animAttr = mapOpenCloseTransitTypes(transit, enter);
            if (animAttr != 0) {
                final CustomAppTransition customAppTransition =
                        getCustomAppTransition(animAttr, container);
                if (customAppTransition != null) {
                    a = loadCustomActivityAnimation(customAppTransition, enter, container);
                } else {
                    if (canCustomizeAppTransition) {
                        a = loadAnimationAttr(lp, animAttr, transit);
                    } else {
                        a = mTransitionAnimation.loadDefaultAnimationAttr(animAttr, transit);
                    }
                }
            } else {
                a = null;
            }

            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: anim=%s animAttr=0x%x transit=%s isEntrance=%b "
                            + " canCustomizeAppTransition=%b Callers=%s",
                    a, animAttr, appTransitionOldToString(transit), enter,
                    canCustomizeAppTransition, Debug.getCallers(3));
        }
        setAppTransitionFinishedCallbackIfNeeded(a);

        return a;
    }

    CustomAppTransition getCustomAppTransition(int animAttr, WindowContainer container) {
        ActivityRecord customAnimationSource = container.asActivityRecord();
        if (customAnimationSource == null) {
            return null;
        }

        // Only top activity can customize activity animation.
        // If the animation is for the one below, try to get from the above activity.
        if (animAttr == WindowAnimation_activityOpenExitAnimation
                || animAttr == WindowAnimation_activityCloseEnterAnimation) {
            customAnimationSource = customAnimationSource.getTask()
                    .getActivityAbove(customAnimationSource);
            if (customAnimationSource == null) {
                return null;
            }
        }
        switch (animAttr) {
            case WindowAnimation_activityOpenEnterAnimation:
            case WindowAnimation_activityOpenExitAnimation:
                return customAnimationSource.getCustomAnimation(true /* open */);
            case WindowAnimation_activityCloseEnterAnimation:
            case WindowAnimation_activityCloseExitAnimation:
                return customAnimationSource.getCustomAnimation(false /* open */);
        }
        return null;
    }
    private Animation loadCustomActivityAnimation(@NonNull CustomAppTransition custom,
            boolean enter, WindowContainer container) {
        final ActivityRecord customAnimationSource = container.asActivityRecord();
        final Animation a = mTransitionAnimation.loadAppTransitionAnimation(
                customAnimationSource.packageName, enter
                        ? custom.mEnterAnim : custom.mExitAnim);
        if (a != null && custom.mBackgroundColor != 0) {
            a.setBackdropColor(custom.mBackgroundColor);
            a.setShowBackdrop(true);
        }
        return a;
    }

    int getAppRootTaskClipMode() {
        return mNextAppTransitionRequests.contains(TRANSIT_RELAUNCH)
                || mNextAppTransitionRequests.contains(TRANSIT_KEYGUARD_GOING_AWAY)
                || mNextAppTransitionType == NEXT_TRANSIT_TYPE_CLIP_REVEAL
                ? ROOT_TASK_CLIP_NONE
                : ROOT_TASK_CLIP_AFTER_ANIM;
    }

    @TransitionFlags
    public int getTransitFlags() {
        return mNextAppTransitionFlags;
    }

    void postAnimationCallback() {
        if (mNextAppTransitionCallback != null) {
            mHandler.sendMessage(PooledLambda.obtainMessage(AppTransition::doAnimationCallback,
                    mNextAppTransitionCallback));
            mNextAppTransitionCallback = null;
        }
    }

    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
            @ColorInt int backgroundColor, IRemoteCallback startedCallback,
            IRemoteCallback endedCallback, boolean overrideTaskTransaction) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionOverrideRequested = true;
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionEnter = enterAnim;
            mNextAppTransitionExit = exitAnim;
            mNextAppTransitionBackgroundColor = backgroundColor;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
            mAnimationFinishedCallback = endedCallback;
            mOverrideTaskTransition = overrideTaskTransaction;
        }
    }

    void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_SCALE_UP;
            putDefaultNextAppTransitionCoordinates(startX, startY, startWidth, startHeight, null);
            postAnimationCallback();
        }
    }

    void overridePendingAppTransitionClipReveal(int startX, int startY,
                                                int startWidth, int startHeight) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_CLIP_REVEAL;
            putDefaultNextAppTransitionCoordinates(startX, startY, startWidth, startHeight, null);
            postAnimationCallback();
        }
    }

    void overridePendingAppTransitionThumb(HardwareBuffer srcThumb, int startX, int startY,
                                           IRemoteCallback startedCallback, boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = scaleUp ? NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP
                    : NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN;
            mNextAppTransitionScaleUp = scaleUp;
            putDefaultNextAppTransitionCoordinates(startX, startY, 0, 0, srcThumb);
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        }
    }

    void overridePendingAppTransitionAspectScaledThumb(HardwareBuffer srcThumb, int startX,
            int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback,
            boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = scaleUp ? NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP
                    : NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN;
            mNextAppTransitionScaleUp = scaleUp;
            putDefaultNextAppTransitionCoordinates(startX, startY, targetWidth, targetHeight,
                    srcThumb);
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        }
    }

    void overridePendingAppTransitionMultiThumb(AppTransitionAnimationSpec[] specs,
            IRemoteCallback onAnimationStartedCallback, IRemoteCallback onAnimationFinishedCallback,
            boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = scaleUp ? NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP
                    : NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN;
            mNextAppTransitionScaleUp = scaleUp;
            if (specs != null) {
                for (int i = 0; i < specs.length; i++) {
                    AppTransitionAnimationSpec spec = specs[i];
                    if (spec != null) {
                        final PooledPredicate p = PooledLambda.obtainPredicate(
                                Task::isTaskId, PooledLambda.__(Task.class), spec.taskId);
                        final WindowContainer container = mDisplayContent.getTask(p);
                        p.recycle();
                        if (container == null) {
                            continue;
                        }
                        mNextAppTransitionAnimationsSpecs.put(container.hashCode(), spec);
                        if (i == 0) {
                            // In full screen mode, the transition code depends on the default spec
                            // to be set.
                            Rect rect = spec.rect;
                            putDefaultNextAppTransitionCoordinates(rect.left, rect.top,
                                    rect.width(), rect.height(), spec.buffer);
                        }
                    }
                }
            }
            postAnimationCallback();
            mNextAppTransitionCallback = onAnimationStartedCallback;
            mAnimationFinishedCallback = onAnimationFinishedCallback;
        }
    }

    void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback,
            boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = scaleUp ? NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP
                    : NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN;
            mNextAppTransitionAnimationsSpecsFuture = specsFuture;
            mNextAppTransitionScaleUp = scaleUp;
            mNextAppTransitionFutureCallback = callback;
            if (isReady()) {
                fetchAppTransitionSpecsFromFuture();
            }
        }
    }

    void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter) {
        overridePendingAppTransitionRemote(remoteAnimationAdapter, false /* sync */,
                false /* isActivityEmbedding*/);
    }

    void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter,
            boolean sync, boolean isActivityEmbedding) {
        ProtoLog.i(WM_DEBUG_APP_TRANSITIONS, "Override pending remote transitionSet=%b adapter=%s",
                        isTransitionSet(), remoteAnimationAdapter);
        if (isTransitionSet() && !mNextAppTransitionIsSync) {
            // ActivityEmbedding animation will run by the app process for which we want to respect
            // the app override for whether or not to show background color.
            clear(!isActivityEmbedding /* clearAppOverride */);
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_REMOTE;
            mRemoteAnimationController = new RemoteAnimationController(mService, mDisplayContent,
                    remoteAnimationAdapter, mHandler, isActivityEmbedding);
            mNextAppTransitionIsSync = sync;
        }
    }

    void overrideInPlaceAppTransition(String packageName, int anim) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE;
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionInPlace = anim;
        }
    }

    /**
     * @see {@link #NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS}
     */
    void overridePendingAppTransitionStartCrossProfileApps() {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS;
            postAnimationCallback();
        }
    }

    private boolean canOverridePendingAppTransition() {
        // Remote animations always take precedence
        return isTransitionSet() &&  mNextAppTransitionType != NEXT_TRANSIT_TYPE_REMOTE;
    }

    /**
     * If a future is set for the app transition specs, fetch it in another thread.
     */
    private void fetchAppTransitionSpecsFromFuture() {
        if (mNextAppTransitionAnimationsSpecsFuture != null) {
            mNextAppTransitionAnimationsSpecsPending = true;
            final IAppTransitionAnimationSpecsFuture future
                    = mNextAppTransitionAnimationsSpecsFuture;
            mNextAppTransitionAnimationsSpecsFuture = null;
            mDefaultExecutor.execute(() -> {
                AppTransitionAnimationSpec[] specs = null;
                try {
                    Binder.allowBlocking(future.asBinder());
                    specs = future.get();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to fetch app transition specs: " + e);
                }
                synchronized (mService.mGlobalLock) {
                    mNextAppTransitionAnimationsSpecsPending = false;
                    overridePendingAppTransitionMultiThumb(specs,
                            mNextAppTransitionFutureCallback, null /* finishedCallback */,
                            mNextAppTransitionScaleUp);
                    mNextAppTransitionFutureCallback = null;
                    mService.requestTraversal();
                }
            });
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("mNextAppTransitionRequests=[");

        boolean separator = false;
        for (Integer transit : mNextAppTransitionRequests) {
            if (separator) {
                sb.append(", ");
            }
            sb.append(appTransitionToString(transit));
            separator = true;
        }
        sb.append("]");
        sb.append(", mNextAppTransitionFlags="
                + appTransitionFlagsToString(mNextAppTransitionFlags));
        return sb.toString();
    }

    /**
     * Returns the human readable name of a old window transition.
     *
     * @param transition The old window transition.
     * @return The transition symbolic name.
     */
    public static String appTransitionOldToString(@TransitionOldType int transition) {
        switch (transition) {
            case TRANSIT_OLD_UNSET: {
                return "TRANSIT_OLD_UNSET";
            }
            case TRANSIT_OLD_NONE: {
                return "TRANSIT_OLD_NONE";
            }
            case TRANSIT_OLD_ACTIVITY_OPEN: {
                return "TRANSIT_OLD_ACTIVITY_OPEN";
            }
            case TRANSIT_OLD_ACTIVITY_CLOSE: {
                return "TRANSIT_OLD_ACTIVITY_CLOSE";
            }
            case TRANSIT_OLD_TASK_OPEN: {
                return "TRANSIT_OLD_TASK_OPEN";
            }
            case TRANSIT_OLD_TASK_CLOSE: {
                return "TRANSIT_OLD_TASK_CLOSE";
            }
            case TRANSIT_OLD_TASK_TO_FRONT: {
                return "TRANSIT_OLD_TASK_TO_FRONT";
            }
            case TRANSIT_OLD_TASK_TO_BACK: {
                return "TRANSIT_OLD_TASK_TO_BACK";
            }
            case TRANSIT_OLD_WALLPAPER_CLOSE: {
                return "TRANSIT_OLD_WALLPAPER_CLOSE";
            }
            case TRANSIT_OLD_WALLPAPER_OPEN: {
                return "TRANSIT_OLD_WALLPAPER_OPEN";
            }
            case TRANSIT_OLD_WALLPAPER_INTRA_OPEN: {
                return "TRANSIT_OLD_WALLPAPER_INTRA_OPEN";
            }
            case TRANSIT_OLD_WALLPAPER_INTRA_CLOSE: {
                return "TRANSIT_OLD_WALLPAPER_INTRA_CLOSE";
            }
            case TRANSIT_OLD_TASK_OPEN_BEHIND: {
                return "TRANSIT_OLD_TASK_OPEN_BEHIND";
            }
            case TRANSIT_OLD_ACTIVITY_RELAUNCH: {
                return "TRANSIT_OLD_ACTIVITY_RELAUNCH";
            }
            case TRANSIT_OLD_KEYGUARD_GOING_AWAY: {
                return "TRANSIT_OLD_KEYGUARD_GOING_AWAY";
            }
            case TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER: {
                return "TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER";
            }
            case TRANSIT_OLD_KEYGUARD_OCCLUDE: {
                return "TRANSIT_OLD_KEYGUARD_OCCLUDE";
            }
            case TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM: {
                return "TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM";
            }
            case TRANSIT_OLD_KEYGUARD_UNOCCLUDE: {
                return "TRANSIT_OLD_KEYGUARD_UNOCCLUDE";
            }
            case TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN: {
                return "TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN";
            }
            case TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE: {
                return "TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE";
            }
            case TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE: {
                return "TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE";
            }
            case TRANSIT_OLD_TASK_FRAGMENT_OPEN: {
                return "TRANSIT_OLD_TASK_FRAGMENT_OPEN";
            }
            case TRANSIT_OLD_TASK_FRAGMENT_CLOSE: {
                return "TRANSIT_OLD_TASK_FRAGMENT_CLOSE";
            }
            case TRANSIT_OLD_TASK_FRAGMENT_CHANGE: {
                return "TRANSIT_OLD_TASK_FRAGMENT_CHANGE";
            }
            case TRANSIT_OLD_DREAM_ACTIVITY_OPEN: {
                return "TRANSIT_OLD_DREAM_ACTIVITY_OPEN";
            }
            case TRANSIT_OLD_DREAM_ACTIVITY_CLOSE: {
                return "TRANSIT_OLD_DREAM_ACTIVITY_CLOSE";
            }
            default: {
                return "<UNKNOWN: " + transition + ">";
            }
        }
    }

    /**
     * Returns the human readable name of a window transition.
     *
     * @param transition The window transition.
     * @return The transition symbolic name.
     */
    public static String appTransitionToString(@TransitionType int transition) {
        switch (transition) {
            case TRANSIT_NONE: {
                return "TRANSIT_NONE";
            }
            case TRANSIT_OPEN: {
                return "TRANSIT_OPEN";
            }
            case TRANSIT_CLOSE: {
                return "TRANSIT_CLOSE";
            }
            case TRANSIT_TO_FRONT: {
                return "TRANSIT_TO_FRONT";
            }
            case TRANSIT_TO_BACK: {
                return "TRANSIT_TO_BACK";
            }
            case TRANSIT_RELAUNCH: {
                return "TRANSIT_RELAUNCH";
            }
            case TRANSIT_CHANGE: {
                return "TRANSIT_CHANGE";
            }
            case TRANSIT_KEYGUARD_GOING_AWAY: {
                return "TRANSIT_KEYGUARD_GOING_AWAY";
            }
            case TRANSIT_KEYGUARD_OCCLUDE: {
                return "TRANSIT_KEYGUARD_OCCLUDE";
            }
            case TRANSIT_KEYGUARD_UNOCCLUDE: {
                return "TRANSIT_KEYGUARD_UNOCCLUDE";
            }
            default: {
                return "<UNKNOWN: " + transition + ">";
            }
        }
    }

    private String appStateToString() {
        switch (mAppTransitionState) {
            case APP_STATE_IDLE:
                return "APP_STATE_IDLE";
            case APP_STATE_READY:
                return "APP_STATE_READY";
            case APP_STATE_RUNNING:
                return "APP_STATE_RUNNING";
            case APP_STATE_TIMEOUT:
                return "APP_STATE_TIMEOUT";
            default:
                return "unknown state=" + mAppTransitionState;
        }
    }

    private String transitTypeToString() {
        switch (mNextAppTransitionType) {
            case NEXT_TRANSIT_TYPE_NONE:
                return "NEXT_TRANSIT_TYPE_NONE";
            case NEXT_TRANSIT_TYPE_CUSTOM:
                return "NEXT_TRANSIT_TYPE_CUSTOM";
            case NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE:
                return "NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE";
            case NEXT_TRANSIT_TYPE_SCALE_UP:
                return "NEXT_TRANSIT_TYPE_SCALE_UP";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN";
            case NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS:
                return "NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS";
            default:
                return "unknown type=" + mNextAppTransitionType;
        }
    }

    private static final ArrayList<Pair<Integer, String>> sFlagToString;

    static {
        sFlagToString = new ArrayList<>();
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE,
                "TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE"));
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION,
                "TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION"));
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER,
                "TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER"));
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION,
                "TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION"));
        sFlagToString.add(new Pair<>(
                TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT,
                "TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_WITH_IN_WINDOW_ANIMATIONS"));
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_APP_CRASHED,
                "TRANSIT_FLAG_APP_CRASHED"));
        sFlagToString.add(new Pair<>(TRANSIT_FLAG_OPEN_BEHIND,
                "TRANSIT_FLAG_OPEN_BEHIND"));
    }

    /**
     * Returns the human readable names of transit flags.
     *
     * @param flags a bitmask combination of transit flags.
     * @return The combination of symbolic names.
     */
    public static String appTransitionFlagsToString(int flags) {
        String sep = "";
        StringBuilder sb = new StringBuilder();
        for (Pair<Integer, String> pair : sFlagToString) {
            if ((flags & pair.first) != 0) {
                sb.append(sep);
                sb.append(pair.second);
                sep = " | ";
            }
        }
        return sb.toString();
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(APP_TRANSITION_STATE, mAppTransitionState);
        proto.write(LAST_USED_APP_TRANSITION, mLastUsedAppTransition);
        proto.end(token);
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.println(this);
        pw.print(prefix); pw.print("mAppTransitionState="); pw.println(appStateToString());
        if (mNextAppTransitionType != NEXT_TRANSIT_TYPE_NONE) {
            pw.print(prefix); pw.print("mNextAppTransitionType=");
                    pw.println(transitTypeToString());
        }
        if (mNextAppTransitionOverrideRequested
                || mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM) {
            pw.print(prefix); pw.print("mNextAppTransitionPackage=");
            pw.println(mNextAppTransitionPackage);
            pw.print(prefix); pw.print("mNextAppTransitionEnter=0x");
            pw.print(Integer.toHexString(mNextAppTransitionEnter));
            pw.print(" mNextAppTransitionExit=0x");
            pw.println(Integer.toHexString(mNextAppTransitionExit));
            pw.print(" mNextAppTransitionBackgroundColor=0x");
            pw.println(Integer.toHexString(mNextAppTransitionBackgroundColor));
        }
        switch (mNextAppTransitionType) {
            case NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE:
                pw.print(prefix); pw.print("mNextAppTransitionPackage=");
                        pw.println(mNextAppTransitionPackage);
                pw.print(prefix); pw.print("mNextAppTransitionInPlace=0x");
                        pw.print(Integer.toHexString(mNextAppTransitionInPlace));
                break;
            case NEXT_TRANSIT_TYPE_SCALE_UP: {
                getDefaultNextAppTransitionStartRect(mTmpRect);
                pw.print(prefix); pw.print("mNextAppTransitionStartX=");
                        pw.print(mTmpRect.left);
                        pw.print(" mNextAppTransitionStartY=");
                        pw.println(mTmpRect.top);
                pw.print(prefix); pw.print("mNextAppTransitionStartWidth=");
                        pw.print(mTmpRect.width());
                        pw.print(" mNextAppTransitionStartHeight=");
                        pw.println(mTmpRect.height());
                break;
            }
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP:
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN:
            case NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP:
            case NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN: {
                pw.print(prefix); pw.print("mDefaultNextAppTransitionAnimationSpec=");
                        pw.println(mDefaultNextAppTransitionAnimationSpec);
                pw.print(prefix); pw.print("mNextAppTransitionAnimationsSpecs=");
                        pw.println(mNextAppTransitionAnimationsSpecs);
                pw.print(prefix); pw.print("mNextAppTransitionScaleUp=");
                        pw.println(mNextAppTransitionScaleUp);
                break;
            }
        }
        if (mNextAppTransitionCallback != null) {
            pw.print(prefix); pw.print("mNextAppTransitionCallback=");
                    pw.println(mNextAppTransitionCallback);
        }
        if (mLastUsedAppTransition != TRANSIT_OLD_NONE) {
            pw.print(prefix); pw.print("mLastUsedAppTransition=");
                    pw.println(appTransitionOldToString(mLastUsedAppTransition));
            pw.print(prefix); pw.print("mLastOpeningApp=");
                    pw.println(mLastOpeningApp);
            pw.print(prefix); pw.print("mLastClosingApp=");
                    pw.println(mLastClosingApp);
            pw.print(prefix); pw.print("mLastChangingApp=");
            pw.println(mLastChangingApp);
        }
    }

    boolean prepareAppTransition(@TransitionType int transit, @TransitionFlags int flags) {
        if (mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            return false;
        }
        mNextAppTransitionRequests.add(transit);
        mNextAppTransitionFlags |= flags;
        updateBooster();
        removeAppTransitionTimeoutCallbacks();
        mHandler.postDelayed(mHandleAppTransitionTimeoutRunnable,
                APP_TRANSITION_TIMEOUT_MS);
        return prepare();
    }

    /**
     * @return true if {@param transit} is representing a transition in which Keyguard is going
     *         away, false otherwise
     */
    public static boolean isKeyguardGoingAwayTransitOld(int transit) {
        return transit == TRANSIT_OLD_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
    }

    static boolean isKeyguardOccludeTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_KEYGUARD_OCCLUDE
                || transit == TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM
                || transit == TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
    }

    static boolean isKeyguardTransitOld(@TransitionOldType int transit) {
        return isKeyguardGoingAwayTransitOld(transit) || isKeyguardOccludeTransitOld(transit);
    }

    static boolean isTaskTransitOld(@TransitionOldType int transit) {
        return isTaskOpenTransitOld(transit)
                || isTaskCloseTransitOld(transit);
    }

    static boolean isTaskCloseTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_TASK_CLOSE
                || transit == TRANSIT_OLD_TASK_TO_BACK;
    }

    private static  boolean isTaskOpenTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_TASK_OPEN
                || transit == TRANSIT_OLD_TASK_OPEN_BEHIND
                || transit == TRANSIT_OLD_TASK_TO_FRONT;
    }

    static boolean isActivityTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_ACTIVITY_OPEN
                || transit == TRANSIT_OLD_ACTIVITY_CLOSE
                || transit == TRANSIT_OLD_ACTIVITY_RELAUNCH;
    }

    static boolean isTaskFragmentTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_TASK_FRAGMENT_OPEN
                || transit == TRANSIT_OLD_TASK_FRAGMENT_CLOSE
                || transit == TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
    }

    static boolean isChangeTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE
                || transit == TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
    }

    static boolean isClosingTransitOld(@TransitionOldType int transit) {
        return transit == TRANSIT_OLD_ACTIVITY_CLOSE
                || transit == TRANSIT_OLD_TASK_CLOSE
                || transit == TRANSIT_OLD_WALLPAPER_CLOSE
                || transit == TRANSIT_OLD_WALLPAPER_INTRA_CLOSE
                || transit == TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE
                || transit == TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
    }

    static boolean isNormalTransit(@TransitionType int transit) {
        return transit == TRANSIT_OPEN
                || transit == TRANSIT_CLOSE
                || transit == TRANSIT_TO_FRONT
                || transit == TRANSIT_TO_BACK;
    }

    static boolean isKeyguardTransit(@TransitionType int transit) {
        return transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_OCCLUDE
                || transit == TRANSIT_KEYGUARD_UNOCCLUDE;
    }

    @TransitionType int getKeyguardTransition() {
        if (mNextAppTransitionRequests.indexOf(TRANSIT_KEYGUARD_GOING_AWAY) != -1) {
            return TRANSIT_KEYGUARD_GOING_AWAY;
        }
        final int unoccludeIndex = mNextAppTransitionRequests.indexOf(TRANSIT_KEYGUARD_UNOCCLUDE);
        final int occludeIndex = mNextAppTransitionRequests.indexOf(TRANSIT_KEYGUARD_OCCLUDE);
        // No keyguard related transition requests.
        if (unoccludeIndex == -1 && occludeIndex == -1) {
            return TRANSIT_NONE;
        }
        // In case we unocclude Keyguard and occlude it again, meaning that we never actually
        // unoccclude/occlude Keyguard, but just run a normal transition.
        if (unoccludeIndex != -1 && unoccludeIndex < occludeIndex) {
            return TRANSIT_NONE;
        }
        return unoccludeIndex != -1 ? TRANSIT_KEYGUARD_UNOCCLUDE : TRANSIT_KEYGUARD_OCCLUDE;
    }

    @TransitionType int getFirstAppTransition() {
        for (int i = 0; i < mNextAppTransitionRequests.size(); ++i) {
            final @TransitionType int transit = mNextAppTransitionRequests.get(i);
            if (transit != TRANSIT_NONE && !isKeyguardTransit(transit)) {
                return transit;
            }
        }
        return TRANSIT_NONE;
    }

    boolean containsTransitRequest(@TransitionType int transit) {
        return mNextAppTransitionRequests.contains(transit);
    }

    private void handleAppTransitionTimeout() {
        synchronized (mService.mGlobalLock) {
            final DisplayContent dc = mDisplayContent;
            if (dc == null) {
                return;
            }
            notifyAppTransitionTimeoutLocked();
            if (isTransitionSet() || !dc.mOpeningApps.isEmpty() || !dc.mClosingApps.isEmpty()
                    || !dc.mChangingContainers.isEmpty()) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                            "*** APP TRANSITION TIMEOUT. displayId=%d isTransitionSet()=%b "
                                    + "mOpeningApps.size()=%d mClosingApps.size()=%d "
                                    + "mChangingApps.size()=%d",
                            dc.getDisplayId(), dc.mAppTransition.isTransitionSet(),
                            dc.mOpeningApps.size(), dc.mClosingApps.size(),
                            dc.mChangingContainers.size());

                setTimeout();
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
        }
    }

    private static void doAnimationCallback(@NonNull IRemoteCallback callback) {
        try {
            ((IRemoteCallback) callback).sendResult(null);
        } catch (RemoteException e) {
        }
    }

    private void setAppTransitionFinishedCallbackIfNeeded(Animation anim) {
        final IRemoteCallback callback = mAnimationFinishedCallback;
        if (callback != null && anim != null) {
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) { }

                @Override
                public void onAnimationEnd(Animation animation) {
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppTransition::doAnimationCallback, callback));
                }

                @Override
                public void onAnimationRepeat(Animation animation) { }
            });
        }
    }

    void removeAppTransitionTimeoutCallbacks() {
        mHandler.removeCallbacks(mHandleAppTransitionTimeoutRunnable);
    }
}
