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

import android.app.Activity;
import android.app.SearchManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Browser;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

class SelectActionModeCallback implements ActionMode.Callback {
    private WebViewClassic mWebView;
    private ActionMode mActionMode;
    private boolean mIsTextSelected = true;

    void setWebView(WebViewClassic webView) {
        mWebView = webView;
    }

    void setTextSelected(boolean isTextSelected) {
        mIsTextSelected = isTextSelected;
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
        mode.setTitle(context.getString(com.android.internal.R.string.textSelectionCABTitle));
        mode.setTitleOptionalHint(true);

        // If the action mode UI we're running in isn't capable of taking window focus
        // the user won't be able to type into the find on page UI. Disable this functionality.
        // (Note that this should only happen in floating dialog windows.)
        // This can be removed once we can handle multiple focusable windows at a time
        // in a better way.
        ClipboardManager cm = (ClipboardManager)(context
                .getSystemService(Context.CLIPBOARD_SERVICE));
        boolean isFocusable = mode.isUiFocusable();
        boolean isEditable = mWebView.focusCandidateIsEditableText();
        boolean canPaste = isEditable && cm.hasPrimaryClip() && isFocusable;
        boolean canFind = !isEditable && isFocusable;
        boolean canCut = isEditable && mIsTextSelected && isFocusable;
        boolean canCopy = mIsTextSelected;
        boolean canWebSearch = mIsTextSelected;
        setMenuVisibility(menu, canFind, com.android.internal.R.id.find);
        setMenuVisibility(menu, canPaste, com.android.internal.R.id.paste);
        setMenuVisibility(menu, canCut, com.android.internal.R.id.cut);
        setMenuVisibility(menu, canCopy, com.android.internal.R.id.copy);
        setMenuVisibility(menu, canWebSearch, com.android.internal.R.id.websearch);
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
            case android.R.id.cut:
                mWebView.cutSelection();
                mode.finish();
                break;

            case android.R.id.copy:
                mWebView.copySelection();
                mode.finish();
                break;

            case android.R.id.paste:
                mWebView.pasteFromClipboard();
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
                if (!(mWebView.getContext() instanceof Activity)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
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

    private void setMenuVisibility(Menu menu, boolean visible, int resourceId) {
        final MenuItem item = menu.findItem(resourceId);
        if (item != null) {
            item.setVisible(visible);
        }
    }
}
