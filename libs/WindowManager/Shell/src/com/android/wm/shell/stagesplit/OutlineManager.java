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

package com.android.wm.shell.stagesplit;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.view.IWindow;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.FrameLayout;

import com.android.wm.shell.R;

/**
 * Handles drawing outline of the bounds of provided root surface. The outline will be drown with
 * the consideration of display insets like status bar, navigation bar and display cutout.
 */
class OutlineManager extends WindowlessWindowManager {
    private static final String WINDOW_NAME = "SplitOutlineLayer";
    private final Context mContext;
    private final Rect mRootBounds = new Rect();
    private final Rect mTempRect = new Rect();
    private final Rect mLastOutlineBounds = new Rect();
    private final InsetsState mInsetsState = new InsetsState();
    private final int mExpandedTaskBarHeight;
    private OutlineView mOutlineView;
    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mHostLeash;
    private SurfaceControl mLeash;

    OutlineManager(Context context, Configuration configuration) {
        super(configuration, null /* rootSurface */, null /* hostInputToken */);
        mContext = context.createWindowContext(context.getDisplay(), TYPE_APPLICATION_OVERLAY,
                null /* options */);
        mExpandedTaskBarHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.taskbar_frame_height);
    }

    @Override
    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        b.setParent(mHostLeash);
    }

    void inflate(SurfaceControl rootLeash, Rect rootBounds) {
        if (mLeash != null || mViewHost != null) return;

        mHostLeash = rootLeash;
        mRootBounds.set(rootBounds);
        mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);

        final FrameLayout rootLayout = (FrameLayout) LayoutInflater.from(mContext)
                .inflate(R.layout.split_outline, null);
        mOutlineView = rootLayout.findViewById(R.id.split_outline);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0 /* width */, 0 /* height */, TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.width = mRootBounds.width();
        lp.height = mRootBounds.height();
        lp.token = new Binder();
        lp.setTitle(WINDOW_NAME);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        // TODO(b/189839391): Set INPUT_FEATURE_NO_INPUT_CHANNEL after WM supports
        //  TRUSTED_OVERLAY for windowless window without input channel.
        mViewHost.setView(rootLayout, lp);
        mLeash = getSurfaceControl(mViewHost.getWindowToken());

        drawOutline();
    }

    void release() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }
        mRootBounds.setEmpty();
        mLastOutlineBounds.setEmpty();
        mOutlineView = null;
        mHostLeash = null;
        mLeash = null;
    }

    @Nullable
    SurfaceControl getOutlineLeash() {
        return mLeash;
    }

    void setVisibility(boolean visible) {
        if (mOutlineView != null) {
            mOutlineView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    void setRootBounds(Rect rootBounds) {
        if (mViewHost == null || mViewHost.getView() == null) {
            return;
        }

        if (!mRootBounds.equals(rootBounds)) {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
            lp.width = rootBounds.width();
            lp.height = rootBounds.height();
            mViewHost.relayout(lp);
            mRootBounds.set(rootBounds);
            drawOutline();
        }
    }

    void onInsetsChanged(InsetsState insetsState) {
        if (!mInsetsState.equals(insetsState)) {
            mInsetsState.set(insetsState);
            drawOutline();
        }
    }

    private void computeOutlineBounds(Rect rootBounds, InsetsState insetsState, Rect outBounds) {
        outBounds.set(rootBounds);
        final InsetsSource taskBarInsetsSource =
                insetsState.getSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        // Only insets the divider bar with task bar when it's expanded so that the rounded corners
        // will be drawn against task bar.
        if (taskBarInsetsSource.getFrame().height() >= mExpandedTaskBarHeight) {
            outBounds.inset(taskBarInsetsSource.calculateVisibleInsets(outBounds));
        }

        // Offset the coordinate from screen based to surface based.
        outBounds.offset(-rootBounds.left, -rootBounds.top);
    }

    void drawOutline() {
        if (mOutlineView == null) {
            return;
        }

        computeOutlineBounds(mRootBounds, mInsetsState, mTempRect);
        if (mTempRect.equals(mLastOutlineBounds)) {
            return;
        }

        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mOutlineView.getLayoutParams();
        lp.leftMargin = mTempRect.left;
        lp.topMargin = mTempRect.top;
        lp.width = mTempRect.width();
        lp.height = mTempRect.height();
        mOutlineView.setLayoutParams(lp);
        mLastOutlineBounds.set(mTempRect);
    }
}
