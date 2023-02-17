/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.view.WindowInsets.Type.systemBars;

import android.annotation.Nullable;
import android.graphics.Insets;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Default implementation of NotificationsInsetsController.
 */
@SysUISingleton
public class NotificationInsetsImpl extends NotificationInsetsController {

    @Inject
    public NotificationInsetsImpl() {

    }

    @Override
    public Pair<Integer, Integer> getinsets(@Nullable WindowInsets windowInsets,
            @Nullable DisplayCutout displayCutout) {
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(systemBars());
        int leftInset = 0;
        int rightInset = 0;

        if (displayCutout != null) {
            leftInset = displayCutout.getSafeInsetLeft();
            rightInset = displayCutout.getSafeInsetRight();
        }
        leftInset = Math.max(insets.left, leftInset);
        rightInset = Math.max(insets.right, rightInset);

        return new Pair(leftInset, rightInset);
    }
}
