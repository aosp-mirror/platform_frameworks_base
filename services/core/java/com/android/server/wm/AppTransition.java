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
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_ACTIVITY_RELAUNCH;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_IN_PLACE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_UNSET;
import static android.view.WindowManager.TRANSIT_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_OPEN;

import static com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
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
import static com.android.server.wm.AppTransitionProto.APP_TRANSITION_STATE;
import static com.android.server.wm.AppTransitionProto.LAST_USED_APP_TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_NONE;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManager.TransitionFlags;
import android.view.WindowManager.TransitionType;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils.Dump;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AttributeCache;
import com.android.server.wm.animation.ClipRectLRAnimation;
import com.android.server.wm.animation.ClipRectTBAnimation;
import com.android.server.wm.animation.CurvedTranslateAnimation;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.android.internal.util.atom.AwesomeAnimationHelper;
import android.widget.Toast;

// State management of app transitions.  When we are preparing for a
// transition, mNextAppTransition will be the kind of transition to
// perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
// mOpeningApps and mClosingApps are the lists of tokens that will be
// made visible or hidden at the next transition.
public class AppTransition implements Dump {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppTransition" : TAG_WM;
    private static final int CLIP_REVEAL_TRANSLATION_Y_DP = 8;

    /** Fraction of animation at which the recents thumbnail stays completely transparent */
    private static final float RECENTS_THUMBNAIL_FADEIN_FRACTION = 0.5f;
    /** Fraction of animation at which the recents thumbnail becomes completely transparent */
    private static final float RECENTS_THUMBNAIL_FADEOUT_FRACTION = 0.5f;

    static final int DEFAULT_APP_TRANSITION_DURATION = 336;

    /** Interpolator to be used for animations that respond directly to a touch */
    static final Interpolator TOUCH_RESPONSE_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private static final Interpolator THUMBNAIL_DOCK_INTERPOLATOR =
            new PathInterpolator(0.85f, 0f, 1f, 1f);

    /**
     * Maximum duration for the clip reveal animation. This is used when there is a lot of movement
     * involved, to make it more understandable.
     */
    private static final int MAX_CLIP_REVEAL_TRANSITION_DURATION = 420;
    private static final int THUMBNAIL_APP_TRANSITION_DURATION = 336;
    private static final long APP_TRANSITION_TIMEOUT_MS = 5000;

    private final Context mContext;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;

    private @TransitionType int mNextAppTransition = TRANSIT_UNSET;
    private @TransitionFlags int mNextAppTransitionFlags = 0;
    private int mLastUsedAppTransition = TRANSIT_UNSET;
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

    // These are the possible states for the enter/exit activities during a thumbnail transition
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_UP = 0;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_UP = 1;
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN = 2;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN = 3;

    private String mNextAppTransitionPackage;
    // Used for thumbnail transitions. True if we're scaling up, false if scaling down
    private boolean mNextAppTransitionScaleUp;
    private IRemoteCallback mNextAppTransitionCallback;
    private IRemoteCallback mNextAppTransitionFutureCallback;
    private IRemoteCallback mAnimationFinishedCallback;
    private int mNextAppTransitionEnter;
    private int mNextAppTransitionExit;
    private int mNextAppTransitionInPlace;

    // Keyed by task id.
    private final SparseArray<AppTransitionAnimationSpec> mNextAppTransitionAnimationsSpecs
            = new SparseArray<>();
    private IAppTransitionAnimationSpecsFuture mNextAppTransitionAnimationsSpecsFuture;
    private boolean mNextAppTransitionAnimationsSpecsPending;
    private AppTransitionAnimationSpec mDefaultNextAppTransitionAnimationSpec;

    private Rect mNextAppTransitionInsets = new Rect();

    private Rect mTmpFromClipRect = new Rect();
    private Rect mTmpToClipRect = new Rect();

    private final Rect mTmpRect = new Rect();

    private final static int APP_STATE_IDLE = 0;
    private final static int APP_STATE_READY = 1;
    private final static int APP_STATE_RUNNING = 2;
    private final static int APP_STATE_TIMEOUT = 3;
    private int mAppTransitionState = APP_STATE_IDLE;

    private final int mConfigShortAnimTime;
    private final Interpolator mDecelerateInterpolator;
    private final Interpolator mThumbnailFadeInInterpolator;
    private final Interpolator mThumbnailFadeOutInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final Interpolator mFastOutLinearInInterpolator;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mClipHorizontalInterpolator = new PathInterpolator(0, 0, 0.4f, 1f);

    private final int mClipRevealTranslationY;

    private SettingsObserver mSettingsObserver;
    private int[] mActivityAnimations = new int[11];
    private int mAnimationDuration;
    private boolean mIsResId = false;

    private int mCurrentUserId = 0;
    private long mLastClipRevealTransitionDuration = DEFAULT_APP_TRANSITION_DURATION;

    private final ArrayList<AppTransitionListener> mListeners = new ArrayList<>();
    private final ExecutorService mDefaultExecutor = Executors.newSingleThreadExecutor();

    private int mLastClipRevealMaxTranslation;
    private boolean mLastHadClipReveal;

    private final boolean mGridLayoutRecentsEnabled;
    private final boolean mLowRamRecentsEnabled;

    private final int mDefaultWindowAnimationStyleResId;

    private RemoteAnimationController mRemoteAnimationController;

    final Handler mHandler;
    final Runnable mHandleAppTransitionTimeoutRunnable = () -> handleAppTransitionTimeout();

    AppTransition(Context context, WindowManagerService service, DisplayContent displayContent) {
        mContext = context;
        mService = service;
        mHandler = new Handler(service.mH.getLooper());
        mDisplayContent = displayContent;
        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();
        updateSettings();
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.linear_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mConfigShortAnimTime = context.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime);
        mDecelerateInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_cubic);
        mThumbnailFadeInInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float input) {
                // Linear response for first fraction, then complete after that.
                if (input < RECENTS_THUMBNAIL_FADEIN_FRACTION) {
                    return 0f;
                }
                float t = (input - RECENTS_THUMBNAIL_FADEIN_FRACTION) /
                        (1f - RECENTS_THUMBNAIL_FADEIN_FRACTION);
                return mFastOutLinearInInterpolator.getInterpolation(t);
            }
        };
        mThumbnailFadeOutInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float input) {
                // Linear response for first fraction, then complete after that.
                if (input < RECENTS_THUMBNAIL_FADEOUT_FRACTION) {
                    float t = input / RECENTS_THUMBNAIL_FADEOUT_FRACTION;
                    return mLinearOutSlowInInterpolator.getInterpolation(t);
                }
                return 1f;
            }
        };
        mClipRevealTranslationY = (int) (CLIP_REVEAL_TRANSLATION_Y_DP
                * mContext.getResources().getDisplayMetrics().density);
        mGridLayoutRecentsEnabled = SystemProperties.getBoolean("ro.recents.grid", false);
        mLowRamRecentsEnabled = ActivityManager.isLowRamDeviceStatic();

        final TypedArray windowStyle = mContext.getTheme().obtainStyledAttributes(
                com.android.internal.R.styleable.Window);
        mDefaultWindowAnimationStyleResId = windowStyle.getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        windowStyle.recycle();
    }

    boolean isTransitionSet() {
        return mNextAppTransition != TRANSIT_UNSET;
    }

    boolean isTransitionEqual(@TransitionType int transit) {
        return mNextAppTransition == transit;
    }

    @TransitionType int getAppTransition() {
        return mNextAppTransition;
     }

    private void setAppTransition(int transit, int flags) {
        mNextAppTransition = transit;
        mNextAppTransitionFlags |= flags;
        setLastAppTransition(TRANSIT_UNSET, null, null, null);
        updateBooster();
    }

    void setLastAppTransition(int transit, AppWindowToken openingApp, AppWindowToken closingApp,
            AppWindowToken changingApp) {
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

    boolean isTimeout() {
        return mAppTransitionState == APP_STATE_TIMEOUT;
    }

    void setTimeout() {
        setAppTransitionState(APP_STATE_TIMEOUT);
    }

    GraphicBuffer getAppTransitionThumbnailHeader(int taskId) {
        AppTransitionAnimationSpec spec = mNextAppTransitionAnimationsSpecs.get(taskId);
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
            mLastHadClipReveal = false;
            mLastClipRevealMaxTranslation = 0;
            mLastClipRevealTransitionDuration = DEFAULT_APP_TRANSITION_DURATION;
            return true;
        }
        return false;
    }

    /**
     * @return bit-map of WindowManagerPolicy#FINISH_LAYOUT_REDO_* to indicate whether another
     *         layout pass needs to be done
     */
    int goodToGo(int transit, AppWindowToken topOpeningApp, ArraySet<AppWindowToken> openingApps) {
        mNextAppTransition = TRANSIT_UNSET;
        mNextAppTransitionFlags = 0;
        setAppTransitionState(APP_STATE_RUNNING);
        final AnimationAdapter topOpeningAnim = topOpeningApp != null
                ? topOpeningApp.getAnimation()
                : null;
        int redoLayout = notifyAppTransitionStartingLocked(transit,
                topOpeningAnim != null ? topOpeningAnim.getDurationHint() : 0,
                topOpeningAnim != null
                        ? topOpeningAnim.getStatusBarTransitionsStartTime()
                        : SystemClock.uptimeMillis(),
                AnimationAdapter.STATUS_BAR_TRANSITION_DURATION);
        mDisplayContent.getDockedDividerController()
                .notifyAppTransitionStarting(openingApps, transit);

        if (mRemoteAnimationController != null) {
            mRemoteAnimationController.goodToGo();
        }
        return redoLayout;
    }

    void clear() {
        mNextAppTransitionType = NEXT_TRANSIT_TYPE_NONE;
        mNextAppTransitionPackage = null;
        mNextAppTransitionAnimationsSpecs.clear();
        mRemoteAnimationController = null;
        mNextAppTransitionAnimationsSpecsFuture = null;
        mDefaultNextAppTransitionAnimationSpec = null;
        mAnimationFinishedCallback = null;
    }

    void freeze() {
        final int transit = mNextAppTransition;
        // The RemoteAnimationControl didn't register AppTransitionListener and
        // only initialized the finish and timeout callback when goodToGo().
        // So cancel the remote animation here to prevent the animation can't do
        // finish after transition state cleared.
        if (mRemoteAnimationController != null) {
            mRemoteAnimationController.cancelAnimation("freeze");
        }
        setAppTransition(TRANSIT_UNSET, 0 /* flags */);
        clear();
        setReady();
        notifyAppTransitionCancelledLocked(transit);
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
        return mNextAppTransition != TRANSIT_UNSET
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

    private void notifyAppTransitionCancelledLocked(int transit) {
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onAppTransitionCancelledLocked(transit);
        }
    }

    private int notifyAppTransitionStartingLocked(int transit, long duration,
            long statusBarAnimationStartTime, long statusBarAnimationDuration) {
        int redoLayout = 0;
        for (int i = 0; i < mListeners.size(); i++) {
            redoLayout |= mListeners.get(i).onAppTransitionStartingLocked(transit, duration,
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
        int resId = lp.windowAnimations;
        if (lp.type == LayoutParams.TYPE_APPLICATION_STARTING) {
            // Note that we don't want application to customize starting window animation.
            // Since this window is specific for displaying while app starting,
            // application should not change its animation directly.
            // In this case, it will use system resource to get default animation.
            resId = mDefaultWindowAnimationStyleResId;
        }
        return resId;
    }

    private AttributeCache.Entry getCachedAnimations(LayoutParams lp) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: layout params pkg="
                + (lp != null ? lp.packageName : null)
                + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : "android";
            int resId = getAnimationStyleResId(lp);
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation, mCurrentUserId);
        }
        return null;
    }

    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: package="
                + packageName + " resId=0x" + Integer.toHexString(resId));
        if (packageName != null) {
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation, mCurrentUserId);
        }
        return null;
    }

    Animation loadAnimationAttr(LayoutParams lp, int animAttr, int transit) {
        int resId = Resources.ID_NULL;
        Context context = mContext;
        if (animAttr >= 0) {
            if (mIsResId) {
                resId = animAttr;
            } else {
                AttributeCache.Entry ent = getCachedAnimations(lp);
                if (ent != null) {
                    context = ent.context;
                    resId = ent.array.getResourceId(animAttr, 0);
                }
            }
        }
        resId = updateToTranslucentAnimIfNeeded(resId, transit);
        if (ResourceId.isValid(resId)) {
            return loadAnimationSafely(context, resId);
        }
        return null;
    }

    private Animation loadAnimationRes(LayoutParams lp, int resId) {
        Context context = mContext;
        if (ResourceId.isValid(resId)) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
            }
            return loadAnimationSafely(context, resId);
        }
        return null;
    }

    private Animation loadAnimationRes(String packageName, int resId) {
        if (ResourceId.isValid(resId)) {
            AttributeCache.Entry ent = getCachedAnimations(packageName, resId);
            if (ent != null) {
                return loadAnimationSafely(ent.context, resId);
            }
        }
        return null;
    }

    @VisibleForTesting
    Animation loadAnimationSafely(Context context, int resId) {
        try {
            return AnimationUtils.loadAnimation(context, resId);
        } catch (NotFoundException e) {
            Slog.w(TAG, "Unable to load animation resource", e);
            return null;
        }
    }

    private int updateToTranslucentAnimIfNeeded(int anim, int transit) {
        if (transit == TRANSIT_TRANSLUCENT_ACTIVITY_OPEN && anim == R.anim.activity_open_enter) {
            return R.anim.activity_translucent_open_enter;
        }
        if (transit == TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE && anim == R.anim.activity_close_exit) {
            return R.anim.activity_translucent_close_exit;
        }
        return anim;
    }

    /**
     * Compute the pivot point for an animation that is scaling from a small
     * rect on screen to a larger rect.  The pivot point varies depending on
     * the distance between the inner and outer edges on both sides.  This
     * function computes the pivot point for one dimension.
     * @param startPos  Offset from left/top edge of outer rectangle to
     * left/top edge of inner rectangle.
     * @param finalScale The scaling factor between the size of the outer
     * and inner rectangles.
     */
    private static float computePivot(int startPos, float finalScale) {

        /*
        Theorem of intercepting lines:

          +      +   +-----------------------------------------------+
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
        x |    y |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      +   |             +--------------------+            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             +--------------------+            |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          +-----------------------------------------------+
          |
          |
          |
          |
          |
          |
          |
          |
          |
          +                                 ++
                                         p  ++

        scale = (x - y) / x
        <=> x = -y / (scale - 1)
        */
        final float denom = finalScale-1;
        if (Math.abs(denom) < .0001f) {
            return startPos;
        }
        return -startPos / denom;
    }

    private Animation createScaleUpAnimationLocked(int transit, boolean enter,
            Rect containingFrame) {
        Animation a;
        getDefaultNextAppTransitionStartRect(mTmpRect);
        final int appWidth = containingFrame.width();
        final int appHeight = containingFrame.height();
        if (enter) {
            // Entering app zooms out from the center of the initial rect.
            float scaleW = mTmpRect.width() / (float) appWidth;
            float scaleH = mTmpRect.height() / (float) appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mTmpRect.left, scaleW),
                    computePivot(mTmpRect.top, scaleH));
            scale.setInterpolator(mDecelerateInterpolator);

            Animation alpha = new AlphaAnimation(0, 1);
            alpha.setInterpolator(mThumbnailFadeOutInterpolator);

            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            set.setDetachWallpaper(true);
            a = set;
        } else  if (transit == TRANSIT_WALLPAPER_INTRA_OPEN ||
                    transit == TRANSIT_WALLPAPER_INTRA_CLOSE) {
            // If we are on top of the wallpaper, we need an animation that
            // correctly handles the wallpaper staying static behind all of
            // the animated elements.  To do this, will just have the existing
            // element fade out.
            a = new AlphaAnimation(1, 0);
            a.setDetachWallpaper(true);
        } else {
            // For normal animations, the exiting element just holds in place.
            a = new AlphaAnimation(1, 1);
        }

        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        final long duration;
        switch (transit) {
            case TRANSIT_ACTIVITY_OPEN:
            case TRANSIT_ACTIVITY_CLOSE:
                duration = mConfigShortAnimTime;
                break;
            default:
                duration = DEFAULT_APP_TRANSITION_DURATION;
                break;
        }
        a.setDuration(duration);
        a.setFillAfter(true);
        a.setInterpolator(mDecelerateInterpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
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

    void getNextAppTransitionStartRect(int taskId, Rect rect) {
        AppTransitionAnimationSpec spec = mNextAppTransitionAnimationsSpecs.get(taskId);
        if (spec == null) {
            spec = mDefaultNextAppTransitionAnimationSpec;
        }
        if (spec == null || spec.rect == null) {
            Slog.e(TAG, "Starting rect for task: " + taskId + " requested, but not available",
                    new Throwable());
            rect.setEmpty();
        } else {
            rect.set(spec.rect);
        }
    }

    private void putDefaultNextAppTransitionCoordinates(int left, int top, int width, int height,
            GraphicBuffer buffer) {
        mDefaultNextAppTransitionAnimationSpec = new AppTransitionAnimationSpec(-1 /* taskId */,
                buffer, new Rect(left, top, left + width, top + height));
    }

    /**
     * @return the duration of the last clip reveal animation
     */
    long getLastClipRevealTransitionDuration() {
        return mLastClipRevealTransitionDuration;
    }

    /**
     * @return the maximum distance the app surface is traveling of the last clip reveal animation
     */
    int getLastClipRevealMaxTranslation() {
        return mLastClipRevealMaxTranslation;
    }

    /**
     * @return true if in the last app transition had a clip reveal animation, false otherwise
     */
    boolean hadClipRevealAnimation() {
        return mLastHadClipReveal;
    }

    /**
     * Calculates the duration for the clip reveal animation. If the clip is "cut off", meaning that
     * the start rect is outside of the target rect, and there is a lot of movement going on.
     *
     * @param cutOff whether the start rect was not fully contained by the end rect
     * @param translationX the total translation the surface moves in x direction
     * @param translationY the total translation the surfaces moves in y direction
     * @param displayFrame our display frame
     *
     * @return the duration of the clip reveal animation, in milliseconds
     */
    private long calculateClipRevealTransitionDuration(boolean cutOff, float translationX,
            float translationY, Rect displayFrame) {
        if (!cutOff) {
            return DEFAULT_APP_TRANSITION_DURATION;
        }
        final float fraction = Math.max(Math.abs(translationX) / displayFrame.width(),
                Math.abs(translationY) / displayFrame.height());
        return (long) (DEFAULT_APP_TRANSITION_DURATION + fraction *
                (MAX_CLIP_REVEAL_TRANSITION_DURATION - DEFAULT_APP_TRANSITION_DURATION));
    }

    private Animation createClipRevealAnimationLocked(int transit, boolean enter, Rect appFrame,
            Rect displayFrame) {
        final Animation anim;
        if (enter) {
            final int appWidth = appFrame.width();
            final int appHeight = appFrame.height();

            // mTmpRect will contain an area around the launcher icon that was pressed. We will
            // clip reveal from that area in the final area of the app.
            getDefaultNextAppTransitionStartRect(mTmpRect);

            float t = 0f;
            if (appHeight > 0) {
                t = (float) mTmpRect.top / displayFrame.height();
            }
            int translationY = mClipRevealTranslationY + (int)(displayFrame.height() / 7f * t);
            int translationX = 0;
            int translationYCorrection = translationY;
            int centerX = mTmpRect.centerX();
            int centerY = mTmpRect.centerY();
            int halfWidth = mTmpRect.width() / 2;
            int halfHeight = mTmpRect.height() / 2;
            int clipStartX = centerX - halfWidth - appFrame.left;
            int clipStartY = centerY - halfHeight - appFrame.top;
            boolean cutOff = false;

            // If the starting rectangle is fully or partially outside of the target rectangle, we
            // need to start the clipping at the edge and then achieve the rest with translation
            // and extending the clip rect from that edge.
            if (appFrame.top > centerY - halfHeight) {
                translationY = (centerY - halfHeight) - appFrame.top;
                translationYCorrection = 0;
                clipStartY = 0;
                cutOff = true;
            }
            if (appFrame.left > centerX - halfWidth) {
                translationX = (centerX - halfWidth) - appFrame.left;
                clipStartX = 0;
                cutOff = true;
            }
            if (appFrame.right < centerX + halfWidth) {
                translationX = (centerX + halfWidth) - appFrame.right;
                clipStartX = appWidth - mTmpRect.width();
                cutOff = true;
            }
            final long duration = calculateClipRevealTransitionDuration(cutOff, translationX,
                    translationY, displayFrame);

            // Clip third of the from size of launch icon, expand to full width/height
            Animation clipAnimLR = new ClipRectLRAnimation(
                    clipStartX, clipStartX + mTmpRect.width(), 0, appWidth);
            clipAnimLR.setInterpolator(mClipHorizontalInterpolator);
            clipAnimLR.setDuration((long) (duration / 2.5f));

            TranslateAnimation translate = new TranslateAnimation(translationX, 0, translationY, 0);
            translate.setInterpolator(cutOff ? TOUCH_RESPONSE_INTERPOLATOR
                    : mLinearOutSlowInInterpolator);
            translate.setDuration(duration);

            Animation clipAnimTB = new ClipRectTBAnimation(
                    clipStartY, clipStartY + mTmpRect.height(),
                    0, appHeight,
                    translationYCorrection, 0,
                    mLinearOutSlowInInterpolator);
            clipAnimTB.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            clipAnimTB.setDuration(duration);

            // Quick fade-in from icon to app window
            final long alphaDuration = duration / 4;
            AlphaAnimation alpha = new AlphaAnimation(0.5f, 1);
            alpha.setDuration(alphaDuration);
            alpha.setInterpolator(mLinearOutSlowInInterpolator);

            AnimationSet set = new AnimationSet(false);
            set.addAnimation(clipAnimLR);
            set.addAnimation(clipAnimTB);
            set.addAnimation(translate);
            set.addAnimation(alpha);
            set.setZAdjustment(Animation.ZORDER_TOP);
            set.initialize(appWidth, appHeight, appWidth, appHeight);
            anim = set;
            mLastHadClipReveal = true;
            mLastClipRevealTransitionDuration = duration;

            // If the start rect was full inside the target rect (cutOff == false), we don't need
            // to store the translation, because it's only used if cutOff == true.
            mLastClipRevealMaxTranslation = cutOff
                    ? Math.max(Math.abs(translationY), Math.abs(translationX)) : 0;
        } else {
            final long duration;
            switch (transit) {
                case TRANSIT_ACTIVITY_OPEN:
                case TRANSIT_ACTIVITY_CLOSE:
                    duration = mConfigShortAnimTime;
                    break;
                default:
                    duration = DEFAULT_APP_TRANSITION_DURATION;
                    break;
            }
            if (transit == TRANSIT_WALLPAPER_INTRA_OPEN ||
                    transit == TRANSIT_WALLPAPER_INTRA_CLOSE) {
                // If we are on top of the wallpaper, we need an animation that
                // correctly handles the wallpaper staying static behind all of
                // the animated elements.  To do this, will just have the existing
                // element fade out.
                anim = new AlphaAnimation(1, 0);
                anim.setDetachWallpaper(true);
            } else {
                // For normal animations, the exiting element just holds in place.
                anim = new AlphaAnimation(1, 1);
            }
            anim.setInterpolator(mDecelerateInterpolator);
            anim.setDuration(duration);
            anim.setFillAfter(true);
        }
        return anim;
    }

    /**
     * Prepares the specified animation with a standard duration, interpolator, etc.
     */
    Animation prepareThumbnailAnimationWithDuration(Animation a, int appWidth, int appHeight,
            long duration, Interpolator interpolator) {
        if (duration > 0) {
            a.setDuration(duration);
        }
        a.setFillAfter(true);
        if (interpolator != null) {
            a.setInterpolator(interpolator);
        }
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    /**
     * Prepares the specified animation with a standard duration, interpolator, etc.
     */
    Animation prepareThumbnailAnimation(Animation a, int appWidth, int appHeight, int transit) {
        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        final int duration;
        switch (transit) {
            case TRANSIT_ACTIVITY_OPEN:
            case TRANSIT_ACTIVITY_CLOSE:
                duration = mConfigShortAnimTime;
                break;
            default:
                duration = DEFAULT_APP_TRANSITION_DURATION;
                break;
        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appHeight, duration,
                mDecelerateInterpolator);
    }

    /**
     * Return the current thumbnail transition state.
     */
    int getThumbnailTransitionState(boolean enter) {
        if (enter) {
            if (mNextAppTransitionScaleUp) {
                return THUMBNAIL_TRANSITION_ENTER_SCALE_UP;
            } else {
                return THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN;
            }
        } else {
            if (mNextAppTransitionScaleUp) {
                return THUMBNAIL_TRANSITION_EXIT_SCALE_UP;
            } else {
                return THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN;
            }
        }
    }

    /**
     * Creates an overlay with a background color and a thumbnail for the cross profile apps
     * animation.
     */
    GraphicBuffer createCrossProfileAppsThumbnail(
            @DrawableRes int thumbnailDrawableRes, Rect frame) {
        final int width = frame.width();
        final int height = frame.height();

        final Picture picture = new Picture();
        final Canvas canvas = picture.beginRecording(width, height);
        canvas.drawColor(Color.argb(0.6f, 0, 0, 0));
        final int thumbnailSize = mService.mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.cross_profile_apps_thumbnail_size);
        final Drawable drawable = mService.mContext.getDrawable(thumbnailDrawableRes);
        drawable.setBounds(
                (width - thumbnailSize) / 2,
                (height - thumbnailSize) / 2,
                (width + thumbnailSize) / 2,
                (height + thumbnailSize) / 2);
        drawable.setTint(mContext.getColor(android.R.color.white));
        drawable.draw(canvas);
        picture.endRecording();

        return Bitmap.createBitmap(picture).createGraphicBufferHandle();
    }

    Animation createCrossProfileAppsThumbnailAnimationLocked(Rect appRect) {
        final Animation animation = loadAnimationRes(
                "android", com.android.internal.R.anim.cross_profile_apps_thumbnail_enter);
        return prepareThumbnailAnimationWithDuration(animation, appRect.width(),
                appRect.height(), 0, null);
    }

    /**
     * This animation runs for the thumbnail that gets cross faded with the enter/exit activity
     * when a thumbnail is specified with the pending animation override.
     */
    Animation createThumbnailAspectScaleAnimationLocked(Rect appRect, @Nullable Rect contentInsets,
            GraphicBuffer thumbnailHeader, final int taskId, int uiMode, int orientation) {
        Animation a;
        final int thumbWidthI = thumbnailHeader.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = thumbnailHeader.getHeight();
        final int appWidth = appRect.width();

        float scaleW = appWidth / thumbWidth;
        getNextAppTransitionStartRect(taskId, mTmpRect);
        final float fromX;
        float fromY;
        final float toX;
        float toY;
        final float pivotX;
        final float pivotY;
        if (shouldScaleDownThumbnailTransition(uiMode, orientation)) {
            fromX = mTmpRect.left;
            fromY = mTmpRect.top;

            // For the curved translate animation to work, the pivot points needs to be at the
            // same absolute position as the one from the real surface.
            toX = mTmpRect.width() / 2 * (scaleW - 1f) + appRect.left;
            toY = appRect.height() / 2 * (1 - 1 / scaleW) + appRect.top;
            pivotX = mTmpRect.width() / 2;
            pivotY = appRect.height() / 2 / scaleW;
            if (mGridLayoutRecentsEnabled) {
                // In the grid layout, the header is displayed above the thumbnail instead of
                // overlapping it.
                fromY -= thumbHeightI;
                toY -= thumbHeightI * scaleW;
            }
        } else {
            pivotX = 0;
            pivotY = 0;
            fromX = mTmpRect.left;
            fromY = mTmpRect.top;
            toX = appRect.left;
            toY = appRect.top;
        }
        final long duration = getAspectScaleDuration();
        final Interpolator interpolator = getAspectScaleInterpolator();
        if (mNextAppTransitionScaleUp) {
            // Animation up from the thumbnail to the full screen
            Animation scale = new ScaleAnimation(1f, scaleW, 1f, scaleW, pivotX, pivotY);
            scale.setInterpolator(interpolator);
            scale.setDuration(duration);
            Animation alpha = new AlphaAnimation(1f, 0f);
            alpha.setInterpolator(mNextAppTransition == TRANSIT_DOCK_TASK_FROM_RECENTS
                    ? THUMBNAIL_DOCK_INTERPOLATOR : mThumbnailFadeOutInterpolator);
            alpha.setDuration(mNextAppTransition == TRANSIT_DOCK_TASK_FROM_RECENTS
                    ? duration / 2
                    : duration);
            Animation translate = createCurvedMotion(fromX, toX, fromY, toY);
            translate.setInterpolator(interpolator);
            translate.setDuration(duration);

            mTmpFromClipRect.set(0, 0, thumbWidthI, thumbHeightI);
            mTmpToClipRect.set(appRect);

            // Containing frame is in screen space, but we need the clip rect in the
            // app space.
            mTmpToClipRect.offsetTo(0, 0);
            mTmpToClipRect.right = (int) (mTmpToClipRect.right / scaleW);
            mTmpToClipRect.bottom = (int) (mTmpToClipRect.bottom / scaleW);

            if (contentInsets != null) {
                mTmpToClipRect.inset((int) (-contentInsets.left * scaleW),
                        (int) (-contentInsets.top * scaleW),
                        (int) (-contentInsets.right * scaleW),
                        (int) (-contentInsets.bottom * scaleW));
            }

            Animation clipAnim = new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect);
            clipAnim.setInterpolator(interpolator);
            clipAnim.setDuration(duration);

            // This AnimationSet uses the Interpolators assigned above.
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            if (!mGridLayoutRecentsEnabled) {
                // In the grid layout, the header should be shown for the whole animation.
                set.addAnimation(alpha);
            }
            set.addAnimation(translate);
            set.addAnimation(clipAnim);
            a = set;
        } else {
            // Animation down from the full screen to the thumbnail
            Animation scale = new ScaleAnimation(scaleW, 1f, scaleW, 1f, pivotX, pivotY);
            scale.setInterpolator(interpolator);
            scale.setDuration(duration);
            Animation alpha = new AlphaAnimation(0f, 1f);
            alpha.setInterpolator(mThumbnailFadeInInterpolator);
            alpha.setDuration(duration);
            Animation translate = createCurvedMotion(toX, fromX, toY, fromY);
            translate.setInterpolator(interpolator);
            translate.setDuration(duration);

            // This AnimationSet uses the Interpolators assigned above.
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            if (!mGridLayoutRecentsEnabled) {
                // In the grid layout, the header should be shown for the whole animation.
                set.addAnimation(alpha);
            }
            set.addAnimation(translate);
            a = set;

        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appRect.height(), 0,
                null);
    }

    private Animation createCurvedMotion(float fromX, float toX, float fromY, float toY) {

        // Almost no x-change - use linear animation
        if (Math.abs(toX - fromX) < 1f || mNextAppTransition != TRANSIT_DOCK_TASK_FROM_RECENTS) {
            return new TranslateAnimation(fromX, toX, fromY, toY);
        } else {
            final Path path = createCurvedPath(fromX, toX, fromY, toY);
            return new CurvedTranslateAnimation(path);
        }
    }

    private Path createCurvedPath(float fromX, float toX, float fromY, float toY) {
        final Path path = new Path();
        path.moveTo(fromX, fromY);

        if (fromY > toY) {
            // If the object needs to go up, move it in horizontal direction first, then vertical.
            path.cubicTo(fromX, fromY, toX, 0.9f * fromY + 0.1f * toY, toX, toY);
        } else {
            // If the object needs to go down, move it in vertical direction first, then horizontal.
            path.cubicTo(fromX, fromY, fromX, 0.1f * fromY + 0.9f * toY, toX, toY);
        }
        return path;
    }

    private long getAspectScaleDuration() {
        if (mNextAppTransition == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            return (long) (THUMBNAIL_APP_TRANSITION_DURATION * 1.35f);
        } else {
            return THUMBNAIL_APP_TRANSITION_DURATION;
        }
    }

    private Interpolator getAspectScaleInterpolator() {
        if (mNextAppTransition == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            return mFastOutSlowInInterpolator;
        } else {
            return TOUCH_RESPONSE_INTERPOLATOR;
        }
    }

    /**
     * This alternate animation is created when we are doing a thumbnail transition, for the
     * activity that is leaving, and the activity that is entering.
     */
    Animation createAspectScaledThumbnailEnterExitAnimationLocked(int thumbTransitState,
            int uiMode, int orientation, int transit, Rect containingFrame, Rect contentInsets,
            @Nullable Rect surfaceInsets, @Nullable Rect stableInsets, boolean freeform,
            int taskId) {
        Animation a;
        final int appWidth = containingFrame.width();
        final int appHeight = containingFrame.height();
        getDefaultNextAppTransitionStartRect(mTmpRect);
        final int thumbWidthI = mTmpRect.width();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = mTmpRect.height();
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;
        final int thumbStartX = mTmpRect.left - containingFrame.left - contentInsets.left;
        final int thumbStartY = mTmpRect.top - containingFrame.top;

        switch (thumbTransitState) {
            case THUMBNAIL_TRANSITION_ENTER_SCALE_UP:
            case THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN: {
                final boolean scaleUp = thumbTransitState == THUMBNAIL_TRANSITION_ENTER_SCALE_UP;
                if (freeform && scaleUp) {
                    a = createAspectScaledThumbnailEnterFreeformAnimationLocked(
                            containingFrame, surfaceInsets, taskId);
                } else if (freeform) {
                    a = createAspectScaledThumbnailExitFreeformAnimationLocked(
                            containingFrame, surfaceInsets, taskId);
                } else {
                    AnimationSet set = new AnimationSet(true);

                    // In portrait, we scale to fit the width
                    mTmpFromClipRect.set(containingFrame);
                    mTmpToClipRect.set(containingFrame);

                    // Containing frame is in screen space, but we need the clip rect in the
                    // app space.
                    mTmpFromClipRect.offsetTo(0, 0);
                    mTmpToClipRect.offsetTo(0, 0);

                    // Exclude insets region from the source clip.
                    mTmpFromClipRect.inset(contentInsets);
                    mNextAppTransitionInsets.set(contentInsets);

                    if (shouldScaleDownThumbnailTransition(uiMode, orientation)) {
                        // We scale the width and clip to the top/left square
                        float scale = thumbWidth /
                                (appWidth - contentInsets.left - contentInsets.right);
                        if (!mGridLayoutRecentsEnabled) {
                            int unscaledThumbHeight = (int) (thumbHeight / scale);
                            mTmpFromClipRect.bottom = mTmpFromClipRect.top + unscaledThumbHeight;
                        }

                        mNextAppTransitionInsets.set(contentInsets);

                        Animation scaleAnim = new ScaleAnimation(
                                scaleUp ? scale : 1, scaleUp ? 1 : scale,
                                scaleUp ? scale : 1, scaleUp ? 1 : scale,
                                containingFrame.width() / 2f,
                                containingFrame.height() / 2f + contentInsets.top);
                        final float targetX = (mTmpRect.left - containingFrame.left);
                        final float x = containingFrame.width() / 2f
                                - containingFrame.width() / 2f * scale;
                        final float targetY = (mTmpRect.top - containingFrame.top);
                        float y = containingFrame.height() / 2f
                                - containingFrame.height() / 2f * scale;

                        // During transition may require clipping offset from any top stable insets
                        // such as the statusbar height when statusbar is hidden
                        if (mLowRamRecentsEnabled && contentInsets.top == 0 && scaleUp) {
                            mTmpFromClipRect.top += stableInsets.top;
                            y += stableInsets.top;
                        }
                        final float startX = targetX - x;
                        final float startY = targetY - y;
                        Animation clipAnim = scaleUp
                                ? new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect)
                                : new ClipRectAnimation(mTmpToClipRect, mTmpFromClipRect);
                        Animation translateAnim = scaleUp
                                ? createCurvedMotion(startX, 0, startY - contentInsets.top, 0)
                                : createCurvedMotion(0, startX, 0, startY - contentInsets.top);

                        set.addAnimation(clipAnim);
                        set.addAnimation(scaleAnim);
                        set.addAnimation(translateAnim);

                    } else {
                        // In landscape, we don't scale at all and only crop
                        mTmpFromClipRect.bottom = mTmpFromClipRect.top + thumbHeightI;
                        mTmpFromClipRect.right = mTmpFromClipRect.left + thumbWidthI;

                        Animation clipAnim = scaleUp
                                ? new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect)
                                : new ClipRectAnimation(mTmpToClipRect, mTmpFromClipRect);
                        Animation translateAnim = scaleUp
                                ? createCurvedMotion(thumbStartX, 0,
                                thumbStartY - contentInsets.top, 0)
                                : createCurvedMotion(0, thumbStartX, 0,
                                        thumbStartY - contentInsets.top);

                        set.addAnimation(clipAnim);
                        set.addAnimation(translateAnim);
                    }
                    a = set;
                    a.setZAdjustment(Animation.ZORDER_TOP);
                }
                break;
            }
            case THUMBNAIL_TRANSITION_EXIT_SCALE_UP: {
                // Previous app window during the scale up
                if (transit == TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade out the source activity if we are animating to a wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    a = new AlphaAnimation(1, 1);
                }
                break;
            }
            case THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN: {
                // Target app window during the scale down
                if (transit == TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade in the destination activity if we are animating from a wallpaper
                    // activity.
                    a = new AlphaAnimation(0, 1);
                } else {
                    a = new AlphaAnimation(1, 1);
                }
                break;
            }
            default:
                throw new RuntimeException("Invalid thumbnail transition state");
        }

        return prepareThumbnailAnimationWithDuration(a, appWidth, appHeight,
                getAspectScaleDuration(), getAspectScaleInterpolator());
    }

    private Animation createAspectScaledThumbnailEnterFreeformAnimationLocked(Rect frame,
            @Nullable Rect surfaceInsets, int taskId) {
        getNextAppTransitionStartRect(taskId, mTmpRect);
        return createAspectScaledThumbnailFreeformAnimationLocked(mTmpRect, frame, surfaceInsets,
                true);
    }

    private Animation createAspectScaledThumbnailExitFreeformAnimationLocked(Rect frame,
            @Nullable Rect surfaceInsets, int taskId) {
        getNextAppTransitionStartRect(taskId, mTmpRect);
        return createAspectScaledThumbnailFreeformAnimationLocked(frame, mTmpRect, surfaceInsets,
                false);
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

        final IRemoteCallback callback = mAnimationFinishedCallback;
        if (callback != null) {
            set.setAnimationListener(new Animation.AnimationListener() {
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
        return set;
    }

    /**
     * This animation runs for the thumbnail that gets cross faded with the enter/exit activity
     * when a thumbnail is specified with the pending animation override.
     */
    Animation createThumbnailScaleAnimationLocked(int appWidth, int appHeight, int transit,
            GraphicBuffer thumbnailHeader) {
        Animation a;
        getDefaultNextAppTransitionStartRect(mTmpRect);
        final int thumbWidthI = thumbnailHeader.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = thumbnailHeader.getHeight();
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;

        if (mNextAppTransitionScaleUp) {
            // Animation for the thumbnail zooming from its initial size to the full screen
            float scaleW = appWidth / thumbWidth;
            float scaleH = appHeight / thumbHeight;
            Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                    computePivot(mTmpRect.left, 1 / scaleW),
                    computePivot(mTmpRect.top, 1 / scaleH));
            scale.setInterpolator(mDecelerateInterpolator);

            Animation alpha = new AlphaAnimation(1, 0);
            alpha.setInterpolator(mThumbnailFadeOutInterpolator);

            // This AnimationSet uses the Interpolators assigned above.
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            a = set;
        } else {
            // Animation for the thumbnail zooming down from the full screen to its final size
            float scaleW = appWidth / thumbWidth;
            float scaleH = appHeight / thumbHeight;
            a = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mTmpRect.left, 1 / scaleW),
                    computePivot(mTmpRect.top, 1 / scaleH));
        }

        return prepareThumbnailAnimation(a, appWidth, appHeight, transit);
    }

    /**
     * This animation is created when we are doing a thumbnail transition, for the activity that is
     * leaving, and the activity that is entering.
     */
    Animation createThumbnailEnterExitAnimationLocked(int thumbTransitState, Rect containingFrame,
            int transit, int taskId) {
        final int appWidth = containingFrame.width();
        final int appHeight = containingFrame.height();
        final GraphicBuffer thumbnailHeader = getAppTransitionThumbnailHeader(taskId);
        Animation a;
        getDefaultNextAppTransitionStartRect(mTmpRect);
        final int thumbWidthI = thumbnailHeader != null ? thumbnailHeader.getWidth() : appWidth;
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = thumbnailHeader != null ? thumbnailHeader.getHeight() : appHeight;
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;

        switch (thumbTransitState) {
            case THUMBNAIL_TRANSITION_ENTER_SCALE_UP: {
                // Entering app scales up with the thumbnail
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                a = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mTmpRect.left, scaleW),
                        computePivot(mTmpRect.top, scaleH));
                break;
            }
            case THUMBNAIL_TRANSITION_EXIT_SCALE_UP: {
                // Exiting app while the thumbnail is scaling up should fade or stay in place
                if (transit == TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade out while bringing up selected activity. This keeps the
                    // current activity from showing through a launching wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    // noop animation
                    a = new AlphaAnimation(1, 1);
                }
                break;
            }
            case THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN: {
                // Entering the other app, it should just be visible while we scale the thumbnail
                // down above it
                a = new AlphaAnimation(1, 1);
                break;
            }
            case THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN: {
                // Exiting the current app, the app should scale down with the thumbnail
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mTmpRect.left, scaleW),
                        computePivot(mTmpRect.top, scaleH));

                Animation alpha = new AlphaAnimation(1, 0);

                AnimationSet set = new AnimationSet(true);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                set.setZAdjustment(Animation.ZORDER_TOP);
                a = set;
                break;
            }
            default:
                throw new RuntimeException("Invalid thumbnail transition state");
        }

        return prepareThumbnailAnimation(a, appWidth, appHeight, transit);
    }

    private Animation createRelaunchAnimation(Rect containingFrame, Rect contentInsets) {
        getDefaultNextAppTransitionStartRect(mTmpFromClipRect);
        final int left = mTmpFromClipRect.left;
        final int top = mTmpFromClipRect.top;
        mTmpFromClipRect.offset(-left, -top);
        // TODO: Isn't that strange that we ignore exact position of the containingFrame?
        mTmpToClipRect.set(0, 0, containingFrame.width(), containingFrame.height());
        AnimationSet set = new AnimationSet(true);
        float fromWidth = mTmpFromClipRect.width();
        float toWidth = mTmpToClipRect.width();
        float fromHeight = mTmpFromClipRect.height();
        // While the window might span the whole display, the actual content will be cropped to the
        // system decoration frame, for example when the window is docked. We need to take into
        // account the visible height when constructing the animation.
        float toHeight = mTmpToClipRect.height() - contentInsets.top - contentInsets.bottom;
        int translateAdjustment = 0;
        if (fromWidth <= toWidth && fromHeight <= toHeight) {
            // The final window is larger in both dimensions than current window (e.g. we are
            // maximizing), so we can simply unclip the new window and there will be no disappearing
            // frame.
            set.addAnimation(new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect));
        } else {
            // The disappearing window has one larger dimension. We need to apply scaling, so the
            // first frame of the entry animation matches the old window.
            set.addAnimation(new ScaleAnimation(fromWidth / toWidth, 1, fromHeight / toHeight, 1));
            // We might not be going exactly full screen, but instead be aligned under the status
            // bar using cropping. We still need to account for the cropped part, which will also
            // be scaled.
            translateAdjustment = (int) (contentInsets.top * fromHeight / toHeight);
        }

        // We animate the translation from the old position of the removed window, to the new
        // position of the added window. The latter might not be full screen, for example docked for
        // docked windows.
        TranslateAnimation translate = new TranslateAnimation(left - containingFrame.left,
                0, top - containingFrame.top - translateAdjustment, 0);
        set.addAnimation(translate);
        set.setDuration(DEFAULT_APP_TRANSITION_DURATION);
        set.setZAdjustment(Animation.ZORDER_TOP);
        return set;
    }

    /**
     * @return true if and only if the first frame of the transition can be skipped, i.e. the first
     *         frame of the transition doesn't change the visuals on screen, so we can start
     *         directly with the second one
     */
    boolean canSkipFirstFrame() {
        return mNextAppTransitionType != NEXT_TRANSIT_TYPE_CUSTOM
                && mNextAppTransitionType != NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE
                && mNextAppTransitionType != NEXT_TRANSIT_TYPE_CLIP_REVEAL
                && mNextAppTransition != TRANSIT_KEYGUARD_GOING_AWAY;
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
    Animation loadAnimation(LayoutParams lp, int transit, boolean enter, int uiMode,
            int orientation, Rect frame, Rect displayFrame, Rect insets,
            @Nullable Rect surfaceInsets, @Nullable Rect stableInsets, boolean isVoiceInteraction,
            boolean freeform, int taskId) {
        mIsResId = false;
        Animation a;
        if (isKeyguardGoingAwayTransit(transit) && enter) {
            a = loadKeyguardExitAnimation(transit);
        } else if (transit == TRANSIT_KEYGUARD_OCCLUDE) {
            a = null;
        } else if (transit == TRANSIT_KEYGUARD_UNOCCLUDE && !enter) {
            a = loadAnimationRes(lp, com.android.internal.R.anim.wallpaper_open_exit);
        } else if (transit == TRANSIT_CRASHING_ACTIVITY_CLOSE) {
            a = null;
        } else if (isVoiceInteraction && (transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_TASK_OPEN
                || transit == TRANSIT_TASK_TO_FRONT)) {
            a = loadAnimationRes(lp, enter
                    ? com.android.internal.R.anim.voice_activity_open_enter
                    : com.android.internal.R.anim.voice_activity_open_exit);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation voice:"
                    + " anim=" + a + " transit=" + appTransitionToString(transit)
                    + " isEntrance=" + enter + " Callers=" + Debug.getCallers(3));
        } else if (isVoiceInteraction && (transit == TRANSIT_ACTIVITY_CLOSE
                || transit == TRANSIT_TASK_CLOSE
                || transit == TRANSIT_TASK_TO_BACK)) {
            a = loadAnimationRes(lp, enter
                    ? com.android.internal.R.anim.voice_activity_close_enter
                    : com.android.internal.R.anim.voice_activity_close_exit);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation voice:"
                    + " anim=" + a + " transit=" + appTransitionToString(transit)
                    + " isEntrance=" + enter + " Callers=" + Debug.getCallers(3));
        } else if (transit == TRANSIT_ACTIVITY_RELAUNCH) {
            a = createRelaunchAnimation(frame, insets);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=" + mNextAppTransition
                    + " transit=" + appTransitionToString(transit)
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM) {
            a = loadAnimationRes(mNextAppTransitionPackage, enter ?
                    mNextAppTransitionEnter : mNextAppTransitionExit);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_CUSTOM"
                    + " transit=" + appTransitionToString(transit) + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE) {
            a = loadAnimationRes(mNextAppTransitionPackage, mNextAppTransitionInPlace);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_CUSTOM_IN_PLACE"
                    + " transit=" + appTransitionToString(transit)
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CLIP_REVEAL) {
            a = createClipRevealAnimationLocked(transit, enter, frame, displayFrame);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                            + " anim=" + a + " nextAppTransition=ANIM_CLIP_REVEAL"
                            + " transit=" + appTransitionToString(transit)
                            + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_SCALE_UP) {
            a = createScaleUpAnimationLocked(transit, enter, frame);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_SCALE_UP"
                    + " transit=" + appTransitionToString(transit) + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP);
            a = createThumbnailEnterExitAnimationLocked(getThumbnailTransitionState(enter),
                    frame, transit, taskId);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                String animName = mNextAppTransitionScaleUp ?
                        "ANIM_THUMBNAIL_SCALE_UP" : "ANIM_THUMBNAIL_SCALE_DOWN";
                Slog.v(TAG, "applyAnimation:"
                        + " anim=" + a + " nextAppTransition=" + animName
                        + " transit=" + appTransitionToString(transit) + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            }
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP);
            a = createAspectScaledThumbnailEnterExitAnimationLocked(
                    getThumbnailTransitionState(enter), uiMode, orientation, transit, frame,
                    insets, surfaceInsets, stableInsets, freeform, taskId);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                String animName = mNextAppTransitionScaleUp ?
                        "ANIM_THUMBNAIL_ASPECT_SCALE_UP" : "ANIM_THUMBNAIL_ASPECT_SCALE_DOWN";
                Slog.v(TAG, "applyAnimation:"
                        + " anim=" + a + " nextAppTransition=" + animName
                        + " transit=" + appTransitionToString(transit) + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            }
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS && enter) {
            a = loadAnimationRes("android",
                    com.android.internal.R.anim.task_open_enter_cross_profile_apps);
            Slog.v(TAG,
                    "applyAnimation NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS:"
                            + " anim=" + a + " transit=" + appTransitionToString(transit)
                            + " isEntrance=true" + " Callers=" + Debug.getCallers(3));
        } else if (transit == TRANSIT_TASK_CHANGE_WINDOWING_MODE) {
            // In the absence of a specific adapter, we just want to keep everything stationary.
            a = new AlphaAnimation(1.f, 1.f);
            a.setDuration(WindowChangeAnimationSpec.ANIMATION_DURATION);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                Slog.v(TAG, "applyAnimation:"
                        + " anim=" + a + " transit=" + appTransitionToString(transit)
                        + " isEntrance=" + enter + " Callers=" + Debug.getCallers(3));
            }
        } else {
            int animAttr = 0;
            switch (transit) {
                case TRANSIT_ACTIVITY_OPEN:
                case TRANSIT_TRANSLUCENT_ACTIVITY_OPEN:
                    if (mActivityAnimations[0] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[0]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_activityOpenEnterAnimation
                                : WindowAnimation_activityOpenExitAnimation;
                    }
                    break;
                case TRANSIT_ACTIVITY_CLOSE:
                case TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE:
                    if (mActivityAnimations[1] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[1]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_activityCloseEnterAnimation
                                : WindowAnimation_activityCloseExitAnimation;
                    }
                    break;
                case TRANSIT_DOCK_TASK_FROM_RECENTS:
                case TRANSIT_TASK_OPEN:
                    if (mActivityAnimations[2] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[2]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskOpenEnterAnimation
                                : WindowAnimation_taskOpenExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_CLOSE:
                    if (mActivityAnimations[3] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[3]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskCloseEnterAnimation
                                : WindowAnimation_taskCloseExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_TO_FRONT:
                    if (mActivityAnimations[4] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[4]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskToFrontEnterAnimation
                                : WindowAnimation_taskToFrontExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_TO_BACK:
                    if (mActivityAnimations[5] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[5]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskToBackEnterAnimation
                                : WindowAnimation_taskToBackExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_OPEN:
                    if (mActivityAnimations[6] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[6]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperOpenEnterAnimation
                                : WindowAnimation_wallpaperOpenExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_CLOSE:
                    if (mActivityAnimations[7] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[7]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperCloseEnterAnimation
                                : WindowAnimation_wallpaperCloseExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_INTRA_OPEN:
                    if (mActivityAnimations[8] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[8]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperIntraOpenEnterAnimation
                                : WindowAnimation_wallpaperIntraOpenExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_INTRA_CLOSE:
                    if (mActivityAnimations[9] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[9]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperIntraCloseEnterAnimation
                                : WindowAnimation_wallpaperIntraCloseExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_OPEN_BEHIND:
                    if (mActivityAnimations[10] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[10]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_launchTaskBehindSourceAnimation
                                : WindowAnimation_launchTaskBehindTargetAnimation;
                    }
            }
            a = animAttr != 0 ? loadAnimationAttr(lp, animAttr, transit) : null;
            if (a != null) {
                if (mAnimationDuration > 0) {
                    a.setDuration(mAnimationDuration);
                }
            }
            mIsResId = false;
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a
                    + " animAttr=0x" + Integer.toHexString(animAttr)
                    + " transit=" + appTransitionToString(transit) + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        }
        return a;
    }

    private Animation loadKeyguardExitAnimation(int transit) {
        if ((mNextAppTransitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) != 0) {
            return null;
        }
        final boolean toShade =
                (mNextAppTransitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0;
        final boolean subtle =
                (mNextAppTransitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0;
        return mService.mPolicy.createHiddenByKeyguardExit(
                transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER, toShade, subtle);
    }

    int getAppStackClipMode() {
        // When dismiss keyguard animation occurs, clip before the animation to prevent docked
        // app from showing beyond the divider
        if (mNextAppTransition == TRANSIT_KEYGUARD_GOING_AWAY
                || mNextAppTransition == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            return STACK_CLIP_BEFORE_ANIM;
        }
        return mNextAppTransition == TRANSIT_ACTIVITY_RELAUNCH
                || mNextAppTransition == TRANSIT_DOCK_TASK_FROM_RECENTS
                || mNextAppTransitionType == NEXT_TRANSIT_TYPE_CLIP_REVEAL
                ? STACK_CLIP_NONE
                : STACK_CLIP_AFTER_ANIM;
    }

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
            IRemoteCallback startedCallback) {
        if (canOverridePendingAppTransition()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_CUSTOM;
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionEnter = enterAnim;
            mNextAppTransitionExit = exitAnim;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
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

    void overridePendingAppTransitionThumb(GraphicBuffer srcThumb, int startX, int startY,
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

    void overridePendingAppTransitionAspectScaledThumb(GraphicBuffer srcThumb, int startX, int startY,
            int targetWidth, int targetHeight, IRemoteCallback startedCallback, boolean scaleUp) {
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
                        mNextAppTransitionAnimationsSpecs.put(spec.taskId, spec);
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
        if (DEBUG_APP_TRANSITIONS) Slog.i(TAG, "Override pending remote transitionSet="
                + isTransitionSet() + " adapter=" + remoteAnimationAdapter);
        if (isTransitionSet()) {
            clear();
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_REMOTE;
            mRemoteAnimationController = new RemoteAnimationController(mService,
                    remoteAnimationAdapter, mHandler);
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
                }
                mService.requestTraversal();
            });
        }
    }

    @Override
    public String toString() {
        return "mNextAppTransition=" + appTransitionToString(mNextAppTransition);
    }

    /**
     * Returns the human readable name of a window transition.
     *
     * @param transition The window transition.
     * @return The transition symbolic name.
     */
    public static String appTransitionToString(int transition) {
        switch (transition) {
            case TRANSIT_UNSET: {
                return "TRANSIT_UNSET";
            }
            case TRANSIT_NONE: {
                return "TRANSIT_NONE";
            }
            case TRANSIT_ACTIVITY_OPEN: {
                return "TRANSIT_ACTIVITY_OPEN";
            }
            case TRANSIT_ACTIVITY_CLOSE: {
                return "TRANSIT_ACTIVITY_CLOSE";
            }
            case TRANSIT_TASK_OPEN: {
                return "TRANSIT_TASK_OPEN";
            }
            case TRANSIT_TASK_CLOSE: {
                return "TRANSIT_TASK_CLOSE";
            }
            case TRANSIT_TASK_TO_FRONT: {
                return "TRANSIT_TASK_TO_FRONT";
            }
            case TRANSIT_TASK_TO_BACK: {
                return "TRANSIT_TASK_TO_BACK";
            }
            case TRANSIT_WALLPAPER_CLOSE: {
                return "TRANSIT_WALLPAPER_CLOSE";
            }
            case TRANSIT_WALLPAPER_OPEN: {
                return "TRANSIT_WALLPAPER_OPEN";
            }
            case TRANSIT_WALLPAPER_INTRA_OPEN: {
                return "TRANSIT_WALLPAPER_INTRA_OPEN";
            }
            case TRANSIT_WALLPAPER_INTRA_CLOSE: {
                return "TRANSIT_WALLPAPER_INTRA_CLOSE";
            }
            case TRANSIT_TASK_OPEN_BEHIND: {
                return "TRANSIT_TASK_OPEN_BEHIND";
            }
            case TRANSIT_ACTIVITY_RELAUNCH: {
                return "TRANSIT_ACTIVITY_RELAUNCH";
            }
            case TRANSIT_DOCK_TASK_FROM_RECENTS: {
                return "TRANSIT_DOCK_TASK_FROM_RECENTS";
            }
            case TRANSIT_KEYGUARD_GOING_AWAY: {
                return "TRANSIT_KEYGUARD_GOING_AWAY";
            }
            case TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER: {
                return "TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER";
            }
            case TRANSIT_KEYGUARD_OCCLUDE: {
                return "TRANSIT_KEYGUARD_OCCLUDE";
            }
            case TRANSIT_KEYGUARD_UNOCCLUDE: {
                return "TRANSIT_KEYGUARD_UNOCCLUDE";
            }
            case TRANSIT_TRANSLUCENT_ACTIVITY_OPEN: {
                return "TRANSIT_TRANSLUCENT_ACTIVITY_OPEN";
            }
            case TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE: {
                return "TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE";
            }
            case TRANSIT_CRASHING_ACTIVITY_CLOSE: {
                return "TRANSIT_CRASHING_ACTIVITY_CLOSE";
            }
            case TRANSIT_SHOW_SINGLE_TASK_DISPLAY: {
                return "TRANSIT_SHOW_SINGLE_TASK_DISPLAY";
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

    void writeToProto(ProtoOutputStream proto, long fieldId) {
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
        switch (mNextAppTransitionType) {
            case NEXT_TRANSIT_TYPE_CUSTOM:
                pw.print(prefix); pw.print("mNextAppTransitionPackage=");
                        pw.println(mNextAppTransitionPackage);
                pw.print(prefix); pw.print("mNextAppTransitionEnter=0x");
                        pw.print(Integer.toHexString(mNextAppTransitionEnter));
                        pw.print(" mNextAppTransitionExit=0x");
                        pw.println(Integer.toHexString(mNextAppTransitionExit));
                break;
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
        if (mLastUsedAppTransition != TRANSIT_NONE) {
            pw.print(prefix); pw.print("mLastUsedAppTransition=");
                    pw.println(appTransitionToString(mLastUsedAppTransition));
            pw.print(prefix); pw.print("mLastOpeningApp=");
                    pw.println(mLastOpeningApp);
            pw.print(prefix); pw.print("mLastClosingApp=");
                    pw.println(mLastClosingApp);
            pw.print(prefix); pw.print("mLastChangingApp=");
            pw.println(mLastChangingApp);
        }
    }

    public void setCurrentUser(int newUserId) {
        mCurrentUserId = newUserId;
    }

    /**
     * @return true if transition is not running and should not be skipped, false if transition is
     *         already running
     */
    boolean prepareAppTransitionLocked(@TransitionType int transit, boolean alwaysKeepCurrent,
            @TransitionFlags int flags, boolean forceOverride) {
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Prepare app transition:"
                + " transit=" + appTransitionToString(transit)
                + " " + this
                + " alwaysKeepCurrent=" + alwaysKeepCurrent
                + " displayId=" + mDisplayContent.getDisplayId()
                + " Callers=" + Debug.getCallers(5));
        final boolean allowSetCrashing = !isKeyguardTransit(mNextAppTransition)
                && transit == TRANSIT_CRASHING_ACTIVITY_CLOSE;
        if (forceOverride || isKeyguardTransit(transit) || !isTransitionSet()
                || mNextAppTransition == TRANSIT_NONE || allowSetCrashing) {
            setAppTransition(transit, flags);
        }
        // We never want to change from a Keyguard transit to a non-Keyguard transit, as our logic
        // relies on the fact that we always execute a Keyguard transition after preparing one. We
        // also don't want to change away from a crashing transition.
        else if (!alwaysKeepCurrent && !isKeyguardTransit(mNextAppTransition)
                && mNextAppTransition != TRANSIT_CRASHING_ACTIVITY_CLOSE) {
            if (transit == TRANSIT_TASK_OPEN && isTransitionEqual(TRANSIT_TASK_CLOSE)) {
                // Opening a new task always supersedes a close for the anim.
                setAppTransition(transit, flags);
            } else if (transit == TRANSIT_ACTIVITY_OPEN
                    && isTransitionEqual(TRANSIT_ACTIVITY_CLOSE)) {
                // Opening a new activity always supersedes a close for the anim.
                setAppTransition(transit, flags);
            } else if (isTaskTransit(transit) && isActivityTransit(mNextAppTransition)) {
                // Task animations always supersede activity animations, because if we have both, it
                // usually means that activity transition were just trampoline activities.
                setAppTransition(transit, flags);
            }
        }
        boolean prepared = prepare();
        if (isTransitionSet()) {
            removeAppTransitionTimeoutCallbacks();
            mHandler.postDelayed(mHandleAppTransitionTimeoutRunnable, APP_TRANSITION_TIMEOUT_MS);
        }
        return prepared;
    }

    /**
     * @return true if {@param transit} is representing a transition in which Keyguard is going
     *         away, false otherwise
     */
    public static boolean isKeyguardGoingAwayTransit(int transit) {
        return transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
    }

    private static boolean isKeyguardTransit(int transit) {
        return isKeyguardGoingAwayTransit(transit) || transit == TRANSIT_KEYGUARD_OCCLUDE
                || transit == TRANSIT_KEYGUARD_UNOCCLUDE;
    }

    static boolean isTaskTransit(int transit) {
        return isTaskOpenTransit(transit)
                || transit == TRANSIT_TASK_CLOSE
                || transit == TRANSIT_TASK_TO_BACK
                || transit == TRANSIT_TASK_IN_PLACE;
    }

    private static  boolean isTaskOpenTransit(int transit) {
        return transit == TRANSIT_TASK_OPEN
                || transit == TRANSIT_TASK_OPEN_BEHIND
                || transit == TRANSIT_TASK_TO_FRONT;
    }

    static boolean isActivityTransit(int transit) {
        return transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_ACTIVITY_CLOSE
                || transit == TRANSIT_ACTIVITY_RELAUNCH;
    }

    static boolean isChangeTransit(int transit) {
        return transit == TRANSIT_TASK_CHANGE_WINDOWING_MODE;
    }

    /**
     * @return whether the transition should show the thumbnail being scaled down.
     */
    private boolean shouldScaleDownThumbnailTransition(int uiMode, int orientation) {
        return mGridLayoutRecentsEnabled
                || orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void handleAppTransitionTimeout() {
        synchronized (mService.mGlobalLock) {
            final DisplayContent dc = mDisplayContent;
            if (dc == null) {
                return;
            }
            if (isTransitionSet() || !dc.mOpeningApps.isEmpty() || !dc.mClosingApps.isEmpty()
                    || !dc.mChangingApps.isEmpty()) {
                if (DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG_WM, "*** APP TRANSITION TIMEOUT."
                            + " displayId=" + dc.getDisplayId()
                            + " isTransitionSet()="
                            + dc.mAppTransition.isTransitionSet()
                            + " mOpeningApps.size()=" + dc.mOpeningApps.size()
                            + " mClosingApps.size()=" + dc.mClosingApps.size()
                            + " mChangingApps.size()=" + dc.mChangingApps.size());
                }
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

    void removeAppTransitionTimeoutCallbacks() {
        mHandler.removeCallbacks(mHandleAppTransitionTimeoutRunnable);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATION_CONTROLS_DURATION), false, this);
            for (int i = 0; i < 11; i++) {
	            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ACTIVITY_ANIMATION_CONTROLS[i]), false, this);
            }
        }
         @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        for (int i = 0; i < 11; i++) {  
            mActivityAnimations[i] = Settings.Global.getInt(resolver,
                Settings.Global.ACTIVITY_ANIMATION_CONTROLS[i], 0);
        }
        int temp = Settings.Global.getInt(resolver, Settings.Global.ANIMATION_CONTROLS_DURATION,
                    0);
        mAnimationDuration = temp * 15;
    }
}
