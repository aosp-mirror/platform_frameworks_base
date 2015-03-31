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

package com.android.verifier.intentfilter;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.Volley;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class IntentVerificationService extends Service {
    private static final String TAG = "IntentVerificationService";

    private static final String WELL_KNOWN_ASSOCIATIONS_JSON = "/.well-known/associations.json";
    private static final String DEFAULT_SCHEME = "https";

    private static final String JSON_KEY_TARGET = "target";
    private static final String JSON_KEY_NAMESPACE = "namespace";
    private static final String JSON_KEY_PACKAGE_NAME = "package_name";
    private static final String JSON_KEY_CERT_FINGERPRINTS = "sha256_cert_fingerprints";

    private static final String JSON_VAL_ANDROID_APP = "android_app";

    private static final char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F' };

    private ConnectivityManager mConnectivityManager;
    private Looper mHandlerLooper;
    private VerificationHandler mHandler;
    private RequestQueue mRequestQueue;

    private static class VerificationState {
        public final int verificationId;
        public final String hosts;
        public final String packageName;
        public final Set<String> fingerprints;
        public int responseCode = PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS;
        public int counter;
        public int numberOfHosts;
        public ArrayList<String> failedHosts = new ArrayList<>();

        private final Object lock = new Object();

        public VerificationState(int id, String h, String p, Set<String> fps) {
            verificationId = id;
            hosts = h;
            packageName = p;
            fingerprints = fps;
            numberOfHosts = hosts.split(" ").length;
        }
        public boolean setResponseCodeAndCheckMax(int code) {
            synchronized (lock) {
                if (code == PackageManager.INTENT_FILTER_VERIFICATION_FAILURE) {
                    responseCode = code;
                    counter++;
                } else if (code == PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS) {
                    counter++;
                }
                return (counter == numberOfHosts);
            }
        }

        public void addFailedHost(String host) {
            synchronized (failedHosts) {
                failedHosts.add(host);
            }
        }

        public ArrayList<String> getFailedHosts() {
            return failedHosts;
        }
    }

    private HashMap<Integer, VerificationState> mVerificationMap =
            new HashMap<Integer, VerificationState>();

    private class VerificationHandler extends Handler {
        private static final int MSG_STOP_SERVICE = 0;
        private static final int MSG_VERIFY_INTENT_START = 1;
        private static final int MSG_VERIFY_INTENT_DONE = 2;

        private static final long SHUTDOWN_DELAY_MILLIS = 8 * 1000;

        private final Context mContext;

        public VerificationHandler(Context context, Looper looper) {
            super(looper);

            mContext = context;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_VERIFY_INTENT_START:
                    final Intent intent = (Intent) msg.obj;
                    Bundle extras = intent.getExtras();
                    boolean immediate = false;

                    if (extras != null) {
                        immediate = doVerification(extras);
                    }

                    // There was no network, so we can stop soon
                    if (immediate) {
                        stopDelayed();
                    }
                    break;

                case MSG_VERIFY_INTENT_DONE:
                    VerificationState vs = (VerificationState) msg.obj;
                    processVerificationDone(mContext, vs);
                    clearVerificationState(vs);
                    break;

                case MSG_STOP_SERVICE:
                    stopSelf();
                    break;

                default:
                    Slog.i(TAG, "Unknown message posted " + msg.toString());
                    break;

            }
        }

        private void stopDelayed() {
            removeMessages(MSG_STOP_SERVICE);
            sendEmptyMessageDelayed(MSG_STOP_SERVICE, SHUTDOWN_DELAY_MILLIS);
        }
    }

    private VerificationState getVerificationState(int id, String hosts, String packageName,
            Set<String> fingerprints) {
        synchronized (mVerificationMap) {
            VerificationState vs = mVerificationMap.get(id);
            if (vs == null) {
                vs = new VerificationState(id, hosts, packageName, fingerprints);
            }
            return vs;
        }
    }

    private void clearVerificationState(VerificationState vs) {
        mVerificationMap.remove(vs);
    }

    private boolean doVerification(Bundle extras) {
        String scheme = extras.getString(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME);
        if (TextUtils.isEmpty(scheme)) {
            scheme = DEFAULT_SCHEME;
        }

        int verificationId = extras.getInt(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID);
        String hosts = extras.getString(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS);
        String packageName = extras.getString(
                PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME);

        Set<String> fingerprints = getFingerprints(packageName);

        Log.d(TAG, "Received IntentFilter verification broadcast with verificationId:" +
                verificationId + " hosts:'" + hosts + "' scheme:" + scheme);

        VerificationState vs = getVerificationState(verificationId, hosts, packageName,
                fingerprints);

        if (hasNetwork()) {
            sendNetworkVerifications(scheme, vs);
            return false;
        }

        // No network, so fail immediately
        sendFailureResponseIfNeeded(vs);

        return true;
    }

    private Set<String> getFingerprints(String packageName) {
        Context context = getApplicationContext();
        try {
            Signature[] signatures = context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES).signatures;
            if (signatures.length > 0) {
                HashSet<String> result = new HashSet<String>();
                for (Signature sig : signatures) {
                    String fingerprint = computeNormalizedSha256Fingerprint(sig.toByteArray());
                    result.add(fingerprint);
                }
                return result;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot get signatures for package name: " + packageName);
        }
        return Collections.EMPTY_SET;
    }

    private static String computeNormalizedSha256Fingerprint(byte[] signature) {
        MessageDigest digester;
        try {
            digester = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("No SHA-256 implementation found.");
        }
        digester.update(signature);
        return byteArrayToHexString(digester.digest());
    }

    private static String byteArrayToHexString(byte[] array) {
        if (array.length == 0) {
            return "";
        }
        char[] buf = new char[array.length * 3 - 1];

        int bufIndex = 0;
        for (int i = 0; i < array.length; i++) {
            byte b = array[i];
            if (i > 0) {
                buf[bufIndex++] = ':';
            }
            buf[bufIndex++] = HEX_DIGITS[(b >>> 4) & 0x0F];
            buf[bufIndex++] = HEX_DIGITS[b & 0x0F];
        }
        return new String(buf);
    }

    private static String getAssociationPath() {
        return WELL_KNOWN_ASSOCIATIONS_JSON;
    }

    private void sendNetworkVerifications(String scheme, final VerificationState vs) {
        final int verificationId = vs.verificationId;
        final String hosts = vs.hosts;

        String[] array = hosts.split(" ");
        for (final String host : array) {
            try {
                final URL url = new URL(scheme, host, getAssociationPath());
                final String urlStr = url.toString();
                Log.d(TAG, "Using verification URL: " + urlStr);
                IntentVerificationRequest req = new IntentVerificationRequest(urlStr,
                    new Response.Listener<JSONArray>() {
                        @Override
                        public void onResponse(JSONArray response) {
                            Log.d(TAG, "From: " + urlStr + " received response: "
                                    + response.toString());
                            handleResponse(vs, host, response);
                        }
                    }, new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Slog.d(TAG, "From: " + urlStr + " got error: " + error.getMessage()
                                    + (error.networkResponse != null ? " with status code: "
                                    + error.networkResponse.statusCode : ""));
                            handleError(vs, host);
                        }
                    }
                );
                mRequestQueue.add(req);
            } catch (MalformedURLException e) {
                Log.w(TAG, "Cannot send verificationId: " + verificationId + " to host: " + host);
            }
        }
    }

    private void handleError(VerificationState vs, String host) {
        vs.addFailedHost(host);
        sendFailureResponseIfNeeded(vs);
    }

    private void handleResponse(VerificationState vs, String host, JSONArray response) {
        try {
            if (response.length() == 0) {
                Log.d(TAG, "Domain response is empty!");
                handleError(vs, host);
                return;
            }

            JSONObject firstRelation = (JSONObject) response.get(0);
            if (firstRelation == null) {
                Log.d(TAG, "Domain response is should have a relation!");
                handleError(vs, host);
                return;
            }

            JSONObject target = (JSONObject) firstRelation.get(JSON_KEY_TARGET);
            if (target == null) {
                Log.d(TAG, "Domain response target is empty!");
                handleError(vs, host);
                return;
            }

            String nameSpace = target.getString(JSON_KEY_NAMESPACE);
            if (TextUtils.isEmpty(nameSpace) || !nameSpace.equals(JSON_VAL_ANDROID_APP)) {
                Log.d(TAG, "Domain response target name space is not valid: " + nameSpace);
                handleError(vs, host);
                return;
            }

            String packageName = target.getString(JSON_KEY_PACKAGE_NAME);
            JSONArray certFingerprints = target.getJSONArray(JSON_KEY_CERT_FINGERPRINTS);

            // Early exits is the JSON response is not correct for the package name or signature
            if (TextUtils.isEmpty(packageName)) {
                Log.d(TAG, "Domain response has empty package name!");
                handleError(vs, host);
                return;
            }
            if (certFingerprints.length() == 0) {
                Log.d(TAG, "Domain response has empty cert signature!");
                handleError(vs, host);
                return;
            }
            // Now do the real test on package name and signature
            if (!packageName.equalsIgnoreCase(vs.packageName)) {
                Log.d(TAG, "Domain response has package name mismatch!" + packageName +
                        " vs " + vs.packageName);
                handleError(vs, host);
                return;
            }
            final int count = certFingerprints.length();
            for (int i = 0; i < count; i++) {
                String fingerprint = certFingerprints.getString(i);
                if (!vs.fingerprints.contains(fingerprint)) {
                    Log.d(TAG, "Domain response has cert fingerprint mismatch! " +
                            "The domain fingerprint '" + fingerprint + "' is not from the App");
                    handleError(vs, host);
                    return;
                }
            }
            sendSuccessResponseIfNeeded(vs);
        } catch (JSONException e) {
            Log.d(TAG, "Domain response is not well formed", e);
            handleError(vs, host);
        }
    }

    private void sendSuccessResponseIfNeeded(VerificationState vs) {
        if (vs.setResponseCodeAndCheckMax(PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS)) {
            sendMessage(vs);
        }
    }

    private void sendFailureResponseIfNeeded(VerificationState vs) {
        if (vs.setResponseCodeAndCheckMax(PackageManager.INTENT_FILTER_VERIFICATION_FAILURE)) {
            sendMessage(vs);
        }
    }

    private void sendMessage(VerificationState vs) {
        Message msg = mHandler.obtainMessage(VerificationHandler.MSG_VERIFY_INTENT_DONE);
        msg.obj = vs;
        mHandler.sendMessage(msg);
    }

    private void processVerificationDone(Context context, VerificationState state) {
        int verificationId = state.verificationId;
        String hosts = state.hosts;
        int responseCode = state.responseCode;

        final PackageManager pm = context.getPackageManager();

        // Callback the PackageManager
        pm.verifyIntentFilter(verificationId, responseCode, state.getFailedHosts());
        Log.d(TAG, "IntentFilter with verificationId: " + verificationId + " and hosts: " +
                hosts + " got verification code: " + responseCode);
    }

    /**
     * We only connect to this service from the same process.
     */
    public class LocalBinder extends Binder {
        IntentVerificationService getService() { return IntentVerificationService.this; }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Slog.i(TAG, "Received start id " + startId + ": " + intent);

        final Message msg = mHandler.obtainMessage(VerificationHandler.MSG_VERIFY_INTENT_START);
        msg.obj = intent;
        mHandler.sendMessage(msg);

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Slog.d(TAG, "Starting up...");

        final HandlerThread handlerThread = new HandlerThread("IntentVerificationService");
        handlerThread.start();
        mHandlerLooper = handlerThread.getLooper();

        mHandler = new VerificationHandler(getApplicationContext(), mHandlerLooper);

        mRequestQueue = Volley.newRequestQueue(this);
        mRequestQueue.start();

        mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Slog.d(TAG, "Shutting down...");

        mHandlerLooper.quit();
        mRequestQueue.stop();
    }

    private boolean hasNetwork() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        return (info != null) && info.isConnected();
    }
}
