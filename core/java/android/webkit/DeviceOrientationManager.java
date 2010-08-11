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
 * This class is simply a container for the methods used to configure WebKit's
 * mock DeviceOrientationClient for use in LayoutTests.
 *
 * This could be part of WebViewCore, but have moved it to its own class to
 * avoid bloat there.
 * @hide
 */
public final class DeviceOrientationManager {
    private WebViewCore mWebViewCore;

    public DeviceOrientationManager(WebViewCore webViewCore) {
        mWebViewCore = webViewCore;
    }

    /**
     * Sets whether the Page for this WebViewCore should use a mock DeviceOrientation
     * client.
     */
    public void useMock() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        nativeUseMock(mWebViewCore);
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

    // Native functions
    private static native void nativeUseMock(WebViewCore webViewCore);
    private static native void nativeSetMockOrientation(WebViewCore webViewCore,
            boolean canProvideAlpha, double alpha, boolean canProvideBeta, double beta,
            boolean canProvideGamma, double gamma);
}
