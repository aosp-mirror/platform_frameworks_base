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

import android.test.AndroidTestCase;

public class ZoomManagerTest extends AndroidTestCase {

    private ZoomManager zoomManager;

    @Override
    public void setUp() {
        WebView webView = new WebView(this.getContext());
        WebViewClassic webViewClassic = WebViewClassic.fromWebView(webView);
        CallbackProxy callbackProxy = new CallbackProxy(this.getContext(), webViewClassic);
        zoomManager = new ZoomManager(webViewClassic, callbackProxy);

        zoomManager.init(1.00f);
    }

    public void testInit() {
        testInit(0.01f);
        testInit(1.00f);
        testInit(1.25f);
    }

    private void testInit(float density) {
        zoomManager.init(density);
        actualScaleTest(density);
        defaultScaleTest(density);
        assertEquals(zoomManager.getDefaultMaxZoomScale(), zoomManager.getMaxZoomScale());
        assertEquals(zoomManager.getDefaultMinZoomScale(), zoomManager.getMinZoomScale());
        assertEquals(density, zoomManager.getTextWrapScale());
    }

    public void testUpdateDefaultZoomDensity() {
        // test the basic case where the actual values are equal to the defaults
        testUpdateDefaultZoomDensity(0.01f);
        testUpdateDefaultZoomDensity(1.00f);
        testUpdateDefaultZoomDensity(1.25f);
    }

    private void testUpdateDefaultZoomDensity(float density) {
        zoomManager.updateDefaultZoomDensity(density);
        defaultScaleTest(density);
    }

    public void testUpdateDefaultZoomDensityWithSmallMinZoom() {
        // test the case where the minZoomScale has changed to be < the default
        float newDefaultScale = 1.50f;
        float minZoomScale = ZoomManager.DEFAULT_MIN_ZOOM_SCALE_FACTOR * newDefaultScale;
        WebViewCore.ViewState minViewState = new WebViewCore.ViewState();
        minViewState.mMinScale = minZoomScale - 0.1f;
        zoomManager.updateZoomRange(minViewState, 0, 0);
        zoomManager.updateDefaultZoomDensity(newDefaultScale);
        defaultScaleTest(newDefaultScale);
    }

    public void testUpdateDefaultZoomDensityWithLargeMinZoom() {
        // test the case where the minZoomScale has changed to be > the default
        float newDefaultScale = 1.50f;
        float minZoomScale = ZoomManager.DEFAULT_MIN_ZOOM_SCALE_FACTOR * newDefaultScale;
        WebViewCore.ViewState minViewState = new WebViewCore.ViewState();
        minViewState.mMinScale = minZoomScale + 0.1f;
        zoomManager.updateZoomRange(minViewState, 0, 0);
        zoomManager.updateDefaultZoomDensity(newDefaultScale);
        defaultScaleTest(newDefaultScale);
    }

    public void testUpdateDefaultZoomDensityWithSmallMaxZoom() {
        // test the case where the maxZoomScale has changed to be < the default
        float newDefaultScale = 1.50f;
        float maxZoomScale = ZoomManager.DEFAULT_MAX_ZOOM_SCALE_FACTOR * newDefaultScale;
        WebViewCore.ViewState maxViewState = new WebViewCore.ViewState();
        maxViewState.mMaxScale = maxZoomScale - 0.1f;
        zoomManager.updateZoomRange(maxViewState, 0, 0);
        zoomManager.updateDefaultZoomDensity(newDefaultScale);
        defaultScaleTest(newDefaultScale);
    }

    public void testUpdateDefaultZoomDensityWithLargeMaxZoom() {
        // test the case where the maxZoomScale has changed to be > the default
        float newDefaultScale = 1.50f;
        float maxZoomScale = ZoomManager.DEFAULT_MAX_ZOOM_SCALE_FACTOR * newDefaultScale;
        WebViewCore.ViewState maxViewState = new WebViewCore.ViewState();
        maxViewState.mMaxScale = maxZoomScale + 0.1f;
        zoomManager.updateZoomRange(maxViewState, 0, 0);
        zoomManager.updateDefaultZoomDensity(newDefaultScale);
        defaultScaleTest(newDefaultScale);
    }

    public void testComputeScaleWithLimits() {
        final float maxScale = zoomManager.getMaxZoomScale();
        final float minScale = zoomManager.getMinZoomScale();
        assertTrue(maxScale > minScale);
        assertEquals(maxScale, zoomManager.computeScaleWithLimits(maxScale));
        assertEquals(maxScale, zoomManager.computeScaleWithLimits(maxScale + .01f));
        assertEquals(minScale, zoomManager.computeScaleWithLimits(minScale));
        assertEquals(minScale, zoomManager.computeScaleWithLimits(minScale - .01f));
    }

    private void actualScaleTest(float actualScale) {
        assertEquals(actualScale, zoomManager.getScale());
        assertEquals(1 / actualScale, zoomManager.getInvScale());
    }

    private void defaultScaleTest(float defaultScale) {
        final float maxDefault = ZoomManager.DEFAULT_MAX_ZOOM_SCALE_FACTOR * defaultScale;
        final float minDefault = ZoomManager.DEFAULT_MIN_ZOOM_SCALE_FACTOR * defaultScale;
        assertEquals(defaultScale, zoomManager.getDefaultScale());
        assertEquals(1 / defaultScale, zoomManager.getInvDefaultScale());
        assertEquals(maxDefault, zoomManager.getDefaultMaxZoomScale());
        assertEquals(minDefault, zoomManager.getDefaultMinZoomScale());
    }
}
