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
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebView;

import com.android.phone.slice.SlicePurchaseController;

import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Activity that launches when the user clicks on the performance boost notification.
 * This will open a {@link WebView} for the carrier website to allow the user to complete the
 * premium capability purchase.
 * The carrier website can get the requested premium capability using the JavaScript interface
 * method {@code SlicePurchaseWebInterface.getRequestedCapability()}.
 * If the purchase is successful, the carrier website shall notify the slice purchase application
 * using the JavaScript interface method
 * {@code SlicePurchaseWebInterface.notifyPurchaseSuccessful(duration)}, where {@code duration} is
 * the optional duration of the performance boost.
 * If the purchase was not successful, the carrier website shall notify the slice purchase
 * application using the JavaScript interface method
 * {@code SlicePurchaseWebInterface.notifyPurchaseFailed(code, reason)}, where {@code code} is the
 * {@link SlicePurchaseController.FailureCode} indicating the reason for failure and {@code reason}
 * is the human-readable reason for failure if the failure code is
 * {@link SlicePurchaseController#FAILURE_CODE_UNKNOWN}.
 * If either of these notification methods are not called, the purchase cannot be completed
 * successfully and the purchase request will eventually time out.
 */
public class SlicePurchaseActivity extends Activity {
    private static final String TAG = "SlicePurchaseActivity";

    @NonNull private WebView mWebView;
    @NonNull private Context mApplicationContext;
    @NonNull private Intent mIntent;
    @NonNull private URL mUrl;
    @TelephonyManager.PremiumCapability protected int mCapability;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIntent = getIntent();
        int subId = mIntent.getIntExtra(SlicePurchaseController.EXTRA_SUB_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mCapability = mIntent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        String url = mIntent.getStringExtra(SlicePurchaseController.EXTRA_PURCHASE_URL);
        mApplicationContext = getApplicationContext();
        logd("onCreate: subId=" + subId + ", capability="
                + TelephonyManager.convertPremiumCapabilityToString(mCapability) + ", url=" + url);

        // Cancel performance boost notification
        SlicePurchaseBroadcastReceiver.cancelNotification(mApplicationContext, mCapability);

        // Verify purchase URL is valid
        mUrl = SlicePurchaseBroadcastReceiver.getPurchaseUrl(url);
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

        // Create and configure WebView
        setupWebView();
    }

    protected void onPurchaseSuccessful(long duration) {
        logd("onPurchaseSuccessful: Carrier website indicated successfully purchased premium "
                + "capability " + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                + (duration > 0 ? " for " + TimeUnit.MILLISECONDS.toMinutes(duration) + " minutes."
                : "."));
        Intent intent = new Intent();
        intent.putExtra(SlicePurchaseController.EXTRA_PURCHASE_DURATION, duration);
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponseWithData(mApplicationContext,
                mIntent, SlicePurchaseController.EXTRA_INTENT_SUCCESS, intent);
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
        logd("onDestroy: User canceled the purchase by closing the application.");
        SlicePurchaseBroadcastReceiver.sendSlicePurchaseAppResponse(
                mIntent, SlicePurchaseController.EXTRA_INTENT_CANCELED);
        super.onDestroy();
    }

    private void setupWebView() {
        // Create WebView
        mWebView = new WebView(this);

        // Enable JavaScript for the carrier purchase website to send results back to
        //  the slice purchase application.
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.addJavascriptInterface(
                new SlicePurchaseWebInterface(this), "SlicePurchaseWebInterface");

        // Display WebView
        setContentView(mWebView);

        // Load the URL
        mWebView.loadUrl(mUrl.toString());
    }

    private static void logd(@NonNull String s) {
        Log.d(TAG, s);
    }

    private static void loge(@NonNull String s) {
        Log.e(TAG, s);
    }
}
