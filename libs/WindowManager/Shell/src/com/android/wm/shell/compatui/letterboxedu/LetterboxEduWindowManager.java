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

package com.android.wm.shell.compatui.letterboxedu;

import android.annotation.Nullable;
import android.app.TaskInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIWindowManagerAbstract;

/**
 * Window manager for the Letterbox Education.
 */
// TODO(b/215316431): Add tests
public class LetterboxEduWindowManager extends CompatUIWindowManagerAbstract {

    /**
     * The Letterbox Education should be the topmost child of the Task in case there can be more
     * than one child.
     */
    public static final int Z_ORDER = Integer.MAX_VALUE;

    /**
     * The name of the {@link SharedPreferences} that holds which user has seen the Letterbox
     * Education for specific packages and which user has seen the full dialog for any package.
     */
    private static final String HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME =
            "has_seen_letterbox_education";

    /**
     * The {@link SharedPreferences} instance for {@link #HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME}.
     */
    private final SharedPreferences mSharedPreferences;

    private final LetterboxEduAnimationController mAnimationController;

    // Remember the last reported state in case visibility changes due to keyguard or IME updates.
    private boolean mEligibleForLetterboxEducation;

    @Nullable
    private LetterboxEduDialogLayout mLayout;

    private final Runnable mOnDismissCallback;

    public LetterboxEduWindowManager(Context context, TaskInfo taskInfo,
            SyncTransactionQueue syncQueue, ShellTaskOrganizer.TaskListener taskListener,
            DisplayLayout displayLayout, Runnable onDismissCallback) {
        super(context, taskInfo, syncQueue, taskListener, displayLayout);
        mOnDismissCallback = onDismissCallback;
        mEligibleForLetterboxEducation = taskInfo.topActivityEligibleForLetterboxEducation;
        mAnimationController = new LetterboxEduAnimationController(context);
        mSharedPreferences = mContext.getSharedPreferences(HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME,
                Context.MODE_PRIVATE);
    }

    @Override
    protected int getZOrder() {
        return Z_ORDER;
    }

    @Override
    protected @Nullable View getLayout() {
        return mLayout;
    }

    @Override
    protected void removeLayout() {
        mLayout = null;
    }

    @Override
    protected boolean eligibleToShowLayout() {
        // If the layout isn't null then it was previously showing, and we shouldn't check if the
        // user has seen the letterbox education before.
        return mEligibleForLetterboxEducation && (mLayout != null
                || !getHasSeenLetterboxEducation());
    }

    @Override
    protected View createLayout() {
        setSeenLetterboxEducation();
        mLayout = inflateLayout();

        mAnimationController.startEnterAnimation(mLayout, /* endCallback= */
                this::setDismissOnClickListener);

        return mLayout;
    }

    private LetterboxEduDialogLayout inflateLayout() {
        return (LetterboxEduDialogLayout) LayoutInflater.from(mContext).inflate(
                R.layout.letterbox_education_dialog_layout, null);
    }

    private void setDismissOnClickListener() {
        if (mLayout == null) {
            return;
        }
        mLayout.setDismissOnClickListener(this::onDismiss);
    }

    private void onDismiss() {
        mAnimationController.startExitAnimation(mLayout, () -> {
            release();
            mOnDismissCallback.run();
        });
    }

    @Override
    public void release() {
        mAnimationController.cancelAnimation();
        super.release();
    }

    @Override
    public boolean updateCompatInfo(TaskInfo taskInfo, ShellTaskOrganizer.TaskListener taskListener,
            boolean canShow) {
        mEligibleForLetterboxEducation = taskInfo.topActivityEligibleForLetterboxEducation;

        return super.updateCompatInfo(taskInfo, taskListener, canShow);
    }

    @Override
    protected void updateSurface() {
        // We need to relayout because the layout dimensions depend on the task bounds.
        relayout();
    }

    @Override
    protected void updateSurfacePosition(Rect taskBounds, Rect stableBounds) {
        // Nothing to do, since the position of the surface is fixed to the top left corner (0,0)
        // of the task (parent surface), which is the default position of a surface.
    }

    @Override
    protected WindowManager.LayoutParams getWindowLayoutParams() {
        final Rect taskBounds = mTaskConfig.windowConfiguration.getBounds();
        return getWindowLayoutParams(/* width= */ taskBounds.width(), /* height= */
                taskBounds.height());
    }

    private boolean getHasSeenLetterboxEducation() {
        return mSharedPreferences.getBoolean(getPrefKey(), /* default= */ false);
    }

    private void setSeenLetterboxEducation() {
        mSharedPreferences.edit().putBoolean(getPrefKey(), true).apply();
    }

    private String getPrefKey() {
        return String.valueOf(mContext.getUserId());
    }
}
