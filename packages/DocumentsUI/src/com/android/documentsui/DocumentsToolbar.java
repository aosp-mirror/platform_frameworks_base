/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MenuItem;
import android.widget.Toolbar;

/**
 * ToolBar of Documents UI.
 */
public class DocumentsToolbar extends Toolbar {
    interface OnActionViewCollapsedListener {
        void onActionViewCollapsed();
    }

    private OnActionViewCollapsedListener mOnActionViewCollapsedListener;

    public DocumentsToolbar(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DocumentsToolbar(Context context, AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DocumentsToolbar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DocumentsToolbar(Context context) {
        super(context);
    }

    @Override
    public void collapseActionView() {
        super.collapseActionView();
        if (mOnActionViewCollapsedListener != null) {
            mOnActionViewCollapsedListener.onActionViewCollapsed();
        }
    }

    /**
     * Adds a listener that is invoked after collapsing the action view.
     * @param listener
     */
    public void setOnActionViewCollapsedListener(
            OnActionViewCollapsedListener listener) {
        mOnActionViewCollapsedListener = listener;
    }

    public MenuItem getSearchMenu() {
        return getMenu().findItem(R.id.menu_search);
    }
}
