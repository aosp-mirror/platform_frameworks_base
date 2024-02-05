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

package com.android.internal.widget;

import static android.app.Flags.evenlyDividedCallStyleActionLayout;
import static android.app.Notification.CallStyle.DEBUG_NEW_ACTION_LAYOUT;
import static android.text.style.DynamicDrawableSpan.ALIGN_CENTER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.ImageSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.RemotableViewMethod;
import android.widget.Button;
import android.widget.RemoteViews;

import com.android.internal.R;

/**
 * A button implementation for the emphasized notification style.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class EmphasizedNotificationButton extends Button {
    private final RippleDrawable mRipple;
    private final GradientDrawable mBackground;
    private boolean mPriority;

    private int mInitialDrawablePadding;
    private int mIconSize;

    private Drawable mIconToGlue;
    private CharSequence mLabelToGlue;
    private int mGluedLayoutDirection = LAYOUT_DIRECTION_UNDEFINED;
    private boolean mGluePending;

    public EmphasizedNotificationButton(Context context) {
        this(context, null);
    }

    public EmphasizedNotificationButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EmphasizedNotificationButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public EmphasizedNotificationButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mRipple = (RippleDrawable) getBackground();
        mRipple.mutate();
        DrawableWrapper inset = (DrawableWrapper) mRipple.getDrawable(0);
        mBackground = (GradientDrawable) inset.getDrawable();

        mIconSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.notification_actions_icon_drawable_size);

        try (TypedArray typedArray = context.obtainStyledAttributes(
                attrs, android.R.styleable.TextView, defStyleAttr, defStyleRes)) {
            mInitialDrawablePadding = typedArray.getDimensionPixelSize(
                    android.R.styleable.TextView_drawablePadding, 0);
        }

        if (DEBUG_NEW_ACTION_LAYOUT) {
            Log.v(TAG, "iconSize = " + mIconSize + "px, "
                    + "initialDrawablePadding = " + mInitialDrawablePadding + "px");
        }
    }

    @RemotableViewMethod
    public void setRippleColor(ColorStateList color) {
        mRipple.setColor(color);
        invalidate();
    }

    @RemotableViewMethod
    public void setButtonBackground(ColorStateList color) {
        mBackground.setColor(color);
        invalidate();
    }

    /**
     * Sets an image icon which will have its size constrained and will be set to the same color as
     * the text. Must be called after {@link #setTextColor(int)} for the latter to work.
     */
    @RemotableViewMethod(asyncImpl = "setImageIconAsync")
    public void setImageIcon(@Nullable Icon icon) {
        final Drawable drawable = icon == null ? null : icon.loadDrawable(mContext);
        setImageDrawable(drawable);
    }

    /**
     * @hide
     */
    @RemotableViewMethod
    public Runnable setImageIconAsync(@Nullable Icon icon) {
        final Drawable drawable = icon == null ? null : icon.loadDrawable(mContext);
        return () -> setImageDrawable(drawable);
    }

    private void setImageDrawable(@Nullable Drawable drawable) {
        if (drawable != null) {
            prepareIcon(drawable);
        }
        setCompoundDrawablesRelative(drawable, null, null, null);
    }

    /**
     * Sets an icon to be 'glued' to the label when this button is displayed, so the icon will stay
     * with the text if the button is wider than needed and the text isn't start-aligned.
     *
     * As with {@link #setImageIcon(Icon)}, the Icon will have its size constrained and will be set
     * to the same color as the text, and this must be called after {@link #setTextColor(int)} for
     * the latter to work.
     *
     * This must be called along with {@link #glueLabel(CharSequence)}, in any order, before the
     * button is displayed.
     */
    @RemotableViewMethod(asyncImpl = "glueIconAsync")
    public void glueIcon(@Nullable Icon icon) {
        final Drawable drawable = icon == null ? null : icon.loadDrawable(mContext);
        setIconToGlue(drawable);
    }

    /**
     * @hide
     */
    @RemotableViewMethod
    public Runnable glueIconAsync(@Nullable Icon icon) {
        final Drawable drawable = icon == null ? null : icon.loadDrawable(mContext);
        return () -> setIconToGlue(drawable);
    }

    private void setIconToGlue(@Nullable Drawable icon) {
        if (!evenlyDividedCallStyleActionLayout()) {
            Log.e(TAG, "glueIcon: new action layout disabled; doing nothing");
            return;
        }

        prepareIcon(icon);

        mIconToGlue = icon;
        mGluePending = true;

        glueIconAndLabelIfNeeded();
    }

    private void prepareIcon(@NonNull Drawable drawable) {
        drawable.mutate();
        drawable.setTintList(getTextColors());
        drawable.setTintBlendMode(BlendMode.SRC_IN);
        drawable.setBounds(0, 0, mIconSize, mIconSize);
    }

    /**
     * Sets a label to be 'glued' to the icon when this button is displayed, so the icon will stay
     * with the text if the button is wider than needed and the text isn't start-aligned.
     *
     * This must be called along with {@link #glueIcon(Icon)}, in any order, before the button is
     * displayed.
     */
    @RemotableViewMethod(asyncImpl = "glueLabelAsync")
    public void glueLabel(@Nullable CharSequence label) {
        setLabelToGlue(label);
    }

    /**
     * @hide
     */
    @RemotableViewMethod
    public Runnable glueLabelAsync(@Nullable CharSequence label) {
        return () -> setLabelToGlue(label);
    }

    private void setLabelToGlue(@Nullable CharSequence label) {
        if (!evenlyDividedCallStyleActionLayout()) {
            Log.e(TAG, "glueLabel: new action layout disabled; doing nothing");
            return;
        }

        mLabelToGlue = label;
        mGluePending = true;

        glueIconAndLabelIfNeeded();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        if (DEBUG_NEW_ACTION_LAYOUT) {
            Log.v(TAG, "onRtlPropertiesChanged: layoutDirection = " + layoutDirection + ", "
                    + "gluedLayoutDirection = " + mGluedLayoutDirection);
        }

        if (layoutDirection != mGluedLayoutDirection) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.d(TAG, "onRtlPropertiesChanged: layout direction changed; regluing");
            }
            mGluePending = true;
        }

        glueIconAndLabelIfNeeded();
    }

    private void glueIconAndLabelIfNeeded() {
        // Don't need to glue:

        if (!mGluePending) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "glueIconAndLabelIfNeeded: glue not pending; doing nothing");
            }
            return;
        }

        if (mIconToGlue == null && mLabelToGlue == null) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "glueIconAndLabelIfNeeded: no icon or label to glue; doing nothing");
            }
            mGluePending = false;
            return;
        }

        if (!evenlyDividedCallStyleActionLayout()) {
            Log.e(TAG, "glueIconAndLabelIfNeeded: new action layout disabled; doing nothing");
            return;
        }

        // Not ready to glue yet:

        if (!isLayoutDirectionResolved()) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "glueIconAndLabelIfNeeded: "
                        + "layout direction not resolved; doing nothing");
            }
            return;
        }

        // Ready to glue but don't have an icon *and* a label:
        //
        // (Note that this will *not* happen while the button is being initialized, since we won't
        // be ready to glue. This can only happen if the button is initialized and displayed and
        // *then* someone calls glueIcon or glueLabel.

        if (mIconToGlue == null) {
            Log.w(TAG, "glueIconAndLabelIfNeeded: label glued without icon; doing nothing");
            return;
        }

        if (mLabelToGlue == null) {
            Log.w(TAG, "glueIconAndLabelIfNeeded: icon glued without label; doing nothing");
            return;
        }

        // Can't glue:

        final int layoutDirection = getLayoutDirection();
        if (layoutDirection != LAYOUT_DIRECTION_LTR && layoutDirection != LAYOUT_DIRECTION_RTL) {
            Log.e(TAG, "glueIconAndLabelIfNeeded: "
                    + "resolved layout direction neither LTR nor RTL; "
                    + "doing nothing");
            return;
        }

        // No excuses left, let's glue it!

        glueIconAndLabel(layoutDirection);

        mGluePending = false;
        mGluedLayoutDirection = layoutDirection;
    }

    // Unicode replacement character
    private static final String IMAGE_SPAN_TEXT = "\ufffd";

    // Unicode no-break space
    private static final String SPACER_SPAN_TEXT = "\u00a0";

    private static final String LEFT_TO_RIGHT_ISOLATE = "\u2066";
    private static final String RIGHT_TO_LEFT_ISOLATE = "\u2067";
    private static final String FIRST_STRONG_ISOLATE = "\u2068";
    private static final String POP_DIRECTIONAL_ISOLATE = "\u2069";

    private void glueIconAndLabel(int layoutDirection) {
        final boolean rtlLayout = layoutDirection == LAYOUT_DIRECTION_RTL;

        if (DEBUG_NEW_ACTION_LAYOUT) {
            Log.d(TAG, "glueIconAndLabel: "
                    + "icon = " + mIconToGlue + ", "
                    + "iconSize = " + mIconSize + "px, "
                    + "initialDrawablePadding = " + mInitialDrawablePadding + "px, "
                    + "labelToGlue.length = " + mLabelToGlue.length() + ", "
                    + "rtlLayout = " + rtlLayout);
        }

        logIfTextDirectionNotFirstStrong();

        final SpannableStringBuilder builder = new SpannableStringBuilder();

        // The text direction of the label might not match the layout direction of the button, so
        // wrap the entire string in a LEFT-TO-RIGHT ISOLATE or RIGHT-TO-LEFT ISOLATE to match the
        // layout direction. This puts the icon, padding, and label in the right order.
        builder.append(rtlLayout ? RIGHT_TO_LEFT_ISOLATE : LEFT_TO_RIGHT_ISOLATE);

        appendSpan(builder, IMAGE_SPAN_TEXT, new ImageSpan(mIconToGlue, ALIGN_CENTER));
        appendSpan(builder, SPACER_SPAN_TEXT, new SpacerSpan(mInitialDrawablePadding));

        // If the text and layout directions are different, we would end up with the *label* in the
        // wrong direction, so wrap the label in a FIRST STRONG ISOLATE. This triggers the same
        // automatic text direction heuristic that Android uses by default.
        builder.append(FIRST_STRONG_ISOLATE);

        appendSpan(builder, mLabelToGlue, new CenterBesideImageSpan(mIconSize));

        builder.append(POP_DIRECTIONAL_ISOLATE);
        builder.append(POP_DIRECTIONAL_ISOLATE);

        setText(builder);
    }

    private void logIfTextDirectionNotFirstStrong() {
        if (!isTextDirectionResolved()) {
            Log.e(TAG, "glueIconAndLabel: text direction not resolved; "
                    + "letting View assume FIRST STRONG");
        }
        final int textDirection = getTextDirection();
        if (textDirection != TEXT_DIRECTION_FIRST_STRONG) {
            Log.w(TAG, "glueIconAndLabel: "
                    + "expected text direction TEXT_DIRECTION_FIRST_STRONG "
                    + "but found " + textDirection + "; "
                    + "will use a FIRST STRONG ISOLATE regardless");
        }
    }

    private void appendSpan(SpannableStringBuilder builder, CharSequence text, Object span) {
        final int spanStart = builder.length();
        builder.append(text);
        final int spanEnd = builder.length();
        builder.setSpan(span, spanStart, spanEnd, 0);
    }

    /**
     * Sets whether this view is a priority over its peers (which affects width).
     * Specifically, this is used by {@link NotificationActionListLayout} to give this view width
     * priority ahead of user-defined buttons when allocating horizontal space.
     */
    @RemotableViewMethod
    public void setIsPriority(boolean priority) {
        mPriority = priority;
    }

    /**
     * Sizing this button is a priority compared with its peers.
     */
    public boolean isPriority() {
        return mPriority;
    }

    private static class SpacerSpan extends ReplacementSpan {
        private int mWidth;

        SpacerSpan(int width) {
            mWidth = width;

            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.d(TAG, "width = " + mWidth + "px");
            }
        }


        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fontMetrics) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "getSize returning " + mWidth + "px");
            }

            return mWidth;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "drawing nothing");
            }

            // Draw nothing, it's a spacer.
        }

        private static final String TAG = "SpacerSpan";
    }

    private static class CenterBesideImageSpan extends MetricAffectingSpan {
        private int mImageHeight;

        private boolean mMeasured;
        private int mBaselineShiftOffset;

        CenterBesideImageSpan(int imageHeight) {
            mImageHeight = imageHeight;

            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.d(TAG, "imageHeight = " + mImageHeight + "px");
            }
        }

        @Override
        public void updateMeasureState(@NonNull TextPaint textPaint) {
            final int textHeight = (int) -textPaint.ascent();

            /*
             * We only need to shift the text *up* if the text is shorter than the image; ImageSpan
             * with ALIGN_CENTER will shift the *image* up if the text is taller than the image.
             */
            if (textHeight < mImageHeight) {
                mBaselineShiftOffset = -(mImageHeight - textHeight) / 2;
            } else {
                mBaselineShiftOffset = 0;
            }

            mMeasured = true;

            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.d(TAG, "updateMeasureState: "
                        + "imageHeight = " + mImageHeight + "px, "
                        + "textHeight = " + textHeight + "px, "
                        + "baselineShiftOffset = " + mBaselineShiftOffset + "px");
            }

            textPaint.baselineShift += mBaselineShiftOffset;
        }

        @Override
        public void updateDrawState(TextPaint textPaint) {
            if (textPaint == null) {
                Log.e(TAG, "updateDrawState: textPaint is null; doing nothing");
                return;
            }

            if (!mMeasured) {
                Log.e(TAG, "updateDrawState: called without measure; doing nothing");
                return;
            }

            if (DEBUG_NEW_ACTION_LAYOUT) {
                Log.v(TAG, "updateDrawState: "
                        + "baselineShiftOffset = " + mBaselineShiftOffset + "px");
            }

            textPaint.baselineShift += mBaselineShiftOffset;
        }

        private static final String TAG = "CenterBesideImageSpan";
    }

    private static final String TAG = "EmphasizedNotificationButton";
}
