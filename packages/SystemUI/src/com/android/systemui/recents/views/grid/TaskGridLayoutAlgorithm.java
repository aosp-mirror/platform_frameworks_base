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
import android.content.res.Resources;
import android.graphics.Rect;
import com.android.systemui.R;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewTransform;

public class TaskGridLayoutAlgorithm  {

    private int mPaddingLeftRight;
    private int mPaddingTopBottom;
    private int mPaddingTaskView;

    private Rect mDisplayRect;
    private Rect mWindowRect;

    private Rect mTaskGridRect;

    public TaskGridLayoutAlgorithm(Context context) {
        reloadOnConfigurationChange(context);
    }

    public void reloadOnConfigurationChange(Context context) {
        Resources res = context.getResources();
        mPaddingLeftRight = res.getDimensionPixelSize(R.dimen.recents_grid_padding_left_right);
        mPaddingTopBottom = res.getDimensionPixelSize(R.dimen.recents_grid_padding_top_bottom);
        mPaddingTaskView = res.getDimensionPixelSize(R.dimen.recents_grid_padding_task_view);

        mTaskGridRect = new Rect();
    }

    public TaskViewTransform getTransform(int taskIndex, int taskAmount,
        TaskViewTransform transformOut, TaskStackLayoutAlgorithm stackLayout) {

        int taskPerLine = taskAmount < 2 ? 1 : (
            taskAmount < 5 ? 2 : 3);

        int taskWidth = (mDisplayRect.width() - mPaddingLeftRight * 2
            - mPaddingTaskView * (taskPerLine - 1)) / taskPerLine;
        int taskHeight = taskWidth * mDisplayRect.height() / mDisplayRect.width();
        mTaskGridRect.set(0, 0, taskWidth, taskHeight);

        int xIndex = taskIndex % taskPerLine;
        int yIndex = taskIndex / taskPerLine;
        int x = mPaddingLeftRight + (taskWidth + mPaddingTaskView) * xIndex;
        int y = mPaddingTopBottom + (taskHeight + mPaddingTaskView) * yIndex;
        float z = stackLayout.mMaxTranslationZ;

        float dimAlpha = 0f;
        float viewOutlineAlpha = 0f;

        // Fill out the transform
        transformOut.scale = 1f;
        transformOut.alpha = 1f;
        transformOut.translationZ = z;
        transformOut.dimAlpha = dimAlpha;
        transformOut.viewOutlineAlpha = viewOutlineAlpha;
        transformOut.rect.set(mTaskGridRect);
        transformOut.rect.offset(x, y);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        transformOut.visible = true;
        return transformOut;
    }

    public void initialize(Rect displayRect, Rect windowRect) {
        mDisplayRect = displayRect;
        mWindowRect = windowRect;
    }
}