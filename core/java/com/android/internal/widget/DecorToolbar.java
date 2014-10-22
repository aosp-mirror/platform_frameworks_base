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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.SpinnerAdapter;
import com.android.internal.view.menu.MenuPresenter;

/**
 * Common interface for a toolbar that sits as part of the window decor.
 * Layouts that control window decor use this as a point of interaction with different
 * bar implementations.
 *
 * @hide
 */
public interface DecorToolbar {
    ViewGroup getViewGroup();
    Context getContext();
    boolean isSplit();
    boolean hasExpandedActionView();
    void collapseActionView();
    void setWindowCallback(Window.Callback cb);
    void setWindowTitle(CharSequence title);
    CharSequence getTitle();
    void setTitle(CharSequence title);
    CharSequence getSubtitle();
    void setSubtitle(CharSequence subtitle);
    void initProgress();
    void initIndeterminateProgress();
    boolean canSplit();
    void setSplitView(ViewGroup splitView);
    void setSplitToolbar(boolean split);
    void setSplitWhenNarrow(boolean splitWhenNarrow);
    boolean hasIcon();
    boolean hasLogo();
    void setIcon(int resId);
    void setIcon(Drawable d);
    void setLogo(int resId);
    void setLogo(Drawable d);
    boolean canShowOverflowMenu();
    boolean isOverflowMenuShowing();
    boolean isOverflowMenuShowPending();
    boolean showOverflowMenu();
    boolean hideOverflowMenu();
    void setMenuPrepared();
    void setMenu(Menu menu, MenuPresenter.Callback cb);
    void dismissPopupMenus();

    int getDisplayOptions();
    void setDisplayOptions(int opts);
    void setEmbeddedTabView(ScrollingTabContainerView tabView);
    boolean hasEmbeddedTabs();
    boolean isTitleTruncated();
    void setCollapsible(boolean collapsible);
    void setHomeButtonEnabled(boolean enable);
    int getNavigationMode();
    void setNavigationMode(int mode);
    void setDropdownParams(SpinnerAdapter adapter, AdapterView.OnItemSelectedListener listener);
    void setDropdownSelectedPosition(int position);
    int getDropdownSelectedPosition();
    int getDropdownItemCount();
    void setCustomView(View view);
    View getCustomView();
    void animateToVisibility(int visibility);
    void setNavigationIcon(Drawable icon);
    void setNavigationIcon(int resId);
    void setNavigationContentDescription(CharSequence description);
    void setNavigationContentDescription(int resId);
    void setDefaultNavigationContentDescription(int defaultNavigationContentDescription);
    void setDefaultNavigationIcon(Drawable icon);
    void saveHierarchyState(SparseArray<Parcelable> toolbarStates);
    void restoreHierarchyState(SparseArray<Parcelable> toolbarStates);
}
