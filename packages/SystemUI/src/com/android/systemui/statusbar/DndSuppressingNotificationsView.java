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

package com.android.systemui.statusbar;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.IntegerRes;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.StackScrollState;

public class DndSuppressingNotificationsView extends StackScrollerDecorView {

    private TextView mText;
    private @StringRes int mTextId = R.string.dnd_suppressing_shade_text;

    public DndSuppressingNotificationsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mText.setText(mTextId);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.hidden_container);
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    public void setColor(@ColorInt int color) {
        mText.setTextColor(color);
    }

    public void setOnContentClickListener(OnClickListener listener) {
        mText.setOnClickListener(listener);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mText = findViewById(R.id.hidden_notifications);
    }

    @Override
    public ExpandableViewState createNewViewState(StackScrollState stackScrollState) {
        return new DndSuppressingViewState();
    }

    public class DndSuppressingViewState extends ExpandableViewState {
        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof DndSuppressingNotificationsView) {
                DndSuppressingNotificationsView dndView = (DndSuppressingNotificationsView) view;
                boolean visible = this.clipTopAmount <= mText.getPaddingTop() * 0.6f;
                dndView.performVisibilityAnimation(visible && !dndView.willBeGone());
            }
        }
    }
}
