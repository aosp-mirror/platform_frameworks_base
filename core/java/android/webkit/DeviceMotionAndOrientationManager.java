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

/**
 * This class is simply a container for the methods used to implement DeviceMotion and
 * DeviceOrientation, including the mock DeviceOrientationClient for use in LayoutTests.
 *
 * This could be part of WebViewCore, but have moved it to its own class to
 * avoid bloat there.
 */
final class DeviceMotionAndOrientationManager {
    private WebViewCore mWebViewCore;

    public DeviceMotionAndOrientationManager(WebViewCore webViewCore) {
        mWebViewCore = webViewCore;
    }

    /**
     * Sets that the Page for this WebViewCore should use a mock DeviceOrientation
     * client.
     */
    public void setUseMock() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        nativeSetUseMock(mWebViewCore);
    }

    /**
     * Set the position for the mock DeviceOrientation service for this WebViewCore.
     */
    public void setMockOrientation(boolean canProvideAlpha, double alpha, boolean canProvideBeta,
            double beta, boolean canProvideGamma, double gamma) {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        nativeSetMockOrientation(mWebViewCore, canProvideAlpha, alpha, canProvideBeta, beta,
                canProvideGamma, gamma);
    }

    // We only provide accelerationIncludingGravity.
    public void onMotionChange(Double x, Double y, Double z, double interval) {
        nativeOnMotionChange(mWebViewCore,
                x != null, x != null ? x.doubleValue() : 0.0,
                y != null, y != null ? y.doubleValue() : 0.0,
                z != null, z != null ? z.doubleValue() : 0.0,
                interval);
    }
    public void onOrientationChange(Double alpha, Double beta, Double gamma) {
        nativeOnOrientationChange(mWebViewCore,
                alpha != null, alpha != null ? alpha.doubleValue() : 0.0,
                beta != null, beta != null ? beta.doubleValue() : 0.0,
                gamma != null, gamma != null ? gamma.doubleValue() : 0.0);
    }

    // Native functions
    private static native void nativeSetUseMock(WebViewCore webViewCore);
    private static native void nativeSetMockOrientation(WebViewCore webViewCore,
            boolean canProvideAlpha, double alpha, boolean canProvideBeta, double beta,
            boolean canProvideGamma, double gamma);
    private static native void nativeOnMotionChange(WebViewCore webViewCore,
            boolean canProvideX, double x, boolean canProvideY, double y,
            boolean canProvideZ, double z, double interval);
    private static native void nativeOnOrientationChange(WebViewCore webViewCore,
            boolean canProvideAlpha, double alpha, boolean canProvideBeta, double beta,
            boolean canProvideGamma, double gamma);
}
