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
 * limitations under the License.
 */


package com.android.internal.widget;

import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.Menu;
import android.view.Window;
import com.android.internal.view.menu.MenuPresenter;

/**
 * Implemented by the top-level decor layout for a window. DecorContentParent offers
 * entry points for a number of title/window decor features.
 */
public interface DecorContentParent {
    void setWindowCallback(Window.Callback cb);
    void setWindowTitle(CharSequence title);
    CharSequence getTitle();
    void initFeature(int windowFeature);
    void setUiOptions(int uiOptions);
    boolean hasIcon();
    boolean hasLogo();
    void setIcon(int resId);
    void setIcon(Drawable d);
    void setLogo(int resId);
    boolean canShowOverflowMenu();
    boolean isOverflowMenuShowing();
    boolean isOverflowMenuShowPending();
    boolean showOverflowMenu();
    boolean hideOverflowMenu();
    void setMenuPrepared();
    void setMenu(Menu menu, MenuPresenter.Callback cb);
    void saveToolbarHierarchyState(SparseArray<Parcelable> toolbarStates);
    void restoreToolbarHierarchyState(SparseArray<Parcelable> toolbarStates);
    void dismissPopups();
}
