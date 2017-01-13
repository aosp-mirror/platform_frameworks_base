/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.net.ICaptivePortal;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import com.android.carrierdefaultapp.R;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.ArrayUtils;

import static android.net.CaptivePortal.APP_RETURN_DISMISSED;

/**
 * Activity that launches in response to the captive portal notification
 * @see com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION
 * This activity requests network connection if there is no available one, launches the
 * {@link com.android.captiveportallogin portalApp} and keeps track of the portal activation result.
 */
public class CaptivePortalLaunchActivity extends Activity {
    private static final String TAG = CaptivePortalLaunchActivity.class.getSimpleName();
    private static final boolean DBG = true;
    public static final int NETWORK_REQUEST_TIMEOUT_IN_MS = 5 * 1000;

    private ConnectivityManager mCm = null;
    private ConnectivityManager.NetworkCallback mCb = null;
    /* Progress dialogue when request network connection for captive portal */
    private AlertDialog mProgressDialog = null;
    /* Alert dialogue when network request is timeout */
    private AlertDialog mAlertDialog = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCm = ConnectivityManager.from(this);
        // Check network connection before loading portal
        Network network = getNetworkForCaptivePortal();
        NetworkInfo nwInfo = mCm.getNetworkInfo(network);
        if (nwInfo == null || !nwInfo.isConnected()) {
            if (DBG) logd("Network unavailable, request restricted connection");
            requestNetwork(getIntent());
        } else {
            launchCaptivePortal(getIntent(), network);
        }
    }

    // show progress dialog during network connecting
    private void showConnectingProgressDialog() {
        mProgressDialog = new ProgressDialog(getApplicationContext());
        mProgressDialog.setMessage(getString(R.string.progress_dialogue_network_connection));
        mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mProgressDialog.show();
    }

    // if network request is timeout, show alert dialog with two option: cancel & wait
    private void showConnectionTimeoutAlertDialog() {
        mAlertDialog = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AlertDialog))
                .setMessage(getString(R.string.alert_dialogue_network_timeout))
                .setTitle(getString(R.string.alert_dialogue_network_timeout_title))
                .setNegativeButton(getString(R.string.quit),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // cancel
                                dismissDialog(mAlertDialog);
                                finish();
                            }
                        })
                .setPositiveButton(getString(R.string.wait),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // wait, request network again
                                dismissDialog(mAlertDialog);
                                requestNetwork(getIntent());
                            }
                        })
                .create();
        mAlertDialog.show();
    }

    private void requestNetwork(final Intent intent) {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        mCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (DBG) logd("Network available: " + network);
                dismissDialog(mProgressDialog);
                mCm.bindProcessToNetwork(network);
                launchCaptivePortal(intent, network);
            }

            @Override
            public void onUnavailable() {
                if (DBG) logd("Network unavailable");
                dismissDialog(mProgressDialog);
                showConnectionTimeoutAlertDialog();
            }
        };
        showConnectingProgressDialog();
        mCm.requestNetwork(request, mCb, NETWORK_REQUEST_TIMEOUT_IN_MS);
    }

    private void releaseNetworkRequest() {
        logd("release Network Request");
        if (mCb != null) {
            mCm.unregisterNetworkCallback(mCb);
            mCb = null;
        }
    }

    private void dismissDialog(AlertDialog dialog) {
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private Network getNetworkForCaptivePortal() {
        Network[] info = mCm.getAllNetworks();
        if (!ArrayUtils.isEmpty(info)) {
            for (Network nw : info) {
                final NetworkCapabilities nc = mCm.getNetworkCapabilities(nw);
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return nw;
                }
            }
        }
        return null;
    }

    private void launchCaptivePortal(final Intent intent, Network network) {
        String redirectUrl = intent.getStringExtra(TelephonyIntents.EXTRA_REDIRECTION_URL_KEY);
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        if (TextUtils.isEmpty(redirectUrl) || !matchUrl(redirectUrl, subId)) {
            loge("Launch portal fails due to incorrect redirection URL: " +
                    Rlog.pii(TAG, redirectUrl));
            return;
        }
        final Intent portalIntent = new Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
        portalIntent.putExtra(ConnectivityManager.EXTRA_NETWORK, network);
        portalIntent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,
                new CaptivePortal(new ICaptivePortal.Stub() {
                    @Override
                    public void appResponse(int response) {
                        logd("portal response code: " + response);
                        releaseNetworkRequest();
                        if (response == APP_RETURN_DISMISSED) {
                            // Upon success http response code, trigger re-evaluation
                            CarrierActionUtils.applyCarrierAction(
                                    CarrierActionUtils.CARRIER_ACTION_ENABLE_RADIO, intent,
                                    getApplicationContext());
                            CarrierActionUtils.applyCarrierAction(
                                    CarrierActionUtils.CARRIER_ACTION_ENABLE_METERED_APNS, intent,
                                    getApplicationContext());
                            CarrierActionUtils.applyCarrierAction(
                                    CarrierActionUtils.CARRIER_ACTION_CANCEL_ALL_NOTIFICATIONS,
                                    intent, getApplicationContext());
                        }
                    }
                }));
        portalIntent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL, redirectUrl);
        portalIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (DBG) logd("launching portal");
        startActivity(portalIntent);
        finish();
    }

    // match configured redirection url
    private boolean matchUrl(String url, int subId) {
        CarrierConfigManager configManager = getApplicationContext()
                .getSystemService(CarrierConfigManager.class);
        String[] redirectURLs = configManager.getConfigForSubId(subId).getStringArray(
                CarrierConfigManager.KEY_CARRIER_DEFAULT_REDIRECTION_URL_STRING_ARRAY);
        if (ArrayUtils.isEmpty(redirectURLs)) {
            if (DBG) logd("match is unnecessary without any configured redirection url");
            return true;
        }
        for (String redirectURL : redirectURLs) {
            if (url.startsWith(redirectURL)) {
                return true;
            }
        }
        if (DBG) loge("no match found for configured redirection url");
        return false;
    }

    private static void logd(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.d(TAG, s);
    }
}
