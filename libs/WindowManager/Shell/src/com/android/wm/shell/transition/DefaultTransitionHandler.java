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
import static android.app.ActivityOptions.ANIM_FROM_STYLE;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.ActivityOptions.ANIM_SCALE_UP;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_UP;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_RESOURCE_UPDATED;
import static android.app.admin.DevicePolicyManager.EXTRA_RESOURCE_TYPE;
import static android.app.admin.DevicePolicyManager.EXTRA_RESOURCE_TYPE_DRAWABLE;
import static android.app.admin.DevicePolicyResources.Drawables.Source.PROFILE_SWITCH_ANIMATION;
import static android.app.admin.DevicePolicyResources.Drawables.Style.OUTLINE;
import static android.app.admin.DevicePolicyResources.Drawables.WORK_PROFILE_ICON;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_DISPLAY_HAS_ALERT_WINDOWS;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
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
import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowManager.TransitionType;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;
import android.window.TransitionMetrics;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.AttributeCache;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private final DisplayController mDisplayController;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionAnimation mTransitionAnimation;
    private final DevicePolicyManager mDevicePolicyManager;

    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    /** Keeps track of the currently-running animations associated with each transition. */
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();

    private final CounterRotatorHelper mRotator = new CounterRotatorHelper();
    private final Rect mInsets = new Rect(0, 0, 0, 0);
    private float mTransitionAnimationScaleSetting = 1.0f;

    private final int mCurrentUserId;

    private ScreenRotationAnimation mRotationAnimation;

    private Drawable mEnterpriseThumbnailDrawable;

    private BroadcastReceiver mEnterpriseResourceUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_RESOURCE_TYPE, /* default= */ -1)
                    != EXTRA_RESOURCE_TYPE_DRAWABLE) {
                return;
            }
            updateEnterpriseThumbnailDrawable();
        }
    };

    DefaultTransitionHandler(@NonNull DisplayController displayController,
            @NonNull TransactionPool transactionPool, Context context,
            @NonNull ShellExecutor mainExecutor, @NonNull Handler mainHandler,
            @NonNull ShellExecutor animExecutor) {
        mDisplayController = displayController;
        mTransactionPool = transactionPool;
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mTransitionAnimation = new TransitionAnimation(context, false /* debug */, Transitions.TAG);
        mCurrentUserId = UserHandle.myUserId();

        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        updateEnterpriseThumbnailDrawable();
        mContext.registerReceiver(
                mEnterpriseResourceUpdatedReceiver,
                new IntentFilter(ACTION_DEVICE_POLICY_RESOURCE_UPDATED),
                /* broadcastPermission = */ null,
                mainHandler);

        AttributeCache.init(context);
    }

    private void updateEnterpriseThumbnailDrawable() {
        mEnterpriseThumbnailDrawable = mDevicePolicyManager.getResources().getDrawable(
                WORK_PROFILE_ICON, OUTLINE, PROFILE_SWITCH_ANIMATION,
                () -> mContext.getDrawable(R.drawable.ic_corp_badge));
    }

    @VisibleForTesting
    static boolean isRotationSeamless(@NonNull TransitionInfo info,
            DisplayController displayController) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Display is changing, check if it should be seamless.");
        boolean checkedDisplayLayout = false;
        boolean hasTask = false;
        boolean displayExplicitSeamless = false;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);

            // Only look at changing things. showing/hiding don't need to rotate.
            if (change.getMode() != TRANSIT_CHANGE) continue;

            // This container isn't rotating, so we can ignore it.
            if (change.getEndRotation() == change.getStartRotation()) continue;
            if ((change.getFlags() & FLAG_IS_DISPLAY) != 0) {
                // In the presence of System Alert windows we can not seamlessly rotate.
                if ((change.getFlags() & FLAG_DISPLAY_HAS_ALERT_WINDOWS) != 0) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                            "  display has system alert windows, so not seamless.");
                    return false;
                }
                displayExplicitSeamless =
                        change.getRotationAnimation() == ROTATION_ANIMATION_SEAMLESS;
            } else if ((change.getFlags() & FLAG_IS_WALLPAPER) != 0) {
                if (change.getRotationAnimation() != ROTATION_ANIMATION_SEAMLESS) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                            "  wallpaper is participating but isn't seamless.");
                    return false;
                }
            } else if (change.getTaskInfo() != null) {
                hasTask = true;
                // We only enable seamless rotation if all the visible task windows requested it.
                if (change.getRotationAnimation() != ROTATION_ANIMATION_SEAMLESS) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                            "  task %s isn't requesting seamless, so not seamless.",
                            change.getTaskInfo().taskId);
                    return false;
                }

                // This is the only way to get display-id currently, so we will check display
                // capabilities here
                if (!checkedDisplayLayout) {
                    // only need to check display once.
                    checkedDisplayLayout = true;
                    final DisplayLayout displayLayout = displayController.getDisplayLayout(
                            change.getTaskInfo().displayId);
                    // For the upside down rotation we don't rotate seamlessly as the navigation
                    // bar moves position. Note most apps (using orientation:sensor or user as
                    // opposed to fullSensor) will not enter the reverse portrait orientation, so
                    // actually the orientation won't change at all.
                    int upsideDownRotation = displayLayout.getUpsideDownRotation();
                    if (change.getStartRotation() == upsideDownRotation
                            || change.getEndRotation() == upsideDownRotation) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                                "  rotation involves upside-down portrait, so not seamless.");
                        return false;
                    }

                    // If the navigation bar can't change sides, then it will jump when we change
                    // orientations and we don't rotate seamlessly - unless that is allowed, eg.
                    // with gesture navigation where the navbar is low-profile enough that this
                    // isn't very noticeable.
                    if (!displayLayout.allowSeamlessRotationDespiteNavBarMoving()
                            && (!(displayLayout.navigationBarCanMove()
                                    && (change.getStartAbsBounds().width()
                                            != change.getStartAbsBounds().height())))) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                                "  nav bar changes sides, so not seamless.");
                        return false;
                    }
                }
            }
        }

        // ROTATION_ANIMATION_SEAMLESS can only be requested by task or display.
        if (hasTask || displayExplicitSeamless) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  Rotation IS seamless.");
            return true;
        }
        return false;
    }

    /**
     * Gets the rotation animation for the topmost task. Assumes that seamless is checked
     * elsewhere, so it will default SEAMLESS to ROTATE.
     */
    private int getRotationAnimation(@NonNull TransitionInfo info) {
        // Traverse in top-to-bottom order so that the first task is top-most
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change change = info.getChanges().get(i);

            // Only look at changing things. showing/hiding don't need to rotate.
            if (change.getMode() != TRANSIT_CHANGE) continue;

            // This container isn't rotating, so we can ignore it.
            if (change.getEndRotation() == change.getStartRotation()) continue;

            if (change.getTaskInfo() != null) {
                final int anim = change.getRotationAnimation();
                if (anim == ROTATION_ANIMATION_UNSPECIFIED
                        // Fallback animation for seamless should also be default.
                        || anim == ROTATION_ANIMATION_SEAMLESS) {
                    return ROTATION_ANIMATION_ROTATE;
                }
                return anim;
            }
        }
        return ROTATION_ANIMATION_ROTATE;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "start default transition animation, info = %s", info);
        // If keyguard goes away, we should loadKeyguardExitAnimation. Otherwise this just
        // immediately finishes since there is no animation for screen-wake.
        if (info.getType() == WindowManager.TRANSIT_WAKE && !info.isKeyguardGoingAway()) {
            startTransaction.apply();
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
            return true;
        }

        if (mAnimations.containsKey(transition)) {
            throw new IllegalStateException("Got a duplicate startAnimation call for "
                    + transition);
        }
        final ArrayList<Animator> animations = new ArrayList<>();
        mAnimations.put(transition, animations);

        final Runnable onAnimFinish = () -> {
            if (!animations.isEmpty()) return;

            if (mRotationAnimation != null) {
                mRotationAnimation.kill();
                mRotationAnimation = null;
            }

            mAnimations.remove(transition);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        };

        final List<Consumer<SurfaceControl.Transaction>> postStartTransactionCallbacks =
                new ArrayList<>();

        @ColorInt int backgroundColorForTransition = 0;
        final int wallpaperTransit = getWallpaperTransitType(info);
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final boolean isTask = change.getTaskInfo() != null;
            boolean isSeamlessDisplayChange = false;

            if (change.getMode() == TRANSIT_CHANGE && (change.getFlags() & FLAG_IS_DISPLAY) != 0) {
                if (info.getType() == TRANSIT_CHANGE) {
                    isSeamlessDisplayChange = isRotationSeamless(info, mDisplayController);
                    final int anim = getRotationAnimation(info);
                    if (!(isSeamlessDisplayChange || anim == ROTATION_ANIMATION_JUMPCUT)) {
                        mRotationAnimation = new ScreenRotationAnimation(mContext, mSurfaceSession,
                                mTransactionPool, startTransaction, change, info.getRootLeash(),
                                anim);
                        mRotationAnimation.startAnimation(animations, onAnimFinish,
                                mTransitionAnimationScaleSetting, mMainExecutor, mAnimExecutor);
                        continue;
                    }
                } else {
                    // Opening/closing an app into a new orientation.
                    mRotator.handleClosingChanges(info, startTransaction, change);
                }
            }

            if (change.getMode() == TRANSIT_CHANGE) {
                // If task is child task, only set position in parent and update crop when needed.
                if (isTask && change.getParent() != null
                        && info.getChange(change.getParent()).getTaskInfo() != null) {
                    final Point positionInParent = change.getTaskInfo().positionInParent;
                    startTransaction.setPosition(change.getLeash(),
                            positionInParent.x, positionInParent.y);

                    if (!change.getEndAbsBounds().equals(
                            info.getChange(change.getParent()).getEndAbsBounds())) {
                        startTransaction.setWindowCrop(change.getLeash(),
                                change.getEndAbsBounds().width(),
                                change.getEndAbsBounds().height());
                    }

                    continue;
                }

                // There is no default animation for Pip window in rotation transition, and the
                // PipTransition will update the surface of its own window at start/finish.
                if (isTask && change.getTaskInfo().configuration.windowConfiguration
                        .getWindowingMode() == WINDOWING_MODE_PINNED) {
                    continue;
                }
                // No default animation for this, so just update bounds/position.
                startTransaction.setPosition(change.getLeash(),
                        change.getEndAbsBounds().left - info.getRootOffset().x,
                        change.getEndAbsBounds().top - info.getRootOffset().y);
                // Seamless display transition doesn't need to animate.
                if (isSeamlessDisplayChange) continue;
                if (isTask) {
                    // Skip non-tasks since those usually have null bounds.
                    startTransaction.setWindowCrop(change.getLeash(),
                            change.getEndAbsBounds().width(), change.getEndAbsBounds().height());
                }
            }

            // Don't animate anything that isn't independent.
            if (!TransitionInfo.isIndependent(change, info)) continue;

            Animation a = loadAnimation(info, change, wallpaperTransit);
            if (a != null) {
                if (isTask) {
                    final @TransitionType int type = info.getType();
                    final boolean isOpenOrCloseTransition = type == TRANSIT_OPEN
                            || type == TRANSIT_CLOSE
                            || type == TRANSIT_TO_FRONT
                            || type == TRANSIT_TO_BACK;
                    final boolean isTranslucent = (change.getFlags() & FLAG_TRANSLUCENT) != 0;
                    if (isOpenOrCloseTransition && !isTranslucent
                            && wallpaperTransit == WALLPAPER_TRANSITION_NONE) {
                        // Use the overview background as the background for the animation
                        final Context uiContext = ActivityThread.currentActivityThread()
                                .getSystemUiContext();
                        backgroundColorForTransition =
                                uiContext.getColor(R.color.overview_background);
                    }
                }

                final float cornerRadius;
                if (a.hasRoundedCorners() && isTask) {
                    // hasRoundedCorners is currently only enabled for tasks
                    final Context displayContext =
                            mDisplayController.getDisplayContext(change.getTaskInfo().displayId);
                    cornerRadius = displayContext == null ? 0
                            : ScreenDecorationsUtils.getWindowCornerRadius(displayContext);
                } else {
                    cornerRadius = 0;
                }

                if (a.getShowBackdrop()) {
                    if (info.getAnimationOptions().getBackgroundColor() != 0) {
                        // If available use the background color provided through AnimationOptions
                        backgroundColorForTransition =
                                info.getAnimationOptions().getBackgroundColor();
                    } else if (a.getBackdropColor() != 0) {
                        // Otherwise fallback on the background color provided through the animation
                        // definition.
                        backgroundColorForTransition = a.getBackdropColor();
                    } else if (change.getBackgroundColor() != 0) {
                        // Otherwise default to the window's background color if provided through
                        // the theme as the background color for the animation - the top most window
                        // with a valid background color and showBackground set takes precedence.
                        backgroundColorForTransition = change.getBackgroundColor();
                    }
                }

                boolean delayedEdgeExtension = false;
                if (!isTask && a.hasExtension()) {
                    if (!Transitions.isOpeningType(change.getMode())) {
                        // Can screenshot now (before startTransaction is applied)
                        edgeExtendWindow(change, a, startTransaction, finishTransaction);
                    } else {
                        // Need to screenshot after startTransaction is applied otherwise activity
                        // may not be visible or ready yet.
                        postStartTransactionCallbacks
                                .add(t -> edgeExtendWindow(change, a, t, finishTransaction));
                        delayedEdgeExtension = true;
                    }
                }

                final Rect clipRect = Transitions.isClosingType(change.getMode())
                        ? new Rect(mRotator.getEndBoundsInStartRotation(change))
                        : new Rect(change.getEndAbsBounds());
                clipRect.offsetTo(0, 0);

                if (delayedEdgeExtension) {
                    // If the edge extension needs to happen after the startTransition has been
                    // applied, then we want to only start the animation after the edge extension
                    // postStartTransaction callback has been run
                    postStartTransactionCallbacks.add(t ->
                            startSurfaceAnimation(animations, a, change.getLeash(), onAnimFinish,
                                    mTransactionPool, mMainExecutor, mAnimExecutor,
                                    null /* position */, cornerRadius, clipRect));
                } else {
                    startSurfaceAnimation(animations, a, change.getLeash(), onAnimFinish,
                            mTransactionPool, mMainExecutor, mAnimExecutor, null /* position */,
                            cornerRadius, clipRect);
                }

                if (info.getAnimationOptions() != null) {
                    attachThumbnail(animations, onAnimFinish, change, info.getAnimationOptions(),
                            cornerRadius);
                }
            }
        }

        if (backgroundColorForTransition != 0) {
            addBackgroundToTransition(info.getRootLeash(), backgroundColorForTransition,
                    startTransaction, finishTransaction);
        }

        // postStartTransactionCallbacks require that the start transaction is already
        // applied to run otherwise they may result in flickers and UI inconsistencies.
        boolean waitForStartTransactionApply = postStartTransactionCallbacks.size() > 0;
        startTransaction.apply(waitForStartTransactionApply);

        // Run tasks that require startTransaction to already be applied
        for (Consumer<SurfaceControl.Transaction> postStartTransactionCallback :
                postStartTransactionCallbacks) {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            postStartTransactionCallback.accept(t);
            t.apply();
            mTransactionPool.release(t);
        }

        mRotator.cleanUp(finishTransaction);
        TransitionMetrics.getInstance().reportAnimationStart(transition);
        // run finish now in-case there are no animations
        onAnimFinish.run();
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ArrayList<Animator> anims = mAnimations.get(mergeTarget);
        if (anims == null) return;
        for (int i = anims.size() - 1; i >= 0; --i) {
            final Animator anim = anims.get(i);
            mAnimExecutor.execute(anim::end);
        }
    }

    private void edgeExtendWindow(TransitionInfo.Change change,
            Animation a, SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction) {
        final Transformation transformationAtStart = new Transformation();
        a.getTransformationAt(0, transformationAtStart);
        final Transformation transformationAtEnd = new Transformation();
        a.getTransformationAt(1, transformationAtEnd);

        // We want to create an extension surface that is the maximal size and the animation will
        // take care of cropping any part that overflows.
        final Insets maxExtensionInsets = Insets.min(
                transformationAtStart.getInsets(), transformationAtEnd.getInsets());

        final int targetSurfaceHeight = Math.max(change.getStartAbsBounds().height(),
                change.getEndAbsBounds().height());
        final int targetSurfaceWidth = Math.max(change.getStartAbsBounds().width(),
                change.getEndAbsBounds().width());
        if (maxExtensionInsets.left < 0) {
            final Rect edgeBounds = new Rect(0, 0, 1, targetSurfaceHeight);
            final Rect extensionRect = new Rect(0, 0,
                    -maxExtensionInsets.left, targetSurfaceHeight);
            final int xPos = maxExtensionInsets.left;
            final int yPos = 0;
            createExtensionSurface(change.getLeash(), edgeBounds, extensionRect, xPos, yPos,
                    "Left Edge Extension", startTransaction, finishTransaction);
        }

        if (maxExtensionInsets.top < 0) {
            final Rect edgeBounds = new Rect(0, 0, targetSurfaceWidth, 1);
            final Rect extensionRect = new Rect(0, 0,
                    targetSurfaceWidth, -maxExtensionInsets.top);
            final int xPos = 0;
            final int yPos = maxExtensionInsets.top;
            createExtensionSurface(change.getLeash(), edgeBounds, extensionRect, xPos, yPos,
                    "Top Edge Extension", startTransaction, finishTransaction);
        }

        if (maxExtensionInsets.right < 0) {
            final Rect edgeBounds = new Rect(targetSurfaceWidth - 1, 0,
                    targetSurfaceWidth, targetSurfaceHeight);
            final Rect extensionRect = new Rect(0, 0,
                    -maxExtensionInsets.right, targetSurfaceHeight);
            final int xPos = targetSurfaceWidth;
            final int yPos = 0;
            createExtensionSurface(change.getLeash(), edgeBounds, extensionRect, xPos, yPos,
                    "Right Edge Extension", startTransaction, finishTransaction);
        }

        if (maxExtensionInsets.bottom < 0) {
            final Rect edgeBounds = new Rect(0, targetSurfaceHeight - 1,
                    targetSurfaceWidth, targetSurfaceHeight);
            final Rect extensionRect = new Rect(0, 0,
                    targetSurfaceWidth, -maxExtensionInsets.bottom);
            final int xPos = maxExtensionInsets.left;
            final int yPos = targetSurfaceHeight;
            createExtensionSurface(change.getLeash(), edgeBounds, extensionRect, xPos, yPos,
                    "Bottom Edge Extension", startTransaction, finishTransaction);
        }
    }

    private SurfaceControl createExtensionSurface(SurfaceControl surfaceToExtend, Rect edgeBounds,
            Rect extensionRect, int xPos, int yPos, String layerName,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction) {
        final SurfaceControl edgeExtensionLayer = new SurfaceControl.Builder()
                .setName(layerName)
                .setParent(surfaceToExtend)
                .setHidden(true)
                .setCallsite("DefaultTransitionHandler#startAnimation")
                .setOpaque(true)
                .setBufferSize(extensionRect.width(), extensionRect.height())
                .build();

        SurfaceControl.LayerCaptureArgs captureArgs =
                new SurfaceControl.LayerCaptureArgs.Builder(surfaceToExtend)
                        .setSourceCrop(edgeBounds)
                        .setFrameScale(1)
                        .setPixelFormat(PixelFormat.RGBA_8888)
                        .setChildrenOnly(true)
                        .setAllowProtected(true)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer edgeBuffer =
                SurfaceControl.captureLayers(captureArgs);

        if (edgeBuffer == null) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                    "Failed to capture edge of window.");
            return null;
        }

        android.graphics.BitmapShader shader =
                new android.graphics.BitmapShader(edgeBuffer.asBitmap(),
                        android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP);
        final Paint paint = new Paint();
        paint.setShader(shader);

        final Surface surface = new Surface(edgeExtensionLayer);
        Canvas c = surface.lockHardwareCanvas();
        c.drawRect(extensionRect, paint);
        surface.unlockCanvasAndPost(c);
        surface.release();

        startTransaction.setLayer(edgeExtensionLayer, Integer.MIN_VALUE);
        startTransaction.setPosition(edgeExtensionLayer, xPos, yPos);
        startTransaction.setVisibility(edgeExtensionLayer, true);
        finishTransaction.remove(edgeExtensionLayer);

        return edgeExtensionLayer;
    }

    private void addBackgroundToTransition(
            @NonNull SurfaceControl rootLeash,
            @ColorInt int color,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction
    ) {
        final Color bgColor = Color.valueOf(color);
        final float[] colorArray = new float[] { bgColor.red(), bgColor.green(), bgColor.blue() };

        final SurfaceControl animationBackgroundSurface = new SurfaceControl.Builder()
                .setName("Animation Background")
                .setParent(rootLeash)
                .setColorLayer()
                .setOpaque(true)
                .build();

        startTransaction
                .setLayer(animationBackgroundSurface, Integer.MIN_VALUE)
                .setColor(animationBackgroundSurface, colorArray)
                .show(animationBackgroundSurface);
        finishTransaction.remove(animationBackgroundSurface);
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
        final Rect endBounds = Transitions.isClosingType(changeMode)
                ? mRotator.getEndBoundsInStartRotation(change)
                : change.getEndAbsBounds();
        final boolean isDream =
                isTask && change.getTaskInfo().topActivityType == ACTIVITY_TYPE_DREAM;

        if (info.isKeyguardGoingAway()) {
            a = mTransitionAnimation.loadKeyguardExitAnimation(flags,
                    (changeFlags & FLAG_SHOW_WALLPAPER) != 0);
        } else if (type == TRANSIT_KEYGUARD_UNOCCLUDE) {
            a = mTransitionAnimation.loadKeyguardUnoccludeAnimation();
        } else if ((changeFlags & FLAG_IS_VOICE_INTERACTION) != 0) {
            if (isOpeningType) {
                a = mTransitionAnimation.loadVoiceActivityOpenAnimation(enter);
            } else {
                a = mTransitionAnimation.loadVoiceActivityExitAnimation(enter);
            }
        } else if (changeMode == TRANSIT_CHANGE) {
            // In the absence of a specific adapter, we just want to keep everything stationary.
            a = new AlphaAnimation(1.f, 1.f);
            a.setDuration(TransitionAnimation.DEFAULT_APP_TRANSITION_DURATION);
        } else if (type == TRANSIT_RELAUNCH) {
            a = mTransitionAnimation.createRelaunchAnimation(endBounds, mInsets, endBounds);
        } else if (overrideType == ANIM_CUSTOM
                && (canCustomContainer || options.getOverrideTaskTransition())) {
            a = mTransitionAnimation.loadAnimationRes(options.getPackageName(), enter
                    ? options.getEnterResId() : options.getExitResId());
        } else if (overrideType == ANIM_OPEN_CROSS_PROFILE_APPS && enter) {
            a = mTransitionAnimation.loadCrossProfileAppEnterAnimation();
        } else if (overrideType == ANIM_CLIP_REVEAL) {
            a = mTransitionAnimation.createClipRevealAnimationLocked(type, wallpaperTransit, enter,
                    endBounds, endBounds, options.getTransitionBounds());
        } else if (overrideType == ANIM_SCALE_UP) {
            a = mTransitionAnimation.createScaleUpAnimationLocked(type, wallpaperTransit, enter,
                    endBounds, options.getTransitionBounds());
        } else if (overrideType == ANIM_THUMBNAIL_SCALE_UP
                || overrideType == ANIM_THUMBNAIL_SCALE_DOWN) {
            final boolean scaleUp = overrideType == ANIM_THUMBNAIL_SCALE_UP;
            a = mTransitionAnimation.createThumbnailEnterExitAnimationLocked(enter, scaleUp,
                    endBounds, type, wallpaperTransit, options.getThumbnail(),
                    options.getTransitionBounds());
        } else if ((changeFlags & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0 && isOpeningType) {
            // This received a transferred starting window, so don't animate
            return null;
        } else {
            int animAttr = 0;
            boolean translucent = false;
            if (isDream) {
                if (type == TRANSIT_OPEN) {
                    animAttr = enter
                            ? R.styleable.WindowAnimation_dreamActivityOpenEnterAnimation
                            : R.styleable.WindowAnimation_dreamActivityOpenExitAnimation;
                } else if (type == TRANSIT_CLOSE) {
                    animAttr = enter
                            ? 0
                            : R.styleable.WindowAnimation_dreamActivityCloseExitAnimation;
                }
            } else if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_OPEN) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation
                        : R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
            } else if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_CLOSE) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation
                        : R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;
            } else if (wallpaperTransit == WALLPAPER_TRANSITION_OPEN) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_wallpaperOpenEnterAnimation
                        : R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
            } else if (wallpaperTransit == WALLPAPER_TRANSITION_CLOSE) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_wallpaperCloseEnterAnimation
                        : R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
            } else if (type == TRANSIT_OPEN) {
                // We will translucent open animation for translucent activities and tasks. Choose
                // WindowAnimation_activityOpenEnterAnimation and set translucent here, then
                // TransitionAnimation loads appropriate animation later.
                if ((changeFlags & FLAG_TRANSLUCENT) != 0 && enter) {
                    translucent = true;
                }
                if (isTask && !translucent) {
                    animAttr = enter
                            ? R.styleable.WindowAnimation_taskOpenEnterAnimation
                            : R.styleable.WindowAnimation_taskOpenExitAnimation;
                } else {
                    animAttr = enter
                            ? R.styleable.WindowAnimation_activityOpenEnterAnimation
                            : R.styleable.WindowAnimation_activityOpenExitAnimation;
                }
            } else if (type == TRANSIT_TO_FRONT) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_taskToFrontEnterAnimation
                        : R.styleable.WindowAnimation_taskToFrontExitAnimation;
            } else if (type == TRANSIT_CLOSE) {
                if (isTask) {
                    animAttr = enter
                            ? R.styleable.WindowAnimation_taskCloseEnterAnimation
                            : R.styleable.WindowAnimation_taskCloseExitAnimation;
                } else {
                    if ((changeFlags & FLAG_TRANSLUCENT) != 0 && !enter) {
                        translucent = true;
                    }
                    animAttr = enter
                            ? R.styleable.WindowAnimation_activityCloseEnterAnimation
                            : R.styleable.WindowAnimation_activityCloseExitAnimation;
                }
            } else if (type == TRANSIT_TO_BACK) {
                animAttr = enter
                        ? R.styleable.WindowAnimation_taskToBackEnterAnimation
                        : R.styleable.WindowAnimation_taskToBackExitAnimation;
            }

            if (animAttr != 0) {
                if (overrideType == ANIM_FROM_STYLE && canCustomContainer) {
                    a = mTransitionAnimation
                            .loadAnimationAttr(options.getPackageName(), options.getAnimations(),
                                    animAttr, translucent);
                } else {
                    a = mTransitionAnimation.loadDefaultAnimationAttr(animAttr, translucent);
                }
            }

            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                    "loadAnimation: anim=%s animAttr=0x%x type=%s isEntrance=%b", a, animAttr,
                    transitTypeToString(type),
                    enter);
        }

        if (a != null) {
            if (!a.isInitialized()) {
                final int width = endBounds.width();
                final int height = endBounds.height();
                a.initialize(width, height, width, height);
            }
            a.restrictDuration(MAX_ANIMATION_DURATION);
            a.scaleCurrentDuration(mTransitionAnimationScaleSetting);
        }
        return a;
    }

    static void startSurfaceAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Animation anim, @NonNull SurfaceControl leash,
            @NonNull Runnable finishCallback, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor,
            @Nullable Point position, float cornerRadius, @Nullable Rect clipRect) {
        final SurfaceControl.Transaction transaction = pool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        final Transformation transformation = new Transformation();
        final float[] matrix = new float[9];
        // Animation length is already expected to be scaled.
        va.overrideDurationScale(1.0f);
        va.setDuration(anim.computeDurationHint());
        va.addUpdateListener(animation -> {
            final long currentPlayTime = Math.min(va.getDuration(), va.getCurrentPlayTime());

            applyTransformation(currentPlayTime, transaction, leash, anim, transformation, matrix,
                    position, cornerRadius, clipRect);
        });

        final Runnable finisher = () -> {
            applyTransformation(va.getDuration(), transaction, leash, anim, transformation, matrix,
                    position, cornerRadius, clipRect);

            pool.release(transaction);
            mainExecutor.execute(() -> {
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
        animExecutor.execute(va::start);
    }

    private void attachThumbnail(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change,
            TransitionInfo.AnimationOptions options, float cornerRadius) {
        final boolean isTask = change.getTaskInfo() != null;
        final boolean isOpen = Transitions.isOpeningType(change.getMode());
        final boolean isClose = Transitions.isClosingType(change.getMode());
        if (isOpen) {
            if (options.getType() == ANIM_OPEN_CROSS_PROFILE_APPS && isTask) {
                attachCrossProfileThumbnailAnimation(animations, finishCallback, change,
                        cornerRadius);
            } else if (options.getType() == ANIM_THUMBNAIL_SCALE_UP) {
                attachThumbnailAnimation(animations, finishCallback, change, options, cornerRadius);
            }
        } else if (isClose && options.getType() == ANIM_THUMBNAIL_SCALE_DOWN) {
            attachThumbnailAnimation(animations, finishCallback, change, options, cornerRadius);
        }
    }

    private void attachCrossProfileThumbnailAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change, float cornerRadius) {
        final Rect bounds = change.getEndAbsBounds();
        // Show the right drawable depending on the user we're transitioning to.
        final Drawable thumbnailDrawable = change.getTaskInfo().userId == mCurrentUserId
                ? mContext.getDrawable(R.drawable.ic_account_circle) : mEnterpriseThumbnailDrawable;
        final HardwareBuffer thumbnail = mTransitionAnimation.createCrossProfileAppsThumbnail(
                thumbnailDrawable, bounds);
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
        startSurfaceAnimation(animations, a, wt.getSurface(), finisher, mTransactionPool,
                mMainExecutor, mAnimExecutor, new Point(bounds.left, bounds.top),
                cornerRadius, change.getEndAbsBounds());
    }

    private void attachThumbnailAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, TransitionInfo.Change change,
            TransitionInfo.AnimationOptions options, float cornerRadius) {
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
        startSurfaceAnimation(animations, a, wt.getSurface(), finisher, mTransactionPool,
                mMainExecutor, mAnimExecutor, null /* position */,
                cornerRadius, change.getEndAbsBounds());
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
            Point position, float cornerRadius, @Nullable Rect immutableClipRect) {
        anim.getTransformation(time, transformation);
        if (position != null) {
            transformation.getMatrix().postTranslate(position.x, position.y);
        }
        t.setMatrix(leash, transformation.getMatrix(), matrix);
        t.setAlpha(leash, transformation.getAlpha());

        final Rect clipRect = immutableClipRect == null ? null : new Rect(immutableClipRect);
        Insets extensionInsets = Insets.min(transformation.getInsets(), Insets.NONE);
        if (!extensionInsets.equals(Insets.NONE) && clipRect != null && !clipRect.isEmpty()) {
            // Clip out any overflowing edge extension
            clipRect.inset(extensionInsets);
            t.setCrop(leash, clipRect);
        }

        if (anim.hasRoundedCorners() && cornerRadius > 0 && clipRect != null) {
            // We can only apply rounded corner if a crop is set
            t.setCrop(leash, clipRect);
            t.setCornerRadius(leash, cornerRadius);
        }

        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
        t.apply();
    }
}
