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
 * limitations under the License.
 */

package com.android.systemui.recents.views.grid;

import android.content.Context;
import android.util.AttributeSet;
import com.android.systemui.R;
import com.android.systemui.shared.recents.view.AnimateableViewBounds;
import com.android.systemui.recents.views.TaskView;

public class GridTaskView extends TaskView {

    /** The height, in pixels, of the header view. */
    private int mHeaderHeight;

    public GridTaskView(Context context) {
        this(context, null);
    }

    public GridTaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GridTaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GridTaskView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHeaderHeight = context.getResources().getDimensionPixelSize(
                R.dimen.recents_grid_task_view_header_height);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // Show the full thumbnail and don't overlap with the header.
        mThumbnailView.setSizeToFit(true);
        mThumbnailView.setOverlayHeaderOnThumbnailActionBar(false);
        mThumbnailView.updateThumbnailMatrix();
        mThumbnailView.setTranslationY(mHeaderHeight);
        mHeaderView.setShouldDarkenBackgroundColor(true);
    }

    @Override
    protected AnimateableViewBounds createOutlineProvider() {
        return new AnimateableGridViewBounds(this, mContext.getResources().getDimensionPixelSize(
            R.dimen.recents_task_view_shadow_rounded_corners_radius));
    }

    @Override
    protected void onConfigurationChanged() {
        super.onConfigurationChanged();
        mHeaderHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_grid_task_view_header_height);
        mThumbnailView.setTranslationY(mHeaderHeight);
    }
}
