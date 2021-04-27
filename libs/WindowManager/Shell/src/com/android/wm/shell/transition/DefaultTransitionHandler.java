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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.SurfaceControl;
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
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionAnimation mTransitionAnimation;

    /** Keeps track of the currently-running animations associated with each transition. */
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();

    private final Rect mInsets = new Rect(0, 0, 0, 0);
    private float mTransitionAnimationScaleSetting = 1.0f;

    DefaultTransitionHandler(@NonNull TransactionPool transactionPool, Context context,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mTransactionPool = transactionPool;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mTransitionAnimation = new TransitionAnimation(context, false /* debug */, Transitions.TAG);

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

            Animation a = loadAnimation(info, change);
            if (a != null) {
                startAnimInternal(animations, a, change.getLeash(), onAnimFinish);
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
    private Animation loadAnimation(TransitionInfo info, TransitionInfo.Change change) {
        // TODO(b/178678389): It should handle more type animation here
        Animation a = null;

        final int type = info.getType();
        final int flags = info.getFlags();
        final boolean isOpening = Transitions.isOpeningType(type);
        final int changeMode = change.getMode();
        final int changeFlags = change.getFlags();
        final boolean enter = Transitions.isOpeningType(changeMode);
        final boolean isTask = change.getTaskInfo() != null;
        final TransitionInfo.AnimationOptions options = info.getAnimationOptions();
        final int overrideType = options != null ? options.getType() : ANIM_NONE;
        final boolean canCustomContainer = isTask ? !sDisableCustomTaskAnimationProperty : true;

        if (type == TRANSIT_RELAUNCH) {
            a = mTransitionAnimation.createRelaunchAnimation(
                    change.getStartAbsBounds(), mInsets, change.getEndAbsBounds());
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
            a = mTransitionAnimation.createClipRevealAnimationLocked(changeMode, enter,
                    change.getEndAbsBounds(), change.getEndAbsBounds(),
                    options.getTransitionBounds());
        } else if (overrideType == ANIM_SCALE_UP) {
            a = mTransitionAnimation.createScaleUpAnimationLocked(changeMode, enter,
                    change.getEndAbsBounds(), options.getTransitionBounds());
        } else if (overrideType == ANIM_THUMBNAIL_SCALE_UP
                || overrideType == ANIM_THUMBNAIL_SCALE_DOWN) {
            final boolean scaleUp = overrideType == ANIM_THUMBNAIL_SCALE_UP;
            a = mTransitionAnimation.createThumbnailEnterExitAnimationLocked(enter, scaleUp,
                    change.getEndAbsBounds(), changeMode, options.getThumbnail(),
                    options.getTransitionBounds());
        } else if (changeMode == TRANSIT_OPEN && isOpening) {
            if ((changeFlags & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                // This received a transferred starting window, so don't animate
                return null;
            }

            if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
                a = mTransitionAnimation.loadVoiceActivityOpenAnimation(true /** enter */);
            } else if (isTask) {
                a = mTransitionAnimation.loadDefaultAnimationAttr(
                        R.styleable.WindowAnimation_taskOpenEnterAnimation);
            } else {
                a = mTransitionAnimation.loadDefaultAnimationRes(
                        (changeFlags & FLAG_TRANSLUCENT) == 0
                        ? R.anim.activity_open_enter : R.anim.activity_translucent_open_enter);
            }
        } else if (changeMode == TRANSIT_TO_FRONT && isOpening) {
            if ((changeFlags & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                // This received a transferred starting window, so don't animate
                return null;
            }

            if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
                a = mTransitionAnimation.loadVoiceActivityOpenAnimation(true /** enter */);
            } else {
                a = mTransitionAnimation.loadDefaultAnimationAttr(
                        R.styleable.WindowAnimation_taskToFrontEnterAnimation);
            }
        } else if (changeMode == TRANSIT_CLOSE && !isOpening) {
            if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
                a = mTransitionAnimation.loadVoiceActivityExitAnimation(false /** enter */);
            } else if (isTask) {
                a = mTransitionAnimation.loadDefaultAnimationAttr(
                        R.styleable.WindowAnimation_taskCloseExitAnimation);
            } else {
                a = mTransitionAnimation.loadDefaultAnimationRes(
                        (changeFlags & FLAG_TRANSLUCENT) == 0
                        ? R.anim.activity_close_exit : R.anim.activity_translucent_close_exit);
            }
        } else if (changeMode == TRANSIT_TO_BACK && !isOpening) {
            if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
                a = mTransitionAnimation.loadVoiceActivityExitAnimation(false /** enter */);
            } else {
                a = mTransitionAnimation.loadDefaultAnimationAttr(
                        R.styleable.WindowAnimation_taskToBackExitAnimation);
            }
        } else if (changeMode == TRANSIT_CHANGE) {
            // In the absence of a specific adapter, we just want to keep everything stationary.
            a = new AlphaAnimation(1.f, 1.f);
            a.setDuration(TransitionAnimation.DEFAULT_APP_TRANSITION_DURATION);
        }

        if (a != null) {
            Rect start = change.getStartAbsBounds();
            Rect end = change.getEndAbsBounds();
            a.restrictDuration(MAX_ANIMATION_DURATION);
            a.initialize(end.width(), end.height(), start.width(), start.height());
            a.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        }
        return a;
    }

    private void startAnimInternal(@NonNull ArrayList<Animator> animations, @NonNull Animation anim,
            @NonNull SurfaceControl leash, @NonNull Runnable finishCallback) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        final Transformation transformation = new Transformation();
        final float[] matrix = new float[9];
        // Animation length is already expected to be scaled.
        va.overrideDurationScale(1.0f);
        va.setDuration(anim.computeDurationHint());
        va.addUpdateListener(animation -> {
            final long currentPlayTime = Math.min(va.getDuration(), va.getCurrentPlayTime());

            applyTransformation(currentPlayTime, transaction, leash, anim, transformation, matrix);
        });

        final Runnable finisher = () -> {
            applyTransformation(va.getDuration(), transaction, leash, anim, transformation, matrix);

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

    private static void applyTransformation(long time, SurfaceControl.Transaction t,
            SurfaceControl leash, Animation anim, Transformation transformation, float[] matrix) {
        anim.getTransformation(time, transformation);
        t.setMatrix(leash, transformation.getMatrix(), matrix);
        t.setAlpha(leash, transformation.getAlpha());
        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
        t.apply();
    }
}
