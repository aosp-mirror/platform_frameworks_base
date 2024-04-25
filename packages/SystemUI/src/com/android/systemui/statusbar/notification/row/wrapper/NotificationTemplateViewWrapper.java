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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.NotificationActionListLayout;
import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.TransformState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.HybridNotificationView;

import java.util.function.Consumer;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationHeaderViewWrapper {

    private final int mFullHeaderTranslation;
    private final boolean mAllowHideHeader;
    protected ImageView mRightIcon;
    protected ImageView mLeftIcon;
    private ProgressBar mProgressBar;
    private TextView mTitle;
    private TextView mText;
    protected View mSmartReplyContainer;
    protected View mActionsContainer;

    private int mContentHeight;
    private int mMinHeightHint;
    @Nullable
    private NotificationActionListLayout mActions;
    // Holds list of pending intents that have been cancelled by now - we only keep hash codes
    // to avoid holding full binder proxies for intents that may have been removed by now.
    @NonNull
    @VisibleForTesting
    final ArraySet<Integer> mCancelledPendingIntents = new ArraySet<>();
    private View mRemoteInputHistory;
    private boolean mCanHideHeader;
    private float mHeaderTranslation;

    protected NotificationTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
        mAllowHideHeader = ctx.getResources().getBoolean(R.bool.heads_up_notification_hides_header);
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

    @MainThread
    private void resolveTemplateViews(StatusBarNotification sbn) {
        mRightIcon = mView.findViewById(com.android.internal.R.id.right_icon);
        if (mRightIcon != null) {
            mRightIcon.setTag(ImageTransformState.ICON_TAG, getRightIcon(sbn.getNotification()));
            mRightIcon.setTag(TransformState.ALIGN_END_TAG, true);
        }
        mLeftIcon = mView.findViewById(com.android.internal.R.id.left_icon);
        if (mLeftIcon != null) {
            mLeftIcon.setTag(ImageTransformState.ICON_TAG, getLargeIcon(sbn.getNotification()));
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
        mSmartReplyContainer = mView.findViewById(com.android.internal.R.id.smart_reply_container);
        mActionsContainer = mView.findViewById(com.android.internal.R.id.actions_container);
        mActions = mView.findViewById(com.android.internal.R.id.actions);
        mRemoteInputHistory = mView.findViewById(
                com.android.internal.R.id.notification_material_reply_container);
        updatePendingIntentCancellations();
    }

    @Nullable
    protected final Icon getLargeIcon(Notification n) {
        Icon modernLargeIcon = n.getLargeIcon();
        if (modernLargeIcon == null && n.largeIcon != null) {
            return Icon.createWithBitmap(n.largeIcon);
        }
        return modernLargeIcon;
    }

    @Nullable
    protected final Icon getRightIcon(Notification n) {
        if (n.extras.getBoolean(Notification.EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED)
                && n.isStyle(Notification.BigPictureStyle.class)) {
            Icon pictureIcon = Notification.BigPictureStyle.getPictureIcon(n.extras);
            if (pictureIcon != null) {
                return pictureIcon;
            }
        }
        return getLargeIcon(n);
    }

    @MainThread
    private void updatePendingIntentCancellations() {
        if (mActions != null) {
            int numActions = mActions.getChildCount();
            final ArraySet<Integer> currentlyActivePendingIntents = new ArraySet<>(numActions);
            for (int i = 0; i < numActions; i++) {
                Button action = (Button) mActions.getChildAt(i);
                PendingIntent pendingIntent = getPendingIntentForAction(action);
                // Check if passed intent has already been cancelled in this class and immediately
                // disable the action to avoid temporary race with enable/disable.
                if (pendingIntent != null) {
                    int pendingIntentHashCode = getHashCodeForPendingIntent(pendingIntent);
                    currentlyActivePendingIntents.add(pendingIntentHashCode);
                    if (mCancelledPendingIntents.contains(pendingIntentHashCode)) {
                        disableActionView(action);
                    }
                }
                updatePendingIntentCancellationListener(action, pendingIntent);
            }

            // This cleanup ensures that the size of this set doesn't grow into unreasonable sizes.
            // There are scenarios where applications updated notifications with different
            // PendingIntents which could cause this Set to grow to 1000+ elements.
            mCancelledPendingIntents.retainAll(currentlyActivePendingIntents);
        }
    }

    @MainThread
    private void updatePendingIntentCancellationListener(Button action,
            @Nullable PendingIntent pendingIntent) {
        ActionPendingIntentCancellationHandler cancellationHandler = null;
        if (pendingIntent != null) {
            // Attach listeners to handle intent cancellation to this view.
            cancellationHandler = new ActionPendingIntentCancellationHandler(pendingIntent, action,
                    this::disableActionViewWithIntent);
            action.addOnAttachStateChangeListener(cancellationHandler);
            // Immediately fire the event if the view is already attached to register
            // pending intent cancellation listener.
            if (action.isAttachedToWindow()) {
                cancellationHandler.onViewAttachedToWindow(action);
            }
        }

        // If the view has an old attached listener, remove it to avoid leaking intents.
        ActionPendingIntentCancellationHandler previousHandler =
                (ActionPendingIntentCancellationHandler) action.getTag(
                        R.id.pending_intent_listener_tag);
        if (previousHandler != null) {
            previousHandler.remove();
        }
        action.setTag(R.id.pending_intent_listener_tag, cancellationHandler);
    }

    private int blendColorWithBackground(int color, float alpha) {
        // alpha doesn't go well for color filters, so let's blend it manually
        return ContrastColorUtil.compositeColors(Color.argb((int) (alpha * 255),
                Color.red(color), Color.green(color), Color.blue(color)), resolveBackgroundColor());
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveTemplateViews(row.getEntry().getSbn());
        super.onContentUpdated(row);
        // With the modern templates, a large icon visually overlaps the header, so we can't
        // hide the header, we must show it.
        mCanHideHeader = mAllowHideHeader && mNotificationHeader != null
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
        addTransformedViews(mSmartReplyContainer);
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

    /**
     * This finds Action view with a given intent and disables it.
     * With maximum of 3 views, this is sufficiently fast to iterate on main thread every time.
     */
    @MainThread
    private void disableActionViewWithIntent(PendingIntent intent) {
        mCancelledPendingIntents.add(getHashCodeForPendingIntent(intent));
        if (mActions != null) {
            int numActions = mActions.getChildCount();
            for (int i = 0; i < numActions; i++) {
                Button action = (Button) mActions.getChildAt(i);
                PendingIntent pendingIntent = getPendingIntentForAction(action);
                if (intent.equals(pendingIntent)) {
                    disableActionView(action);
                }
            }
        }
    }

    /**
     * Disables Action view when, e.g., its PendingIntent is disabled.
     */
    @MainThread
    private void disableActionView(Button action) {
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
    }

    /**
     * Returns the hashcode of underlying target of PendingIntent. We can get multiple
     * Java PendingIntent wrapper objects pointing to the same cancelled PI in system_server.
     * This makes sure we treat them equally.
     */
    private static int getHashCodeForPendingIntent(PendingIntent pendingIntent) {
        return System.identityHashCode(pendingIntent.getTarget().asBinder());
    }

    /**
     * Returns PendingIntent contained in the action tag. May be null.
     */
    @Nullable
    private static PendingIntent getPendingIntentForAction(View action) {
        return (PendingIntent) action.getTag(com.android.internal.R.id.pending_intent_tag);
    }

    /**
     * Registers listeners for pending intent cancellation when Action views are attached
     * to window.
     * It calls onCancelPendingIntentForActionView when a PendingIntent is cancelled.
     */
    @VisibleForTesting
    static final class ActionPendingIntentCancellationHandler
            implements View.OnAttachStateChangeListener {

        @Nullable
        private static UiOffloadThread sUiOffloadThread = null;

        @NonNull
        private static UiOffloadThread getUiOffloadThread() {
            if (sUiOffloadThread == null) {
                sUiOffloadThread = Dependency.get(UiOffloadThread.class);
            }
            return sUiOffloadThread;
        }

        private final View mView;
        private final Consumer<PendingIntent> mOnCancelledCallback;

        private final PendingIntent mPendingIntent;

        ActionPendingIntentCancellationHandler(PendingIntent pendingIntent, View actionView,
                Consumer<PendingIntent> onCancelled) {
            this.mPendingIntent = pendingIntent;
            this.mView = actionView;
            this.mOnCancelledCallback = onCancelled;
        }

        private final PendingIntent.CancelListener mCancelListener =
                new PendingIntent.CancelListener() {
            @Override
            public void onCanceled(PendingIntent pendingIntent) {
                mView.post(() -> {
                    mOnCancelledCallback.accept(pendingIntent);
                    // We don't need this listener anymore once the intent was cancelled.
                    remove();
                });
            }
        };

        @MainThread
        @Override
        public void onViewAttachedToWindow(View view) {
            // This is safe to call multiple times with the same listener instance.
            getUiOffloadThread().execute(() -> {
                mPendingIntent.registerCancelListener(mCancelListener);
            });
        }

        @MainThread
        @Override
        public void onViewDetachedFromWindow(View view) {
            // This is safe to call multiple times with the same listener instance.
            getUiOffloadThread().execute(() ->
                    mPendingIntent.unregisterCancelListener(mCancelListener));
        }

        /**
         * Removes this listener from callbacks and releases the held PendingIntent.
         */
        @MainThread
        public void remove() {
            mView.removeOnAttachStateChangeListener(this);
            if (mView.getTag(R.id.pending_intent_listener_tag) == this) {
                mView.setTag(R.id.pending_intent_listener_tag, null);
            }
            getUiOffloadThread().execute(() ->
                    mPendingIntent.unregisterCancelListener(mCancelListener));
        }
    }
}
