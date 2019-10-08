/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * Custom view for the NotificationInfo confirmation views so that the confirmation text can
 * occupy the full width of the notification and push the undo button down to the next line if
 * necessary.
 *
 * @see NotificationInfo
 */
public class NotificationUndoLayout extends FrameLayout {
    /**
     * View for the prompt/confirmation text to tell the user the previous action was successful.
     */
    private View mConfirmationTextView;
    /** Undo button (actionable text) view. */
    private View mUndoView;

    /**
     * Whether {@link #mConfirmationTextView} is multiline and will require the full width of the
     * parent (which causes the {@link #mUndoView} to push down).
     */
    private boolean mIsMultiline = false;
    private int mMultilineTopMargin;

    public NotificationUndoLayout(Context context) {
        this(context, null);
    }

    public NotificationUndoLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationUndoLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mConfirmationTextView = findViewById(R.id.confirmation_text);
        mUndoView = findViewById(R.id.undo);

        mMultilineTopMargin = getResources().getDimensionPixelOffset(
                com.android.internal.R.dimen.notification_content_margin_start);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        LayoutParams confirmationLayoutParams =
                (LayoutParams) mConfirmationTextView.getLayoutParams();
        LayoutParams undoLayoutParams =(LayoutParams) mUndoView.getLayoutParams();

        int measuredWidth = getMeasuredWidth();
        // Ignore the left margin on the undo button - no need for additional extra space between
        // the text and the button.
        int requiredWidth = mConfirmationTextView.getMeasuredWidth()
                + confirmationLayoutParams.rightMargin
                + confirmationLayoutParams.leftMargin
                + mUndoView.getMeasuredWidth()
                + undoLayoutParams.rightMargin;
        // If the measured width isn't enough to accommodate both the undo button and the text in
        // the same line, we'll need to adjust the view to be multi-line. Otherwise, we're done.
        if (requiredWidth > measuredWidth) {
            mIsMultiline = true;

            // Update height requirement to the text height and the button's height (along with
            // additional spacing for the top of the text).
            int updatedHeight = mMultilineTopMargin
                    + mConfirmationTextView.getMeasuredHeight()
                    + mUndoView.getMeasuredHeight()
                    + undoLayoutParams.topMargin
                    + undoLayoutParams.bottomMargin;

            setMeasuredDimension(measuredWidth, updatedHeight);
        } else {
            mIsMultiline = false;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // If the text view and undo view don't fit on the same line, we'll need to manually lay
        // out the content.
        if (mIsMultiline) {
            // Re-align parent right/bottom values. Left and top are considered to be 0.
            int parentBottom = getMeasuredHeight();
            int parentRight = getMeasuredWidth();

            LayoutParams confirmationLayoutParams =
                    (LayoutParams) mConfirmationTextView.getLayoutParams();
            LayoutParams undoLayoutParams = (LayoutParams) mUndoView.getLayoutParams();

            // The confirmation text occupies the full width as computed earlier. Both side margins
            // are equivalent, so we only need to grab the left one here.
            mConfirmationTextView.layout(
                    confirmationLayoutParams.leftMargin,
                    mMultilineTopMargin,
                    confirmationLayoutParams.leftMargin + mConfirmationTextView.getMeasuredWidth(),
                    mMultilineTopMargin + mConfirmationTextView.getMeasuredHeight());

            // The undo button is aligned bottom|end with the parent in the case of multiline text.
            int undoViewLeft = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? undoLayoutParams.rightMargin
                    : parentRight - mUndoView.getMeasuredWidth() - undoLayoutParams.rightMargin;
            mUndoView.layout(
                    undoViewLeft,
                    parentBottom - mUndoView.getMeasuredHeight() - undoLayoutParams.bottomMargin,
                    undoViewLeft + mUndoView.getMeasuredWidth(),
                    parentBottom - undoLayoutParams.bottomMargin);
        } else {
            super.onLayout(changed, left, top, right, bottom);
        }
    }
}
