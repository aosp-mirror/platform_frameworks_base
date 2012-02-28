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

import android.util.DisplayMetrics;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;

import java.util.ArrayList;

class ViewManager {
    private final WebViewClassic mWebView;
    private final ArrayList<ChildView> mChildren = new ArrayList<ChildView>();
    private boolean mHidden;
    private boolean mReadyToDraw;
    private boolean mZoomInProgress = false;

    // Threshold at which a surface is prevented from further increasing in size
    private final int MAX_SURFACE_AREA;
    // GPU Limit (hard coded for now)
    private static final int MAX_SURFACE_DIMENSION = 2048;

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
            mWebView.getWebView().addView(mView);
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
            mWebView.getWebView().removeView(mView);
            mChildren.remove(this);
        }
    }

    ViewManager(WebViewClassic w) {
        mWebView = w;
        DisplayMetrics metrics = w.getWebView().getResources().getDisplayMetrics();
        int pixelArea = metrics.widthPixels * metrics.heightPixels;
        /* set the threshold to be 275% larger than the screen size. The
           percentage is simply an estimation and is not based on anything but
           basic trial-and-error tests run on multiple devices.
         */
        MAX_SURFACE_AREA = (int)(pixelArea * 2.75);
    }

    ChildView createView() {
        return new ChildView();
    }

    /**
     * This should only be called from the UI thread.
     */
    private void requestLayout(ChildView v) {

        int width = mWebView.contentToViewDimension(v.width);
        int height = mWebView.contentToViewDimension(v.height);
        int x = mWebView.contentToViewX(v.x);
        int y = mWebView.contentToViewY(v.y);

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

            if (sView.isFixedSize() && mZoomInProgress) {
                /* If we're already fixed, and we're in a zoom, then do nothing
                   about the size. Just wait until we get called at the end of
                   the zoom session (with mZoomInProgress false) and we'll
                   fixup our size then.
                 */
                return;
            }

            /* Compute proportional fixed width/height if necessary.
             *
             * NOTE: plugins (e.g. Flash) must not explicitly fix the size of
             * their surface. The logic below will result in unexpected behavior
             * for the plugin if they attempt to fix the size of the surface.
             */
            int fixedW = width;
            int fixedH = height;
            if (fixedW > MAX_SURFACE_DIMENSION || fixedH > MAX_SURFACE_DIMENSION) {
                if (v.width > v.height) {
                    fixedW = MAX_SURFACE_DIMENSION;
                    fixedH = v.height * MAX_SURFACE_DIMENSION / v.width;
                } else {
                    fixedH = MAX_SURFACE_DIMENSION;
                    fixedW = v.width * MAX_SURFACE_DIMENSION / v.height;
                }
            }
            if (fixedW * fixedH > MAX_SURFACE_AREA) {
                float area = MAX_SURFACE_AREA;
                if (v.width > v.height) {
                    fixedW = (int)Math.sqrt(area * v.width / v.height);
                    fixedH = v.height * fixedW / v.width;
                } else {
                    fixedH = (int)Math.sqrt(area * v.height / v.width);
                    fixedW = v.width * fixedH / v.height;
                }
            }

            if (fixedW != width || fixedH != height) {
                // if we get here, either our dimensions or area (or both)
                // exeeded our max, so we had to compute fixedW and fixedH
                sView.getHolder().setFixedSize(fixedW, fixedH);
            } else if (!sView.isFixedSize() && mZoomInProgress) {
                // just freeze where we were (view size) until we're done with
                // the zoom progress
                sView.getHolder().setFixedSize(sView.getWidth(),
                                               sView.getHeight());
            } else if (sView.isFixedSize() && !mZoomInProgress) {
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
                    // setLayoutParams() only requests the layout. If we set it
                    // to VISIBLE now, it will use the old dimension to set the
                    // size. Post a message to ensure that it shows the new size.
                    mWebView.mPrivateHandler.post(new Runnable() {
                        public void run() {
                            sView.setVisibility(View.VISIBLE);
                        }
                    });
                } else {
                    sView.getHolder().setSizeFromLayout();
                }
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
