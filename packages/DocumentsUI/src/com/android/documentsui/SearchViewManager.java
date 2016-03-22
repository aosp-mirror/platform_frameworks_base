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

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.Nullable;
import android.os.Bundle;
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
final class SearchViewManager implements
        SearchView.OnCloseListener, OnQueryTextListener, OnClickListener, OnFocusChangeListener {

    public interface SearchManagerListener {
        void onSearchChanged(@Nullable String query);
    }

    public static final String TAG = "SearchManger";

    private SearchManagerListener mListener;
    private boolean mSearchExpanded;
    private String mCurrentSearch;
    private boolean mIgnoreNextClose;

    private DocumentsToolbar mActionBar;
    private MenuItem mMenu;
    private SearchView mView;

    public SearchViewManager(SearchManagerListener listener, @Nullable Bundle savedState) {
        mListener = listener;
        mCurrentSearch = savedState != null ? savedState.getString(Shared.EXTRA_QUERY) : null;
    }

    public void setSearchMangerListener(SearchManagerListener listener) {
        mListener = listener;
    }

    public void install(DocumentsToolbar actionBar) {
        // assert(mActionBar == null);

        mActionBar = actionBar;
        mMenu = actionBar.getSearchMenu();
        mView = (SearchView) mMenu.getActionView();

        mView.setOnQueryTextListener(this);
        mView.setOnCloseListener(this);
        mView.setOnSearchClickListener(this);
        mView.setOnQueryTextFocusChangeListener(this);

        restoreSearch();
    }

    /**
     * @param root Info about the current directory.
     */
    void update(RootInfo root) {
        if (mMenu == null) {
            if (DEBUG) Log.d(TAG, "update called before Search MenuItem installed.");
            return;
        }

        if (mCurrentSearch != null) {
            mMenu.expandActionView();

            mView.setIconified(false);
            mView.clearFocus();
            mView.setQuery(mCurrentSearch, false);
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
            if (DEBUG) Log.d(TAG, "showMenu called before Search MenuItem installed.");
            return;
        }

        if (!visible) {
            mCurrentSearch = null;
        }

        mMenu.setVisible(visible);
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

    private void restoreSearch() {
        if (isSearching()) {
            onSearchExpanded();
            mView.setIconified(false);
            mView.setQuery(mCurrentSearch, false);
            mView.clearFocus();
        }
    }

    private void onSearchExpanded() {
        mSearchExpanded = true;
    }

    boolean isSearching() {
        return mCurrentSearch != null;
    }

    boolean isExpanded() {
        return mSearchExpanded;
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     * @param state Bundle to save state too
     */
    public void onSaveInstanceState(Bundle state) {
        state.putString(Shared.EXTRA_QUERY, mCurrentSearch);
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

        // Refresh the directory if a search was done
        if (mCurrentSearch != null) {
            mCurrentSearch = null;
            if (mListener != null) {
                mListener.onSearchChanged(mCurrentSearch);
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
        onSearchExpanded();
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mCurrentSearch = query;
        mView.clearFocus();
        if (mListener != null) {
            mListener.onSearchChanged(mCurrentSearch);
        }
        return true;
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!hasFocus) {
            if (mCurrentSearch == null) {
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

    String getCurrentSearch() {
        return mCurrentSearch;
    }

}
