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

import android.provider.Browser;
import android.webkit.WebView;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

class SelectActionModeCallback implements ActionMode.Callback {
    private WebView mWebView;
    private View mTitleBar;
    private ActionMode mActionMode;

    void setWebView(WebView webView) {
        mWebView = webView;
    }

    void setTitleBar(View v) { mTitleBar = v; }

    void finish() {
        mActionMode.finish();
    }

    // ActionMode.Callback implementation

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(com.android.internal.R.menu.webview_copy,
                menu);
        mode.setTitle(com.android.internal.R.string.textSelectionCABTitle);
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
                mWebView.showFindDialog(sel);
                break;

            default:
                return false;
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        if (mTitleBar != null) mWebView.setEmbeddedTitleBar(mTitleBar);
        mWebView.selectionDone();
    }
}
