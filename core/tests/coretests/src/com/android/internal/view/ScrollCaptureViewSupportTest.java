/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.view;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

public class ScrollCaptureViewSupportTest {

    ScrollCaptureViewHelper<View> mViewHelper = new ScrollCaptureViewHelper<View>() {
        @Override
        public void onPrepareForStart(@NonNull View view, @NonNull Rect scrollBounds) {
        }

        @NonNull
        @Override
        public ScrollResult onScrollRequested(@NonNull View view, @NonNull Rect scrollBounds,
                @NonNull Rect requestRect) {
            return new ScrollResult();
        }

        @Override
        public void onPrepareForEnd(@NonNull View view) {
        }
    };


    /**
     * Test scroll bounds are computed correctly. onComputeScrollBounds is currently a
     * default interface method of ScrollCaptureViewHelper.
     */
    @Test
    public void testComputeScrollBounds() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();

        ViewGroup target = new ViewGroup(context) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                // n/a
            }
        };

        target.setPadding(25, 50, 25, 50);
        target.setLeftTopRightBottom(0, 0, 200, 200);


        // clipToPadding == false: No effect
        target.setClipToPadding(false);
        Rect scrollBounds = mViewHelper.onComputeScrollBounds(target);
        assertEquals("Computed scroll bounds are incorrect with clipToPadding=false",
                new Rect(0, 0, 200, 200), scrollBounds);

        // clipToPadding == true: Inset by padding
        target.setClipToPadding(true);
        scrollBounds = mViewHelper.onComputeScrollBounds(target);
        assertEquals("Computed scroll bounds are incorrect with clipToPadding=true",
                new Rect(25, 50, 175, 150), scrollBounds);
    }

}
