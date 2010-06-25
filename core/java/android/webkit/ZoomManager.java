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
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Point;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * The ZoomManager is responsible for maintaining the WebView's current zoom
 * level state.  It is also responsible for managing the on-screen zoom controls
 * as well as any animation of the WebView due to zooming.
 *
 * Currently, there are two methods for animating the zoom of a WebView.
 *
 * (1) The first method is triggered by startZoomAnimation(...) and is a fixed
 * length animation where the final zoom scale is known at startup.  This type of
 * animation notifies webkit of the final scale BEFORE it animates. The animation
 * is then done by scaling the CANVAS incrementally based on a stepping function.
 *
 * (2) The second method is triggered by a multi-touch pinch and the new scale
 * is determined dynamically based on the user's gesture. This type of animation
 * only notifies webkit of new scale AFTER the gesture is complete. The animation
 * effect is achieved by scaling the VIEWS (both WebView and ViewManager.ChildView)
 * to the new scale in response to events related to the user's gesture.
 */
class ZoomManager {

    static final String LOGTAG = "webviewZoom";

    private final WebView mWebView;
    private final CallbackProxy mCallbackProxy;

    // Widgets responsible for the on-screen zoom functions of the WebView.
    private ZoomControlEmbedded mEmbeddedZoomControl;
    private ZoomControlExternal mExternalZoomControl;

    // The default scale limits, which are dependent on the display density.
    private static float DEFAULT_MAX_ZOOM_SCALE;

    private static float DEFAULT_MIN_ZOOM_SCALE;

    // The actual scale limits, which can be set through a webpage's viewport
    // meta-tag.
    private float mMaxZoomScale;
    private float mMinZoomScale;

    // Locks the minimum ZoomScale to the value currently set in mMinZoomScale.
    private boolean mMinZoomScaleFixed = true;

    /*
     * When loading a new page the WebView does not initially know the final
     * width of the page. Therefore, when a new page is loaded in overview mode
     * the overview scale is initialized to a default value. This flag is then
     * set and used to notify the ZoomManager to take the width of the next
     * picture from webkit and use that width to enter into zoom overview mode.
     */
    private boolean mInitialZoomOverview = false;

    /*
     * When in the zoom overview mode, the page's width is fully fit to the
     * current window. Additionally while the page is in this state it is
     * active, in other words, you can click to follow the links. We cache a
     * boolean to enable us to quickly check whether or not we are in overview
     * mode, but this value should only be modified by changes to the zoom
     * scale.
     */
    private boolean mInZoomOverview = false;
    private int mZoomOverviewWidth;
    private float mInvZoomOverviewWidth;

    /*
     * These variables track the center point of the zoom and they are used to
     * determine the point around which we should zoom. They are stored in view
     * coordinates.
     */
    private float mZoomCenterX;
    private float mZoomCenterY;

    /*
     * These values represent the point around which the screen should be
     * centered after zooming. In other words it is used to determine the center
     * point of the visible document after the page has finished zooming. This
     * is important because the zoom may have potentially reflowed the text and
     * we need to ensure the proper portion of the document remains on the
     * screen.
     */
    private int mAnchorX;
    private int mAnchorY;

    // The scale factor that is used to determine the column width for text
    private float mTextWrapScale;

    /*
     * The default zoom scale is the scale factor used when the user triggers a
     * zoom in by double tapping on the WebView. The value is initially set
     * based on the display density, but can be changed at any time via the
     * WebSettings.
     */
    private float mDefaultScale;
    private float mInvDefaultScale;

    // the current computed zoom scale and its inverse.
    private float mActualScale;
    private float mInvActualScale;
    
    /*
     * The initial scale for the WebView. 0 means default. If initial scale is
     * greater than 0 the WebView starts with this value as its initial scale. The
     * value is converted from an integer percentage so it is guarenteed to have
     * no more than 2 significant digits after the decimal.  This restriction
     * allows us to convert the scale back to the original percentage by simply
     * multiplying the value by 100.
     */
    private float mInitialScale;

    private static float MINIMUM_SCALE_INCREMENT = 0.01f;

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

    private static final int ZOOM_ANIMATION_LENGTH = 500;

    // whether support multi-touch
    private boolean mSupportMultiTouch;

    // use the framework's ScaleGestureDetector to handle multi-touch
    private ScaleGestureDetector mScaleDetector;
    private boolean mPinchToZoomAnimating = false;

    public ZoomManager(WebView webView, CallbackProxy callbackProxy) {
        mWebView = webView;
        mCallbackProxy = callbackProxy;

        /*
         * Ideally mZoomOverviewWidth should be mContentWidth. But sites like
         * ESPN and Engadget always have wider mContentWidth no matter what the
         * viewport size is.
         */
        setZoomOverviewWidth(WebView.DEFAULT_VIEWPORT_WIDTH);
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
            float scaleFactor = density * mInvDefaultScale;
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
        mInvDefaultScale = 1 / defaultScale;
        DEFAULT_MAX_ZOOM_SCALE = 4.0f * defaultScale;
        DEFAULT_MIN_ZOOM_SCALE = 0.25f * defaultScale;
    }

    public final float getScale() {
        return mActualScale;
    }

    public final float getInvScale() {
        return mInvActualScale;
    }

    public final float getTextWrapScale() {
        return mTextWrapScale;
    }

    public final float getDefaultScale() {
        return mDefaultScale;
    }

    public final int getDocumentAnchorX() {
        return mAnchorX;
    }

    public final int getDocumentAnchorY() {
        return mAnchorY;
    }

    public final void clearDocumentAnchor() {
        mAnchorX = mAnchorY = 0;
    }

    public final void setZoomCenter(float x, float y) {
        mZoomCenterX = x;
        mZoomCenterY = y;
    }

    public final void setInitialScaleInPercent(int scaleInPercent) {
        mInitialScale = scaleInPercent * 0.01f;
    }

    public static final float getDefaultMinZoomScale() {
        return DEFAULT_MIN_ZOOM_SCALE;
    }

    public final float computeScaleWithLimits(float scale) {
        if (scale < mMinZoomScale) {
            scale = mMinZoomScale;
        } else if (scale > mMaxZoomScale) {
            scale = mMaxZoomScale;
        }
        return scale;
    }

    public final boolean isZoomScaleFixed() {
        return mMinZoomScale >= mMaxZoomScale;
    }

    public static final boolean exceedsMinScaleIncrement(float scaleA, float scaleB) {
        return Math.abs(scaleA - scaleB) >= MINIMUM_SCALE_INCREMENT;
    }

    public boolean willScaleTriggerZoom(float scale) {
        return exceedsMinScaleIncrement(scale, mActualScale);
    }

    public final boolean canZoomIn() {
        return mMaxZoomScale - mActualScale > MINIMUM_SCALE_INCREMENT;
    }

    public final boolean canZoomOut() {
        return mActualScale - mMinZoomScale > MINIMUM_SCALE_INCREMENT;
    }

    public boolean zoomIn() {
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
        mAnchorX = mWebView.viewToContentX((int) mZoomCenterX + mWebView.getScrollX());
        mAnchorY = mWebView.viewToContentY((int) mZoomCenterY + mWebView.getScrollY());
        return startZoomAnimation(mActualScale * zoomMultiplier, true);
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
            mWebView.onFixedLengthZoomAnimationStart();
            mWebView.invalidate();
            return true;
        } else {
            return false;
        }
    }

    /**
     * This method is called by the WebView's drawing code when a fixed length zoom
     * animation is occurring. Its purpose is to animate the zooming of the canvas
     * to the desired scale which was specified in startZoomAnimation(...).
     *
     * A fixed length animation begins when startZoomAnimation(...) is called and
     * continues until the ZOOM_ANIMATION_LENGTH time has elapsed. During that
     * interval each time the WebView draws it calls this function which is 
     * responsible for generating the animation.
     *
     * Additionally, the WebView can check to see if such an animation is currently
     * in progress by calling isFixedLengthAnimationInProgress().
     */
    public void animateZoom(Canvas canvas) {
        if (mZoomScale == 0) {
            Log.w(LOGTAG, "A WebView is attempting to perform a fixed length "
                    + "zoom animation when no zoom is in progress");
            return;
        }

        float zoomScale;
        int interval = (int) (SystemClock.uptimeMillis() - mZoomStart);
        if (interval < ZOOM_ANIMATION_LENGTH) {
            float ratio = (float) interval / ZOOM_ANIMATION_LENGTH;
            zoomScale = 1.0f / (mInvInitialZoomScale
                    + (mInvFinalZoomScale - mInvInitialZoomScale) * ratio);
            mWebView.invalidate();
        } else {
            zoomScale = mZoomScale;
            // set mZoomScale to be 0 as we have finished animating
            mZoomScale = 0;
            mWebView.onFixedLengthZoomAnimationEnd();
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

        canvas.translate(tx, ty);
        canvas.scale(zoomScale, zoomScale);
    }

    public boolean isZoomAnimating() {
        return isFixedLengthAnimationInProgress() || mPinchToZoomAnimating;
    }

    public boolean isFixedLengthAnimationInProgress() {
        return mZoomScale != 0;
    }

    public void refreshZoomScale(boolean reflowText) {
        setZoomScale(mActualScale, reflowText, true);
    }

    public void setZoomScale(float scale, boolean reflowText) {
        setZoomScale(scale, reflowText, false);
    }

    private void setZoomScale(float scale, boolean reflowText, boolean force) {
        final boolean isScaleLessThanMinZoom = scale < mMinZoomScale;
        scale = computeScaleWithLimits(scale);

        // determine whether or not we are in the zoom overview mode
        if (isScaleLessThanMinZoom && mMinZoomScale < mDefaultScale) {
            mInZoomOverview = true;
        } else {
            mInZoomOverview = !exceedsMinScaleIncrement(scale, getZoomOverviewScale());
        }

        if (reflowText) {
            mTextWrapScale = scale;
        }

        if (scale != mActualScale || force) {
            float oldScale = mActualScale;
            float oldInvScale = mInvActualScale;

            if (scale != mActualScale && !mPinchToZoomAnimating) {
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

    /**
     * The double tap gesture can result in different behaviors depending on the
     * content that is tapped.
     *
     * (1) PLUGINS: If the taps occur on a plugin then we maximize the plugin on
     * the screen. If the plugin is already maximized then zoom the user into
     * overview mode.
     *
     * (2) HTML/OTHER: If the taps occur outside a plugin then the following
     * heuristic is used.
     *   A. If the current scale is not the same as the text wrap scale and the
     *      layout algorithm specifies the use of NARROW_COLUMNS, then fit to
     *      column by reflowing the text.
     *   B. If the page is not in overview mode then change to overview mode.
     *   C. If the page is in overmode then change to the default scale.
     */
    public void handleDoubleTap(float lastTouchX, float lastTouchY) {
        WebSettings settings = mWebView.getSettings();
        if (settings == null || settings.getUseWideViewPort() == false) {
            return;
        }

        setZoomCenter(lastTouchX, lastTouchY);
        mAnchorX = mWebView.viewToContentX((int) lastTouchX + mWebView.getScrollX());
        mAnchorY = mWebView.viewToContentY((int) lastTouchY + mWebView.getScrollY());
        settings.setDoubleTapToastCount(0);

        // remove the zoom control after double tap
        dismissZoomPicker();

        /*
         * If the double tap was on a plugin then either zoom to maximize the
         * plugin on the screen or scale to overview mode.
         */
        ViewManager.ChildView plugin = mWebView.mViewManager.hitTest(mAnchorX, mAnchorY);
        if (plugin != null) {
            if (mWebView.isPluginFitOnScreen(plugin)) {
                zoomToOverview();
            } else {
                mWebView.centerFitRect(plugin.x, plugin.y, plugin.width, plugin.height);
            }
            return;
        }

        if (settings.getLayoutAlgorithm() == WebSettings.LayoutAlgorithm.NARROW_COLUMNS
                && willScaleTriggerZoom(mTextWrapScale)) {
            refreshZoomScale(true);
        } else if (!mInZoomOverview) {
            zoomToOverview();
        } else {
            zoomToDefaultLevel();
        }
    }

    private void setZoomOverviewWidth(int width) {
        mZoomOverviewWidth = width;
        mInvZoomOverviewWidth = 1.0f / width;
    }

    private float getZoomOverviewScale() {
        return mWebView.getViewWidth() * mInvZoomOverviewWidth;
    }

    public boolean isInZoomOverview() {
        return mInZoomOverview;
    }

    private void zoomToOverview() {
        if (!willScaleTriggerZoom(getZoomOverviewScale())) return;

        // Force the titlebar fully reveal in overview mode
        int scrollY = mWebView.getScrollY();
        if (scrollY < mWebView.getTitleHeight()) {
            mWebView.updateScrollCoordinates(mWebView.getScrollX(), 0);
        }
        startZoomAnimation(getZoomOverviewScale(), true);
    }

    private void zoomToDefaultLevel() {
        int left = mWebView.nativeGetBlockLeftEdge(mAnchorX, mAnchorY, mActualScale);
        if (left != WebView.NO_LEFTEDGE) {
            // add a 5pt padding to the left edge.
            int viewLeft = mWebView.contentToViewX(left < 5 ? 0 : (left - 5))
                    - mWebView.getScrollX();
            // Re-calculate the zoom center so that the new scroll x will be
            // on the left edge.
            if (viewLeft > 0) {
                mZoomCenterX = viewLeft * mDefaultScale / (mDefaultScale - mActualScale);
            } else {
                mWebView.scrollBy(viewLeft, 0);
                mZoomCenterX = 0;
            }
        }
        startZoomAnimation(mDefaultScale, true);
    }

    public void updateMultiTouchSupport(Context context) {
        // check the preconditions
        assert mWebView.getSettings() != null;

        WebSettings settings = mWebView.getSettings();
        mSupportMultiTouch = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH)
                && settings.supportZoom() && settings.getBuiltInZoomControls();
        if (mSupportMultiTouch && (mScaleDetector == null)) {
            mScaleDetector = new ScaleGestureDetector(context, new ScaleDetectorListener());
        } else if (!mSupportMultiTouch && (mScaleDetector != null)) {
            mScaleDetector = null;
        }
    }

    public boolean supportsMultiTouchZoom() {
        return mSupportMultiTouch;
    }

    /**
     * Notifies the caller that the ZoomManager is requesting that scale related
     * updates should not be sent to webkit. This can occur in cases where the
     * ZoomManager is performing an animation and does not want webkit to update
     * until the animation is complete.
     *
     * @return true if scale related updates should not be sent to webkit and
     *         false otherwise.
     */
    public boolean isPreventingWebkitUpdates() {
        // currently only animating a multi-touch zoom prevents updates, but
        // others can add their own conditions to this method if necessary.
        return mPinchToZoomAnimating;
    }

    public ScaleGestureDetector getMultiTouchGestureDetector() {
        return mScaleDetector;
    }

    private class ScaleDetectorListener implements ScaleGestureDetector.OnScaleGestureListener {

        public boolean onScaleBegin(ScaleGestureDetector detector) {
            dismissZoomPicker();
            mWebView.mViewManager.startZoom();
            mWebView.onPinchToZoomAnimationStart();
            return true;
        }

        public boolean onScale(ScaleGestureDetector detector) {
            float scale = Math.round(detector.getScaleFactor() * mActualScale * 100) * 0.01f;
            if (willScaleTriggerZoom(scale)) {
                mPinchToZoomAnimating = true;
                // limit the scale change per step
                if (scale > mActualScale) {
                    scale = Math.min(scale, mActualScale * 1.25f);
                } else {
                    scale = Math.max(scale, mActualScale * 0.8f);
                }
                setZoomCenter(detector.getFocusX(), detector.getFocusY());
                setZoomScale(scale, false);
                mWebView.invalidate();
                return true;
            }
            return false;
        }

        public void onScaleEnd(ScaleGestureDetector detector) {
            if (mPinchToZoomAnimating) {
                mPinchToZoomAnimating = false;
                mAnchorX = mWebView.viewToContentX((int) mZoomCenterX + mWebView.getScrollX());
                mAnchorY = mWebView.viewToContentY((int) mZoomCenterY + mWebView.getScrollY());
                // don't reflow when zoom in; when zoom out, do reflow if the
                // new scale is almost minimum scale;
                boolean reflowNow = !canZoomOut() || (mActualScale <= 0.8 * mTextWrapScale);
                // force zoom after mPreviewZoomOnly is set to false so that the
                // new view size will be passed to the WebKit
                refreshZoomScale(reflowNow);
                // call invalidate() to draw without zoom filter
                mWebView.invalidate();
            }

            mWebView.mViewManager.endZoom();
            mWebView.onPinchToZoomAnimationEnd(detector);
        }
    }

    public void onSizeChanged(int w, int h, int ow, int oh) {
        // reset zoom and anchor to the top left corner of the screen
        // unless we are already zooming
        if (!isFixedLengthAnimationInProgress()) {
            int visibleTitleHeight = mWebView.getVisibleTitleHeight();
            mZoomCenterX = 0;
            mZoomCenterY = visibleTitleHeight;
            mAnchorX = mWebView.viewToContentX(mWebView.getScrollX());
            mAnchorY = mWebView.viewToContentY(visibleTitleHeight + mWebView.getScrollY());
        }

        // update mMinZoomScale if the minimum zoom scale is not fixed
        if (!mMinZoomScaleFixed) {
            // when change from narrow screen to wide screen, the new viewWidth
            // can be wider than the old content width. We limit the minimum
            // scale to 1.0f. The proper minimum scale will be calculated when
            // the new picture shows up.
            mMinZoomScale = Math.min(1.0f, (float) mWebView.getViewWidth()
                    / (mWebView.drawHistory() ? mWebView.getHistoryPictureWidth()
                            : mZoomOverviewWidth));
            // limit the minZoomScale to the initialScale if it is set
            if (mInitialScale > 0 && mInitialScale < mMinZoomScale) {
                mMinZoomScale = mInitialScale;
            }
        }

        dismissZoomPicker();

        // onSizeChanged() is called during WebView layout. And any
        // requestLayout() is blocked during layout. As refreshZoomScale() will
        // cause its child View to reposition itself through ViewManager's
        // scaleAll(), we need to post a Runnable to ensure requestLayout().
        // Additionally, only update the text wrap scale if the width changed.
        mWebView.post(new PostScale(w != ow));
    }

    private class PostScale implements Runnable {
        final boolean mUpdateTextWrap;

        public PostScale(boolean updateTextWrap) {
            mUpdateTextWrap = updateTextWrap;
        }

        public void run() {
            if (mWebView.getWebViewCore() != null) {
                // we always force, in case our height changed, in which case we
                // still want to send the notification over to webkit.
                refreshZoomScale(mUpdateTextWrap);
                // update the zoom buttons as the scale can be changed
                updateZoomPicker();
            }
        }
    }

    public void updateZoomRange(WebViewCore.RestoreState restoreState,
            int viewWidth, int minPrefWidth) {
        if (restoreState.mMinScale == 0) {
            if (restoreState.mMobileSite) {
                if (minPrefWidth > Math.max(0, viewWidth)) {
                    mMinZoomScale = (float) viewWidth / minPrefWidth;
                    mMinZoomScaleFixed = false;
                } else {
                    mMinZoomScale = restoreState.mDefaultScale;
                    mMinZoomScaleFixed = true;
                }
            } else {
                mMinZoomScale = DEFAULT_MIN_ZOOM_SCALE;
                mMinZoomScaleFixed = false;
            }
        } else {
            mMinZoomScale = restoreState.mMinScale;
            mMinZoomScaleFixed = true;
        }
        if (restoreState.mMaxScale == 0) {
            mMaxZoomScale = DEFAULT_MAX_ZOOM_SCALE;
        } else {
            mMaxZoomScale = restoreState.mMaxScale;
        }
    }

    /**
     * Updates zoom values when Webkit produces a new picture. This method
     * should only be called from the UI thread's message handler.
     */
    public void onNewPicture(WebViewCore.DrawData drawData) {
        final int viewWidth = mWebView.getViewWidth();

        if (mWebView.getSettings().getUseWideViewPort()) {
            // limit mZoomOverviewWidth upper bound to
            // sMaxViewportWidth so that if the page doesn't behave
            // well, the WebView won't go insane. limit the lower
            // bound to match the default scale for mobile sites.
            setZoomOverviewWidth(Math.min(WebView.sMaxViewportWidth,
                    Math.max((int) (viewWidth * mInvDefaultScale),
                            Math.max(drawData.mMinPrefWidth, drawData.mViewPoint.x))));
        }

        final float zoomOverviewScale = getZoomOverviewScale();
        if (!mMinZoomScaleFixed) {
            mMinZoomScale = zoomOverviewScale;
        }
        // fit the content width to the current view. Ignore the rounding error case.
        if (!mWebView.drawHistory() && (mInZoomOverview || mInitialZoomOverview)
                && Math.abs((viewWidth * mInvActualScale) - mZoomOverviewWidth) > 1) {
            mInitialZoomOverview = false;
            setZoomScale(zoomOverviewScale, !willScaleTriggerZoom(mTextWrapScale));
        }
    }

    /**
     * Updates zoom values when Webkit restores a old picture. This method
     * should only be called from the UI thread's message handler.
     */
    public void restoreZoomState(WebViewCore.DrawData drawData) {
        // precondition check
        assert drawData != null;
        assert drawData.mRestoreState != null;
        assert mWebView.getSettings() != null;

        WebViewCore.RestoreState restoreState = drawData.mRestoreState;
        final Point viewSize = drawData.mViewPoint;
        updateZoomRange(restoreState, viewSize.x, drawData.mMinPrefWidth);

        if (!mWebView.drawHistory()) {
            final float scale;
            final boolean reflowText;

            if (mInitialScale > 0) {
                scale = mInitialScale;
                reflowText = exceedsMinScaleIncrement(mTextWrapScale, scale);
            } else if (restoreState.mViewScale > 0) {
                mTextWrapScale = restoreState.mTextWrapScale;
                scale = restoreState.mViewScale;
                reflowText = false;
            } else {
                WebSettings settings = mWebView.getSettings();
                if (settings.getUseWideViewPort() && settings.getLoadWithOverviewMode()) {
                    mInitialZoomOverview = true;
                    scale = (float) mWebView.getViewWidth() / WebView.DEFAULT_VIEWPORT_WIDTH;
                } else {
                    scale = restoreState.mTextWrapScale;
                }
                reflowText = exceedsMinScaleIncrement(mTextWrapScale, scale);
            }
            setZoomScale(scale, reflowText);

            // update the zoom buttons as the scale can be changed
            updateZoomPicker();
        }
    }

    public void saveZoomState(Bundle b) {
        b.putFloat("scale", mActualScale);
        b.putFloat("textwrapScale", mTextWrapScale);
        b.putBoolean("overview", mInZoomOverview);
    }

    public void restoreZoomState(Bundle b) {
        // as getWidth() / getHeight() of the view are not available yet, set up
        // mActualScale, so that when onSizeChanged() is called, the rest will
        // be set correctly
        mActualScale = b.getFloat("scale", 1.0f);
        mInvActualScale = 1 / mActualScale;
        mTextWrapScale = b.getFloat("textwrapScale", mActualScale);
        mInZoomOverview = b.getBoolean("overview");
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
