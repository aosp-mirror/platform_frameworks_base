/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;

import com.android.statementservice.retriever.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Receives {@link Intent#ACTION_INTENT_FILTER_NEEDS_VERIFICATION} broadcast and calls
 * {@link DirectStatementService} to verify the request. Calls
 * {@link PackageManager#verifyIntentFilter} to notify {@link PackageManager} the result of the
 * verification.
 *
 * This implementation of the API will send a HTTP request for each host specified in the query.
 * To avoid overwhelming the network at app install time, {@code MAX_HOSTS_PER_REQUEST} limits
 * the maximum number of hosts in a query. If a query contains more than
 * {@code MAX_HOSTS_PER_REQUEST} hosts, it will fail immediately without making any HTTP request
 * and call {@link PackageManager#verifyIntentFilter} with
 * {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}.
 */
public final class IntentFilterVerificationReceiver extends BroadcastReceiver {
    private static final String TAG = IntentFilterVerificationReceiver.class.getSimpleName();

    private static final Integer MAX_HOSTS_PER_REQUEST = 10;

    private static final String HANDLE_ALL_URLS_RELATION
            = "delegate_permission/common.handle_all_urls";

    private static final String ANDROID_ASSET_FORMAT = "{\"namespace\": \"android_app\", "
            + "\"package_name\": \"%s\", \"sha256_cert_fingerprints\": [\"%s\"]}";
    private static final String WEB_ASSET_FORMAT = "{\"namespace\": \"web\", \"site\": \"%s\"}";
    private static final Pattern ANDROID_PACKAGE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    private static final String TOO_MANY_HOSTS_FORMAT =
            "Request contains %d hosts which is more than the allowed %d.";

    private static void sendErrorToPackageManager(PackageManager packageManager,
            int verificationId) {
        packageManager.verifyIntentFilter(verificationId,
                PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
                Collections.<String>emptyList());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION.equals(action)) {
            Bundle inputExtras = intent.getExtras();
            if (inputExtras != null) {
                Intent serviceIntent = new Intent(context, DirectStatementService.class);
                serviceIntent.setAction(DirectStatementService.CHECK_ALL_ACTION);

                int verificationId = inputExtras.getInt(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID);
                String scheme = inputExtras.getString(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME);
                String hosts = inputExtras.getString(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS);
                String packageName = inputExtras.getString(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME);

                Bundle extras = new Bundle();
                extras.putString(DirectStatementService.EXTRA_RELATION, HANDLE_ALL_URLS_RELATION);

                String[] hostList = hosts.split(" ");
                if (hostList.length > MAX_HOSTS_PER_REQUEST) {
                    Log.w(TAG, String.format(TOO_MANY_HOSTS_FORMAT,
                            hostList.length, MAX_HOSTS_PER_REQUEST));
                    sendErrorToPackageManager(context.getPackageManager(), verificationId);
                    return;
                }

                ArrayList<String> finalHosts = new ArrayList<String>(hostList.length);
                try {
                    ArrayList<String> sourceAssets = new ArrayList<String>();
                    for (String host : hostList) {
                        // "*.example.tld" is validated via https://example.tld
                        if (host.startsWith("*.")) {
                            host = host.substring(2);
                        }
                        sourceAssets.add(createWebAssetString(scheme, host));
                        finalHosts.add(host);
                    }
                    extras.putStringArrayList(DirectStatementService.EXTRA_SOURCE_ASSET_DESCRIPTORS,
                            sourceAssets);
                } catch (MalformedURLException e) {
                    Log.w(TAG, "Error when processing input host: " + e.getMessage());
                    sendErrorToPackageManager(context.getPackageManager(), verificationId);
                    return;
                }
                try {
                    extras.putString(DirectStatementService.EXTRA_TARGET_ASSET_DESCRIPTOR,
                            createAndroidAssetString(context, packageName));
                } catch (NameNotFoundException e) {
                    Log.w(TAG, "Error when processing input Android package: " + e.getMessage());
                    sendErrorToPackageManager(context.getPackageManager(), verificationId);
                    return;
                }
                extras.putParcelable(DirectStatementService.EXTRA_RESULT_RECEIVER,
                        new IsAssociatedResultReceiver(
                                new Handler(), context.getPackageManager(), verificationId));

                // Required for CTS: log a few details of the validcation operation to be performed
                logValidationParametersForCTS(verificationId, scheme, finalHosts, packageName);

                serviceIntent.putExtras(extras);
                context.startService(serviceIntent);
            }
        } else {
            Log.w(TAG, "Intent action not supported: " + action);
        }
    }

    // CTS requirement: logging of the validation parameters in a specific format
    private static final String CTS_LOG_FORMAT =
            "Verifying IntentFilter. verificationId:%d scheme:\"%s\" hosts:\"%s\" package:\"%s\".";
    private void logValidationParametersForCTS(int verificationId, String scheme,
            ArrayList<String> finalHosts, String packageName) {
        String hostString = TextUtils.join(" ", finalHosts.toArray());
        Log.i(TAG, String.format(CTS_LOG_FORMAT, verificationId, scheme, hostString, packageName));
    }

    private String createAndroidAssetString(Context context, String packageName)
            throws NameNotFoundException {
        if (!ANDROID_PACKAGE_NAME_PATTERN.matcher(packageName).matches()) {
            throw new NameNotFoundException("Input package name is not valid.");
        }

        List<String> certFingerprints =
                Utils.getCertFingerprintsFromPackageManager(packageName, context);

        return String.format(ANDROID_ASSET_FORMAT, packageName,
                Utils.joinStrings("\", \"", certFingerprints));
    }

    private String createWebAssetString(String scheme, String host) throws MalformedURLException {
        if (!Patterns.DOMAIN_NAME.matcher(host).matches()) {
            throw new MalformedURLException("Input host is not valid.");
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new MalformedURLException("Input scheme is not valid.");
        }

        return String.format(WEB_ASSET_FORMAT, new URL(scheme, host, "").toString());
    }

    /**
     * Receives the result of {@code StatementService.CHECK_ACTION} from
     * {@link DirectStatementService} and passes it back to {@link PackageManager}.
     */
    private static class IsAssociatedResultReceiver extends ResultReceiver {

        private final int mVerificationId;
        private final PackageManager mPackageManager;

        public IsAssociatedResultReceiver(Handler handler, PackageManager packageManager,
                int verificationId) {
            super(handler);
            mVerificationId = verificationId;
            mPackageManager = packageManager;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == DirectStatementService.RESULT_SUCCESS) {
                if (resultData.getBoolean(DirectStatementService.IS_ASSOCIATED)) {
                    mPackageManager.verifyIntentFilter(mVerificationId,
                            PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS,
                            Collections.<String>emptyList());
                } else {
                    mPackageManager.verifyIntentFilter(mVerificationId,
                            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
                            resultData.getStringArrayList(DirectStatementService.FAILED_SOURCES));
                }
            } else {
                sendErrorToPackageManager(mPackageManager, mVerificationId);
            }
        }
    }
}
