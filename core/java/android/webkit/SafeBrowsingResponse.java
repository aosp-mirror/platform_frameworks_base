/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Used to indicate an action to take when hitting a malicious URL. Instances of this class are
 * created by the WebView and passed to {@link android.webkit.WebViewClient#onSafeBrowsingHit}. The
 * host application must call {@link #showInterstitial(boolean)}, {@link #proceed(boolean)}, or
 * {@link #backToSafety(boolean)} to set the WebView's response to the Safe Browsing hit.
 *
 * <p>
 * If reporting is enabled, all reports will be sent according to the privacy policy referenced by
 * {@link android.webkit.WebView#getSafeBrowsingPrivacyPolicyUrl()}.
 */
public abstract class SafeBrowsingResponse {

    /**
     * Display the default interstitial.
     *
     * @param allowReporting {@code true} if the interstitial should show a reporting checkbox.
     */
    public abstract void showInterstitial(boolean allowReporting);

    /**
     * Act as if the user clicked "visit this unsafe site."
     *
     * @param report {@code true} to enable Safe Browsing reporting.
     */
    public abstract void proceed(boolean report);

    /**
     * Act as if the user clicked "back to safety."
     *
     * @param report {@code true} to enable Safe Browsing reporting.
     */
    public abstract void backToSafety(boolean report);
}
