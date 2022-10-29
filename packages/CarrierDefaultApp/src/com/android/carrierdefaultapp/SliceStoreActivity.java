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
import android.content.Intent;
import android.os.Bundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.WebView;

import com.android.phone.slicestore.SliceStore;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Activity that launches when the user clicks on the network boost notification.
 */
public class SliceStoreActivity extends Activity {
    private static final String TAG = "SliceStoreActivity";

    private URL mUrl;
    private WebView mWebView;
    private int mPhoneId;
    private int mSubId;
    private @TelephonyManager.PremiumCapability int mCapability;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mPhoneId = intent.getIntExtra(SliceStore.EXTRA_PHONE_ID,
                SubscriptionManager.INVALID_PHONE_INDEX);
        mSubId = intent.getIntExtra(SliceStore.EXTRA_SUB_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mCapability = intent.getIntExtra(SliceStore.EXTRA_PREMIUM_CAPABILITY,
                SliceStore.PREMIUM_CAPABILITY_INVALID);
        mUrl = getUrl();
        logd("onCreate: mPhoneId=" + mPhoneId + ", mSubId=" + mSubId + ", mCapability="
                + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                + ", mUrl=" + mUrl);
        getApplicationContext().getSystemService(NotificationManager.class)
                .cancel(SliceStoreBroadcastReceiver.NETWORK_BOOST_NOTIFICATION_TAG, mCapability);
        if (!SliceStoreBroadcastReceiver.isIntentValid(intent)) {
            loge("Not starting SliceStoreActivity with an invalid Intent: " + intent);
            SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                    intent, SliceStore.EXTRA_INTENT_REQUEST_FAILED);
            finishAndRemoveTask();
            return;
        }
        if (mUrl == null) {
            loge("Unable to create a URL from carrier configs.");
            SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                    intent, SliceStore.EXTRA_INTENT_CARRIER_ERROR);
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

        SliceStoreBroadcastReceiver.updateSliceStoreActivity(mCapability, this);

        mWebView = new WebView(this);
        setContentView(mWebView);
        mWebView.loadUrl(mUrl.toString());
        // TODO(b/245882601): Get back response from WebView
    }

    @Override
    protected void onDestroy() {
        logd("onDestroy: User canceled the purchase by closing the application.");
        SliceStoreBroadcastReceiver.sendSliceStoreResponse(
                getIntent(), SliceStore.EXTRA_INTENT_CANCELED);
        SliceStoreBroadcastReceiver.removeSliceStoreActivity(mCapability);
        super.onDestroy();
    }

    private @Nullable URL getUrl() {
        String url = getApplicationContext().getSystemService(CarrierConfigManager.class)
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
