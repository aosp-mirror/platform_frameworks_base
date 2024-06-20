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
import android.telephony.TelephonyManager;
import android.webkit.JavascriptInterface;

import com.android.phone.slice.SlicePurchaseController;

/**
 * Data boost web service flow interface allowing carrier websites to send responses back to the
 * slice purchase application using JavaScript.
 */
public class DataBoostWebServiceFlow {
    @NonNull SlicePurchaseActivity mActivity;

    public DataBoostWebServiceFlow(@NonNull SlicePurchaseActivity activity) {
        mActivity = activity;
    }

    /**
     * Interface method allowing the carrier website to get the premium capability
     * that was requested to purchase.
     *
     * This can be called using the JavaScript below:
     * <script type="text/javascript">
     *     function getRequestedCapability() {
     *         DataBoostWebServiceFlow.getRequestedCapability();
     *     }
     * </script>
     */
    @JavascriptInterface
    @TelephonyManager.PremiumCapability public int getRequestedCapability() {
        return mActivity.mCapability;
    }

    /**
     * Interface method allowing the carrier website to notify the slice purchase application of
     * a successful premium capability purchase and the duration for which the premium capability is
     * purchased.
     *
     * This can be called using the JavaScript below:
     * <script type="text/javascript">
     *     function notifyPurchaseSuccessful(duration_ms_long = 0) {
     *         DataBoostWebServiceFlow.notifyPurchaseSuccessful(duration_ms_long);
     *     }
     * </script>
     *
     * @param duration The duration for which the premium capability is purchased in milliseconds.
     *                 NOTE: The duration parameter is not used.
     */
    @JavascriptInterface
    public void notifyPurchaseSuccessful(long duration) {
        mActivity.onPurchaseSuccessful();
    }

    /**
     * Interface method allowing the carrier website to notify the slice purchase application of
     * a successful premium capability purchase.
     *
     * This can be called using the JavaScript below:
     * <script type="text/javascript">
     *     function notifyPurchaseSuccessful() {
     *         DataBoostWebServiceFlow.notifyPurchaseSuccessful();
     *     }
     * </script>
     */
    @JavascriptInterface
    public void notifyPurchaseSuccessful() {
        mActivity.onPurchaseSuccessful();
    }

    /**
     * Interface method allowing the carrier website to notify the slice purchase application of
     * a failed premium capability purchase.
     *
     * This can be called using the JavaScript below:
     * <script type="text/javascript">
     *     function notifyPurchaseFailed(failure_code = 0, failure_reason = "unknown") {
     *         DataBoostWebServiceFlow.notifyPurchaseFailed();
     *     }
     * </script>
     *
     * @param failureCode The failure code.
     * @param failureReason If the failure code is
     *                      {@link SlicePurchaseController#FAILURE_CODE_UNKNOWN},
     *                      the human-readable reason for failure.
     */
    @JavascriptInterface
    public void notifyPurchaseFailed(@SlicePurchaseController.FailureCode int failureCode,
            @Nullable String failureReason) {
        mActivity.onPurchaseFailed(failureCode, failureReason);
    }

    /**
     * Interface method allowing the carrier website to notify the slice purchase application that
     * the service flow ended prematurely. This can be due to user action, an error in the
     * web sheet logic, or an error on the network side.
     *
     * This can be called using the JavaScript below:
     * <script type="text/javascript">
     *     function dismissFlow() {
     *         DataBoostWebServiceFlow.dismissFlow();
     *     }
     * </script>
     */
    @JavascriptInterface
    public void dismissFlow() {
        mActivity.onDismissFlow();
    }
}
