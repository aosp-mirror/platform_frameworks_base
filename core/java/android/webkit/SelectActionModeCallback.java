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

package android.webkit;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Browser;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

class SelectActionModeCallback implements ActionMode.Callback {
    private WebView mWebView;
    private ActionMode mActionMode;

    void setWebView(WebView webView) {
        mWebView = webView;
    }

    void finish() {
        // It is possible that onCreateActionMode was never called, in the case
        // where there is no ActionBar, for example.
        if (mActionMode != null) {
            mActionMode.finish();
        }
    }

    // ActionMode.Callback implementation

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(com.android.internal.R.menu.webview_copy, menu);

        final Context context = mWebView.getContext();
        boolean allowText = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowActionMenuItemTextWithIcon);
        mode.setTitle(allowText ?
                context.getString(com.android.internal.R.string.textSelectionCABTitle) : null);

        if (!mode.isUiFocusable()) {
            // If the action mode UI we're running in isn't capable of taking window focus
            // the user won't be able to type into the find on page UI. Disable this functionality.
            // (Note that this should only happen in floating dialog windows.)
            // This can be removed once we can handle multiple focusable windows at a time
            // in a better way.
            final MenuItem findOnPageItem = menu.findItem(com.android.internal.R.id.find);
            if (findOnPageItem != null) {
                findOnPageItem.setVisible(false);
            }
        }
        mActionMode = mode;
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.copy:
                mWebView.copySelection();
                mode.finish();
                break;

            case com.android.internal.R.id.share:
                String selection = mWebView.getSelection();
                Browser.sendString(mWebView.getContext(), selection);
                mode.finish();
                break;

            case com.android.internal.R.id.select_all:
                mWebView.selectAll();
                break;

            case com.android.internal.R.id.find:
                String sel= mWebView.getSelection();
                mode.finish();
                mWebView.showFindDialog(sel, false);
                break;
            case com.android.internal.R.id.websearch:
                mode.finish();
                Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
                i.putExtra(SearchManager.EXTRA_NEW_SEARCH, true);
                i.putExtra(SearchManager.QUERY, mWebView.getSelection());
                mWebView.getContext().startActivity(i);
                break;

            default:
                return false;
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mWebView.selectionDone();
    }
}
