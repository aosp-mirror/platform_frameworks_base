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

package com.android.settingslib.collapsingtoolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.CollapsingToolbarLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * A customized version of CollapsingToolbarLayout that can apply different font size based on
 * the line count of its title.
 */
public class AdjustableToolbarLayout extends CollapsingToolbarLayout {

    private static final int TOOLBAR_MAX_LINE_NUMBER = 2;

    public AdjustableToolbarLayout(@NonNull Context context) {
        this(context, null);

    }

    public AdjustableToolbarLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AdjustableToolbarLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initCollapsingToolbar();
    }

    @SuppressWarnings("RestrictTo")
    private void initCollapsingToolbar() {
        this.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.removeOnLayoutChangeListener(this);
                final int count = getLineCountWithReflection();
                if (count > TOOLBAR_MAX_LINE_NUMBER) {
                    final ViewGroup.LayoutParams lp = getLayoutParams();
                    lp.height = getResources()
                            .getDimensionPixelSize(R.dimen.toolbar_three_lines_height);
                    setLayoutParams(lp);
                } else if (count == TOOLBAR_MAX_LINE_NUMBER) {
                    final ViewGroup.LayoutParams lp = getLayoutParams();
                    lp.height = getResources()
                            .getDimensionPixelSize(R.dimen.toolbar_two_lines_height);
                    setLayoutParams(lp);
                }
            }
        });
    }

    /**
     * Returns a number of title line count for CollapsingToolbarLayout so that facilitates the
     * determination to apply what kind of font size. Since the title of CollapsingToolbarLayout is
     * drawn in a canvas and the text process is wrapped in a CollapsingTextHelper, the way we used
     * here is to get the line count from the CollapsingTextHelper via Java Reflection.
     */
    private int getLineCountWithReflection() {
        try {
            final Field textHelperField =
                    this.getClass().getSuperclass().getDeclaredField("collapsingTextHelper");
            textHelperField.setAccessible(true);
            final Object textHelperObj = textHelperField.get(this);

            final Field layoutField = textHelperObj.getClass().getDeclaredField("textLayout");
            layoutField.setAccessible(true);
            final Object layoutObj = layoutField.get(textHelperObj);

            final Method method = layoutObj.getClass().getDeclaredMethod("getLineCount");
            return (int) method.invoke(layoutObj);
        } catch (Exception e) {
            return 0;
        }
    }
}
