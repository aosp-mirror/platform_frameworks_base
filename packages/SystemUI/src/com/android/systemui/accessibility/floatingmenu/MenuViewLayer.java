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

package com.android.systemui.accessibility.floatingmenu;

import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The basic interactions with the child view {@link MenuView}.
 */
@SuppressLint("ViewConstructor")
class MenuViewLayer extends FrameLayout {
    private final MenuView mMenuView;

    @IntDef({
            LayerIndex.MENU_VIEW
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface LayerIndex {
        int MENU_VIEW = 0;
    }

    MenuViewLayer(@NonNull Context context, WindowManager windowManager) {
        super(context);

        final MenuViewModel menuViewModel = new MenuViewModel(context);
        final MenuViewAppearance menuViewAppearance = new MenuViewAppearance(context,
                windowManager);
        mMenuView = new MenuView(context, menuViewModel, menuViewAppearance);

        addView(mMenuView, LayerIndex.MENU_VIEW);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mMenuView.show();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mMenuView.hide();
    }
}
