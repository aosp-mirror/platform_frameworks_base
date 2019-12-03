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
 * limitations under the License.
 */
package com.android.systemui.bubbles;

import android.content.Context;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.systemui.R;

/**
 * Factory for creating normalized bubble icons.
 * We are not using Launcher's IconFactory because bubbles only runs on the UI thread,
 * so there is no need to manage a pool across multiple threads.
 */
public class BubbleIconFactory extends BaseIconFactory {
    protected BubbleIconFactory(Context context) {
        super(context, context.getResources().getConfiguration().densityDpi,
                context.getResources().getDimensionPixelSize(R.dimen.individual_bubble_size));
    }

    public int getBadgeSize() {
        return mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.icons.R.dimen.profile_badge_size);
    }
}
