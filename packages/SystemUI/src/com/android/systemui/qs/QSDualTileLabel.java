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
import android.widget.FrameLayout;
import android.widget.TextView;

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
public class QSDualTileLabel extends FrameLayout {

    private static final String SPACING_TEXT = "  ";

    private final Context mContext;
    private final TextView mFirstLine;
    private final TextView mSecondLine;

    private String mText;

    public QSDualTileLabel(Context context) {
        super(context);
        mContext = context;
        mFirstLine = initTextView();
        mSecondLine = initTextView();
        mSecondLine.setEllipsize(TruncateAt.END);
        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((oldRight - oldLeft) != (right - left)) {
                    updateText();
                }
            }
        });
    }

    public void setFirstLineBackground(Drawable d) {
        mFirstLine.setBackground(d);
        if (d != null) {
            final LayoutParams lp = (LayoutParams) mSecondLine.getLayoutParams();
            lp.topMargin = d.getIntrinsicHeight() * 3 / 4;
            mSecondLine.setLayoutParams(lp);
        }
    }

    private TextView initTextView() {
        final TextView tv = new TextView(mContext);
        tv.setPadding(0, 0, 0, 0);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setBackground(null);
        final LayoutParams lp =
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(tv, lp);
        return tv;
    }

    public void setText(CharSequence text) {
        final String newText = text == null ? null : text.toString().trim();
        if (Objects.equals(newText, mText)) return;
        mText = newText;
        updateText();
    }

    public String getText() {
        return mText;
    }

    public void setTextSize(int unit, float size) {
        mFirstLine.setTextSize(unit, size);
        mSecondLine.setTextSize(unit, size);
    }

    public void setTextColor(int color) {
        mFirstLine.setTextColor(color);
        mSecondLine.setTextColor(color);
    }

    public void setTypeface(Typeface tf) {
        mFirstLine.setTypeface(tf);
        mSecondLine.setTypeface(tf);
    }

    private void updateText() {
        if (getWidth() == 0) return;
        if (TextUtils.isEmpty(mText)) {
            mFirstLine.setText(null);
            mSecondLine.setText(null);
            return;
        }
        final float maxWidth = getWidth() - mFirstLine.getBackground().getIntrinsicWidth()
                - getPaddingLeft() - getPaddingRight();
        float width = mFirstLine.getPaint().measureText(mText + SPACING_TEXT);
        if (width <= maxWidth) {
            mFirstLine.setText(mText + SPACING_TEXT);
            mSecondLine.setText(null);
            return;
        }
        final int n = mText.length();
        int lastWordBoundary = -1;
        boolean inWhitespace = false;
        int i = 0;
        for (i = 1; i < n; i++) {
            if (Character.isWhitespace(mText.charAt(i))) {
                if (!inWhitespace) {
                    lastWordBoundary = i;
                }
                inWhitespace = true;
            } else {
                inWhitespace = false;
            }
            width = mFirstLine.getPaint().measureText(mText.substring(0, i) + SPACING_TEXT);
            if (width > maxWidth) {
                break;
            }
        }
        if (lastWordBoundary == -1) {
            lastWordBoundary = i - 1;
        }
        mFirstLine.setText(mText.substring(0, lastWordBoundary) + SPACING_TEXT);
        mSecondLine.setText(mText.substring(lastWordBoundary).trim());
    }
}
