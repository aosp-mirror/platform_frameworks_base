/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.ActivityOptions.ANIM_FROM_STYLE;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_CLOSE;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_INTRA_CLOSE;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_INTRA_OPEN;
import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_OPEN;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemProperties;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;

import com.android.internal.R;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

/** The helper class that provides methods for adding styles to transition animations. */
public class TransitionAnimationHelper {

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
    static final boolean sDisableCustomTaskAnimationProperty =
            SystemProperties.getBoolean(DISABLE_CUSTOM_TASK_ANIMATION_PROPERTY, true);

    /** Loads the animation that is defined through attribute id for the given transition. */
    @Nullable
    public static Animation loadAttributeAnimation(@NonNull TransitionInfo info,
            @NonNull TransitionInfo.Change change, int wallpaperTransit,
            @NonNull TransitionAnimation transitionAnimation) {
        final int type = info.getType();
        final int changeMode = change.getMode();
        final int changeFlags = change.getFlags();
        final boolean enter = Transitions.isOpeningType(changeMode);
        final boolean isTask = change.getTaskInfo() != null;
        final TransitionInfo.AnimationOptions options = info.getAnimationOptions();
        final int overrideType = options != null ? options.getType() : ANIM_NONE;
        final boolean canCustomContainer = !isTask || !sDisableCustomTaskAnimationProperty;
        final boolean isDream =
                isTask && change.getTaskInfo().topActivityType == ACTIVITY_TYPE_DREAM;
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

        Animation a = null;
        if (animAttr != 0) {
            if (overrideType == ANIM_FROM_STYLE && canCustomContainer) {
                a = transitionAnimation
                        .loadAnimationAttr(options.getPackageName(), options.getAnimations(),
                                animAttr, translucent);
            } else {
                a = transitionAnimation.loadDefaultAnimationAttr(animAttr, translucent);
            }
        }

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "loadAnimation: anim=%s animAttr=0x%x type=%s isEntrance=%b", a, animAttr,
                transitTypeToString(type),
                enter);
        return a;
    }

    /**
     * Gets the background {@link ColorInt} for the given transition animation if it is set.
     *
     * @param defaultColor  {@link ColorInt} to return if there is no background color specified by
     *                      the given transition animation.
     */
    @ColorInt
    public static int getTransitionBackgroundColorIfSet(@NonNull TransitionInfo info,
            @NonNull TransitionInfo.Change change, @NonNull Animation a,
            @ColorInt int defaultColor) {
        if (!a.getShowBackdrop()) {
            return defaultColor;
        }
        if (info.getAnimationOptions() != null
                && info.getAnimationOptions().getBackgroundColor() != 0) {
            // If available use the background color provided through AnimationOptions
            return info.getAnimationOptions().getBackgroundColor();
        } else if (a.getBackdropColor() != 0) {
            // Otherwise fallback on the background color provided through the animation
            // definition.
            return a.getBackdropColor();
        } else if (change.getBackgroundColor() != 0) {
            // Otherwise default to the window's background color if provided through
            // the theme as the background color for the animation - the top most window
            // with a valid background color and showBackground set takes precedence.
            return change.getBackgroundColor();
        }
        return defaultColor;
    }

    /**
     * Adds the given {@code backgroundColor} as the background color to the transition animation.
     */
    public static void addBackgroundToTransition(@NonNull SurfaceControl rootLeash,
            @ColorInt int backgroundColor, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        if (backgroundColor == 0) {
            // No background color.
            return;
        }
        final Color bgColor = Color.valueOf(backgroundColor);
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

    /**
     * Adds edge extension surface to the given {@code change} for edge extension animation.
     */
    public static void edgeExtendWindow(@NonNull TransitionInfo.Change change,
            @NonNull Animation a, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        // Do not create edge extension surface for transfer starting window change.
        // The app surface could be empty thus nothing can draw on the hardware renderer, which will
        // block this thread when calling Surface#unlockCanvasAndPost.
        if ((change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
            return;
        }
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

    /**
     * Takes a screenshot of {@code surfaceToExtend}'s edge and extends it for edge extension
     * animation.
     */
    private static SurfaceControl createExtensionSurface(@NonNull SurfaceControl surfaceToExtend,
            @NonNull Rect edgeBounds, @NonNull Rect extensionRect, int xPos, int yPos,
            @NonNull String layerName, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        final SurfaceControl edgeExtensionLayer = new SurfaceControl.Builder()
                .setName(layerName)
                .setParent(surfaceToExtend)
                .setHidden(true)
                .setCallsite("TransitionAnimationHelper#createExtensionSurface")
                .setOpaque(true)
                .setBufferSize(extensionRect.width(), extensionRect.height())
                .build();

        final SurfaceControl.LayerCaptureArgs captureArgs =
                new SurfaceControl.LayerCaptureArgs.Builder(surfaceToExtend)
                        .setSourceCrop(edgeBounds)
                        .setFrameScale(1)
                        .setPixelFormat(PixelFormat.RGBA_8888)
                        .setChildrenOnly(true)
                        .setAllowProtected(true)
                        .setCaptureSecureLayers(true)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer edgeBuffer =
                SurfaceControl.captureLayers(captureArgs);

        if (edgeBuffer == null) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                    "Failed to capture edge of window.");
            return null;
        }

        final BitmapShader shader = new BitmapShader(edgeBuffer.asBitmap(),
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        final Paint paint = new Paint();
        paint.setShader(shader);

        final Surface surface = new Surface(edgeExtensionLayer);
        final Canvas c = surface.lockHardwareCanvas();
        c.drawRect(extensionRect, paint);
        surface.unlockCanvasAndPost(c);
        surface.release();

        startTransaction.setLayer(edgeExtensionLayer, Integer.MIN_VALUE);
        startTransaction.setPosition(edgeExtensionLayer, xPos, yPos);
        startTransaction.setVisibility(edgeExtensionLayer, true);
        finishTransaction.remove(edgeExtensionLayer);

        return edgeExtensionLayer;
    }
}
