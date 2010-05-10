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

import android.view.View;

class ZoomManager {

    static final String LOGTAG = "webviewZoom";

    private final WebView mWebView;

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

    public ZoomManager(WebView webView) {
        mWebView = webView;
    }

    public void init(float density) {
        DEFAULT_MAX_ZOOM_SCALE = 4.0f * density;
        DEFAULT_MIN_ZOOM_SCALE = 0.25f * density;
        mMaxZoomScale = DEFAULT_MAX_ZOOM_SCALE;
        mMinZoomScale = DEFAULT_MIN_ZOOM_SCALE;
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
