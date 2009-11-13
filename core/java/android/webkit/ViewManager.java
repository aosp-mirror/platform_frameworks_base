/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.view.View;
import android.widget.AbsoluteLayout;

import java.util.ArrayList;

class ViewManager {
    private final WebView mWebView;
    private final ArrayList<ChildView> mChildren = new ArrayList<ChildView>();
    private boolean mHidden;

    class ChildView {
        int x;
        int y;
        int width;
        int height;
        View mView; // generic view to show

        ChildView() {
        }

        void setBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        void attachView(int x, int y, int width, int height) {
            if (mView == null) {
                return;
            }
            setBounds(x, y, width, height);
            final AbsoluteLayout.LayoutParams lp =
                    new AbsoluteLayout.LayoutParams(ctvD(width), ctvD(height),
                            ctvX(x), ctvY(y));
            mWebView.mPrivateHandler.post(new Runnable() {
                public void run() {
                    // This method may be called multiple times. If the view is
                    // already attached, just set the new LayoutParams,
                    // otherwise attach the view and add it to the list of
                    // children.
                    if (mView.getParent() != null) {
                        mView.setLayoutParams(lp);
                    } else {
                        attachViewOnUIThread(lp);
                    }
                }
            });
        }

        void attachViewOnUIThread(AbsoluteLayout.LayoutParams lp) {
            mWebView.addView(mView, lp);
            mChildren.add(this);
        }

        void removeView() {
            if (mView == null) {
                return;
            }
            mWebView.mPrivateHandler.post(new Runnable() {
                public void run() {
                    removeViewOnUIThread();
                }
            });
        }

        void removeViewOnUIThread() {
            mWebView.removeView(mView);
            mChildren.remove(this);
        }
    }

    ViewManager(WebView w) {
        mWebView = w;
    }

    ChildView createView() {
        return new ChildView();
    }

    /**
     * Shorthand for calling mWebView.contentToViewDimension.  Used when
     * obtaining a view dimension from a content dimension, whether it be in x
     * or y.
     */
    private int ctvD(int val) {
        return mWebView.contentToViewDimension(val);
    }

    /**
     * Shorthand for calling mWebView.contentToViewX.  Used when obtaining a
     * view x coordinate from a content x coordinate.
     */
    private int ctvX(int val) {
        return mWebView.contentToViewX(val);
    }

    /**
     * Shorthand for calling mWebView.contentToViewY.  Used when obtaining a
     * view y coordinate from a content y coordinate.
     */
    private int ctvY(int val) {
        return mWebView.contentToViewY(val);
    }

    void scaleAll() {
        for (ChildView v : mChildren) {
            View view = v.mView;
            AbsoluteLayout.LayoutParams lp =
                    (AbsoluteLayout.LayoutParams) view.getLayoutParams();
            lp.width = ctvD(v.width);
            lp.height = ctvD(v.height);
            lp.x = ctvX(v.x);
            lp.y = ctvY(v.y);
            view.setLayoutParams(lp);
        }
    }

    void hideAll() {
        if (mHidden) {
            return;
        }
        for (ChildView v : mChildren) {
            v.mView.setVisibility(View.GONE);
        }
        mHidden = true;
    }

    void showAll() {
        if (!mHidden) {
            return;
        }
        for (ChildView v : mChildren) {
            v.mView.setVisibility(View.VISIBLE);
        }
        mHidden = false;
    }
}
