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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

class FindActionModeCallback implements ActionMode.Callback, TextWatcher,
        View.OnLongClickListener, View.OnClickListener {
    private View mCustomView;
    private EditText mEditText;
    private TextView mMatches;
    private WebView mWebView;
    private InputMethodManager mInput;
    private Resources mResources;
    private boolean mMatchesFound;
    private int mNumberOfMatches;
    private ActionMode mActionMode;

    FindActionModeCallback(Context context) {
        mCustomView = LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.webview_find, null);
        mEditText = (EditText) mCustomView.findViewById(
                com.android.internal.R.id.edit);
        // Override long click so that select ActionMode is not opened, which
        // would exit find ActionMode.
        mEditText.setOnLongClickListener(this);
        mEditText.setOnClickListener(this);
        setText("");
        mMatches = (TextView) mCustomView.findViewById(
                com.android.internal.R.id.matches);
        mInput = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        mResources = context.getResources();
    }

    void finish() {
        mActionMode.finish();
    }

    /*
     * Place text in the text field so it can be searched for.  Need to press
     * the find next or find previous button to find all of the matches.
     */
    void setText(String text) {
        mEditText.setText(text);
        Spannable span = (Spannable) mEditText.getText();
        int length = span.length();
        // Ideally, we would like to set the selection to the whole field,
        // but this brings up the Text selection CAB, which dismisses this
        // one.
        Selection.setSelection(span, length, length);
        // Necessary each time we set the text, so that this will watch
        // changes to it.
        span.setSpan(this, 0, length, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        mMatchesFound = false;
    }

    /*
     * Set the WebView to search.  Must be non null, and set before calling
     * startActionMode.
     */
    void setWebView(WebView webView) {
        if (null == webView) {
            throw new AssertionError("WebView supplied to "
                    + "FindActionModeCallback cannot be null");
        }
        mWebView = webView;
    }

    /*
     * Move the highlight to the next match.
     * @param next If true, find the next match further down in the document.
     *             If false, find the previous match, up in the document.
     */
    private void findNext(boolean next) {
        if (mWebView == null) {
            throw new AssertionError(
                    "No WebView for FindActionModeCallback::findNext");
        }
        if (!mMatchesFound) {
            findAll();
            return;
        }
        if (0 == mNumberOfMatches) {
            // There are no matches, so moving to the next match will not do
            // anything.
            return;
        }
        mWebView.findNext(next);
        updateMatchesString();
    }

    /*
     * Highlight all the instances of the string from mEditText in mWebView.
     */
    void findAll() {
        if (mWebView == null) {
            throw new AssertionError(
                    "No WebView for FindActionModeCallback::findAll");
        }
        CharSequence find = mEditText.getText();
        if (0 == find.length()) {
            mWebView.clearMatches();
            mMatches.setVisibility(View.GONE);
            mMatchesFound = false;
        } else {
            mMatchesFound = true;
            mMatches.setVisibility(View.VISIBLE);
            mNumberOfMatches = mWebView.findAll(find.toString());
            if (0 == mNumberOfMatches) {
                mMatches.setText(mResources.getString(
                        com.android.internal.R.string.no_matches));
            } else {
                updateMatchesString();
            }
        }
    }

    public void showSoftInput() {
        mInput.startGettingWindowFocus(mEditText.getRootView());
        mInput.focusIn(mEditText);
        mInput.showSoftInput(mEditText, 0);
    }

    /*
     * Update the string which tells the user how many matches were found, and
     * which match is currently highlighted.
     * Not to be called when mNumberOfMatches is 0.
     */
    private void updateMatchesString() {
        String template = mResources.getQuantityString(
                com.android.internal.R.plurals.matches_found, mNumberOfMatches,
                mWebView.findIndex() + 1, mNumberOfMatches);

        mMatches.setText(template);
    }

    // OnLongClickListener implementation

    @Override
    public boolean onLongClick(View v) { return true; }

    // OnClickListener implementation

    @Override
    public void onClick(View v) {
        findNext(true);
    }

    // ActionMode.Callback implementation

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        if (!mode.isUiFocusable()) {
            // If the action mode we're running in is not focusable the user
            // will not be able to type into the find on page field. This
            // should only come up when we're running in a dialog which is
            // already less than ideal; disable the option for now.
            return false;
        }

        mode.setCustomView(mCustomView);
        mode.getMenuInflater().inflate(com.android.internal.R.menu.webview_find,
                menu);
        mActionMode = mode;
        Editable edit = mEditText.getText();
        Selection.setSelection(edit, edit.length());
        mMatches.setVisibility(View.GONE);
        mMatchesFound = false;
        mMatches.setText("0");
        mEditText.requestFocus();
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mActionMode = null;
        mWebView.notifyFindDialogDismissed();
        mInput.hideSoftInputFromWindow(mWebView.getWindowToken(), 0);
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (mWebView == null) {
            throw new AssertionError(
                    "No WebView for FindActionModeCallback::onActionItemClicked");
        }
        mInput.hideSoftInputFromWindow(mWebView.getWindowToken(), 0);
        switch(item.getItemId()) {
            case com.android.internal.R.id.find_prev:
                findNext(false);
                break;
            case com.android.internal.R.id.find_next:
                findNext(true);
                break;
            default:
                return false;
        }
        return true;
    }

    // TextWatcher implementation

    @Override
    public void beforeTextChanged(CharSequence s,
                                  int start,
                                  int count,
                                  int after) {
        // Does nothing.  Needed to implement TextWatcher.
    }

    @Override
    public void onTextChanged(CharSequence s,
                              int start,
                              int before,
                              int count) {
        findAll();
    }

    @Override
    public void afterTextChanged(Editable s) {
        // Does nothing.  Needed to implement TextWatcher.
    }

    private Rect mGlobalVisibleRect = new Rect();
    private Point mGlobalVisibleOffset = new Point();
    public int getActionModeGlobalBottom() {
        if (mActionMode == null) {
            return 0;
        }
        View view = (View) mCustomView.getParent();
        if (view == null) {
            view = mCustomView;
        }
        view.getGlobalVisibleRect(mGlobalVisibleRect, mGlobalVisibleOffset);
        return mGlobalVisibleRect.bottom;
    }

}
