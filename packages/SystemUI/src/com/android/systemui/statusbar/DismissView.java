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

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;

public class DismissView extends StackScrollerDecorView {
    private boolean mDismissAllInProgress;
    private DismissViewButton mDismissButton;

    public DismissView(Context context, AttributeSet attrs) {
        super(context, attrs);
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

    public void setOnButtonClickListener(OnClickListener listener) {
        mContent.setOnClickListener(listener);
    }

    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    public void showClearButton() {
        mDismissButton.showButton();
    }

    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        if (dismissAllInProgress) {
            setClipBounds(null);
        }
        mDismissAllInProgress = dismissAllInProgress;
    }

    @Override
    public void setClipBounds(Rect clipBounds) {
        if (mDismissAllInProgress) {
            // we don't want any clipping to happen!
            return;
        }
        super.setClipBounds(clipBounds);
    }

    public boolean isButtonVisible() {
        return mDismissButton.isButtonStatic();
    }
}
