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
    private boolean mZoomInProgress = false;

    // Threshold at which a surface is prevented from further increasing in size
    private final int MAX_SURFACE_THRESHOLD;

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

            mWebView.mPrivateHandler.post(new Runnable() {
                public void run() {
                    // This method may be called multiple times. If the view is
                    // already attached, just set the new LayoutParams,
                    // otherwise attach the view and add it to the list of
                    // children.
                    requestLayout(ChildView.this);

                    if (mView.getParent() == null) {
                        attachViewOnUIThread();
                    }
                }
            });
        }

        private void attachViewOnUIThread() {
            mWebView.addView(mView);
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
    private void requestLayout(ChildView v) {

        int width = ctvD(v.width);
        int height = ctvD(v.height);
        int x = ctvX(v.x);
        int y = ctvY(v.y);

        AbsoluteLayout.LayoutParams lp;
        ViewGroup.LayoutParams layoutParams = v.mView.getLayoutParams();

        if (layoutParams instanceof AbsoluteLayout.LayoutParams) {
            lp = (AbsoluteLayout.LayoutParams) layoutParams;
            lp.width = width;
            lp.height = height;
            lp.x = x;
            lp.y = y;
        } else {
            lp = new AbsoluteLayout.LayoutParams(width, height, x, y);
        }

        // apply the layout to the view
        v.mView.setLayoutParams(lp);

        if(v.mView instanceof SurfaceView) {

            final SurfaceView sView = (SurfaceView) v.mView;
            boolean exceedThreshold = (width * height) > MAX_SURFACE_THRESHOLD;

            /* If the surface has exceeded a predefined threshold or the webview
             * is currently zoom then fix the size of the surface.
             *
             * NOTE: plugins (e.g. Flash) must not explicitly fix the size of
             * their surface. The logic below will result in unexpected behavior
             * for the plugin if they attempt to fix the size of the surface.
             */
            if (!sView.isFixedSize() && (exceedThreshold || mZoomInProgress)) {
                sView.getHolder().setFixedSize(width, height);
            }
            else if (sView.isFixedSize() && !exceedThreshold && !mZoomInProgress) {
                /* The changing of visibility is a hack to get around a bug in
                 * the framework that causes the surface to revert to the size
                 * it was prior to being fixed before it redraws using the
                 * values currently in its layout.
                 *
                 * The surface is destroyed when it is set to invisible and then
                 * recreated at the new dimensions when it is made visible. The
                 * same destroy/create step occurs without the change in
                 * visibility, but then exhibits the behavior described in the
                 * previous paragraph.
                 */
                if (sView.getVisibility() == View.VISIBLE) {
                    sView.setVisibility(View.INVISIBLE);
                    sView.getHolder().setSizeFromLayout();
                    sView.setVisibility(View.VISIBLE);
                } else {
                    sView.getHolder().setSizeFromLayout();
                }
            }
            else if (sView.isFixedSize() && exceedThreshold) {
                sView.requestLayout();
            }
        }
    }

    void startZoom() {
        mZoomInProgress = true;
        for (ChildView v : mChildren) {
            requestLayout(v);
        }
    }

    void endZoom() {
        mZoomInProgress = false;
        for (ChildView v : mChildren) {
            requestLayout(v);
        }
    }

    void scaleAll() {
        for (ChildView v : mChildren) {
            requestLayout(v);
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
