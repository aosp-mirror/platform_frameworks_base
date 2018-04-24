/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;

/**
 * A TextView that can float around an image on the end.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class ImageFloatingTextView extends TextView {

    /** Number of lines from the top to indent */
    private int mIndentLines;

    /** Resolved layout direction */
    private int mResolvedDirection = LAYOUT_DIRECTION_UNDEFINED;
    private int mMaxLinesForHeight = -1;
    private boolean mFirstMeasure = true;
    private int mLayoutMaxLines = -1;
    private boolean mBlockLayouts;
    private int mImageEndMargin;

    public ImageFloatingTextView(Context context) {
        this(context, null);
    }

    public ImageFloatingTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageFloatingTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ImageFloatingTextView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected Layout makeSingleLayout(int wantWidth, BoringLayout.Metrics boring, int ellipsisWidth,
            Layout.Alignment alignment, boolean shouldEllipsize,
            TextUtils.TruncateAt effectiveEllipsize, boolean useSaved) {
        TransformationMethod transformationMethod = getTransformationMethod();
        CharSequence text = getText();
        if (transformationMethod != null) {
            text = transformationMethod.getTransformation(text, this);
        }
        text = text == null ? "" : text;
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(),
                getPaint(), wantWidth)
                .setAlignment(alignment)
                .setTextDirection(getTextDirectionHeuristic())
                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                .setIncludePad(getIncludeFontPadding())
                .setUseLineSpacingFromFallbacks(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL);
        int maxLines;
        if (mMaxLinesForHeight > 0) {
            maxLines = mMaxLinesForHeight;
        } else {
            maxLines = getMaxLines() >= 0 ? getMaxLines() : Integer.MAX_VALUE;
        }
        builder.setMaxLines(maxLines);
        mLayoutMaxLines = maxLines;
        if (shouldEllipsize) {
            builder.setEllipsize(effectiveEllipsize)
                    .setEllipsizedWidth(ellipsisWidth);
        }

        // we set the endmargin on the requested number of lines.
        int[] margins = null;
        if (mIndentLines > 0) {
            margins = new int[mIndentLines + 1];
            for (int i = 0; i < mIndentLines; i++) {
                margins[i] = mImageEndMargin;
            }
        }
        if (mResolvedDirection == LAYOUT_DIRECTION_RTL) {
            builder.setIndents(margins, null);
        } else {
            builder.setIndents(null, margins);
        }

        return builder.build();
    }

    @RemotableViewMethod
    public void setImageEndMargin(int imageEndMargin) {
        mImageEndMargin = imageEndMargin;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        // Lets calculate how many lines the given measurement allows us.
        int availableHeight = height - mPaddingTop - mPaddingBottom;
        int maxLines = availableHeight / getLineHeight();
        maxLines = Math.max(1, maxLines);
        if (getMaxLines() > 0) {
            maxLines = Math.min(getMaxLines(), maxLines);
        }
        if (maxLines != mMaxLinesForHeight) {
            mMaxLinesForHeight = maxLines;
            if (getLayout() != null && mMaxLinesForHeight != mLayoutMaxLines) {
                // Invalidate layout.
                mBlockLayouts = true;
                setHint(getHint());
                mBlockLayouts = false;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void requestLayout() {
        if (!mBlockLayouts) {
            super.requestLayout();
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        if (layoutDirection != mResolvedDirection && isLayoutDirectionResolved()) {
            mResolvedDirection = layoutDirection;
            if (mIndentLines > 0) {
                // Invalidate layout.
                setHint(getHint());
            }
        }
    }

    @RemotableViewMethod
    public void setHasImage(boolean hasImage) {
        setNumIndentLines(hasImage ? 2 : 0);
    }

    /**
     * @param lines the number of lines at the top that should be indented by indentEnd
     * @return whether a change was made
     */
    public boolean setNumIndentLines(int lines) {
        if (mIndentLines != lines) {
            mIndentLines = lines;
            // Invalidate layout.
            setHint(getHint());
            return true;
        }
        return false;
    }
}
