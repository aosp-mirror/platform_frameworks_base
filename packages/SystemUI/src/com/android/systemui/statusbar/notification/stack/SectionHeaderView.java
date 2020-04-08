/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;

/**
 * Similar in size and appearance to the NotificationShelf, appears at the beginning of some
 * notification sections.
 */
public class SectionHeaderView extends StackScrollerDecorView {
    private ViewGroup mContents;
    private TextView mLabelView;

    public SectionHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContents = checkNotNull(findViewById(R.id.content));
        bindContents();
        setVisible(true, false);
    }

    private void bindContents() {
        mLabelView = checkNotNull(findViewById(R.id.header_label));
    }

    @Override
    protected View findContentView() {
        return mContents;
    }

    /**
     * Destroys and reinflates the visible contents of the section header. For use on configuration
     * changes or any other time that layout values might need to be re-evaluated.
     *
     * Does not reinflate the base content view itself ({@link #getContentView()} or any of the
     * decorator views, such as the background view or shadow view.
     */
    void reinflateContents() {
        mContents.removeAllViews();
        LayoutInflater.from(getContext()).inflate(
                R.layout.status_bar_notification_section_header_contents,
                mContents);
        bindContents();
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    public void setLabelText(String label) {
        mLabelView.setText(label);
    }
}
