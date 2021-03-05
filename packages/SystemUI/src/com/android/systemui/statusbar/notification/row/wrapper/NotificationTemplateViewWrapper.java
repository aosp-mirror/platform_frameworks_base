/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.wrapper;

import static android.view.View.VISIBLE;

import static com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.DEFAULT_HEADER_VISIBLE_AMOUNT;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.NotificationActionListLayout;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.TransformState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.HybridNotificationView;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationHeaderViewWrapper {

    private final int mFullHeaderTranslation;
    protected ImageView mRightIcon;
    protected ImageView mLeftIcon;
    private ProgressBar mProgressBar;
    private TextView mTitle;
    private TextView mText;
    protected View mActionsContainer;

    private int mContentHeight;
    private int mMinHeightHint;
    private NotificationActionListLayout mActions;
    private ArraySet<PendingIntent> mCancelledPendingIntents = new ArraySet<>();
    private UiOffloadThread mUiOffloadThread;
    private View mRemoteInputHistory;
    private boolean mCanHideHeader;
    private float mHeaderTranslation;

    protected NotificationTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
        mTransformationHelper.setCustomTransformation(
                new ViewTransformationHelper.CustomTransformation() {
                    @Override
                    public boolean transformTo(TransformState ownState,
                            TransformableView notification, final float transformationAmount) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        final View text = ownState.getTransformedView();
                        CrossFadeHelper.fadeOut(text, transformationAmount);
                        if (otherState != null) {
                            ownState.transformViewVerticalTo(otherState, this,
                                    transformationAmount);
                            otherState.recycle();
                        }
                        return true;
                    }

                    @Override
                    public boolean customTransformTarget(TransformState ownState,
                            TransformState otherState) {
                        float endY = getTransformationY(ownState, otherState);
                        ownState.setTransformationEndY(endY);
                        return true;
                    }

                    @Override
                    public boolean transformFrom(TransformState ownState,
                            TransformableView notification, float transformationAmount) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        final View text = ownState.getTransformedView();
                        CrossFadeHelper.fadeIn(text, transformationAmount, true /* remap */);
                        if (otherState != null) {
                            ownState.transformViewVerticalFrom(otherState, this,
                                    transformationAmount);
                            otherState.recycle();
                        }
                        return true;
                    }

                    @Override
                    public boolean initTransformation(TransformState ownState,
                            TransformState otherState) {
                        float startY = getTransformationY(ownState, otherState);
                        ownState.setTransformationStartY(startY);
                        return true;
                    }

                    private float getTransformationY(TransformState ownState,
                            TransformState otherState) {
                        int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
                        int[] ownStablePosition = ownState.getLaidOutLocationOnScreen();
                        return (otherStablePosition[1]
                                + otherState.getTransformedView().getHeight()
                                - ownStablePosition[1]) * 0.33f;
                    }

                }, TRANSFORMING_VIEW_TEXT);
        mFullHeaderTranslation = ctx.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin)
                - ctx.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_top);
    }

    private void resolveTemplateViews(StatusBarNotification notification) {
        mRightIcon = mView.findViewById(com.android.internal.R.id.right_icon);
        if (mRightIcon != null) {
            mRightIcon.setTag(ImageTransformState.ICON_TAG,
                    notification.getNotification().getLargeIcon());
        }
        mLeftIcon = mView.findViewById(com.android.internal.R.id.left_icon);
        if (mLeftIcon != null) {
            mLeftIcon.setTag(ImageTransformState.ICON_TAG,
                    notification.getNotification().getLargeIcon());
        }
        mTitle = mView.findViewById(com.android.internal.R.id.title);
        mText = mView.findViewById(com.android.internal.R.id.text);
        final View progress = mView.findViewById(com.android.internal.R.id.progress);
        if (progress instanceof ProgressBar) {
            mProgressBar = (ProgressBar) progress;
        } else {
            // It's still a viewstub
            mProgressBar = null;
        }
        mActionsContainer = mView.findViewById(com.android.internal.R.id.actions_container);
        mActions = mView.findViewById(com.android.internal.R.id.actions);
        mRemoteInputHistory = mView.findViewById(
                com.android.internal.R.id.notification_material_reply_container);
        updatePendingIntentCancellations();
    }

    private void updatePendingIntentCancellations() {
        if (mActions != null) {
            int numActions = mActions.getChildCount();
            for (int i = 0; i < numActions; i++) {
                Button action = (Button) mActions.getChildAt(i);
                performOnPendingIntentCancellation(action, () -> {
                    if (action.isEnabled()) {
                        action.setEnabled(false);
                        // The visual appearance doesn't look disabled enough yet, let's add the
                        // alpha as well. Since Alpha doesn't play nicely right now with the
                        // transformation, we rather blend it manually with the background color.
                        ColorStateList textColors = action.getTextColors();
                        int[] colors = textColors.getColors();
                        int[] newColors = new int[colors.length];
                        float disabledAlpha = mView.getResources().getFloat(
                                com.android.internal.R.dimen.notification_action_disabled_alpha);
                        for (int j = 0; j < colors.length; j++) {
                            int color = colors[j];
                            color = blendColorWithBackground(color, disabledAlpha);
                            newColors[j] = color;
                        }
                        ColorStateList newColorStateList = new ColorStateList(
                                textColors.getStates(), newColors);
                        action.setTextColor(newColorStateList);
                    }
                });
            }
        }
    }

    private int blendColorWithBackground(int color, float alpha) {
        // alpha doesn't go well for color filters, so let's blend it manually
        return ContrastColorUtil.compositeColors(Color.argb((int) (alpha * 255),
                Color.red(color), Color.green(color), Color.blue(color)), resolveBackgroundColor());
    }

    private void performOnPendingIntentCancellation(View view, Runnable cancellationRunnable) {
        PendingIntent pendingIntent = (PendingIntent) view.getTag(
                com.android.internal.R.id.pending_intent_tag);
        if (pendingIntent == null) {
            return;
        }
        if (mCancelledPendingIntents.contains(pendingIntent)) {
            cancellationRunnable.run();
        } else {
            PendingIntent.CancelListener listener = (PendingIntent intent) -> {
                mView.post(() -> {
                    mCancelledPendingIntents.add(pendingIntent);
                    cancellationRunnable.run();
                });
            };
            if (mUiOffloadThread == null) {
                mUiOffloadThread = Dependency.get(UiOffloadThread.class);
            }
            if (view.isAttachedToWindow()) {
                mUiOffloadThread.execute(() -> pendingIntent.registerCancelListener(listener));
            }
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mUiOffloadThread.execute(() -> pendingIntent.registerCancelListener(listener));
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mUiOffloadThread.execute(
                            () -> pendingIntent.unregisterCancelListener(listener));
                }
            });
        }
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveTemplateViews(row.getEntry().getSbn());
        super.onContentUpdated(row);
        // With the modern templates, a large icon visually overlaps the header, so we can't
        // hide the header, we must show it.
        mCanHideHeader = mNotificationHeader != null
                && (mRightIcon == null || mRightIcon.getVisibility() != VISIBLE);
        if (row.getHeaderVisibleAmount() != DEFAULT_HEADER_VISIBLE_AMOUNT) {
            setHeaderVisibleAmount(row.getHeaderVisibleAmount());
        }
    }

    @Override
    protected void updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes();
        if (mTitle != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TITLE,
                    mTitle);
        }
        if (mText != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TEXT,
                    mText);
        }
        if (mRightIcon != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_IMAGE,
                    mRightIcon);
        }
        if (mProgressBar != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_PROGRESS,
                    mProgressBar);
        }
        addViewsTransformingToSimilar(mLeftIcon);
    }

    @Override
    public void setContentHeight(int contentHeight, int minHeightHint) {
        super.setContentHeight(contentHeight, minHeightHint);

        mContentHeight = contentHeight;
        mMinHeightHint = minHeightHint;
        updateActionOffset();
    }

    @Override
    public boolean shouldClipToRounding(boolean topRounded, boolean bottomRounded) {
        if (super.shouldClipToRounding(topRounded, bottomRounded)) {
            return true;
        }
        return bottomRounded && mActionsContainer != null
                && mActionsContainer.getVisibility() != View.GONE;
    }

    private void updateActionOffset() {
        if (mActionsContainer != null) {
            // We should never push the actions higher than they are in the headsup view.
            int constrainedContentHeight = Math.max(mContentHeight, mMinHeightHint);

            // We also need to compensate for any header translation, since we're always at the end.
            mActionsContainer.setTranslationY(constrainedContentHeight - mView.getHeight()
                    - getHeaderTranslation(false /* forceNoHeader */));
        }
    }

    @Override
    public int getHeaderTranslation(boolean forceNoHeader) {
        return forceNoHeader && mCanHideHeader ? mFullHeaderTranslation : (int) mHeaderTranslation;
    }

    @Override
    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        super.setHeaderVisibleAmount(headerVisibleAmount);
        float headerTranslation = 0f;
        if (mCanHideHeader && mNotificationHeader != null) {
            mNotificationHeader.setAlpha(headerVisibleAmount);
            headerTranslation = (1.0f - headerVisibleAmount) * mFullHeaderTranslation;
        }
        mHeaderTranslation = headerTranslation;
        mView.setTranslationY(mHeaderTranslation);
    }

    @Override
    public int getExtraMeasureHeight() {
        int extra = 0;
        if (mActions != null) {
            extra = mActions.getExtraMeasureHeight();
        }
        if (mRemoteInputHistory != null && mRemoteInputHistory.getVisibility() != View.GONE) {
            extra += mRow.getContext().getResources().getDimensionPixelSize(
                    R.dimen.remote_input_history_extra_height);
        }
        return extra + super.getExtraMeasureHeight();
    }
}
