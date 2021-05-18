/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.app.ActivityOptions.ANIM_CLIP_REVEAL;
import static android.app.ActivityOptions.ANIM_CUSTOM;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.ActivityOptions.ANIM_SCALE_UP;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_UP;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_CLOSE;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_INTRA_CLOSE;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_INTRA_OPEN;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_NONE;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_OPEN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.R;
import com.android.internal.policy.AttributeCache;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/** The default handler that handles anything not already handled. */
public class DefaultTransitionHandler implements Transitions.TransitionHandler {
    private static final int MAX_ANIMATION_DURATION = 3000;

    /**
     * Restrict ability of activities overriding transition animation in a way such that
     * an activity can do it only when the transition happens within a same task.
     *
     * @see android.app.Activity#overridePendingTransition(int, int)
     */
    private static final String DISABLE_CUSTOM_TASK_ANIMATION_PROPERTY =
            "persist.wm.disable_custom_task_animation";

    /**
     * @see #DISABLE_CUSTOM_TASK_ANIMATION_PROPERTY
     */
    static boolean sDisableCustomTaskAnimationProperty =
            SystemProperties.getBoolean(DISABLE_CUSTOM_TASK_ANIMATION_PROPERTY, true);

    private final TransactionPool mTransactionPool;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionAnimation mTransitionAnimation;

    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    /** Keeps track of the currently-running animations associated with each transition. */
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();

    private final Rect mInsets = new Rect(0, 0, 0, 0);
    private float mTransitionAnimationScaleSetting = 1.0f;

    private final int mCurrentUserId;

    DefaultTransitionHandler(@NonNull TransactionPool transactionPool, Context context,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mTransactionPool = transactionPool;
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mTransitionAnimation = new TransitionAnimation(context, false /* debug */, Transitions.TAG);
        mCurrentUserId = UserHandle.myUserId();

        AttributeCache.init(context);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "start default transition animation, info = %s", info);
        if (mAnimations.containsKey(transition)) {
            throw new IllegalStateException("Got a duplicate startAnimation call for "
                    + transition);
        }
        final ArrayList<Animator> animations = new ArrayList<>();
        mAnimations.put(transition, animations);

        final Runnable onAnimFinish = () -> {
            if (!animations.isEmpty()) return;
            mAnimations.remove(transition);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        };

        final int wallpaperTransit = getWallpaperTransitType(info);
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getMode() == TRANSIT_CHANGE) {
                // No default animation for this, so just update bounds/position.
                t.setPosition(change.getLeash(),
                        change.getEndAbsBounds().left - change.getEndRelOffset().x,
                        change.getEndAbsBounds().top - change.getEndRelOffset().y);
                if (change.getTaskInfo() != null) {
                    // Skip non-tasks since those usually have null bounds.
                    t.setWindowCrop(change.getLeash(),
                            change.getEndAbsBounds().width(), change.getEndAbsBounds().height());
                }
            }

            // Don't animate anything that isn't independent.
            if (!TransitionInfo.isIndependent(change, info)) continue;

            Animation a = loadAnimation(info, change, wallpaperTransit);
            if (a != null) {
                startAnimInternal(animations, a, change.getLeash(), onAnimFinish,
                        null /* position */);

                if (info.getAnimationOptions() != null) {
                    attachThumbnail(animations, onAnimFinish, change, info.getAnimationOptions());
                }
            }
        }
        t.apply();
        // run finish now in-case there are no animations
        onAnimFinish.run();
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    @Override
    public void setAnimScaleSetting(float scale) {
        mTransitionAnimationScaleSetting = scale;
    }

    @Nullable
    private Animation loadAnimation(TransitionInfo info, TransitionInfo.Change change,
            int wallpaperTransit) {
        Animation a = null;

        final int type = info.getType();
        final int flags = info.getFlags();
        final int changeMode = change.getMode();
        final int changeFlags = change.getFlags();
        final boolean isOpeningType = Transitions.isOpeningType(type);
        final boolean enter = Transitions.isOpeningType(changeMode);
        final boolean isTask = change.getTaskInfo() != null;
        final TransitionInfo.AnimationOptions options = info.getAnimationOptions();
        final int overrideType = options != null ? options.getType() : ANIM_NONE;
        final boolean canCustomContainer = isTask ? !sDisableCustomTaskAnimationProperty : true;

        if (type == TRANSIT_RELAUNCH) {
            a = mTransitionAnimation.createRelaunchAnimation(
                    change.getEndAbsBounds(), mInsets, change.getEndAbsBounds());
        } else if (type == TRANSIT_KEYGUARD_GOING_AWAY) {
            a = mTransitionAnimation.loadKeyguardExitAnimation(flags,
                    (changeFlags & FLAG_SHOW_WALLPAPER) != 0);
        } else if (type == TRANSIT_KEYGUARD_UNOCCLUDE) {
            a = mTransitionAnimation.loadKeyguardUnoccludeAnimation();
        } else if (overrideType == ANIM_CUSTOM
                && (canCustomContainer || options.getOverrideTaskTransition())) {
            a = mTransitionAnimation.loadAnimationRes(options.getPackageName(), enter
                    ? options.getEnterResId() : options.getExitResId());
        } else if (overrideType == ANIM_OPEN_CROSS_PROFILE_APPS && enter) {
            a = mTransitionAnimation.loadCrossProfileAppEnterAnimation();
        } else if (overrideType == ANIM_CLIP_REVEAL) {
            a = mTransitionAnimation.createClipRevealAnimationLocked(type, wallpaperTransit, enter,
                    change.getEndAbsBounds(), change.getEndAbsBounds(),
                    options.getTransitionBounds());
        } else if (overrideType == ANIM_SCALE_UP) {
            a = mTransitionAnimation.createScaleUpAnimationLocked(type, wallpaperTransit, enter,
                    change.getEndAbsBounds(), options.getTransitionBounds());
        } else if (overrideType == ANIM_THUMBNAIL_SCALE_UP
                || overrideType == ANIM_THUMBNAIL_SCALE_DOWN) {
            final boolean scaleUp = overrideType == ANIM_THUMBNAIL_SCALE_UP;
            a = mTransitionAnimation.createThumbnailEnterExitAnimationLocked(enter, scaleUp,
                    change.getEndAbsBounds(), type, wallpaperTransit, options.getThumbnail(),
                    options.getTransitionBounds());
        } else if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_OPEN) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation
                    : R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation);
        } else if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_CLOSE) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation
                    : R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation);
        } else if (wallpaperTransit == WALLPAPER_TRANSITION_OPEN) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_wallpaperOpenEnterAnimation
                    : R.styleable.WindowAnimation_wallpaperOpenExitAnimation);
        } else if (wallpaperTransit == WALLPAPER_TRANSITION_CLOSE) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_wallpaperCloseEnterAnimation
                    : R.styleable.WindowAnimation_wallpaperCloseExitAnimation);
        } else if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
            if (isOpeningType) {
                a = mTransitionAnimation.loadVoiceActivityOpenAnimation(enter);
            } else {
                a = mTransitionAnimation.loadVoiceActivityExitAnimation(enter);
            }
        } else if ((changeFlags & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0 && isOpeningType) {
            // This received a transferred starting window, so don't animate
            return null;
        } else if (type == TRANSIT_OPEN) {
            if (isTask) {
                a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                        ? R.styleable.WindowAnimation_taskOpenEnterAnimation
                        : R.styleable.WindowAnimation_taskOpenExitAnimation);
            } else {
                if ((changeFlags & FLAG_TRANSLUCENT) != 0 && enter) {
                    a = mTransitionAnimation.loadDefaultAnimationRes(
                            R.anim.activity_translucent_open_enter);
                } else {
                    a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                            ? R.styleable.WindowAnimation_activityOpenEnterAnimation
                            : R.styleable.WindowAnimation_activityOpenExitAnimation);
                }
            }
        } else if (type == TRANSIT_TO_FRONT) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_taskToFrontEnterAnimation
                    : R.styleable.WindowAnimation_taskToFrontExitAnimation);
        } else if (type == TRANSIT_CLOSE) {
            if (isTask) {
                a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                        ? R.styleable.WindowAnimation_taskCloseEnterAnimation
                        : R.styleable.WindowAnimation_taskCloseExitAnimation);
            } else {
                if ((changeFlags & FLAG_TRANSLUCENT) != 0 && !enter) {
                    a = mTransitionAnimation.loadDefaultAnimationRes(
                            R.anim.activity_translucent_close_exit);
                } else {
                    a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                            ? R.styleable.WindowAnimation_activityCloseEnterAnimation
                            : R.styleable.WindowAnimation_activityCloseExitAnimation);
                }
            }
        } else if (type == TRANSIT_TO_BACK) {
            a = mTransitionAnimation.loadDefaultAnimationAttr(enter
                    ? R.styleable.WindowAnimation_taskToBackEnterAnimation
                    : R.styleable.WindowAnimation_taskToBackExitAnimation);
        } else if (changeMode == TRANSIT_CHANGE) {
            // In the absence of a specific adapter, we just want to keep everything stationary.
            a = new AlphaAnimation(1.f, 1.f);
            a.setDuration(TransitionAnimation.DEFAULT_APP_TRANSITION_DURATION);
        }

        if (a != null) {
            if (!a.isInitialized()) {
                Rect end = change.getEndAbsBounds();
                a.initialize(end.width(), end.height(), end.width(), end.height());
            }
            a.restrictDuration(MAX_ANIMATION_DURATION);
            a.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        }
        return a;
    }

    private void startAnimInternal(@NonNull ArrayList<Animator> animations, @NonNull Animation anim,
            @NonNull SurfaceControl leash, @NonNull Runnable finishCallback,
            @Nullable Point position) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        final Transformation transformation = new Transformation();
        final float[] matrix = new float[9];
        // Animation length is already expected to be scaled.
        va.overrideDurationScale(1.0f);
        va.setDuration(anim.computeDurationHint());
        va.addUpdateListener(animation -> {
            final long currentPlayTime = Math.min(va.getDuration(), va.getCurrentPlayTime());

            applyTransformation(currentPlayTime, transaction, leash, anim, transformation, matrix,
                    position);
        });

        final Runnable finisher = () -> {
            applyTransformation(va.getDuration(), transaction, leash, anim, transformation, matrix,
                    position);

            mTransactionPool.release(transaction);
            mMainExecutor.execute(() -> {
                animations.remove(va);
                finishCallback.run();
            });
        };
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }
        });
        animations.add(va);
        mAnimExecutor.execute(va::start);
    }

    private void attachThumbnail(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change,
            TransitionInfo.AnimationOptions options) {
        final boolean isTask = change.getTaskInfo() != null;
        final boolean isOpen = Transitions.isOpeningType(change.getMode());
        final boolean isClose = Transitions.isClosingType(change.getMode());
        if (isOpen) {
            if (options.getType() == ANIM_OPEN_CROSS_PROFILE_APPS && isTask) {
                attachCrossProfileThunmbnailAnimation(animations, finishCallback, change);
            } else if (options.getType() == ANIM_THUMBNAIL_SCALE_UP) {
                attachThumbnailAnimation(animations, finishCallback, change, options);
            }
        } else if (isClose && options.getType() == ANIM_THUMBNAIL_SCALE_DOWN) {
            attachThumbnailAnimation(animations, finishCallback, change, options);
        }
    }

    private void attachCrossProfileThunmbnailAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change) {
        final int thumbnailDrawableRes = change.getTaskInfo().userId == mCurrentUserId
                ? R.drawable.ic_account_circle : R.drawable.ic_corp_badge;
        final Rect bounds = change.getEndAbsBounds();
        final HardwareBuffer thumbnail = mTransitionAnimation.createCrossProfileAppsThumbnail(
                thumbnailDrawableRes, bounds);
        if (thumbnail == null) {
            return;
        }

        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final WindowThumbnail wt = WindowThumbnail.createAndAttach(mSurfaceSession,
                change.getLeash(), thumbnail, transaction);
        final Animation a =
                mTransitionAnimation.createCrossProfileAppsThumbnailAnimationLocked(bounds);
        if (a == null) {
            return;
        }

        final Runnable finisher = () -> {
            wt.destroy(transaction);
            mTransactionPool.release(transaction);

            finishCallback.run();
        };
        a.restrictDuration(MAX_ANIMATION_DURATION);
        a.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        startAnimInternal(animations, a, wt.getSurface(), finisher,
                new Point(bounds.left, bounds.top));
    }

    private void attachThumbnailAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change,
            TransitionInfo.AnimationOptions options) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final WindowThumbnail wt = WindowThumbnail.createAndAttach(mSurfaceSession,
                change.getLeash(), options.getThumbnail(), transaction);
        final Rect bounds = change.getEndAbsBounds();
        final int orientation = mContext.getResources().getConfiguration().orientation;
        final Animation a = mTransitionAnimation.createThumbnailAspectScaleAnimationLocked(bounds,
                mInsets, options.getThumbnail(), orientation, null /* startRect */,
                options.getTransitionBounds(), options.getType() == ANIM_THUMBNAIL_SCALE_UP);

        final Runnable finisher = () -> {
            wt.destroy(transaction);
            mTransactionPool.release(transaction);

            finishCallback.run();
        };
        a.restrictDuration(MAX_ANIMATION_DURATION);
        a.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        startAnimInternal(animations, a, wt.getSurface(), finisher, null /* position */);
    }

    private static int getWallpaperTransitType(TransitionInfo info) {
        boolean hasOpenWallpaper = false;
        boolean hasCloseWallpaper = false;

        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if ((change.getFlags() & FLAG_SHOW_WALLPAPER) != 0) {
                if (Transitions.isOpeningType(change.getMode())) {
                    hasOpenWallpaper = true;
                } else if (Transitions.isClosingType(change.getMode())) {
                    hasCloseWallpaper = true;
                }
            }
        }

        if (hasOpenWallpaper && hasCloseWallpaper) {
            return Transitions.isOpeningType(info.getType())
                    ? WALLPAPER_TRANSITION_INTRA_OPEN : WALLPAPER_TRANSITION_INTRA_CLOSE;
        } else if (hasOpenWallpaper) {
            return WALLPAPER_TRANSITION_OPEN;
        } else if (hasCloseWallpaper) {
            return WALLPAPER_TRANSITION_CLOSE;
        } else {
            return WALLPAPER_TRANSITION_NONE;
        }
    }

    private static void applyTransformation(long time, SurfaceControl.Transaction t,
            SurfaceControl leash, Animation anim, Transformation transformation, float[] matrix,
            Point position) {
        anim.getTransformation(time, transformation);
        if (position != null) {
            transformation.getMatrix().postTranslate(position.x, position.y);
        }
        t.setMatrix(leash, transformation.getMatrix(), matrix);
        t.setAlpha(leash, transformation.getAlpha());
        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
        t.apply();
    }
}
