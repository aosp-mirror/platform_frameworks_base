/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.provider.DocumentsContract.Root;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;

import com.android.documentsui.model.RootInfo;

/**
 * Manages searching UI behavior.
 */
final class SearchManager implements
        SearchView.OnCloseListener, OnQueryTextListener, OnClickListener, OnFocusChangeListener {

    public interface SearchManagerListener {
        void onSearchChanged();

        void onSearchQueryChanged(String query);
    }

    public static final String TAG = "SearchManger";

    private SearchManagerListener mListener;
    private String currentSearch;
    private boolean mSearchExpanded;
    private boolean mIgnoreNextClose;

    private DocumentsToolBar mActionBar;
    private MenuItem mMenu;
    private SearchView mView;

    public SearchManager(SearchManagerListener listener) {
        mListener = listener;
    }

    public void setSearchMangerListener(SearchManagerListener listener) {
        mListener = listener;
    }

    public void install(DocumentsToolBar actionBar) {
        assert (mActionBar == null);
        mActionBar = actionBar;
        mMenu = actionBar.getSearchMenu();
        mView = (SearchView) mMenu.getActionView();

        mView.setOnQueryTextListener(this);
        mView.setOnCloseListener(this);
        mView.setOnSearchClickListener(this);
        mView.setOnQueryTextFocusChangeListener(this);
    }

    /**
     * @param root Info about the current directory.
     */
    void update(RootInfo root) {
        if (mMenu == null) {
            Log.d(TAG, "update called before Search MenuItem installed.");
            return;
        }

        if (currentSearch != null) {
            mMenu.expandActionView();

            mView.setIconified(false);
            mView.clearFocus();
            mView.setQuery(currentSearch, false);
        } else {
            mView.clearFocus();
            if (!mView.isIconified()) {
                mIgnoreNextClose = true;
                mView.setIconified(true);
            }

            if (mMenu.isActionViewExpanded()) {
                mMenu.collapseActionView();
            }
        }

        showMenu(root != null
                && ((root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0));
    }

    void showMenu(boolean visible) {
        if (mMenu == null) {
            Log.d(TAG, "showMenu called before Search MenuItem installed.");
            return;
        }

        mMenu.setVisible(visible);
        if (!visible) {
            currentSearch = null;
            if (mListener != null) {
                mListener.onSearchQueryChanged(currentSearch);
            }
        }
    }

    /**
     * Cancels current search operation. Triggers clearing and collapsing the SearchView.
     *
     * @return True if it cancels search. False if it does not operate search currently.
     */
    boolean cancelSearch() {
        if (isExpanded() || isSearching()) {
            // If the query string is not empty search view won't get iconified
            mView.setQuery("", false);
            // Causes calling onClose(). onClose() is triggering directory content update.
            mView.setIconified(true);
            return true;
        }
        return false;
    }

    boolean isSearching() {
        return currentSearch != null;
    }

    boolean isExpanded() {
        return mSearchExpanded;
    }

    /**
     * Clears the search. Clears the SearchView background color. Triggers refreshing of the
     * directory content.
     * @return True if the default behavior of clearing/dismissing SearchView should be overridden.
     *         False otherwise.
     */
    @Override
    public boolean onClose() {
        mSearchExpanded = false;
        if (mIgnoreNextClose) {
            mIgnoreNextClose = false;
            return false;
        }

        mView.setBackgroundColor(
                mView.getResources().getColor(android.R.color.transparent, null));

        // Refresh the directory if a search was done
        if (currentSearch != null) {
            currentSearch = null;
            if (mListener != null) {
                mListener.onSearchQueryChanged(currentSearch);
                mListener.onSearchChanged();
            }
        }
        return false;
    }

    /**
     * Sets mSearchExpanded. Called when search icon is clicked to start search. Used to detect when
     * the view expanded instead of onMenuItemActionExpand, because SearchView has showAsAction set
     * to always and onMenuItemAction* methods are not called.
     */
    @Override
    public void onClick(View v) {
        mSearchExpanded = true;
        mView.setBackgroundColor(
                mView.getResources().getColor(R.color.menu_search_background, null));
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        currentSearch = query;
        mView.clearFocus();
        if (mListener != null) {
            mListener.onSearchQueryChanged(currentSearch);
            mListener.onSearchChanged();
        }
        return true;
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!hasFocus) {
            if (currentSearch == null) {
                mView.setIconified(true);
            } else if (TextUtils.isEmpty(mView.getQuery())) {
                cancelSearch();
            }
        }
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        return false;
    }
}
