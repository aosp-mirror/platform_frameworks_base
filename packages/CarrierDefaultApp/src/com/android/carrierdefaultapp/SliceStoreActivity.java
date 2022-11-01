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
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebView;

import com.android.phone.slicestore.SliceStore;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Activity that launches when the user clicks on the network boost notification.
 * This will open a {@link WebView} for the carrier website to allow the user to complete the
 * premium capability purchase.
 * The carrier website can get the requested premium capability using the JavaScript interface
 * method {@code SliceStoreWebInterface.getRequestedCapability()}.
 * If the purchase is successful, the carrier website shall notify SliceStore using the JavaScript
 * interface method {@code SliceStoreWebInterface.notifyPurchaseSuccessful(duration)}, where
 * {@code duration} is the duration of the network boost.
 * If the purchase was not successful, the carrier website shall notify SliceStore using the
 * JavaScript interface method {@code SliceStoreWebInterface.notifyPurchaseFailed()}.
 * If either of these notification methods are not called, the purchase cannot be completed
 * successfully and the purchase request will eventually time out.
 */
public class SliceStoreActivity extends Activity {
    private static final String TAG = "SliceStoreActivity";

    private @NonNull WebView mWebView;
    private @NonNull Context mApplicationContext;
    private int mSubId;
    @TelephonyManager.PremiumCapability protected int mCapability;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mSubId = intent.getIntExtra(SliceStore.EXTRA_SUB_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mCapability = intent.getIntExtra(SliceStore.EXTRA_PREMIUM_CAPABILITY,
                SliceStore.PREMIUM_CAPABILITY_INVALID);
        mApplicationContext = getApplicationContext();
        URL url = getUrl();
        logd("onCreate: subId=" + mSubId + ", capability="
                + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                + ", url=" + url);

        // Cancel network boost notification
        mApplicationContext.getSystemService(NotificationManager.class)
                .cancel(SliceStoreBroadcastReceiver.NETWORK_BOOST_NOTIFICATION_TAG, mCapability);

        // Verify intent and values are valid
        if (!SliceStoreBroadcastReceiver.isIntentValid(intent)) {
            loge("Not starting SliceStoreActivity with an invalid Intent: " + intent);
            SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                    intent, SliceStore.EXTRA_INTENT_REQUEST_FAILED);
            finishAndRemoveTask();
            return;
        }
        if (url == null) {
            String error = "Unable to create a URL from carrier configs.";
            loge(error);
            Intent data = new Intent();
            data.putExtra(SliceStore.EXTRA_FAILURE_CODE,
                    SliceStore.FAILURE_CODE_CARRIER_URL_UNAVAILABLE);
            data.putExtra(SliceStore.EXTRA_FAILURE_REASON, error);
            SliceStoreBroadcastReceiver.sendSliceStoreResponseWithData(
                    mApplicationContext, getIntent(), SliceStore.EXTRA_INTENT_CARRIER_ERROR, data);
            finishAndRemoveTask();
            return;
        }
        if (mSubId != SubscriptionManager.getDefaultSubscriptionId()) {
            loge("Unable to start SliceStore on the non-default data subscription: " + mSubId);
            SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                    intent, SliceStore.EXTRA_INTENT_NOT_DEFAULT_DATA);
            finishAndRemoveTask();
            return;
        }

        // Create a reference to this activity in SliceStoreBroadcastReceiver
        SliceStoreBroadcastReceiver.updateSliceStoreActivity(mCapability, this);

        // Create and configure WebView
        mWebView = new WebView(this);
        // Enable JavaScript for the carrier purchase website to send results back to SliceStore
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.addJavascriptInterface(new SliceStoreWebInterface(this), "SliceStoreWebInterface");

        // Display WebView
        setContentView(mWebView);
        mWebView.loadUrl(url.toString());
    }

    protected void onPurchaseSuccessful(long duration) {
        logd("onPurchaseSuccessful: Carrier website indicated successfully purchased premium "
                + "capability " + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                + " for " + TimeUnit.MILLISECONDS.toMinutes(duration) + " minutes.");
        Intent intent = new Intent();
        intent.putExtra(SliceStore.EXTRA_PURCHASE_DURATION, duration);
        SliceStoreBroadcastReceiver.sendSliceStoreResponseWithData(
                mApplicationContext, getIntent(), SliceStore.EXTRA_INTENT_SUCCESS, intent);
        finishAndRemoveTask();
    }

    protected void onPurchaseFailed(@SliceStore.FailureCode int failureCode,
            @Nullable String failureReason) {
        logd("onPurchaseFailed: Carrier website indicated purchase failed for premium capability "
                + TelephonyManager.convertPremiumCapabilityToString(mCapability) + " with code: "
                + SliceStore.convertFailureCodeToString(failureCode) + " and reason: "
                + failureReason);
        Intent data = new Intent();
        data.putExtra(SliceStore.EXTRA_FAILURE_CODE, failureCode);
        data.putExtra(SliceStore.EXTRA_FAILURE_REASON, failureReason);
        SliceStoreBroadcastReceiver.sendSliceStoreResponseWithData(
                mApplicationContext, getIntent(), SliceStore.EXTRA_INTENT_CARRIER_ERROR, data);
        finishAndRemoveTask();
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        // Pressing back in the WebView will go to the previous page instead of closing SliceStore.
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        logd("onDestroy: User canceled the purchase by closing the application.");
        SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                getIntent(), SliceStore.EXTRA_INTENT_CANCELED);
        SliceStoreBroadcastReceiver.removeSliceStoreActivity(mCapability);
        super.onDestroy();
    }

    @Nullable private URL getUrl() {
        String url = mApplicationContext.getSystemService(CarrierConfigManager.class)
                .getConfigForSubId(mSubId).getString(
                        CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING);
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            loge("Invalid URL: " + url);
        }
        return null;
    }

    private static void logd(@NonNull String s) {
        Log.d(TAG, s);
    }

    private static void loge(@NonNull String s) {
        Log.e(TAG, s);
    }
}
