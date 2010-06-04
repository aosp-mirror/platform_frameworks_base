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
    private float mDefaultScale;
    private float mInvDefaultScale;

    private static float MINIMUM_SCALE_INCREMENT = 0.01f;

    // the current computed zoom scale and its inverse.
    float mActualScale;
    float mInvActualScale;
    
    /*
     * The initial scale for the WebView. 0 means default. If initial scale is
     * greater than 0 the WebView starts with this value as its initial scale. The
     * value is converted from an integer percentage so it is guarenteed to have
     * no more than 2 significant digits after the decimal.  This restriction
     * allows us to convert the scale back to the original percentage by simply
     * multiplying the value by 100.
     */
    private float mInitialScale;

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

    // whether support multi-touch
    private boolean mSupportMultiTouch;

    // use the framework's ScaleGestureDetector to handle multi-touch
    private ScaleGestureDetector mScaleDetector;

    private boolean mPinchToZoomAnimating = false;

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

    public float getDefaultScale() {
        return mDefaultScale;
    }

    public void setZoomCenter(float x, float y) {
        mZoomCenterX = x;
        mZoomCenterY = y;
    }

    public void setInitialScaleInPercent(int scaleInPercent) {
        mInitialScale = scaleInPercent * 0.01f;
    }

    public static final boolean exceedsMinScaleIncrement(float scaleA, float scaleB) {
        return Math.abs(scaleA - scaleB) >= MINIMUM_SCALE_INCREMENT;
    }

    public boolean willScaleTriggerZoom(float scale) {
        return exceedsMinScaleIncrement(scale, mActualScale);
    }

    public boolean canZoomIn() {
        return mMaxZoomScale - mActualScale > MINIMUM_SCALE_INCREMENT;
    }

    public boolean canZoomOut() {
        return mActualScale - mMinZoomScale > MINIMUM_SCALE_INCREMENT;
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
            // reset the zoom overview mode so that the page won't auto grow
            mInZoomOverview = false;
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
                mWebView.setViewSizeAnchor(mWebView.viewToContentX((int) mZoomCenterX
                        + mWebView.getScrollX()), mWebView.viewToContentY((int) mZoomCenterY
                        + mWebView.getScrollY()));
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
            int anchorX = mWebView.viewToContentX(mWebView.getScrollX());
            int anchorY = mWebView.viewToContentY(visibleTitleHeight + mWebView.getScrollY());
            mWebView.setViewSizeAnchor(anchorX, anchorY);

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
            int viewWidth, int minPrefWidth, boolean updateZoomOverview) {
        if (restoreState.mMinScale == 0) {
            if (restoreState.mMobileSite) {
                if (minPrefWidth > Math.max(0, viewWidth)) {
                    mMinZoomScale = (float) viewWidth / minPrefWidth;
                    mMinZoomScaleFixed = false;
                    if (updateZoomOverview) {
                        WebSettings settings = mWebView.getSettings();
                        mInZoomOverview = settings.getUseWideViewPort() &&
                                settings.getLoadWithOverviewMode();
                    }
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
            mZoomOverviewWidth = Math.min(WebView.sMaxViewportWidth,
                    Math.max((int) (viewWidth * mInvDefaultScale),
                            Math.max(drawData.mMinPrefWidth,
                                    drawData.mViewPoint.x)));
        }
        if (!mMinZoomScaleFixed) {
            mMinZoomScale = (float) viewWidth / mZoomOverviewWidth;
        }
        if (!mWebView.drawHistory() && mInZoomOverview) {
            // fit the content width to the current view. Ignore
            // the rounding error case.
            if (Math.abs((viewWidth * mInvActualScale) - mZoomOverviewWidth) > 1) {
                setZoomScale((float) viewWidth / mZoomOverviewWidth,
                        !willScaleTriggerZoom(mTextWrapScale));
            }
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
        updateZoomRange(restoreState, viewSize.x, drawData.mMinPrefWidth, true);

        if (!mWebView.drawHistory()) {
            mInZoomOverview = false;

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
                mInZoomOverview = settings.getUseWideViewPort() &&
                        settings.getLoadWithOverviewMode();
                if (mInZoomOverview) {
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
