/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.google.android.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.http.SslError;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.util.Locale;

/**
 * Helper to display Google web content, and fallback on a static message if the
 * web content is unreachable. For example, this can be used to display
 * "Legal terms".
 * <p>
 * The typical usage pattern is to have two Gservices settings defined:
 * <ul>
 * <li>A secure URL that will be displayed on the device. This should be HTTPS
 * so hotspots won't intercept it giving us a false positive that the page
 * loaded successfully.
 * <li>A pretty human-readable URL that will be displayed to the user in case we
 * cannot reach the above URL.
 * </ul>
 * <p>
 * The typical call sequence is {@link #setUrlsFromGservices(String, String)},
 * {@link #setUnsuccessfulMessage(String)}, and {@link #loadUrl()}. At some
 * point, you'll want to display the layout via {@link #getLayout()}.
 */
public class GoogleWebContentHelper {
    
    private Context mContext;
    
    private String mSecureUrl;
    private String mPrettyUrl;

    private String mUnsuccessfulMessage;
    
    private ViewGroup mLayout;
    private WebView mWebView;
    private View mProgressBar;
    private TextView mTextView;
    
    private boolean mReceivedResponse;
    
    public GoogleWebContentHelper(Context context) {
        mContext = context;
    }
    
    /**
     * Fetches the URLs from Gservices.
     * 
     * @param secureSetting The setting key whose value contains the HTTPS URL.
     * @param prettySetting The setting key whose value contains the pretty URL.
     * @return This {@link GoogleWebContentHelper} so methods can be chained.
     */
    public GoogleWebContentHelper setUrlsFromGservices(String secureSetting, String prettySetting) {
        ContentResolver contentResolver = mContext.getContentResolver();
        mSecureUrl = fillUrl(Settings.Gservices.getString(contentResolver, secureSetting),
                mContext);
        mPrettyUrl = fillUrl(Settings.Gservices.getString(contentResolver, prettySetting), 
                mContext);
        return this;
    }
    
    /**
     * Fetch directly from provided urls.
     * 
     * @param secureUrl The HTTPS URL.
     * @param prettyUrl The pretty URL.
     * @return This {@link GoogleWebContentHelper} so methods can be chained.
     */
    public GoogleWebContentHelper setUrls(String secureUrl, String prettyUrl) {
        mSecureUrl = fillUrl(secureUrl, mContext);
        mPrettyUrl = fillUrl(prettyUrl, mContext);
        return this;
    }
    

    /**
     * Sets the message that will be shown if we are unable to load the page.
     * <p>
     * This should be called after {@link #setUrlsFromGservices(String, String)}
     * .
     * 
     * @param message The message to load. The first argument, according to
     *        {@link java.util.Formatter}, will be substituted with the pretty
     *        URL.
     * @return This {@link GoogleWebContentHelper} so methods can be chained.
     */
    public GoogleWebContentHelper setUnsuccessfulMessage(String message) {
        Locale locale = mContext.getResources().getConfiguration().locale;
        mUnsuccessfulMessage = String.format(locale, message, mPrettyUrl);
        return this;
    }

    /**
     * Begins loading the secure URL.
     * 
     * @return This {@link GoogleWebContentHelper} so methods can be chained.
     */
    public GoogleWebContentHelper loadUrl() {
        ensureViews();
        mWebView.loadUrl(mSecureUrl);
        return this;
    }
    
    /**
     * Helper to handle the back key. Returns true if the back key was handled, 
     * otherwise returns false.
     * @param event the key event sent to {@link Activity#dispatchKeyEvent()}
     */
    public boolean handleKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK 
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the layout containing the web view, progress bar, and text view.
     * This class takes care of setting each one's visibility based on current
     * state.
     * 
     * @return The layout you should display.
     */
    public ViewGroup getLayout() {
        ensureViews();
        return mLayout;
    }

    private synchronized void ensureViews() {
        if (mLayout == null) {
            initializeViews();
        }
    }

    /**
     * Fills the URL with the locale.
     * 
     * @param url The URL in Formatter style for the extra info to be filled in.
     * @return The filled URL.
     */
    private static String fillUrl(String url, Context context) {
        
        if (TextUtils.isEmpty(url)) {
            return "";
        }

        /* We add another layer of indirection here to allow mcc's to fill
         * in Locales for TOS.  TODO - REMOVE when needed locales supported
         * natively (when not shipping devices to country X without support
         * for their locale).
         */
        String localeReplacement = context.
                getString(com.android.internal.R.string.locale_replacement);
        if (localeReplacement != null && localeReplacement.length() != 0) {
            url = String.format(url, localeReplacement);
        }

        Locale locale = Locale.getDefault();
        String tmp = locale.getLanguage() + "_" + locale.getCountry().toLowerCase();
        return String.format(url, tmp);
    }
    
    private void initializeViews() {

        LayoutInflater inflater =
                (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        
        mLayout = (ViewGroup) inflater.inflate(
                com.android.internal.R.layout.google_web_content_helper_layout, null);

        mWebView = (WebView) mLayout.findViewById(com.android.internal.R.id.web);
        mWebView.setWebViewClient(new MyWebViewClient());
        WebSettings settings = mWebView.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        
        mProgressBar = mLayout.findViewById(com.android.internal.R.id.progress);
        TextView message = (TextView) mProgressBar.findViewById(com.android.internal.R.id.message);
        message.setText(com.android.internal.R.string.googlewebcontenthelper_loading);
        
        mTextView = (TextView) mLayout.findViewById(com.android.internal.R.id.text);
        mTextView.setText(mUnsuccessfulMessage);
    }

    private synchronized void handleWebViewCompletion(boolean success) {
        
        if (mReceivedResponse) {
            return;
        } else {
            mReceivedResponse = true;
        }
        
        // In both cases, remove the progress bar
        ((ViewGroup) mProgressBar.getParent()).removeView(mProgressBar);

        // Remove the view that isn't relevant
        View goneView = success ? mTextView : mWebView;
        ((ViewGroup) goneView.getParent()).removeView(goneView);

        // Show the next view, which depends on success
        View visibleView = success ? mWebView : mTextView;
        visibleView.setVisibility(View.VISIBLE);
    }
    
    private class MyWebViewClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            handleWebViewCompletion(true);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
            handleWebViewCompletion(false);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view,
                HttpAuthHandler handler, String host, String realm) {
            handleWebViewCompletion(false);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                SslError error) {
            handleWebViewCompletion(false);
        }

        @Override
        public void onTooManyRedirects(WebView view, Message cancelMsg,
                Message continueMsg) {
            handleWebViewCompletion(false);
        }
        
    }
    
}
