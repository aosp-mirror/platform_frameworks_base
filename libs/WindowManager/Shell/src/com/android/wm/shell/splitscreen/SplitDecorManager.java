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

package com.android.wm.shell.splitscreen;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManagerPolicyConstants.SPLIT_DIVIDER_LAYER;

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

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.common.SurfaceUtils;

/**
 * Handles split decor like showing resizing hint for a specific split.
 */
class SplitDecorManager extends WindowlessWindowManager {
    private static final String TAG = SplitDecorManager.class.getSimpleName();
    private static final String RESIZING_BACKGROUND_SURFACE_NAME = "ResizingBackground";

    private final IconProvider mIconProvider;
    private final SurfaceSession mSurfaceSession;

    private Drawable mIcon;
    private ImageView mResizingIconView;
    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mHostLeash;
    private SurfaceControl mIconLeash;
    private SurfaceControl mBackgroundLeash;

    SplitDecorManager(Configuration configuration, IconProvider iconProvider,
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

    void inflate(Context context, SurfaceControl rootLeash, Rect rootBounds) {
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

    void release(SurfaceControl.Transaction t) {
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
    void onResizing(ActivityManager.RunningTaskInfo resizingTask, Rect newBounds,
            SurfaceControl.Transaction t) {
        if (mResizingIconView == null) {
            return;
        }

        if (mIcon == null) {
            // TODO: add fade-in animation.
            mBackgroundLeash = SurfaceUtils.makeColorLayer(mHostLeash,
                    RESIZING_BACKGROUND_SURFACE_NAME, mSurfaceSession);
            t.setColor(mBackgroundLeash, getResizingBackgroundColor(resizingTask))
                    .setLayer(mBackgroundLeash, SPLIT_DIVIDER_LAYER - 1)
                    .show(mBackgroundLeash);

            mIcon = mIconProvider.getIcon(resizingTask.topActivityInfo);
            mResizingIconView.setImageDrawable(mIcon);
            mResizingIconView.setVisibility(View.VISIBLE);

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
            lp.width = mIcon.getIntrinsicWidth();
            lp.height = mIcon.getIntrinsicHeight();
            mViewHost.relayout(lp);
            t.show(mIconLeash).setLayer(mIconLeash, SPLIT_DIVIDER_LAYER);
        }

        t.setPosition(mIconLeash,
                newBounds.width() / 2 - mIcon.getIntrinsicWidth() / 2,
                newBounds.height() / 2 - mIcon.getIntrinsicWidth() / 2);
    }

    /** Stops showing resizing hint. */
    void onResized(Rect newBounds, SurfaceControl.Transaction t) {
        if (mResizingIconView == null) {
            return;
        }

        if (mIcon != null) {
            mResizingIconView.setVisibility(View.GONE);
            mResizingIconView.setImageDrawable(null);
            t.remove(mBackgroundLeash).hide(mIconLeash);
            mIcon = null;
            mBackgroundLeash = null;
        }
    }

    private static float[] getResizingBackgroundColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getBackgroundColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).getComponents();
    }
}
