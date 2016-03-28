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
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnActionExpandListener;
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
        SearchView.OnCloseListener, OnQueryTextListener, OnClickListener, OnFocusChangeListener,
        OnActionExpandListener {

    public interface SearchManagerListener {
        void onSearchChanged(@Nullable String query);
        void onSearchFinished();
    }

    public static final String TAG = "SearchManger";

    private SearchManagerListener mListener;
    private boolean mSearchExpanded;
    private String mCurrentSearch;
    private boolean mIgnoreNextClose;
    private boolean mFullBar;

    private DocumentsToolbar mActionBar;
    private MenuItem mMenuItem;
    private SearchView mSearchView;

    public SearchViewManager(SearchManagerListener listener, @Nullable Bundle savedState) {
        mListener = listener;
        mCurrentSearch = savedState != null ? savedState.getString(Shared.EXTRA_QUERY) : null;
    }

    public void setSearchMangerListener(SearchManagerListener listener) {
        mListener = listener;
    }

    public void install(DocumentsToolbar actionBar, boolean isFullBarSearch) {
        mActionBar = actionBar;
        mMenuItem = actionBar.getSearchMenu();
        mSearchView = (SearchView) mMenuItem.getActionView();

        mSearchView.setOnQueryTextListener(this);
        mSearchView.setOnCloseListener(this);
        mSearchView.setOnSearchClickListener(this);
        mSearchView.setOnQueryTextFocusChangeListener(this);

        mFullBar = isFullBarSearch;
        if (mFullBar) {
            mMenuItem.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
                    | MenuItem.SHOW_AS_ACTION_ALWAYS);
            mMenuItem.setOnActionExpandListener(this);
        }

        restoreSearch();
    }

    /**
     * Used to hide menu icons, when the search is being restored. Needed because search restoration
     * is done before onPrepareOptionsMenu(Menu menu) that is overriding the icons visibility.
     */
    public void updateMenu() {
        if (isSearching() && mFullBar) {
            Menu menu = mActionBar.getMenu();
            menu.setGroupVisible(R.id.group_hide_when_searching, false);
        }
    }

    /**
     * @param root Info about the current directory.
     */
    void update(RootInfo root) {
        if (mMenuItem == null) {
            if (DEBUG) Log.d(TAG, "update called before Search MenuItem installed.");
            return;
        }

        if (mCurrentSearch != null) {
            mMenuItem.expandActionView();

            mSearchView.setIconified(false);
            mSearchView.clearFocus();
            mSearchView.setQuery(mCurrentSearch, false);
        } else {
            mSearchView.clearFocus();
            if (!mSearchView.isIconified()) {
                mIgnoreNextClose = true;
                mSearchView.setIconified(true);
            }

            if (mMenuItem.isActionViewExpanded()) {
                mMenuItem.collapseActionView();
            }
        }

        showMenu(root != null
                && ((root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0));
    }

    void showMenu(boolean visible) {
        if (mMenuItem == null) {
            if (DEBUG) Log.d(TAG, "showMenu called before Search MenuItem installed.");
            return;
        }

        if (!visible) {
            mCurrentSearch = null;
        }

        mMenuItem.setVisible(visible);
    }

    /**
     * Cancels current search operation. Triggers clearing and collapsing the SearchView.
     *
     * @return True if it cancels search. False if it does not operate search currently.
     */
    boolean cancelSearch() {
        if (isExpanded() || isSearching()) {
            // If the query string is not empty search view won't get iconified
            mSearchView.setQuery("", false);

            if (mFullBar) {
               onClose();
            } else {
                // Causes calling onClose(). onClose() is triggering directory content update.
                mSearchView.setIconified(true);
            }
            return true;
        }
        return false;
    }

    /**
     * Sets search view into the searching state. Used to restore state after device orientation
     * change.
     */
    private void restoreSearch() {
        if (isSearching()) {
            if(mFullBar) {
                mMenuItem.expandActionView();
            } else {
                mSearchView.setIconified(false);
            }
            onSearchExpanded();
            mSearchView.setQuery(mCurrentSearch, false);
            mSearchView.clearFocus();
        }
    }

    private void onSearchExpanded() {
        mSearchExpanded = true;
        if(mFullBar) {
            Menu menu = mActionBar.getMenu();
            menu.setGroupVisible(R.id.group_hide_when_searching, false);
        }
    }

    /**
     * Clears the search. Triggers refreshing of the directory content.
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

        if(mFullBar) {
            mMenuItem.collapseActionView();
        }
        mListener.onSearchFinished();

        return false;
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     * @param state Bundle to save state too
     */
    public void onSaveInstanceState(Bundle state) {
        state.putString(Shared.EXTRA_QUERY, mCurrentSearch);
    }

    /**
     * Sets mSearchExpanded. Called when search icon is clicked to start search for both search view
     * modes.
     */
    @Override
    public void onClick(View v) {
        onSearchExpanded();
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mCurrentSearch = query;
        mSearchView.clearFocus();
        if (mListener != null) {
            mListener.onSearchChanged(mCurrentSearch);
        }
        return true;
    }

    /**
     * Used to detect and handle back button pressed event when search is expanded.
     */
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!hasFocus) {
            if (mCurrentSearch == null) {
                mSearchView.setIconified(true);
            } else if (TextUtils.isEmpty(mSearchView.getQuery())) {
                cancelSearch();
            }
        }
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        return false;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        Menu menu = mActionBar.getMenu();
        menu.setGroupVisible(R.id.group_hide_when_searching, true);

        // Handles case when search view is collapsed by using the arrow on the left of the bar
        if (isExpanded() || isSearching()) {
            cancelSearch();
            return false;
        }
        return true;
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        return true;
    }

    String getCurrentSearch() {
        return mCurrentSearch;
    }

    boolean isSearching() {
        return mCurrentSearch != null;
    }

    boolean isExpanded() {
        return mSearchExpanded;
    }

}
