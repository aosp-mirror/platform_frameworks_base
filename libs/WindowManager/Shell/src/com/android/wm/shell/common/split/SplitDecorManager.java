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
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManagerPolicyConstants.SPLIT_DIVIDER_LAYER;

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
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.common.SurfaceUtils;

/**
 * Handles split decor like showing resizing hint for a specific split.
 */
public class SplitDecorManager extends WindowlessWindowManager {
    private static final String TAG = SplitDecorManager.class.getSimpleName();
    private static final String RESIZING_BACKGROUND_SURFACE_NAME = "ResizingBackground";
    private static final long FADE_DURATION = 133;

    private final IconProvider mIconProvider;
    private final SurfaceSession mSurfaceSession;

    private Drawable mIcon;
    private ImageView mResizingIconView;
    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mHostLeash;
    private SurfaceControl mIconLeash;
    private SurfaceControl mBackgroundLeash;

    private boolean mShown;
    private boolean mIsResizing;
    private Rect mBounds = new Rect();
    private ValueAnimator mFadeAnimator;

    public SplitDecorManager(Configuration configuration, IconProvider iconProvider,
            SurfaceSession surfaceSession) {
        super(configuration, null /* rootSurface */, null /* hostInputToken */);
        mIconProvider = iconProvider;
        mSurfaceSession = surfaceSession;
    }

    @Override
    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        // Can't set position for the ViewRootImpl SC directly. Create a leash to manipulate later.
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName(TAG)
                .setHidden(true)
                .setParent(mHostLeash)
                .setCallsite("SplitDecorManager#attachToParentSurface");
        mIconLeash = builder.build();
        b.setParent(mIconLeash);
    }

    /** Inflates split decor surface on the root surface. */
    public void inflate(Context context, SurfaceControl rootLeash, Rect rootBounds) {
        if (mIconLeash != null && mViewHost != null) {
            return;
        }

        context = context.createWindowContext(context.getDisplay(), TYPE_APPLICATION_OVERLAY,
                null /* options */);
        mHostLeash = rootLeash;
        mViewHost = new SurfaceControlViewHost(context, context.getDisplay(), this);

        final FrameLayout rootLayout = (FrameLayout) LayoutInflater.from(context)
                .inflate(R.layout.split_decor, null);
        mResizingIconView = rootLayout.findViewById(R.id.split_resizing_icon);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0 /* width */, 0 /* height */, TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.width = rootBounds.width();
        lp.height = rootBounds.height();
        lp.token = new Binder();
        lp.setTitle(TAG);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        // TODO(b/189839391): Set INPUT_FEATURE_NO_INPUT_CHANNEL after WM supports
        //  TRUSTED_OVERLAY for windowless window without input channel.
        mViewHost.setView(rootLayout, lp);
    }

    /** Releases the surfaces for split decor. */
    public void release(SurfaceControl.Transaction t) {
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
        mHostLeash = null;
        mIcon = null;
        mResizingIconView = null;
    }

    /** Showing resizing hint. */
    public void onResizing(ActivityManager.RunningTaskInfo resizingTask, Rect newBounds,
            SurfaceControl.Transaction t) {
        if (mResizingIconView == null) {
            return;
        }

        if (!mIsResizing) {
            mIsResizing = true;
            mBounds.set(newBounds);
        }

        if (mBackgroundLeash == null) {
            mBackgroundLeash = SurfaceUtils.makeColorLayer(mHostLeash,
                    RESIZING_BACKGROUND_SURFACE_NAME, mSurfaceSession);
            t.setColor(mBackgroundLeash, getResizingBackgroundColor(resizingTask))
                    .setLayer(mBackgroundLeash, SPLIT_DIVIDER_LAYER - 1);
        }

        if (mIcon == null && resizingTask.topActivityInfo != null) {
            mIcon = mIconProvider.getIcon(resizingTask.topActivityInfo);
            mResizingIconView.setImageDrawable(mIcon);
            mResizingIconView.setVisibility(View.VISIBLE);

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
            lp.width = mIcon.getIntrinsicWidth();
            lp.height = mIcon.getIntrinsicHeight();
            mViewHost.relayout(lp);
            t.setLayer(mIconLeash, SPLIT_DIVIDER_LAYER);
        }
        t.setPosition(mIconLeash,
                newBounds.width() / 2 - mIcon.getIntrinsicWidth() / 2,
                newBounds.height() / 2 - mIcon.getIntrinsicWidth() / 2);

        boolean show = newBounds.width() > mBounds.width() || newBounds.height() > mBounds.height();
        if (show != mShown) {
            if (mFadeAnimator != null && mFadeAnimator.isRunning()) {
                mFadeAnimator.cancel();
            }
            startFadeAnimation(show, false /* releaseLeash */);
            mShown = show;
        }
    }

    /** Stops showing resizing hint. */
    public void onResized(Rect newBounds, SurfaceControl.Transaction t) {
        if (mResizingIconView == null) {
            return;
        }

        mIsResizing = false;
        if (mFadeAnimator != null && mFadeAnimator.isRunning()) {
            if (!mShown) {
                // If fade-out animation is running, just add release callback to it.
                SurfaceControl.Transaction finishT = new SurfaceControl.Transaction();
                mFadeAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        releaseLeash(finishT);
                        finishT.apply();
                        finishT.close();
                    }
                });
                return;
            }

            // If fade-in animation is running, cancel it and re-run fade-out one.
            mFadeAnimator.cancel();
        }
        if (mShown) {
            startFadeAnimation(false /* show */, true /* releaseLeash */);
            mShown = false;
        } else {
            // Surface is hidden so release it directly.
            releaseLeash(t);
        }
    }

    private void startFadeAnimation(boolean show, boolean releaseLeash) {
        final SurfaceControl.Transaction animT = new SurfaceControl.Transaction();
        mFadeAnimator = ValueAnimator.ofFloat(0f, 1f);
        mFadeAnimator.setDuration(FADE_DURATION);
        mFadeAnimator.addUpdateListener(valueAnimator-> {
            final float progress = (float) valueAnimator.getAnimatedValue();
            animT.setAlpha(mBackgroundLeash, show ? progress : 1 - progress);
            animT.setAlpha(mIconLeash, show ? progress : 1 - progress);
            animT.apply();
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                if (show) {
                    animT.show(mBackgroundLeash).show(mIconLeash).apply();
                }
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                if (!show) {
                    animT.hide(mBackgroundLeash).hide(mIconLeash).apply();
                }
                if (releaseLeash) {
                    releaseLeash(animT);
                    animT.apply();
                }
                animT.close();
            }
        });
        mFadeAnimator.start();
    }

    private void releaseLeash(SurfaceControl.Transaction t) {
        if (mBackgroundLeash != null) {
            t.remove(mBackgroundLeash);
            mBackgroundLeash = null;
        }

        if (mIcon != null) {
            mResizingIconView.setVisibility(View.GONE);
            mResizingIconView.setImageDrawable(null);
            t.hide(mIconLeash);
            mIcon = null;
        }
    }

    private static float[] getResizingBackgroundColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getBackgroundColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).getComponents();
    }
}
