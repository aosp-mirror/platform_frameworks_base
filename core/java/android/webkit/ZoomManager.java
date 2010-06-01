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

import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

class ZoomManager {

    static final String LOGTAG = "webviewZoom";

    private final WebView mWebView;
    private final CallbackProxy mCallbackProxy;

    // manages the on-screen zoom functions of the WebView
    private ZoomControlEmbedded mEmbeddedZoomControl;

    private ZoomControlExternal mExternalZoomControl;

    /*
     * TODO: clean up the visibility of the class variables when the zoom
     * refactoring is complete
     */

    // default scale limits, which are dependent on the display density
    static float DEFAULT_MAX_ZOOM_SCALE;
    static float DEFAULT_MIN_ZOOM_SCALE;

    // actual scale limits, which can be set through a webpage viewport meta tag
    float mMaxZoomScale;
    float mMinZoomScale;

    // locks the minimum ZoomScale to the value currently set in mMinZoomScale
    boolean mMinZoomScaleFixed = true;

    // while in the zoom overview mode, the page's width is fully fit to the
    // current window. The page is alive, in another words, you can click to
    // follow the links. Double tap will toggle between zoom overview mode and
    // the last zoom scale.
    boolean mInZoomOverview = false;

    // These keep track of the center point of the zoom and they are used to
    // determine the point around which we should zoom. They are stored in view
    // coordinates.
    float mZoomCenterX;
    float mZoomCenterY;

    // ideally mZoomOverviewWidth should be mContentWidth. But sites like espn,
    // engadget always have wider mContentWidth no matter what viewport size is.
    int mZoomOverviewWidth = WebView.DEFAULT_VIEWPORT_WIDTH;
    float mTextWrapScale;

    // the default zoom scale. This value will is initially set based on the
    // display density, but can be changed at any time via the WebSettings.
    float mDefaultScale;

    private static float MINIMUM_SCALE_INCREMENT = 0.01f;

    // set to true temporarily during ScaleGesture triggered zoom
    boolean mPreviewZoomOnly = false;

    // the current computed zoom scale and its inverse.
    float mActualScale;
    float mInvActualScale;
    
    /*
     * The following member variables are only to be used for animating zoom. If
     * mZoomScale is non-zero then we are in the middle of a zoom animation. The
     * other variables are used as a cache (e.g. inverse) or as a way to store
     * the state of the view prior to animating (e.g. initial scroll coords).
     */
    private float mZoomScale;
    private float mInvInitialZoomScale;
    private float mInvFinalZoomScale;
    private int mInitialScrollX;
    private int mInitialScrollY;
    private long mZoomStart;
    static final int ZOOM_ANIMATION_LENGTH = 500;

    public ZoomManager(WebView webView, CallbackProxy callbackProxy) {
        mWebView = webView;
        mCallbackProxy = callbackProxy;
    }

    public void init(float density) {
        setDefaultZoomScale(density);
        mMaxZoomScale = DEFAULT_MAX_ZOOM_SCALE;
        mMinZoomScale = DEFAULT_MIN_ZOOM_SCALE;
        mActualScale = density;
        mInvActualScale = 1 / density;
        mTextWrapScale = density;
    }

    public void updateDefaultZoomDensity(float density) {
        if (Math.abs(density - mDefaultScale) > MINIMUM_SCALE_INCREMENT) {
            float scaleFactor = density / mDefaultScale;
            // set the new default density
            setDefaultZoomScale(density);
            // adjust the limits
            mMaxZoomScale *= scaleFactor;
            mMinZoomScale *= scaleFactor;
            setZoomScale(mActualScale * scaleFactor, true);
        }
    }

    private void setDefaultZoomScale(float defaultScale) {
        mDefaultScale = defaultScale;
        DEFAULT_MAX_ZOOM_SCALE = 4.0f * defaultScale;
        DEFAULT_MIN_ZOOM_SCALE = 0.25f * defaultScale;
    }

    public void setZoomCenter(float x, float y) {
        mZoomCenterX = x;
        mZoomCenterY = y;
    }

    public static final boolean exceedsMinScaleIncrement(float scaleA, float scaleB) {
        return Math.abs(scaleA - scaleB) >= MINIMUM_SCALE_INCREMENT;
    }

    public boolean willScaleTriggerZoom(float scale) {
        return exceedsMinScaleIncrement(scale, mActualScale);
    }

    public boolean isZoomedOut() {
        return mActualScale - mMinZoomScale <= MINIMUM_SCALE_INCREMENT;
    }

    public boolean zoomIn() {
        mInZoomOverview = false;
        return zoom(1.25f);
    }

    public boolean zoomOut() {
        return zoom(0.8f);
    }

    // returns TRUE if zoom out succeeds and FALSE if no zoom changes.
    private boolean zoom(float zoomMultiplier) {
        // TODO: alternatively we can disallow this during draw history mode
        mWebView.switchOutDrawHistory();
        // Center zooming to the center of the screen.
        mZoomCenterX = mWebView.getViewWidth() * .5f;
        mZoomCenterY = mWebView.getViewHeight() * .5f;
        int anchorX = mWebView.viewToContentX((int) mZoomCenterX + mWebView.getScrollX());
        int anchorY = mWebView.viewToContentY((int) mZoomCenterY + mWebView.getScrollY());
        mWebView.setViewSizeAnchor(anchorX, anchorY);
        return startZoomAnimation(mActualScale * zoomMultiplier, true);
    }

    public void zoomToOverview() {
        mInZoomOverview = true;
        // Force the titlebar fully reveal in overview mode
        int scrollY = mWebView.getScrollY();
        if (scrollY < mWebView.getTitleHeight()) {
            mWebView.updateScrollCoordinates(mWebView.getScrollX(), 0);
        }
        startZoomAnimation((float) mWebView.getViewWidth() / mZoomOverviewWidth, true);
    }

    public void zoomToDefaultLevel(boolean reflowText) {
        mInZoomOverview = false;
        startZoomAnimation(mDefaultScale, reflowText);
    }

    /**
     * Initiates an animated zoom of the WebView.
     *
     * @return true if the new scale triggered an animation and false otherwise.
     */
    public boolean startZoomAnimation(float scale, boolean reflowText) {
        float oldScale = mActualScale;
        mInitialScrollX = mWebView.getScrollX();
        mInitialScrollY = mWebView.getScrollY();

        // snap to DEFAULT_SCALE if it is close
        if (!exceedsMinScaleIncrement(scale, mDefaultScale)) {
            scale = mDefaultScale;
        }

        setZoomScale(scale, reflowText);

        if (oldScale != mActualScale) {
            // use mZoomPickerScale to see zoom preview first
            mZoomStart = SystemClock.uptimeMillis();
            mInvInitialZoomScale = 1.0f / oldScale;
            mInvFinalZoomScale = 1.0f / mActualScale;
            mZoomScale = mActualScale;
            WebViewCore.pauseUpdatePicture(mWebView.getWebViewCore());
            mWebView.invalidate();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Computes and returns the relevant data needed by the WebView's drawing
     * model to animate a zoom.
     *
     * This method is to be called when a zoom animation is occurring. The
     * animation begins by calling startZoomAnimation(...).  The caller can
     * check to see if the animation has completed by calling isZoomAnimating().
     *
     * @return an array containing the values needed to animate the drawing
     * surface.
     * [0] = delta for the new scrollX position
     * [1] = delta for the new scrollY position
     * [2] = current zoom scale
     */
    public float[] animateZoom() {
        if (mZoomScale == 0) {
            Log.w(LOGTAG, "A WebView is attempting to animate a zoom when no " +
                    "zoom is in progress");
            float[] result = {0, 0, mActualScale};
            return result;
        }

        float zoomScale;
        int interval = (int) (SystemClock.uptimeMillis() - mZoomStart);
        if (interval < ZOOM_ANIMATION_LENGTH) {
            float ratio = (float) interval / ZOOM_ANIMATION_LENGTH;
            zoomScale = 1.0f / (mInvInitialZoomScale
                    + (mInvFinalZoomScale - mInvInitialZoomScale) * ratio);
        } else {
            zoomScale = mZoomScale;
            // set mZoomScale to be 0 as we have finished animating
            mZoomScale = 0;
        }
        // calculate the intermediate scroll position. Since we need to use
        // zoomScale, we can't use the WebView's pinLocX/Y functions directly.
        float scale = zoomScale * mInvInitialZoomScale;
        int tx = Math.round(scale * (mInitialScrollX + mZoomCenterX) - mZoomCenterX);
        tx = -WebView.pinLoc(tx, mWebView.getViewWidth(), Math.round(mWebView.getContentWidth()
                * zoomScale)) + mWebView.getScrollX();
        int titleHeight = mWebView.getTitleHeight();
        int ty = Math.round(scale
                * (mInitialScrollY + mZoomCenterY - titleHeight)
                - (mZoomCenterY - titleHeight));
        ty = -(ty <= titleHeight ? Math.max(ty, 0) : WebView.pinLoc(ty
                - titleHeight, mWebView.getViewHeight(), Math.round(mWebView.getContentHeight()
                * zoomScale)) + titleHeight) + mWebView.getScrollY();

        float[] result = {tx, ty, zoomScale};
        return result;
    }

    public boolean isZoomAnimating() {
        return mZoomScale != 0;
    }

    public void refreshZoomScale(boolean reflowText) {
        setZoomScale(mActualScale, reflowText, true);
    }

    public void setZoomScale(float scale, boolean reflowText) {
        setZoomScale(scale, reflowText, false);
    }

    private void setZoomScale(float scale, boolean reflowText, boolean force) {
        if (scale < mMinZoomScale) {
            scale = mMinZoomScale;
            // set mInZoomOverview for non mobile sites
            if (scale < mDefaultScale) {
                mInZoomOverview = true;
            }
        } else if (scale > mMaxZoomScale) {
            scale = mMaxZoomScale;
        }

        if (reflowText) {
            mTextWrapScale = scale;
        }

        if (scale != mActualScale || force) {
            float oldScale = mActualScale;
            float oldInvScale = mInvActualScale;

            if (scale != mActualScale && !mPreviewZoomOnly) {
                mCallbackProxy.onScaleChanged(mActualScale, scale);
            }

            mActualScale = scale;
            mInvActualScale = 1 / scale;

            if (!mWebView.drawHistory()) {

                // If history Picture is drawn, don't update scroll. They will
                // be updated when we get out of that mode.
                // update our scroll so we don't appear to jump
                // i.e. keep the center of the doc in the center of the view
                int oldX = mWebView.getScrollX();
                int oldY = mWebView.getScrollY();
                float ratio = scale * oldInvScale;
                float sx = ratio * oldX + (ratio - 1) * mZoomCenterX;
                float sy = ratio * oldY + (ratio - 1)
                        * (mZoomCenterY - mWebView.getTitleHeight());

                // Scale all the child views
                mWebView.mViewManager.scaleAll();

                // as we don't have animation for scaling, don't do animation
                // for scrolling, as it causes weird intermediate state
                int scrollX = mWebView.pinLocX(Math.round(sx));
                int scrollY = mWebView.pinLocY(Math.round(sy));
                if(!mWebView.updateScrollCoordinates(scrollX, scrollY)) {
                    // the scroll position is adjusted at the beginning of the
                    // zoom animation. But we want to update the WebKit at the
                    // end of the zoom animation. See comments in onScaleEnd().
                    mWebView.sendOurVisibleRect();
                }
            }

            // if the we need to reflow the text then force the VIEW_SIZE_CHANGED
            // event to be sent to WebKit
            mWebView.sendViewSizeZoom(reflowText);
        }
    }

    private ZoomControlBase getCurrentZoomControl() {
        if (mWebView.getSettings() != null && mWebView.getSettings().supportZoom()) {
            if (mWebView.getSettings().getBuiltInZoomControls()) {
                if (mEmbeddedZoomControl == null) {
                    mEmbeddedZoomControl = new ZoomControlEmbedded(this, mWebView);
                }
                return mEmbeddedZoomControl;
            } else {
                if (mExternalZoomControl == null) {
                    mExternalZoomControl = new ZoomControlExternal(mWebView);
                }
                return mExternalZoomControl;
            }
        }
        return null;
    }

    public void invokeZoomPicker() {
        ZoomControlBase control = getCurrentZoomControl();
        if (control != null) {
            control.show();
        }
    }

    public void dismissZoomPicker() {
        ZoomControlBase control = getCurrentZoomControl();
        if (control != null) {
            control.hide();
        }
    }

    public boolean isZoomPickerVisible() {
        ZoomControlBase control = getCurrentZoomControl();
        return (control != null) ? control.isVisible() : false;
    }

    public void updateZoomPicker() {
        ZoomControlBase control = getCurrentZoomControl();
        if (control != null) {
            control.update();
        }
    }

    /**
     * The embedded zoom control intercepts touch events and automatically stays
     * visible. The external control needs to constantly refresh its internal
     * timer to stay visible.
     */
    public void keepZoomPickerVisible() {
        ZoomControlBase control = getCurrentZoomControl();
        if (control != null && control == mExternalZoomControl) {
            control.show();
        }
    }

    public View getExternalZoomPicker() {
        ZoomControlBase control = getCurrentZoomControl();
        if (control != null && control == mExternalZoomControl) {
            return mExternalZoomControl.getControls();
        } else {
            return null;
        }
    }
}
