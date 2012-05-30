/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AbsoluteLayout;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListAdapter;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow.OnDismissListener;

class AutoCompletePopup implements OnItemClickListener, Filter.FilterListener,
        OnDismissListener{
    private static class AnchorView extends View {
        AnchorView(Context context) {
            super(context);
            setFocusable(false);
            setVisibility(INVISIBLE);
        }
    }
    private static final int AUTOFILL_FORM = 100;
    private boolean mIsAutoFillProfileSet;
    private Handler mHandler;
    private int mQueryId;
    private ListPopupWindow mPopup;
    private Filter mFilter;
    private CharSequence mText;
    private ListAdapter mAdapter;
    private View mAnchor;
    private WebViewClassic.WebViewInputConnection mInputConnection;
    private WebViewClassic mWebView;

    public AutoCompletePopup(WebViewClassic webView,
            WebViewClassic.WebViewInputConnection inputConnection) {
        mInputConnection = inputConnection;
        mWebView = webView;
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                case AUTOFILL_FORM:
                    mWebView.autoFillForm(mQueryId);
                    break;
                }
            }
        };
    }

    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mPopup == null) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && mPopup.isShowing()) {
            // special case for the back key, we do not even try to send it
            // to the drop down list but instead, consume it immediately
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                KeyEvent.DispatcherState state = mAnchor.getKeyDispatcherState();
                if (state != null) {
                    state.startTracking(event, this);
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                KeyEvent.DispatcherState state = mAnchor.getKeyDispatcherState();
                if (state != null) {
                    state.handleUpEvent(event);
                }
                if (event.isTracking() && !event.isCanceled()) {
                    mPopup.dismiss();
                    return true;
                }
            }
        }
        if (mPopup.isShowing()) {
            return mPopup.onKeyPreIme(keyCode, event);
        }
        return false;
    }

    public void setText(CharSequence text) {
        mText = text;
        if (mFilter != null) {
            mFilter.filter(text, this);
        }
    }

    public void setAutoFillQueryId(int queryId) {
        mQueryId = queryId;
    }

    public void clearAdapter() {
        mAdapter = null;
        mFilter = null;
        if (mPopup != null) {
            mPopup.dismiss();
            mPopup.setAdapter(null);
        }
    }

    public <T extends ListAdapter & Filterable> void setAdapter(T adapter) {
        ensurePopup();
        mPopup.setAdapter(adapter);
        mAdapter = adapter;
        if (adapter != null) {
            mFilter = adapter.getFilter();
            mFilter.filter(mText, this);
        } else {
            mFilter = null;
        }
        resetRect();
    }

    public void resetRect() {
        ensurePopup();
        int left = mWebView.contentToViewX(mWebView.mEditTextContentBounds.left);
        int right = mWebView.contentToViewX(mWebView.mEditTextContentBounds.right);
        int width = right - left;
        mPopup.setWidth(width);

        int bottom = mWebView.contentToViewY(mWebView.mEditTextContentBounds.bottom);
        int top = mWebView.contentToViewY(mWebView.mEditTextContentBounds.top);
        int height = bottom - top;

        AbsoluteLayout.LayoutParams lp =
                (AbsoluteLayout.LayoutParams) mAnchor.getLayoutParams();
        boolean needsUpdate = false;
        if (null == lp) {
            lp = new AbsoluteLayout.LayoutParams(width, height, left, top);
        } else {
            if ((lp.x != left) || (lp.y != top) || (lp.width != width)
                    || (lp.height != height)) {
                needsUpdate = true;
                lp.x = left;
                lp.y = top;
                lp.width = width;
                lp.height = height;
            }
        }
        if (needsUpdate) {
            mAnchor.setLayoutParams(lp);
        }
        if (mPopup.isShowing()) {
            mPopup.show(); // update its position
        }
    }

    // AdapterView.OnItemClickListener implementation
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mPopup == null) {
            return;
        }
        if (id == 0 && position == 0 && mInputConnection.getIsAutoFillable()) {
            mText = "";
            pushTextToInputConnection();
            // Blank out the text box while we wait for WebCore to fill the form.
            if (mIsAutoFillProfileSet) {
                // Call a webview method to tell WebCore to autofill the form.
                mWebView.autoFillForm(mQueryId);
            } else {
                // There is no autofill profile setup yet and the user has
                // elected to try and set one up. Call through to the
                // embedder to action that.
                WebChromeClient webChromeClient = mWebView.getWebChromeClient();
                if (webChromeClient != null) {
                    webChromeClient.setupAutoFill(
                        mHandler.obtainMessage(AUTOFILL_FORM));
                }
            }
        } else {
            Object selectedItem;
            if (position < 0) {
                selectedItem = mPopup.getSelectedItem();
            } else {
                selectedItem = mAdapter.getItem(position);
            }
            if (selectedItem != null) {
                setText(mFilter.convertResultToString(selectedItem));
                pushTextToInputConnection();
            }
        }
        mPopup.dismiss();
    }

    public void setIsAutoFillProfileSet(boolean isAutoFillProfileSet) {
        mIsAutoFillProfileSet = isAutoFillProfileSet;
    }

    private void pushTextToInputConnection() {
        Editable oldText = mInputConnection.getEditable();
        mInputConnection.setSelection(0, oldText.length());
        mInputConnection.replaceSelection(mText);
        mInputConnection.setSelection(mText.length(), mText.length());
    }

    @Override
    public void onFilterComplete(int count) {
        ensurePopup();
        boolean showDropDown = (count > 0) &&
                (mInputConnection.getIsAutoFillable() || mText.length() > 0);
        if (showDropDown) {
            if (!mPopup.isShowing()) {
                // Make sure the list does not obscure the IME when shown for the first time.
                mPopup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);
            }
            mPopup.show();
            mPopup.getListView().setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        } else {
            mPopup.dismiss();
        }
    }

    @Override
    public void onDismiss() {
        mWebView.getWebView().removeView(mAnchor);
    }

    private void ensurePopup() {
        if (mPopup == null) {
            mPopup = new ListPopupWindow(mWebView.getContext());
            mAnchor = new AnchorView(mWebView.getContext());
            mWebView.getWebView().addView(mAnchor);
            mPopup.setOnItemClickListener(this);
            mPopup.setAnchorView(mAnchor);
            mPopup.setPromptPosition(ListPopupWindow.POSITION_PROMPT_BELOW);
        } else if (mWebView.getWebView().indexOfChild(mAnchor) < 0) {
            mWebView.getWebView().addView(mAnchor);
        }
    }
}

