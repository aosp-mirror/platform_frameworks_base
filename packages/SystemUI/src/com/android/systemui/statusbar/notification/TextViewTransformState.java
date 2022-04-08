/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Pools;
import android.view.View;
import android.widget.TextView;

/**
 * A transform state of a mText view.
*/
public class TextViewTransformState extends TransformState {

    private static Pools.SimplePool<TextViewTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private TextView mText;

    @Override
    public void initFrom(View view, TransformInfo transformInfo) {
        super.initFrom(view, transformInfo);
        mText = (TextView) view;
    }

    @Override
    protected boolean sameAs(TransformState otherState) {
        if (super.sameAs(otherState)) {
            return true;
        }
        if (otherState instanceof TextViewTransformState) {
            TextViewTransformState otherTvs = (TextViewTransformState) otherState;
            if(TextUtils.equals(otherTvs.mText.getText(), mText.getText())) {
                int ownEllipsized = getEllipsisCount();
                int otherEllipsized = otherTvs.getEllipsisCount();
                return ownEllipsized == otherEllipsized
                        && mText.getLineCount() == otherTvs.mText.getLineCount()
                        && hasSameSpans(otherTvs);
            }
        }
        return false;
    }

    private boolean hasSameSpans(TextViewTransformState otherTvs) {
        boolean hasSpans = mText instanceof Spanned;
        boolean otherHasSpans = otherTvs.mText instanceof Spanned;
        if (hasSpans != otherHasSpans) {
            return false;
        } else if (!hasSpans) {
            return true;
        }
        // Actually both have spans, let's try to compare them
        Spanned ownSpanned = (Spanned) mText;
        Object[] spans = ownSpanned.getSpans(0, ownSpanned.length(), Object.class);
        Spanned otherSpanned = (Spanned) otherTvs.mText;
        Object[] otherSpans = otherSpanned.getSpans(0, otherSpanned.length(), Object.class);
        if (spans.length != otherSpans.length) {
            return false;
        }
        for (int i = 0; i < spans.length; i++) {
            Object span = spans[i];
            Object otherSpan = otherSpans[i];
            if (!span.getClass().equals(otherSpan.getClass())) {
                return false;
            }
            if (ownSpanned.getSpanStart(span) != otherSpanned.getSpanStart(otherSpan)
                    || ownSpanned.getSpanEnd(span) != otherSpanned.getSpanEnd(otherSpan)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean transformScale(TransformState otherState) {
        if (!(otherState instanceof TextViewTransformState)) {
            return false;
        }
        TextViewTransformState otherTvs = (TextViewTransformState) otherState;
        if (!TextUtils.equals(mText.getText(), otherTvs.mText.getText())) {
            return false;
        }
        int lineCount = mText.getLineCount();
        return lineCount == 1 && lineCount == otherTvs.mText.getLineCount()
                && getEllipsisCount() == otherTvs.getEllipsisCount()
                && getViewHeight() != otherTvs.getViewHeight();
    }

    @Override
    protected int getViewWidth() {
        Layout l = mText.getLayout();
        if (l != null) {
            return (int) l.getLineWidth(0);
        }
        return super.getViewWidth();
    }

    @Override
    protected int getViewHeight() {
        return mText.getLineHeight();
    }

    private int getInnerHeight(TextView text) {
        return text.getHeight() - text.getPaddingTop() - text.getPaddingBottom();
    }

    private int getEllipsisCount() {
        Layout l = mText.getLayout();
        if (l != null) {
            int lines = l.getLineCount();
            if (lines > 0) {
                // we only care about the first line
                return l.getEllipsisCount(0);
            }
        }
        return 0;
    }

    public static TextViewTransformState obtain() {
        TextViewTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new TextViewTransformState();
    }

    @Override
    public void recycle() {
        super.recycle();
        sInstancePool.release(this);
    }

    @Override
    protected void reset() {
        super.reset();
        mText = null;
    }
}
