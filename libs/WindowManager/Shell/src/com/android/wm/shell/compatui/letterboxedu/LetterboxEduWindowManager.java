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

import static android.provider.Settings.Secure.LAUNCHER_TASKBAR_EDUCATION_SHOWING;

import android.annotation.Nullable;
import android.app.TaskInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIWindowManagerAbstract;
import com.android.wm.shell.transition.Transitions;

/**
 * Window manager for the Letterbox Education.
 */
public class LetterboxEduWindowManager extends CompatUIWindowManagerAbstract {

    /**
     * The Letterbox Education should be the topmost child of the Task in case there can be more
     * than one child.
     */
    public static final int Z_ORDER = Integer.MAX_VALUE;

    /**
     * The name of the {@link SharedPreferences} that holds which user has seen the Letterbox
     * Education dialog.
     */
    @VisibleForTesting
    static final String HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME =
            "has_seen_letterbox_education";

    /**
     * The {@link SharedPreferences} instance for {@link #HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME}.
     */
    private final SharedPreferences mSharedPreferences;

    private final LetterboxEduAnimationController mAnimationController;

    private final Transitions mTransitions;

    /**
     * The id of the current user, to associate with a boolean in {@link
     * #HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME}, indicating whether that user has already seen the
     * Letterbox Education dialog.
     */
    private final int mUserId;

    // Remember the last reported state in case visibility changes due to keyguard or IME updates.
    private boolean mEligibleForLetterboxEducation;

    @Nullable
    @VisibleForTesting
    LetterboxEduDialogLayout mLayout;

    private final Runnable mOnDismissCallback;

    /**
     * The vertical margin between the dialog container and the task stable bounds (excluding
     * insets).
     */
    private final int mDialogVerticalMargin;

    public LetterboxEduWindowManager(Context context, TaskInfo taskInfo,
            SyncTransactionQueue syncQueue, ShellTaskOrganizer.TaskListener taskListener,
            DisplayLayout displayLayout, Transitions transitions,
            Runnable onDismissCallback) {
        this(context, taskInfo, syncQueue, taskListener, displayLayout, transitions,
                onDismissCallback, new LetterboxEduAnimationController(context));
    }

    @VisibleForTesting
    LetterboxEduWindowManager(Context context, TaskInfo taskInfo,
            SyncTransactionQueue syncQueue, ShellTaskOrganizer.TaskListener taskListener,
            DisplayLayout displayLayout, Transitions transitions, Runnable onDismissCallback,
            LetterboxEduAnimationController animationController) {
        super(context, taskInfo, syncQueue, taskListener, displayLayout);
        mTransitions = transitions;
        mOnDismissCallback = onDismissCallback;
        mAnimationController = animationController;
        mUserId = taskInfo.userId;
        mEligibleForLetterboxEducation = taskInfo.topActivityEligibleForLetterboxEducation;
        mSharedPreferences = mContext.getSharedPreferences(HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME,
                Context.MODE_PRIVATE);
        mDialogVerticalMargin = (int) mContext.getResources().getDimension(
                R.dimen.letterbox_education_dialog_margin);
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
        // - If taskbar education is showing, the letterbox education shouldn't be shown for the
        //   given task until the taskbar education is dismissed and the compat info changes (then
        //   the controller will create a new instance of this class since this one isn't eligible).
        // - If the layout isn't null then it was previously showing, and we shouldn't check if the
        //   user has seen the letterbox education before.
        return mEligibleForLetterboxEducation && !isTaskbarEduShowing() && (mLayout != null
                || !getHasSeenLetterboxEducation());
    }

    @Override
    protected View createLayout() {
        mLayout = inflateLayout();
        updateDialogMargins();

        // startEnterAnimation will be called immediately if shell-transitions are disabled.
        mTransitions.runOnIdle(this::startEnterAnimation);

        return mLayout;
    }

    private void updateDialogMargins() {
        if (mLayout == null) {
            return;
        }
        final View dialogContainer = mLayout.getDialogContainer();
        MarginLayoutParams marginParams = (MarginLayoutParams) dialogContainer.getLayoutParams();

        final Rect taskBounds = getTaskBounds();
        final Rect taskStableBounds = getTaskStableBounds();
        marginParams.topMargin = taskStableBounds.top - taskBounds.top + mDialogVerticalMargin;
        marginParams.bottomMargin =
                taskBounds.bottom - taskStableBounds.bottom + mDialogVerticalMargin;
        dialogContainer.setLayoutParams(marginParams);
    }

    private LetterboxEduDialogLayout inflateLayout() {
        return (LetterboxEduDialogLayout) LayoutInflater.from(mContext).inflate(
                R.layout.letterbox_education_dialog_layout, null);
    }

    private void startEnterAnimation() {
        if (mLayout == null) {
            // Dialog has already been released.
            return;
        }
        mAnimationController.startEnterAnimation(mLayout, /* endCallback= */
                this::onDialogEnterAnimationEnded);
    }

    private void onDialogEnterAnimationEnded() {
        if (mLayout == null) {
            // Dialog has already been released.
            return;
        }
        setSeenLetterboxEducation();
        mLayout.setDismissOnClickListener(this::onDismiss);
        // Focus on the dialog title for accessibility.
        mLayout.getDialogTitle().sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private void onDismiss() {
        if (mLayout == null) {
            return;
        }
        mLayout.setDismissOnClickListener(null);
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
    protected void onParentBoundsChanged() {
        if (mLayout == null) {
            return;
        }
        // Both the layout dimensions and dialog margins depend on the parent bounds.
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        mLayout.setLayoutParams(windowLayoutParams);
        updateDialogMargins();
        relayout(windowLayoutParams);
    }

    @Override
    protected void updateSurfacePosition() {
        // Nothing to do, since the position of the surface is fixed to the top left corner (0,0)
        // of the task (parent surface), which is the default position of a surface.
    }

    @Override
    protected WindowManager.LayoutParams getWindowLayoutParams() {
        final Rect taskBounds = getTaskBounds();
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
        return String.valueOf(mUserId);
    }

    @VisibleForTesting
    boolean isTaskbarEduShowing() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                LAUNCHER_TASKBAR_EDUCATION_SHOWING, /* def= */ 0) == 1;
    }
}
