/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.wm.shell.windowdecor;

import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;

/**
 * {@link WindowDecoration} to show app controls for windows on automotive.
 */
public class CarWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private WindowDecorLinearLayout mRootView;
    private @ShellBackgroundThread final ShellExecutor mBgExecutor;
    private final View.OnClickListener mClickListener;

    CarWindowDecoration(
            Context context,
            @android.annotation.NonNull Context userContext,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            View.OnClickListener clickListener) {
        super(context, userContext, displayController, taskOrganizer, taskInfo, taskSurface,
                windowDecorViewHostSupplier);
        mBgExecutor = bgExecutor;
        mClickListener = clickListener;
    }

    @Override
    void relayout(ActivityManager.RunningTaskInfo taskInfo, boolean hasGlobalFocus,
            @NonNull Region displayExclusionRegion) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        relayout(taskInfo, t, t);
    }

    @SuppressLint("MissingPermission")
    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        RelayoutParams relayoutParams = new RelayoutParams();
        RelayoutResult<WindowDecorLinearLayout> outResult = new RelayoutResult<>();

        updateRelayoutParams(relayoutParams, taskInfo,
                mDisplayController.getInsetsState(taskInfo.displayId));

        relayout(relayoutParams, startT, finishT, wct, mRootView, outResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo
        mBgExecutor.execute(() -> mTaskOrganizer.applyTransaction(wct));

        if (outResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (mRootView != outResult.mRootView) {
            mRootView = outResult.mRootView;
            setupRootView(outResult.mRootView, mClickListener);
        }
    }

    @Override
    @NonNull
    Rect calculateValidDragArea() {
        return new Rect();
    }

    @Override
    int getCaptionViewId() {
        return R.id.caption;
    }

    private void updateRelayoutParams(
            RelayoutParams relayoutParams,
            ActivityManager.RunningTaskInfo taskInfo,
            InsetsState displayInsetsState) {
        relayoutParams.reset();
        relayoutParams.mRunningTaskInfo = taskInfo;
        // todo(b/382071404): update to car specific UI
        relayoutParams.mLayoutResId = R.layout.caption_window_decor;
        relayoutParams.mCaptionHeightId = R.dimen.freeform_decor_caption_height;
        relayoutParams.mIsCaptionVisible = mIsStatusBarVisible && !mIsKeyguardVisibleAndOccluded;
        relayoutParams.mCaptionTopPadding = 0;
        relayoutParams.mInsetSourceFlags |= FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
        relayoutParams.mApplyStartTransactionOnDraw = true;
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView(View rootView, View.OnClickListener onClickListener) {
        final View caption = rootView.findViewById(R.id.caption);
        final View close = caption.findViewById(R.id.close_window);
        if (close != null) {
            close.setOnClickListener(onClickListener);
        }
        final View back = caption.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(onClickListener);
        }
    }
}
