/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.carrierdefaultapp;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.slice.SlicePurchaseController;

import java.net.URL;
import java.util.Base64;

/**
 * Activity that launches when the user clicks on the performance boost notification.
 * This will open a {@link WebView} for the carrier website to allow the user to complete the
 * premium capability purchase.
 * The carrier website can get the requested premium capability using the JavaScript interface
 * method {@code DataBoostWebServiceFlow.getRequestedCapability()}.
 * If the purchase is successful, the carrier website shall notify the slice purchase application
 * using the JavaScript interface method
 * {@code DataBoostWebServiceFlow.notifyPurchaseSuccessful()}.
 * If the purchase was not successful, the carrier website shall notify the slice purchase
 * application using the JavaScript interface method
 * {@code DataBoostWebServiceFlow.notifyPurchaseFailed(code, reason)}, where {@code code} is the
 * {@link SlicePurchaseController.FailureCode} indicating the reason for failure and {@code reason}
 * is the human-readable reason for failure if the failure code is
 * {@link SlicePurchaseController#FAILURE_CODE_UNKNOWN}.
 * If either of these notification methods are not called, the purchase cannot be completed
 * successfully and the purchase request will eventually time out.
 */
public class SlicePurchaseActivity extends Activity {
    private static final String TAG = "SlicePurchaseActivity";

    private static final int CONTENTS_TYPE_UNSPECIFIED = 0;
    private static final int CONTENTS_TYPE_JSON = 1;
    private static final int CONTENTS_TYPE_XML = 2;

    @NonNull private WebView mWebView;
    @NonNull private Context mApplicationContext;
    @NonNull private Intent mIntent;
    @NonNull private URL mUrl;
    @TelephonyManager.PremiumCapability protected int mCapability;
    @Nullable private String mUserData;
    private int mContentsType;
    private boolean mIsUserTriggeredFinish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIntent = getIntent();
        int subId = mIntent.getIntExtra(SlicePurchaseController.EXTRA_SUB_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mCapability = mIntent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        String url = mIntent.getStringExtra(SlicePurchaseController.EXTRA_PURCHASE_URL);
        mUserData = mIntent.getStringExtra(SlicePurchaseController.EXTRA_USER_DATA);
        mApplicationContext = getApplicationContext();
        mIsUserTriggeredFinish = true;
        logd("onCreate: subId=" + subId + ", capability="
                + TelephonyManager.convertPremiumCapabilityToString(mCapability) + ", url=" + url);

        // Cancel performance boost notification
        SlicePurchaseBroadcastReceiver.cancelNotification(mApplicationContext, mCapability);

        // Verify purchase URL is valid
        String contentsType = mIntent.getStringExtra(SlicePurchaseController.EXTRA_CONTENTS_TYPE);
        mContentsType = CONTENTS_TYPE_UNSPECIFIED;
        if (!TextUtils.isEmpty(contentsType)) {
            if (contentsType.equals("json")) {
                mContentsType = CONTENTS_TYPE_JSON;
            } else if (contentsType.equals("xml")) {
                mContentsType = CONTENTS_TYPE_XML;
            }
        }
        mUrl = SlicePurchaseBroadcastReceiver.getPurchaseUrl(url, mUserData,
                mContentsType == CONTENTS_TYPE_UNSPECIFIED);
        if (mUrl == null) {
            String error = "Unable to create a purchase URL.";
            loge(error);
            Intent data = new Intent();
            data.putExtra(SlicePurchaseController.EXTRA_FAILURE_CODE,
                    SlicePurchaseController.FAILURE_CODE_CARRIER_URL_UNAVAILABLE);
            data.putExtra(SlicePurchaseController.EXTRA_FAILURE_REASON, error);
            SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(mApplicationContext,
                    mIntent, SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR, data);
            finishAndRemoveTask();
            return;
        }

        // Verify user data exists if contents type is specified
        if (mContentsType != CONTENTS_TYPE_UNSPECIFIED && TextUtils.isEmpty(mUserData)) {
            String error = "Contents type was specified but user data does not exist.";
            loge(error);
            Intent data = new Intent();
            data.putExtra(SlicePurchaseController.EXTRA_FAILURE_CODE,
                    SlicePurchaseController.FAILURE_CODE_NO_USER_DATA);
            data.putExtra(SlicePurchaseController.EXTRA_FAILURE_REASON, error);
            SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(mApplicationContext,
                    mIntent, SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR, data);
            finishAndRemoveTask();
            return;
        }

        // Verify intent is valid
        if (!SlicePurchaseBroadcastReceiver.isIntentValid(mIntent)) {
            loge("Not starting SlicePurchaseActivity with an invalid Intent: " + mIntent);
            SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                    mIntent, SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED);
            finishAndRemoveTask();
            return;
        }

        // Verify sub ID is valid
        if (subId != SubscriptionManager.getDefaultSubscriptionId()) {
            loge("Unable to start the slice purchase application on the non-default data "
                    + "subscription: " + subId);
            SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                    mIntent, SlicePurchaseController.EXTRA_INTENT_NOT_DEFAULT_DATA_SUBSCRIPTION);
            finishAndRemoveTask();
            return;
        }

        // Clear any cookies that might be persisted from previous sessions before loading WebView
        CookieManager.getInstance().removeAllCookies(value -> setupWebView());
    }

    protected void onPurchaseSuccessful() {
        logd("onPurchaseSuccessful: Carrier website indicated successfully purchased premium "
                + "capability " + TelephonyManager.convertPremiumCapabilityToString(mCapability));
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                mIntent, SlicePurchaseController.EXTRA_INTENT_SUCCESS);
        finishAndRemoveTask();
    }

    protected void onPurchaseFailed(@SlicePurchaseController.FailureCode int failureCode,
            @Nullable String failureReason) {
        logd("onPurchaseFailed: Carrier website indicated purchase failed for premium capability "
                + TelephonyManager.convertPremiumCapabilityToString(mCapability) + " with code: "
                + failureCode + " and reason: " + failureReason);
        Intent data = new Intent();
        data.putExtra(SlicePurchaseController.EXTRA_FAILURE_CODE, failureCode);
        data.putExtra(SlicePurchaseController.EXTRA_FAILURE_REASON, failureReason);
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(mApplicationContext,
                mIntent, SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR, data);
        finishAndRemoveTask();
    }

    protected void onDismissFlow() {
        logd("onDismissFlow: Dismiss flow called while purchasing premium capability "
                + TelephonyManager.convertPremiumCapabilityToString(mCapability));
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                mIntent, SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED);
        finishAndRemoveTask();
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        // Pressing back in the WebView will go to the previous page instead of closing
        //  the slice purchase application.
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (mIsUserTriggeredFinish) {
            logd("onDestroy: User canceled the purchase by closing the application.");
            SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                    mIntent, SlicePurchaseController.EXTRA_INTENT_CANCELED);
        }
        super.onDestroy();
    }

    @Override
    public void finishAndRemoveTask() {
        mIsUserTriggeredFinish = false;
        super.finishAndRemoveTask();
    }

    private void setupWebView() {
        // Create WebView
        mWebView = new WebView(this);
        mWebView.setWebViewClient(new WebViewClient());

        // Enable JavaScript for the carrier purchase website to send results back to
        //  the slice purchase application.
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.addJavascriptInterface(
                new DataBoostWebServiceFlow(this), "DataBoostWebServiceFlow");

        // Display WebView
        setContentView(mWebView);

        // Start the WebView
        startWebView(mWebView, mUrl.toString(), mContentsType, mUserData);
    }

    /**
     * Send the URL to the WebView as either a GET or POST request, based on the contents type:
     * <ul>
     *     <li>
     *         CONTENTS_TYPE_UNSPECIFIED:
     *         If the user data exists, append it to the purchase URL and load it as a GET request.
     *         If the user data does not exist, load just the purchase URL as a GET request.
     *     </li>
     *     <li>
     *         CONTENTS_TYPE_JSON or CONTENTS_TYPE_XML:
     *         The user data must exist. Send the JSON or XML formatted user data in a POST request.
     *         If the user data is encoded, it must be prefaced by {@code encodedValue=} and will be
     *         encoded in Base64. Decode the user data and send it in the POST request.
     *     </li>
     * </ul>
     * @param webView The WebView to start.
     * @param url The URL to start the WebView with.
     * @param contentsType The contents type of the userData.
     * @param userData The user data to send with the GET or POST request, if it exists.
     */
    @VisibleForTesting
    public static void startWebView(@NonNull WebView webView, @NonNull String url, int contentsType,
            @Nullable String userData) {
        if (contentsType == CONTENTS_TYPE_UNSPECIFIED) {
            logd("Starting WebView GET with url: " + url);
            webView.loadUrl(url);
        } else {
            byte[] data = userData.getBytes();
            String[] split = userData.split("encodedValue=");
            if (split.length > 1) {
                logd("Decoding encoded value: " + split[1]);
                data = Base64.getDecoder().decode(split[1]);
            }
            logd("Starting WebView POST with url: " + url + ", contentsType: " + contentsType
                    + ", data: " + new String(data));
            webView.postUrl(url, data);
        }
    }

    private static void logd(@NonNull String s) {
        Log.d(TAG, s);
    }

    private static void loge(@NonNull String s) {
        Log.e(TAG, s);
    }
}
