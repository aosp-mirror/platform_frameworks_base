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


package com.android.internal.app;

import android.annotation.Nullable;
import android.app.ActionBar;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SpinnerAdapter;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.Map;

public class ToolbarActionBar extends ActionBar {
    private Toolbar mToolbar;
    private View mCustomView;

    private int mDisplayOptions;

    private int mNavResId;
    private int mIconResId;
    private int mLogoResId;
    private Drawable mNavDrawable;
    private Drawable mIconDrawable;
    private Drawable mLogoDrawable;
    private int mTitleResId;
    private int mSubtitleResId;
    private CharSequence mTitle;
    private CharSequence mSubtitle;

    private boolean mLastMenuVisibility;
    private ArrayList<OnMenuVisibilityListener> mMenuVisibilityListeners =
            new ArrayList<OnMenuVisibilityListener>();

    public ToolbarActionBar(Toolbar toolbar) {
        mToolbar = toolbar;
    }

    @Override
    public void setCustomView(View view) {
        setCustomView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void setCustomView(View view, LayoutParams layoutParams) {
        if (mCustomView != null) {
            mToolbar.removeView(mCustomView);
        }
        mCustomView = view;
        if (view != null) {
            mToolbar.addView(view, generateLayoutParams(layoutParams));
        }
    }

    private Toolbar.LayoutParams generateLayoutParams(LayoutParams lp) {
        final Toolbar.LayoutParams result = new Toolbar.LayoutParams(lp);
        result.gravity = lp.gravity;
        return result;
    }

    @Override
    public void setCustomView(int resId) {
        final LayoutInflater inflater = LayoutInflater.from(mToolbar.getContext());
        setCustomView(inflater.inflate(resId, mToolbar, false));
    }

    @Override
    public void setIcon(int resId) {
        mIconResId = resId;
        mIconDrawable = null;
        updateToolbarLogo();
    }

    @Override
    public void setIcon(Drawable icon) {
        mIconResId = 0;
        mIconDrawable = icon;
        updateToolbarLogo();
    }

    @Override
    public void setLogo(int resId) {
        mLogoResId = resId;
        mLogoDrawable = null;
        updateToolbarLogo();
    }

    @Override
    public void setLogo(Drawable logo) {
        mLogoResId = 0;
        mLogoDrawable = logo;
        updateToolbarLogo();
    }

    private void updateToolbarLogo() {
        Drawable drawable = null;
        if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_HOME) != 0) {
            final int resId;
            if ((mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) != 0) {
                resId = mLogoResId;
                drawable = mLogoDrawable;
            } else {
                resId = mIconResId;
                drawable = mIconDrawable;
            }
            if (resId != 0) {
                drawable = mToolbar.getContext().getDrawable(resId);
            }
        }
        mToolbar.setLogo(drawable);
    }

    @Override
    public void setStackedBackgroundDrawable(Drawable d) {
        // This space for rent (do nothing)
    }

    @Override
    public void setSplitBackgroundDrawable(Drawable d) {
        // This space for rent (do nothing)
    }

    @Override
    public void setHomeButtonEnabled(boolean enabled) {
        // If the nav button on a Toolbar is present, it's enabled. No-op.
    }

    @Override
    public Context getThemedContext() {
        return mToolbar.getContext();
    }

    @Override
    public boolean isTitleTruncated() {
        return super.isTitleTruncated();
    }

    @Override
    public void setHomeAsUpIndicator(Drawable indicator) {
        mToolbar.setNavigationIcon(indicator);
    }

    @Override
    public void setHomeAsUpIndicator(int resId) {
        mToolbar.setNavigationIcon(resId);
    }

    @Override
    public void setHomeActionContentDescription(CharSequence description) {
        mToolbar.setNavigationDescription(description);
    }

    @Override
    public void setDefaultDisplayHomeAsUpEnabled(boolean enabled) {
        // Do nothing
    }

    @Override
    public void setHomeActionContentDescription(int resId) {
        mToolbar.setNavigationDescription(resId);
    }

    @Override
    public void setShowHideAnimationEnabled(boolean enabled) {
        // This space for rent; no-op.
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return mToolbar.startActionMode(callback);
    }

    @Override
    public void setListNavigationCallbacks(SpinnerAdapter adapter, OnNavigationListener callback) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void setSelectedNavigationItem(int position) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public int getSelectedNavigationIndex() {
        return -1;
    }

    @Override
    public int getNavigationItemCount() {
        return 0;
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitle = title;
        mTitleResId = 0;
        updateToolbarTitle();
    }

    @Override
    public void setTitle(int resId) {
        mTitleResId = resId;
        mTitle = null;
        updateToolbarTitle();
    }

    @Override
    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        mSubtitleResId = 0;
        updateToolbarTitle();
    }

    @Override
    public void setSubtitle(int resId) {
        mSubtitleResId = resId;
        mSubtitle = null;
        updateToolbarTitle();
    }

    private void updateToolbarTitle() {
        final Context context = mToolbar.getContext();
        CharSequence title = null;
        CharSequence subtitle = null;
        if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
            title = mTitleResId != 0 ? context.getText(mTitleResId) : mTitle;
            subtitle = mSubtitleResId != 0 ? context.getText(mSubtitleResId) : mSubtitle;
        }
        mToolbar.setTitle(title);
        mToolbar.setSubtitle(subtitle);
    }

    @Override
    public void setDisplayOptions(@DisplayOptions int options) {
        setDisplayOptions(options, 0xffffffff);
    }

    @Override
    public void setDisplayOptions(@DisplayOptions int options, @DisplayOptions int mask) {
        final int oldOptions = mDisplayOptions;
        mDisplayOptions = (options & mask) | (mDisplayOptions & ~mask);
        final int optionsChanged = oldOptions ^ mDisplayOptions;
    }

    @Override
    public void setDisplayUseLogoEnabled(boolean useLogo) {
        setDisplayOptions(useLogo ? DISPLAY_USE_LOGO : 0, DISPLAY_USE_LOGO);
    }

    @Override
    public void setDisplayShowHomeEnabled(boolean showHome) {
        setDisplayOptions(showHome ? DISPLAY_SHOW_HOME : 0, DISPLAY_SHOW_HOME);
    }

    @Override
    public void setDisplayHomeAsUpEnabled(boolean showHomeAsUp) {
        setDisplayOptions(showHomeAsUp ? DISPLAY_HOME_AS_UP : 0, DISPLAY_HOME_AS_UP);
    }

    @Override
    public void setDisplayShowTitleEnabled(boolean showTitle) {
        setDisplayOptions(showTitle ? DISPLAY_SHOW_TITLE : 0, DISPLAY_SHOW_TITLE);
    }

    @Override
    public void setDisplayShowCustomEnabled(boolean showCustom) {
        setDisplayOptions(showCustom ? DISPLAY_SHOW_CUSTOM : 0, DISPLAY_SHOW_CUSTOM);
    }

    @Override
    public void setBackgroundDrawable(@Nullable Drawable d) {
        mToolbar.setBackground(d);
    }

    @Override
    public View getCustomView() {
        return mCustomView;
    }

    @Override
    public CharSequence getTitle() {
        return mToolbar.getTitle();
    }

    @Override
    public CharSequence getSubtitle() {
        return mToolbar.getSubtitle();
    }

    @Override
    public int getNavigationMode() {
        return NAVIGATION_MODE_STANDARD;
    }

    @Override
    public void setNavigationMode(@NavigationMode int mode) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public int getDisplayOptions() {
        return mDisplayOptions;
    }

    @Override
    public Tab newTab() {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void addTab(Tab tab) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void addTab(Tab tab, boolean setSelected) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void addTab(Tab tab, int position) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void addTab(Tab tab, int position, boolean setSelected) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void removeTab(Tab tab) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void removeTabAt(int position) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void removeAllTabs() {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public void selectTab(Tab tab) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public Tab getSelectedTab() {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public Tab getTabAt(int index) {
        throw new UnsupportedOperationException(
                "Navigation modes are not supported in toolbar action bars");
    }

    @Override
    public int getTabCount() {
        return 0;
    }

    @Override
    public int getHeight() {
        return mToolbar.getHeight();
    }

    @Override
    public void show() {
        // TODO: Consider a better transition for this.
        // Right now use no automatic transition so that the app can supply one if desired.
        mToolbar.setVisibility(View.VISIBLE);
    }

    @Override
    public void hide() {
        // TODO: Consider a better transition for this.
        // Right now use no automatic transition so that the app can supply one if desired.
        mToolbar.setVisibility(View.GONE);
    }

    @Override
    public boolean isShowing() {
        return mToolbar.getVisibility() == View.VISIBLE;
    }

    public void addOnMenuVisibilityListener(OnMenuVisibilityListener listener) {
        mMenuVisibilityListeners.add(listener);
    }

    public void removeOnMenuVisibilityListener(OnMenuVisibilityListener listener) {
        mMenuVisibilityListeners.remove(listener);
    }

    public void dispatchMenuVisibilityChanged(boolean isVisible) {
        if (isVisible == mLastMenuVisibility) {
            return;
        }
        mLastMenuVisibility = isVisible;

        final int count = mMenuVisibilityListeners.size();
        for (int i = 0; i < count; i++) {
            mMenuVisibilityListeners.get(i).onMenuVisibilityChanged(isVisible);
        }
    }
}
