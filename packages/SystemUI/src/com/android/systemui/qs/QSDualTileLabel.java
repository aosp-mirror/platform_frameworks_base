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

package com.android.systemui.qs;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Objects;

/**
 * Text displayed over one or two lines, centered horizontally.  A caret is always drawn at the end
 * of the first line, and considered part of the content for centering purposes.
 *
 * Text overflow rules:
 *   First line: break on a word, unless a single word takes up the entire line - in which case
 *               truncate.
 *   Second line: ellipsis if necessary
 */
public class QSDualTileLabel extends LinearLayout {

    private final Context mContext;
    private final TextView mFirstLine;
    private final ImageView mFirstLineCaret;
    private final TextView mSecondLine;
    private final int mHorizontalPaddingPx;

    private String mText;

    public QSDualTileLabel(Context context) {
        super(context);
        mContext = context;
        setOrientation(LinearLayout.VERTICAL);

        mHorizontalPaddingPx = mContext.getResources()
                .getDimensionPixelSize(R.dimen.qs_dual_tile_padding_horizontal);

        mFirstLine = initTextView();
        mFirstLine.setPadding(mHorizontalPaddingPx, 0, mHorizontalPaddingPx, 0);
        final LinearLayout firstLineLayout = new LinearLayout(mContext);
        firstLineLayout.setPadding(0, 0, 0, 0);
        firstLineLayout.setOrientation(LinearLayout.HORIZONTAL);
        firstLineLayout.setClickable(false);
        firstLineLayout.setBackground(null);
        firstLineLayout.addView(mFirstLine);
        mFirstLineCaret = new ImageView(mContext);
        mFirstLineCaret.setScaleType(ImageView.ScaleType.MATRIX);
        mFirstLineCaret.setClickable(false);
        firstLineLayout.addView(mFirstLineCaret);
        addView(firstLineLayout, newLinearLayoutParams());

        mSecondLine = initTextView();
        mSecondLine.setPadding(mHorizontalPaddingPx, 0, mHorizontalPaddingPx, 0);
        mSecondLine.setEllipsize(TruncateAt.END);
        mSecondLine.setVisibility(GONE);
        addView(mSecondLine, newLinearLayoutParams());

        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((oldRight - oldLeft) != (right - left)) {
                    rescheduleUpdateText();
                }
            }
        });
    }

    private static LayoutParams newLinearLayoutParams() {
        final LayoutParams lp =
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    public void setFirstLineCaret(Drawable d) {
        mFirstLineCaret.setImageDrawable(d);
        if (d != null) {
            final int h = d.getIntrinsicHeight();
            final LayoutParams lp = (LayoutParams) mSecondLine.getLayoutParams();
            lp.topMargin = h * 4 / 5;
            mSecondLine.setLayoutParams(lp);
            mFirstLine.setMinHeight(h);
            mFirstLine.setPadding(mHorizontalPaddingPx, 0, 0, 0);
        }
    }

    private TextView initTextView() {
        final TextView tv = new TextView(mContext);
        tv.setPadding(0, 0, 0, 0);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setBackground(null);
        return tv;
    }

    public void setText(CharSequence text) {
        final String newText = text == null ? null : text.toString().trim();
        if (Objects.equals(newText, mText)) return;
        mText = newText;
        rescheduleUpdateText();
    }

    public String getText() {
        return mText;
    }

    public void setTextSize(int unit, float size) {
        mFirstLine.setTextSize(unit, size);
        mSecondLine.setTextSize(unit, size);
        rescheduleUpdateText();
    }

    public void setTextColor(int color) {
        mFirstLine.setTextColor(color);
        mSecondLine.setTextColor(color);
        rescheduleUpdateText();
    }

    public void setTypeface(Typeface tf) {
        mFirstLine.setTypeface(tf);
        mSecondLine.setTypeface(tf);
        rescheduleUpdateText();
    }

    private void rescheduleUpdateText() {
        removeCallbacks(mUpdateText);
        post(mUpdateText);
    }

    private void updateText() {
        if (getWidth() == 0) return;
        if (TextUtils.isEmpty(mText)) {
            mFirstLine.setText(null);
            mSecondLine.setText(null);
            mSecondLine.setVisibility(GONE);
            return;
        }
        final float maxWidth = getWidth() - mFirstLineCaret.getWidth() - mHorizontalPaddingPx
                - getPaddingLeft() - getPaddingRight();
        float width = mFirstLine.getPaint().measureText(mText);
        if (width <= maxWidth) {
            mFirstLine.setText(mText);
            mSecondLine.setText(null);
            mSecondLine.setVisibility(GONE);
            return;
        }
        final int n = mText.length();
        int lastWordBoundary = -1;
        boolean inWhitespace = false;
        int i = 0;
        for (i = 1; i < n; i++) {
            width = mFirstLine.getPaint().measureText(mText.substring(0, i));
            final boolean done = width > maxWidth;
            if (Character.isWhitespace(mText.charAt(i))) {
                if (!inWhitespace && !done) {
                    lastWordBoundary = i;
                }
                inWhitespace = true;
            } else {
                inWhitespace = false;
            }
            if (done) {
                break;
            }
        }
        if (lastWordBoundary == -1) {
            lastWordBoundary = i - 1;
        }
        mFirstLine.setText(mText.substring(0, lastWordBoundary));
        mSecondLine.setText(mText.substring(lastWordBoundary).trim());
        mSecondLine.setVisibility(VISIBLE);
    }

    private final Runnable mUpdateText = new Runnable() {
        @Override
        public void run() {
            updateText();
        }
    };
}
