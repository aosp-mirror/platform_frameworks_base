/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.ActionBar;
import android.graphics.drawable.Drawable;
import android.view.ActionBarView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SpinnerAdapter;

/**
 * SplitActionBar is the ActionBar implementation used
 * by small-screen devices. It expects to split contextual
 * modes across both the ActionBarView at the top of the screen
 * and a horizontal LinearLayout at the bottom which is normally
 * hidden.
 */
public class SplitActionBar extends ActionBar {
    private ActionBarView mActionView;
    private LinearLayout mContextView;
    
    public SplitActionBar(ActionBarView view, LinearLayout contextView) {
        mActionView = view;
        mContextView = contextView;
    }
    
    public void setCallback(Callback callback) {
        mActionView.setCallback(callback);
    }

    public void setCustomNavigationMode(View view) {
        mActionView.setCustomNavigationView(view);
    }
    
    public void setDropdownNavigationMode(SpinnerAdapter adapter) {
        mActionView.setNavigationMode(NAVIGATION_MODE_DROPDOWN_LIST);
        mActionView.setDropdownAdapter(adapter);
    }
    
    public void setStandardNavigationMode(CharSequence title) {
        setStandardNavigationMode(title, null);
    }
    
    public void setStandardNavigationMode(CharSequence title, CharSequence subtitle) {
        mActionView.setNavigationMode(NAVIGATION_MODE_STANDARD);
        mActionView.setTitle(title);
        mActionView.setSubtitle(subtitle);
    }

    public void setTitle(CharSequence title) {
        mActionView.setTitle(title);
    }

    public void setSubtitle(CharSequence subtitle) {
        mActionView.setSubtitle(subtitle);
    }

    public void setDisplayOptions(int options) {
        mActionView.setDisplayOptions(options);
    }
    
    public void setDisplayOptions(int options, int mask) {
        final int current = mActionView.getDisplayOptions(); 
        mActionView.setDisplayOptions((options & mask) | (current & ~mask));
    }

    public void setBackgroundDrawable(Drawable d) {
        mActionView.setBackgroundDrawable(d);
    }

    public View getCustomNavigationView() {
        return mActionView.getCustomNavigationView();
    }

    public CharSequence getTitle() {
        return mActionView.getTitle();
    }

    public CharSequence getSubtitle() {
        return mActionView.getSubtitle();
    }

    public int getNavigationMode() {
        return mActionView.getNavigationMode();
    }

    public int getDisplayOptions() {
        return mActionView.getDisplayOptions();
    }
}
