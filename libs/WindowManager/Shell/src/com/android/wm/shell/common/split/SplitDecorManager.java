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

package com.android.wm.shell.common.split;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.wm.shell.common.split.SplitLayout.BEHIND_APP_VEIL_LAYER;
import static com.android.wm.shell.common.split.SplitLayout.FRONT_APP_VEIL_LAYER;
import static com.android.wm.shell.shared.split.SplitScreenConstants.FADE_DURATION;
import static com.android.wm.shell.shared.split.SplitScreenConstants.VEIL_DELAY_DURATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.common.ScreenshotUtils;
import com.android.wm.shell.common.SurfaceUtils;

import java.util.function.Consumer;

/**
 * Handles additional layers over a running task in a split pair, for example showing a veil with an
 * app icon when the task is being resized (usually to hide weird layouts while the app is being
 * stretched). One SplitDecorManager is initialized on each window.
 * <br>
 * Currently, we show a veil when:
 *  a) Task is resizing down from a fullscreen window.
 *  b) Task is being stretched past its original bounds.
 */
public class SplitDecorManager extends WindowlessWindowManager {
    private static final String TAG = SplitDecorManager.class.getSimpleName();
    private static final String RESIZING_BACKGROUND_SURFACE_NAME = "ResizingBackground";
    private static final String GAP_BACKGROUND_SURFACE_NAME = "GapBackground";

    private final IconProvider mIconProvider;

    private Drawable mIcon;
    private ImageView mVeilIconView;
    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mHostLeash;
    private SurfaceControl mIconLeash;
    private SurfaceControl mBackgroundLeash;
    private SurfaceControl mGapBackgroundLeash;
    private SurfaceControl mScreenshot;

    private boolean mShown;
    /** True if the task is going through some kind of transition (moving or changing size). */
    private boolean mIsCurrentlyChanging;
    /** The original bounds of the main task, captured at the beginning of a resize transition. */
    private final Rect mOldMainBounds = new Rect();
    /** The original bounds of the side task, captured at the beginning of a resize transition. */
    private final Rect mOldSideBounds = new Rect();
    /** The current bounds of the main task, mid-resize. */
    private final Rect mInstantaneousBounds = new Rect();
    private final Rect mTempRect = new Rect();
    private ValueAnimator mFadeAnimator;
    private ValueAnimator mScreenshotAnimator;

    private int mIconSize;
    private int mOffsetX;
    private int mOffsetY;
    private int mRunningAnimationCount = 0;

    public SplitDecorManager(Configuration configuration, IconProvider iconProvider) {
        super(configuration, null /* rootSurface */, null /* hostInputToken */);
        mIconProvider = iconProvider;
    }

    @Override
    protected SurfaceControl getParentSurface(IWindow window, WindowManager.LayoutParams attrs) {
        // Can't set position for the ViewRootImpl SC directly. Create a leash to manipulate later.
        final SurfaceControl.Builder builder = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName(TAG)
                .setHidden(true)
                .setParent(mHostLeash)
                .setCallsite("SplitDecorManager#attachToParentSurface");
        mIconLeash = builder.build();
        return mIconLeash;
    }

    /** Inflates split decor surface on the root surface. */
    public void inflate(Context context, SurfaceControl rootLeash) {
        if (mIconLeash != null && mViewHost != null) {
            return;
        }

        context = context.createWindowContext(context.getDisplay(), TYPE_APPLICATION_OVERLAY,
                null /* options */);
        mHostLeash = rootLeash;
        mViewHost = new SurfaceControlViewHost(context, context.getDisplay(), this,
                "SplitDecorManager");

        mIconSize = context.getResources().getDimensionPixelSize(R.dimen.split_icon_size);
        final FrameLayout rootLayout = (FrameLayout) LayoutInflater.from(context)
                .inflate(R.layout.split_decor, null);
        mVeilIconView = rootLayout.findViewById(R.id.split_resizing_icon);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0 /* width */, 0 /* height */, TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.width = mIconSize;
        lp.height = mIconSize;
        lp.token = new Binder();
        lp.setTitle(TAG);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.inputFeatures |= INPUT_FEATURE_NO_INPUT_CHANNEL;
        mViewHost.setView(rootLayout, lp);
    }

    /**
     * Cancels any currently running animations.
     */
    public void cancelRunningAnimations() {
        if (mFadeAnimator != null) {
            if (mFadeAnimator.isRunning()) {
                mFadeAnimator.cancel();
            }
            mFadeAnimator = null;
        }
        if (mScreenshotAnimator != null) {
            if (mScreenshotAnimator.isRunning()) {
                mScreenshotAnimator.cancel();
            }
            mScreenshotAnimator = null;
        }
    }

    /** Releases the surfaces for split decor. */
    public void release(SurfaceControl.Transaction t) {
        cancelRunningAnimations();
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }
        if (mIconLeash != null) {
            t.remove(mIconLeash);
            mIconLeash = null;
        }
        if (mBackgroundLeash != null) {
            t.remove(mBackgroundLeash);
            mBackgroundLeash = null;
        }
        if (mGapBackgroundLeash != null) {
            t.remove(mGapBackgroundLeash);
            mGapBackgroundLeash = null;
        }
        if (mScreenshot != null) {
            t.remove(mScreenshot);
            mScreenshot = null;
        }
        mHostLeash = null;
        mIcon = null;
        mVeilIconView = null;
        mIsCurrentlyChanging = false;
        mShown = false;
        mOldMainBounds.setEmpty();
        mOldSideBounds.setEmpty();
        mInstantaneousBounds.setEmpty();
    }

    /** Showing resizing hint. */
    public void onResizing(ActivityManager.RunningTaskInfo resizingTask, Rect newBounds,
            Rect sideBounds, SurfaceControl.Transaction t, int offsetX, int offsetY,
            boolean immediately) {
        if (mVeilIconView == null) {
            return;
        }

        if (!mIsCurrentlyChanging) {
            mIsCurrentlyChanging = true;
            mOldMainBounds.set(newBounds);
            mOldSideBounds.set(sideBounds);
        }
        mInstantaneousBounds.set(newBounds);
        mOffsetX = offsetX;
        mOffsetY = offsetY;

        // Show a veil when:
        //  a) Task is resizing down from a fullscreen window.
        //  b) Task is being stretched past its original bounds.
        final boolean isResizingDownFromFullscreen =
                mOldSideBounds.width() <= 1 || mOldSideBounds.height() <= 1;
        final boolean isStretchingPastOriginalBounds =
                newBounds.width() > mOldMainBounds.width()
                        || newBounds.height() > mOldMainBounds.height();
        final boolean showVeil = isResizingDownFromFullscreen || isStretchingPastOriginalBounds;
        final boolean update = showVeil != mShown;
        if (update && mFadeAnimator != null && mFadeAnimator.isRunning()) {
            // If we need to animate and animator still running, cancel it before we ensure both
            // background and icon surfaces are non null for next animation.
            mFadeAnimator.cancel();
        }

        if (mBackgroundLeash == null) {
            mBackgroundLeash = SurfaceUtils.makeColorLayer(mHostLeash,
                    RESIZING_BACKGROUND_SURFACE_NAME);
            t.setColor(mBackgroundLeash, getResizingBackgroundColor(resizingTask))
                    .setLayer(mBackgroundLeash, Integer.MAX_VALUE - 1);
        }

        if (mGapBackgroundLeash == null && !immediately) {
            final boolean isLandscape = newBounds.height() == sideBounds.height();
            final int left = isLandscape ? mOldMainBounds.width() : 0;
            final int top = isLandscape ? 0 : mOldMainBounds.height();
            mGapBackgroundLeash = SurfaceUtils.makeColorLayer(mHostLeash,
                    GAP_BACKGROUND_SURFACE_NAME);
            // Fill up another side bounds area.
            t.setColor(mGapBackgroundLeash, getResizingBackgroundColor(resizingTask))
                    .setLayer(mGapBackgroundLeash, Integer.MAX_VALUE - 2)
                    .setPosition(mGapBackgroundLeash, left, top)
                    .setWindowCrop(mGapBackgroundLeash, sideBounds.width(), sideBounds.height());
        }

        if (mIcon == null && resizingTask.topActivityInfo != null) {
            mIcon = mIconProvider.getIcon(resizingTask.topActivityInfo);
            mVeilIconView.setImageDrawable(mIcon);
            mVeilIconView.setVisibility(View.VISIBLE);

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
            lp.width = mIconSize;
            lp.height = mIconSize;
            mViewHost.relayout(lp);
            t.setLayer(mIconLeash, Integer.MAX_VALUE);
        }
        t.setPosition(mIconLeash,
                newBounds.width() / 2 - mIconSize / 2,
                newBounds.height() / 2 - mIconSize / 2);

        if (update) {
            if (immediately) {
                t.setAlpha(mBackgroundLeash, showVeil ? 1f : 0f);
                t.setVisibility(mBackgroundLeash, showVeil);
                t.setAlpha(mIconLeash, showVeil ? 1f : 0f);
                t.setVisibility(mIconLeash, showVeil);
            } else {
                startFadeAnimation(
                        showVeil,
                        false /* releaseSurface */,
                        null /* finishedCallback */,
                        false /* addDelay */
                );
            }
            mShown = showVeil;
        }
    }

    /** Stops showing resizing hint. */
    public void onResized(SurfaceControl.Transaction t, Consumer<Boolean> animFinishedCallback) {
        if (mScreenshotAnimator != null && mScreenshotAnimator.isRunning()) {
            mScreenshotAnimator.cancel();
        }

        if (mScreenshot != null) {
            t.setPosition(mScreenshot, mOffsetX, mOffsetY);

            final SurfaceControl.Transaction animT = new SurfaceControl.Transaction();
            mScreenshotAnimator = ValueAnimator.ofFloat(1, 0);
            mScreenshotAnimator.setDuration(FADE_DURATION);
            mScreenshotAnimator.addUpdateListener(valueAnimator -> {
                final float progress = (float) valueAnimator.getAnimatedValue();
                animT.setAlpha(mScreenshot, progress);
                animT.apply();
            });
            mScreenshotAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mRunningAnimationCount++;
                }

                @Override
                public void onAnimationEnd(@NonNull Animator animation) {
                    mRunningAnimationCount--;
                    animT.remove(mScreenshot);
                    animT.apply();
                    animT.close();
                    mScreenshot = null;

                    if (mRunningAnimationCount == 0 && animFinishedCallback != null) {
                        animFinishedCallback.accept(true);
                    }
                }
            });
            mScreenshotAnimator.start();
        }

        if (mVeilIconView == null) {
            if (mRunningAnimationCount == 0 && animFinishedCallback != null) {
                animFinishedCallback.accept(false);
            }
            return;
        }

        mIsCurrentlyChanging = false;
        mOffsetX = 0;
        mOffsetY = 0;
        mOldMainBounds.setEmpty();
        mOldSideBounds.setEmpty();
        mInstantaneousBounds.setEmpty();
        if (mFadeAnimator != null && mFadeAnimator.isRunning()) {
            if (!mShown) {
                // If fade-out animation is running, just add release callback to it.
                SurfaceControl.Transaction finishT = new SurfaceControl.Transaction();
                mFadeAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        releaseDecor(finishT);
                        finishT.apply();
                        finishT.close();
                        if (mRunningAnimationCount == 0 && animFinishedCallback != null) {
                            animFinishedCallback.accept(true);
                        }
                    }
                });
                return;
            }
        }
        if (mShown) {
            fadeOutDecor(()-> {
                if (mRunningAnimationCount == 0 && animFinishedCallback != null) {
                    animFinishedCallback.accept(true);
                }
            }, false /* addDelay */);
        } else {
            // Decor surface is hidden so release it directly.
            releaseDecor(t);
            if (mRunningAnimationCount == 0 && animFinishedCallback != null) {
                animFinishedCallback.accept(false);
            }
        }
    }

    /**
     * Called (on every frame) when two split apps are swapping, and a veil is needed.
     */
    public void drawNextVeilFrameForSwapAnimation(ActivityManager.RunningTaskInfo resizingTask,
            Rect newBounds, SurfaceControl.Transaction t, boolean isGoingBehind,
            SurfaceControl leash, float iconOffsetX, float iconOffsetY) {
        if (mVeilIconView == null) {
            return;
        }

        if (!mIsCurrentlyChanging) {
            mIsCurrentlyChanging = true;
        }

        mInstantaneousBounds.set(newBounds);
        mOffsetX = (int) iconOffsetX;
        mOffsetY = (int) iconOffsetY;

        t.setLayer(leash, isGoingBehind ? BEHIND_APP_VEIL_LAYER : FRONT_APP_VEIL_LAYER);

        if (!mShown) {
            if (mFadeAnimator != null && mFadeAnimator.isRunning()) {
                // Cancel mFadeAnimator if it is running
                mFadeAnimator.cancel();
            }
        }

        if (mBackgroundLeash == null) {
            // Initialize background
            mBackgroundLeash = SurfaceUtils.makeColorLayer(mHostLeash,
                    RESIZING_BACKGROUND_SURFACE_NAME);
            t.setColor(mBackgroundLeash, getResizingBackgroundColor(resizingTask))
                    .setLayer(mBackgroundLeash, Integer.MAX_VALUE - 1);
        }

        if (mIcon == null && resizingTask.topActivityInfo != null) {
            // Initialize icon
            mIcon = mIconProvider.getIcon(resizingTask.topActivityInfo);
            mVeilIconView.setImageDrawable(mIcon);
            mVeilIconView.setVisibility(View.VISIBLE);

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
            lp.width = mIconSize;
            lp.height = mIconSize;
            mViewHost.relayout(lp);

            t.setLayer(mIconLeash, Integer.MAX_VALUE);
        }

        t.setPosition(mIconLeash,
                newBounds.width() / 2 - mIconSize / 2 - mOffsetX,
                newBounds.height() / 2 - mIconSize / 2 - mOffsetY);

        // If this is the first frame, we need to trigger the veil's fade-in animation.
        if (!mShown) {
            startFadeAnimation(
                    true /* show */,
                    false /* releaseSurface */,
                    null /* finishedCallball */,
                    false /* addDelay */
            );
            mShown = true;
        }
    }

    /** Called at the end of the swap animation. */
    public void fadeOutVeilAndCleanUp(SurfaceControl.Transaction t) {
        if (mVeilIconView == null) {
            return;
        }

        // Recenter icon
        t.setPosition(mIconLeash,
                mInstantaneousBounds.width() / 2f - mIconSize / 2f,
                mInstantaneousBounds.height() / 2f - mIconSize / 2f);

        mIsCurrentlyChanging = false;
        mOffsetX = 0;
        mOffsetY = 0;
        mInstantaneousBounds.setEmpty();

        fadeOutDecor(() -> {}, true /* addDelay */);
    }

    /** Screenshot host leash and attach on it if meet some conditions */
    public void screenshotIfNeeded(SurfaceControl.Transaction t) {
        if (!mShown && mIsCurrentlyChanging && !mOldMainBounds.equals(mInstantaneousBounds)) {
            if (mScreenshotAnimator != null && mScreenshotAnimator.isRunning()) {
                mScreenshotAnimator.cancel();
            } else if (mScreenshot != null) {
                t.remove(mScreenshot);
            }

            mTempRect.set(mOldMainBounds);
            mTempRect.offsetTo(0, 0);
            mScreenshot = ScreenshotUtils.takeScreenshot(t, mHostLeash, mTempRect,
                    Integer.MAX_VALUE - 1);
        }
    }

    /** Set screenshot and attach on host leash it if meet some conditions */
    public void setScreenshotIfNeeded(SurfaceControl screenshot, SurfaceControl.Transaction t) {
        if (screenshot == null || !screenshot.isValid()) return;

        if (!mShown && mIsCurrentlyChanging && !mOldMainBounds.equals(mInstantaneousBounds)) {
            if (mScreenshotAnimator != null && mScreenshotAnimator.isRunning()) {
                mScreenshotAnimator.cancel();
            } else if (mScreenshot != null) {
                t.remove(mScreenshot);
            }

            mScreenshot = screenshot;
            t.reparent(screenshot, mHostLeash);
            t.setLayer(screenshot, Integer.MAX_VALUE - 1);
        }
    }

    /** Fade-out decor surface with animation end callback, if decor is hidden, run the callback
     * directly. */
    public void fadeOutDecor(Runnable finishedCallback, boolean addDelay) {
        if (mShown) {
            // If previous animation is running, just cancel it.
            if (mFadeAnimator != null && mFadeAnimator.isRunning()) {
                mFadeAnimator.cancel();
            }

            startFadeAnimation(
                    false /* show */, true /* releaseSurface */, finishedCallback, addDelay);
            mShown = false;
        } else {
            if (finishedCallback != null) finishedCallback.run();
        }
    }

    /**
     * Fades the veil in or out. Called at the first frame of a movement or resize when a veil is
     * needed (with show = true), and called again at the end (with show = false).
     * @param addDelay If true, adds a short delay before fading out to get the app behind the veil
     *                 time to redraw.
     */
    private void startFadeAnimation(boolean show, boolean releaseSurface,
            Runnable finishedCallback, boolean addDelay) {
        final SurfaceControl.Transaction animT = new SurfaceControl.Transaction();

        mFadeAnimator = ValueAnimator.ofFloat(0f, 1f);
        if (addDelay) {
            mFadeAnimator.setStartDelay(VEIL_DELAY_DURATION);
        }
        mFadeAnimator.setDuration(FADE_DURATION);
        mFadeAnimator.addUpdateListener(valueAnimator-> {
            final float progress = (float) valueAnimator.getAnimatedValue();
            if (mBackgroundLeash != null) {
                animT.setAlpha(mBackgroundLeash, show ? progress : 1 - progress);
            }
            if (mIconLeash != null) {
                animT.setAlpha(mIconLeash, show ? progress : 1 - progress);
            }
            animT.apply();
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                mRunningAnimationCount++;
                if (show) {
                    animT.show(mBackgroundLeash).show(mIconLeash);
                }
                if (mGapBackgroundLeash != null) {
                    animT.setVisibility(mGapBackgroundLeash, show);
                }
                animT.apply();
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                mRunningAnimationCount--;
                if (!show) {
                    if (mBackgroundLeash != null) {
                        animT.hide(mBackgroundLeash);
                    }
                    if (mIconLeash != null) {
                        animT.hide(mIconLeash);
                    }
                }
                if (releaseSurface) {
                    releaseDecor(animT);
                }
                animT.apply();
                animT.close();

                if (mRunningAnimationCount == 0 && finishedCallback != null) {
                    finishedCallback.run();
                }
            }
        });
        mFadeAnimator.start();
    }

    /** Release or hide decor hint. */
    private void releaseDecor(SurfaceControl.Transaction t) {
        if (mBackgroundLeash != null) {
            t.remove(mBackgroundLeash);
            mBackgroundLeash = null;
        }

        if (mGapBackgroundLeash != null) {
            t.remove(mGapBackgroundLeash);
            mGapBackgroundLeash = null;
        }

        if (mIcon != null) {
            mVeilIconView.setVisibility(View.GONE);
            mVeilIconView.setImageDrawable(null);
            t.hide(mIconLeash);
            mIcon = null;
        }
    }

    private static float[] getResizingBackgroundColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getBackgroundColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).getComponents();
    }
}
