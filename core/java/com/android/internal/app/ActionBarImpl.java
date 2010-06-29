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

import com.android.internal.view.menu.ActionMenu;
import com.android.internal.view.menu.ActionMenuItem;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.ActionBarView;

import android.app.ActionBar;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SpinnerAdapter;
import android.widget.ViewAnimator;

/**
 * ActionBarImpl is the ActionBar implementation used
 * by devices of all screen sizes. If it detects a compatible decor,
 * it will split contextual modes across both the ActionBarView at
 * the top of the screen and a horizontal LinearLayout at the bottom
 * which is normally hidden.
 */
public class ActionBarImpl extends ActionBar {
    private static final int NORMAL_VIEW = 0;
    private static final int CONTEXT_VIEW = 1;
    
    private ViewAnimator mAnimatorView;
    private ActionBarView mActionView;
    private ActionBarContextView mUpperContextView;
    private LinearLayout mLowerContextView;
    
    private ContextMode mContextMode;
    
    private static final int CONTEXT_DISPLAY_NORMAL = 0;
    private static final int CONTEXT_DISPLAY_SPLIT = 1;
    
    private int mContextDisplayMode;
    
    public ActionBarImpl(View decor) {
        mActionView = (ActionBarView) decor.findViewById(com.android.internal.R.id.action_bar);
        mUpperContextView = (ActionBarContextView) decor.findViewById(
                com.android.internal.R.id.action_context_bar);
        mLowerContextView = (LinearLayout) decor.findViewById(
                com.android.internal.R.id.lower_action_context_bar);
        mAnimatorView = (ViewAnimator) decor.findViewById(
                com.android.internal.R.id.action_bar_animator);
        
        if (mActionView == null || mUpperContextView == null || mAnimatorView == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with a compatible window decor layout");
        }
        
        mContextDisplayMode = mLowerContextView == null ?
                CONTEXT_DISPLAY_NORMAL : CONTEXT_DISPLAY_SPLIT;
    }

    public void setCustomNavigationMode(View view) {
        mActionView.setCustomNavigationView(view);
        mActionView.setCallback(null);
    }
    
    public void setDropdownNavigationMode(SpinnerAdapter adapter, NavigationCallback callback) {
        mActionView.setCallback(callback);
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
        mActionView.setCallback(null);
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

    @Override
    public void startContextMode(ContextModeCallback callback) {
        if (mContextMode != null) {
            mContextMode.finish();
        }
        mContextMode = new ContextMode(callback);
        if (callback.onCreateContextMode(mContextMode, mContextMode.getMenu())) {
            mContextMode.invalidate();
            mUpperContextView.initForMode(mContextMode);
            mAnimatorView.setDisplayedChild(CONTEXT_VIEW);
            if (mLowerContextView != null) {
                // TODO animate this
                mLowerContextView.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void finishContextMode() {
        if (mContextMode != null) {
            mContextMode.finish();
        }
    }

    /**
     * @hide 
     */
    public class ContextMode extends ActionBar.ContextMode {
        private ContextModeCallback mCallback;
        private ActionMenu mMenu;
        
        public ContextMode(ContextModeCallback callback) {
            mCallback = callback;
            mMenu = new ActionMenu(mActionView.getContext());
        }
        
        @Override
        public Menu getMenu() {
            return mMenu;
        }

        @Override
        public void finish() {
            mCallback.onDestroyContextMode(this);
            mUpperContextView.closeMode();
            if (mLowerContextView != null) {
                mLowerContextView.removeAllViews();
            }
            mAnimatorView.setDisplayedChild(NORMAL_VIEW);
            if (mLowerContextView != null && mLowerContextView.getVisibility() != View.GONE) {
                // TODO Animate this
                mLowerContextView.setVisibility(View.GONE);
            }
            mContextMode = null;
        }

        @Override
        public void invalidate() {
            if (mCallback.onPrepareContextMode(this, mMenu)) {
                // Refresh content in both context views
            }
        }

        @Override
        public void setCustomView(View view) {
            mUpperContextView.setCustomView(view);
        }

        @Override
        public void setSubtitle(CharSequence subtitle) {
            mUpperContextView.setSubtitle(subtitle);
        }

        @Override
        public void setTitle(CharSequence title) {
            mUpperContextView.setTitle(title);
        }
        
        public void dispatchOnContextItemClicked(MenuItem item) {
            ActionMenuItem actionItem = (ActionMenuItem) item;
            if (!actionItem.invoke()) {
                mCallback.onContextItemClicked(this, item);
            }
        }
    }
}
