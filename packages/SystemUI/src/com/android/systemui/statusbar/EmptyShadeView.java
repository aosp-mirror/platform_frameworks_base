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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.annotation.ColorInt;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.StackScrollState;

public class EmptyShadeView extends StackScrollerDecorView {

    private TextView mEmptyText;
    private @StringRes int mText = R.string.empty_shade_text;

    public EmptyShadeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mEmptyText.setText(mText);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.no_notifications);
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    public void setTextColor(@ColorInt int color) {
        mEmptyText.setTextColor(color);
    }

    public void setText(@StringRes int text) {
        mText = text;
        mEmptyText.setText(mText);
    }

    public int getTextResource() {
        return mText;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEmptyText = (TextView) findContentView();
    }

    @Override
    public ExpandableViewState createNewViewState(StackScrollState stackScrollState) {
        return new EmptyShadeViewState();
    }

    public class EmptyShadeViewState extends ExpandableViewState {
        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof EmptyShadeView) {
                EmptyShadeView emptyShadeView = (EmptyShadeView) view;
                boolean visible = this.clipTopAmount <= mEmptyText.getPaddingTop() * 0.6f;
                emptyShadeView.performVisibilityAnimation(
                        visible && !emptyShadeView.willBeGone());
            }
        }
    }
}
