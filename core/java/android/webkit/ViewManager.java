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

import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;

import java.util.ArrayList;

class ViewManager {
    private final WebView mWebView;
    private final ArrayList<ChildView> mChildren = new ArrayList<ChildView>();
    private boolean mHidden;
    private boolean mReadyToDraw;

    // Threshold at which a surface is prevented from further increasing in size
    private final int MAX_SURFACE_THRESHOLD;

    class ChildView {
        int x;
        int y;
        int width;
        int height;
        View mView; // generic view to show

        /* set to true if the view is a surface and it has exceeded the pixel
           threshold specified in MAX_SURFACE_THRESHOLD.
         */
        boolean isFixedSize = false;

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

            mWebView.mPrivateHandler.post(new Runnable() {
                public void run() {
                    // This method may be called multiple times. If the view is
                    // already attached, just set the new LayoutParams,
                    // otherwise attach the view and add it to the list of
                    // children.
                    AbsoluteLayout.LayoutParams lp = computeLayout(ChildView.this);

                    if (mView.getParent() != null) {
                        mView.setLayoutParams(lp);
                    } else {
                        attachViewOnUIThread(lp);
                    }
                }
            });
        }

        private void attachViewOnUIThread(AbsoluteLayout.LayoutParams lp) {
            mWebView.addView(mView, lp);
            mChildren.add(this);
            if (!mReadyToDraw) {
                mView.setVisibility(View.GONE);
            }
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

        private void removeViewOnUIThread() {
            mWebView.removeView(mView);
            mChildren.remove(this);
        }
    }

    ViewManager(WebView w) {
        mWebView = w;

        int pixelArea = w.getResources().getDisplayMetrics().widthPixels *
                        w.getResources().getDisplayMetrics().heightPixels;
        /* set the threshold to be 275% larger than the screen size. The
           percentage is simply an estimation and is not based on anything but
           basic trial-and-error tests run on multiple devices.
         */
        MAX_SURFACE_THRESHOLD = (int)(pixelArea * 2.75);
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

    /**
     * This should only be called from the UI thread.
     */
    private AbsoluteLayout.LayoutParams computeLayout(ChildView v) {

        // if the surface has exceed a predefined threshold then fix the size
        // of the surface.
        if (!v.isFixedSize && (v.width * v.height) > MAX_SURFACE_THRESHOLD
                && v.mView instanceof SurfaceView) {
            ((SurfaceView)v.mView).getHolder().setFixedSize(v.width, v.height);
            v.isFixedSize = true;
        }

        AbsoluteLayout.LayoutParams lp;
        ViewGroup.LayoutParams layoutParams = v.mView.getLayoutParams();

        if (layoutParams instanceof AbsoluteLayout.LayoutParams) {
            lp = (AbsoluteLayout.LayoutParams) layoutParams;
            lp.width = ctvD(v.width);
            lp.height = ctvD(v.height);
            lp.x = ctvX(v.x);
            lp.y = ctvY(v.y);
        } else {
            lp = new AbsoluteLayout.LayoutParams(ctvD(v.width), ctvD(v.height),
                    ctvX(v.x), ctvY(v.y));
        }
        return lp;
    }

    void scaleAll() {
        for (ChildView v : mChildren) {
            v.mView.setLayoutParams(computeLayout(v));
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

    void postResetStateAll() {
        mWebView.mPrivateHandler.post(new Runnable() {
            public void run() {
                mReadyToDraw = false;
            }
        });
    }

    void postReadyToDrawAll() {
        mWebView.mPrivateHandler.post(new Runnable() {
            public void run() {
                mReadyToDraw = true;
                for (ChildView v : mChildren) {
                    v.mView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    ChildView hitTest(int contentX, int contentY) {
        if (mHidden) {
            return null;
        }
        for (ChildView v : mChildren) {
            if (v.mView.getVisibility() == View.VISIBLE) {
                if (contentX >= v.x && contentX < (v.x + v.width)
                        && contentY >= v.y && contentY < (v.y + v.height)) {
                    return v;
                }
            }
        }
        return null;
    }
}
