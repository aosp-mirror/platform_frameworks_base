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
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.StackScrollState;

public class DismissView extends StackScrollerDecorView {
    private final int mClearAllTopPadding;
    private DismissViewButton mDismissButton;

    public DismissView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mClearAllTopPadding = context.getResources().getDimensionPixelSize(
                R.dimen.clear_all_padding_top);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.dismiss_text);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDismissButton = (DismissViewButton) findContentView();
    }

    public void setTextColor(@ColorInt int color) {
        mDismissButton.setTextColor(color);
    }

    public void setOnButtonClickListener(OnClickListener listener) {
        mContent.setOnClickListener(listener);
    }

    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDismissButton.setText(R.string.clear_all_notifications_text);
        mDismissButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
    }

    public boolean isButtonVisible() {
        return mDismissButton.getAlpha() != 0.0f;
    }

    @Override
    public ExpandableViewState createNewViewState(StackScrollState stackScrollState) {
        return new DismissViewState();
    }

    public class DismissViewState extends ExpandableViewState {
        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof DismissView) {
                DismissView dismissView = (DismissView) view;
                boolean visible = this.clipTopAmount < mClearAllTopPadding;
                dismissView.performVisibilityAnimation(visible && !dismissView.willBeGone());
            }
        }
    }
}
