/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.android.internal.R;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;

/**
 * This class is a proxy class for handling WebCore -> UI thread messaging. All
 * the callback functions are called from the WebCore thread and messages are
 * posted to the UI thread for the actual client callback.
 */
/*
 * This class is created in the UI thread so its handler and any private classes
 * that extend Handler will operate in the UI thread.
 */
class CallbackProxy extends Handler {
    // Logging tag
    private static final String LOGTAG = "CallbackProxy";
    // Instance of WebViewClient that is the client callback.
    private volatile WebViewClient mWebViewClient;
    // Instance of WebChromeClient for handling all chrome functions.
    private volatile WebChromeClient mWebChromeClient;
    // Instance of WebView for handling UI requests.
    private final WebView mWebView;
    // Client registered callback listener for download events
    private volatile DownloadListener mDownloadListener;
    // Keep track of multiple progress updates.
    private boolean mProgressUpdatePending;
    // Keep track of the last progress amount.
    private volatile int mLatestProgress;
    // Back/Forward list
    private final WebBackForwardList mBackForwardList;
    // Used to call startActivity during url override.
    private final Context mContext;

    // Message Ids
    private static final int PAGE_STARTED         = 100;
    private static final int RECEIVED_ICON        = 101;
    private static final int RECEIVED_TITLE       = 102;
    private static final int OVERRIDE_URL         = 103;
    private static final int AUTH_REQUEST         = 104;
    private static final int SSL_ERROR            = 105;
    private static final int PROGRESS             = 106;
    private static final int UPDATE_VISITED       = 107;
    private static final int LOAD_RESOURCE        = 108;
    private static final int CREATE_WINDOW        = 109;
    private static final int CLOSE_WINDOW         = 110;
    private static final int SAVE_PASSWORD        = 111;
    private static final int JS_ALERT             = 112;
    private static final int JS_CONFIRM           = 113;
    private static final int JS_PROMPT            = 114;
    private static final int JS_UNLOAD            = 115;
    private static final int ASYNC_KEYEVENTS      = 116;
    private static final int TOO_MANY_REDIRECTS   = 117;
    private static final int DOWNLOAD_FILE        = 118;
    private static final int REPORT_ERROR         = 119;
    private static final int RESEND_POST_DATA     = 120;
    private static final int PAGE_FINISHED        = 121;
    private static final int REQUEST_FOCUS        = 122;
    private static final int SCALE_CHANGED        = 123;
    private static final int RECEIVED_CERTIFICATE = 124;
    private static final int SWITCH_OUT_HISTORY   = 125;

    // Message triggered by the client to resume execution
    private static final int NOTIFY               = 200;

    // Result transportation object for returning results across thread
    // boundaries.
    private class ResultTransport<E> {
        // Private result object
        private E mResult;

        public synchronized void setResult(E result) {
            mResult = result;
        }

        public synchronized E getResult() {
            return mResult;
        }
    }

    /**
     * Construct a new CallbackProxy.
     */
    public CallbackProxy(Context context, WebView w) {
        // Used to start a default activity.
        mContext = context;
        mWebView = w;
        mBackForwardList = new WebBackForwardList();
    }

    /**
     * Set the WebViewClient.
     * @param client An implementation of WebViewClient.
     */
    public void setWebViewClient(WebViewClient client) {
        mWebViewClient = client;
    }

    /**
     * Set the WebChromeClient.
     * @param client An implementation of WebChromeClient.
     */
    public void setWebChromeClient(WebChromeClient client) {
        mWebChromeClient = client;
    }

    /**
     * Set the client DownloadListener.
     * @param client An implementation of DownloadListener.
     */
    public void setDownloadListener(DownloadListener client) {
        mDownloadListener = client;
    }

    /**
     * Get the Back/Forward list to return to the user or to update the cached
     * history list.
     */
    public WebBackForwardList getBackForwardList() {
        return mBackForwardList;
    }

    /**
     * Called by the UI side.  Calling overrideUrlLoading from the WebCore
     * side will post a message to call this method.
     */
    public boolean uiOverrideUrlLoading(String overrideUrl) {
        if (overrideUrl == null || overrideUrl.length() == 0) {
            return false;
        }
        boolean override = false;
        if (mWebViewClient != null) {
            override = mWebViewClient.shouldOverrideUrlLoading(mWebView,
                    overrideUrl);
        } else {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(overrideUrl));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                mContext.startActivity(intent);
                override = true;
            } catch (ActivityNotFoundException ex) {
                // If no application can handle the URL, assume that the
                // browser can handle it.
            }
        }
        return override;
    }

    /**
     * Called by UI side.
     */
    public boolean uiOverrideKeyEvent(KeyEvent event) {
        if (mWebViewClient != null) {
            return mWebViewClient.shouldOverrideKeyEvent(mWebView, event);
        }
        return false;
    }

    @Override
    public void handleMessage(Message msg) {
        // We don't have to do synchronization because this function operates
        // in the UI thread. The WebViewClient and WebChromeClient functions
        // that check for a non-null callback are ok because java ensures atomic
        // 32-bit reads and writes.
        switch (msg.what) {
            case PAGE_STARTED:
                if (mWebViewClient != null) {
                    mWebViewClient.onPageStarted(mWebView,
                            msg.getData().getString("url"),
                            (Bitmap) msg.obj);
                }
                break;

            case PAGE_FINISHED:
                if (mWebViewClient != null) {
                    mWebViewClient.onPageFinished(mWebView, (String) msg.obj);
                }
                break;
                
            case RECEIVED_ICON:
                if (mWebChromeClient != null) {
                    mWebChromeClient.onReceivedIcon(mWebView, (Bitmap) msg.obj);
                }
                break;

            case RECEIVED_TITLE:
                if (mWebChromeClient != null) {
                    mWebChromeClient.onReceivedTitle(mWebView,
                            (String) msg.obj);
                }
                break;

            case TOO_MANY_REDIRECTS:
                Message cancelMsg =
                        (Message) msg.getData().getParcelable("cancelMsg");
                Message continueMsg =
                        (Message) msg.getData().getParcelable("continueMsg");
                if (mWebViewClient != null) {
                    mWebViewClient.onTooManyRedirects(mWebView, cancelMsg,
                            continueMsg);
                } else {
                    cancelMsg.sendToTarget();
                }
                break;

            case REPORT_ERROR:
                if (mWebViewClient != null) {
                    int reasonCode = msg.arg1;
                    final String description  = msg.getData().getString("description");
                    final String failUrl  = msg.getData().getString("failingUrl");
                    mWebViewClient.onReceivedError(mWebView, reasonCode,
                            description, failUrl);
                }
                break;

            case RESEND_POST_DATA:
                Message resend =
                        (Message) msg.getData().getParcelable("resend");
                Message dontResend =
                        (Message) msg.getData().getParcelable("dontResend");
                if (mWebViewClient != null) {
                    mWebViewClient.onFormResubmission(mWebView, dontResend,
                            resend);
                } else {
                    dontResend.sendToTarget();
                }
                break;

            case OVERRIDE_URL:
                String overrideUrl = msg.getData().getString("url");
                boolean override = uiOverrideUrlLoading(overrideUrl);
                ResultTransport<Boolean> result =
                        (ResultTransport<Boolean>) msg.obj;
                synchronized (this) {
                    result.setResult(override);
                    notify();
                }
                break;

            case AUTH_REQUEST:
                if (mWebViewClient != null) {
                    HttpAuthHandler handler = (HttpAuthHandler) msg.obj;
                    String host = msg.getData().getString("host");
                    String realm = msg.getData().getString("realm");
                    mWebViewClient.onReceivedHttpAuthRequest(mWebView, handler,
                            host, realm);
                }
                break;

            case SSL_ERROR:
                if (mWebViewClient != null) {
                    HashMap<String, Object> map = 
                        (HashMap<String, Object>) msg.obj;
                    mWebViewClient.onReceivedSslError(mWebView,
                            (SslErrorHandler) map.get("handler"),
                            (SslError) map.get("error"));
                }
                break;

            case PROGRESS:
                // Synchronize to ensure mLatestProgress is not modified after
                // setProgress is called and before mProgressUpdatePending is
                // changed.
                synchronized (this) {
                    if (mWebChromeClient != null) {
                        mWebChromeClient.onProgressChanged(mWebView,
                                mLatestProgress);
                    }
                    mProgressUpdatePending = false;
                }
                break;

            case UPDATE_VISITED:
                if (mWebViewClient != null) {
                    mWebViewClient.doUpdateVisitedHistory(mWebView,
                            (String) msg.obj, msg.arg1 != 0);
                }
                break;

            case LOAD_RESOURCE:
                if (mWebViewClient != null) {
                    mWebViewClient.onLoadResource(mWebView, (String) msg.obj);
                }
                break;

            case DOWNLOAD_FILE:
                if (mDownloadListener != null) {
                    String url = msg.getData().getString("url");
                    String userAgent = msg.getData().getString("userAgent");
                    String contentDisposition =
                        msg.getData().getString("contentDisposition");
                    String mimetype = msg.getData().getString("mimetype");
                    Long contentLength = msg.getData().getLong("contentLength");

                    mDownloadListener.onDownloadStart(url, userAgent,
                            contentDisposition, mimetype, contentLength);
                }
                break;

            case CREATE_WINDOW:
                if (mWebChromeClient != null) {
                    if (!mWebChromeClient.onCreateWindow(mWebView,
                                msg.arg1 == 1, msg.arg2 == 1,
                                (Message) msg.obj)) {
                        synchronized (this) {
                            notify();
                        }
                    }
                }
                break;

            case REQUEST_FOCUS:
                if (mWebChromeClient != null) {
                    mWebChromeClient.onRequestFocus(mWebView);
                }
                break;

            case CLOSE_WINDOW:
                if (mWebChromeClient != null) {
                    mWebChromeClient.onCloseWindow((WebView) msg.obj);
                }
                break;

            case SAVE_PASSWORD:
                Bundle bundle = msg.getData();
                String schemePlusHost = bundle.getString("host");
                String username = bundle.getString("username");
                String password = bundle.getString("password");
                // If the client returned false it means that the notify message
                // will not be sent and we should notify WebCore ourselves.
                if (!mWebView.onSavePassword(schemePlusHost, username, password,
                            (Message) msg.obj)) {
                    synchronized (this) {
                        notify();
                    }
                }
                break;

            case ASYNC_KEYEVENTS:
                if (mWebViewClient != null) {
                    mWebViewClient.onUnhandledKeyEvent(mWebView,
                            (KeyEvent) msg.obj);
                }
                break;

            case JS_ALERT:
                if (mWebChromeClient != null) {
                    final JsResult res = (JsResult) msg.obj;
                    String message = msg.getData().getString("message");
                    String url = msg.getData().getString("url");
                    if (!mWebChromeClient.onJsAlert(mWebView, url, message,
                            res)) {
                        new AlertDialog.Builder(mContext)
                                .setTitle(getJsDialogTitle(url))
                                .setMessage(message)
                                .setPositiveButton(R.string.ok,
                                        new AlertDialog.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                res.confirm();
                                            }
                                        })
                                .setCancelable(false)
                                .show();
                    }
                    res.setReady();
                }
                break;

            case JS_CONFIRM:
                if (mWebChromeClient != null) {
                    final JsResult res = (JsResult) msg.obj;
                    String message = msg.getData().getString("message");
                    String url = msg.getData().getString("url");
                    if (!mWebChromeClient.onJsConfirm(mWebView, url, message,
                            res)) {
                        new AlertDialog.Builder(mContext)
                                .setTitle(getJsDialogTitle(url))
                                .setMessage(message)
                                .setPositiveButton(R.string.ok, 
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                res.confirm();
                                            }})
                                .setNegativeButton(R.string.cancel, 
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                res.cancel();
                                            }})
                                .show();
                    }
                    // Tell the JsResult that it is ready for client
                    // interaction.
                    res.setReady();
                }
                break;

            case JS_PROMPT:
                if (mWebChromeClient != null) {
                    final JsPromptResult res = (JsPromptResult) msg.obj;
                    String message = msg.getData().getString("message");
                    String defaultVal = msg.getData().getString("default");
                    String url = msg.getData().getString("url");
                    if (!mWebChromeClient.onJsPrompt(mWebView, url, message,
                                defaultVal, res)) {
                        final LayoutInflater factory = LayoutInflater
                                .from(mContext);
                        final View view = factory.inflate(R.layout.js_prompt,
                                null);
                        final EditText v = (EditText) view
                                .findViewById(R.id.value);
                        v.setText(defaultVal);
                        ((TextView) view.findViewById(R.id.message))
                                .setText(message);
                        new AlertDialog.Builder(mContext)
                                .setTitle(getJsDialogTitle(url))
                                .setView(view)
                                .setPositiveButton(R.string.ok,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int whichButton) {
                                                res.confirm(v.getText()
                                                        .toString());
                                            }
                                        })
                                .setNegativeButton(R.string.cancel,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int whichButton) {
                                                res.cancel();
                                            }
                                        })
                                .setOnCancelListener(
                                        new DialogInterface.OnCancelListener() {
                                            public void onCancel(
                                                    DialogInterface dialog) {
                                                res.cancel();
                                            }
                                        })
                                .show();
                    }
                    // Tell the JsResult that it is ready for client
                    // interaction.
                    res.setReady();
                }
                break;

            case JS_UNLOAD:
                if (mWebChromeClient != null) {
                    final JsResult res = (JsResult) msg.obj;
                    String message = msg.getData().getString("message");
                    String url = msg.getData().getString("url");
                    if (!mWebChromeClient.onJsBeforeUnload(mWebView, url,
                            message, res)) {
                        final String m = mContext.getString(
                                R.string.js_dialog_before_unload, message);
                        new AlertDialog.Builder(mContext)
                                .setMessage(m)
                                .setPositiveButton(R.string.ok,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                res.confirm();
                                            }
                                        })
                                .setNegativeButton(R.string.cancel,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                res.cancel();
                                            }
                                        })
                                .show();
                    }
                    res.setReady();
                }
                break;

            case RECEIVED_CERTIFICATE:
                mWebView.setCertificate((SslCertificate) msg.obj);
                break;

            case NOTIFY:
                synchronized (this) {
                    notify();
                }
                break;

            case SCALE_CHANGED:
                if (mWebViewClient != null) {
                    mWebViewClient.onScaleChanged(mWebView, msg.getData()
                            .getFloat("old"), msg.getData().getFloat("new"));
                }
                break;

            case SWITCH_OUT_HISTORY:
                mWebView.switchOutDrawHistory();
                break;
        }
    }

    /**
     * Return the latest progress.
     */
    public int getProgress() {
        return mLatestProgress;
    }

    /**
     * Called by WebCore side to switch out of history Picture drawing mode
     */
    void switchOutDrawHistory() {
        sendMessage(obtainMessage(SWITCH_OUT_HISTORY));
    }

    private String getJsDialogTitle(String url) {
        String title = url;
        if (URLUtil.isDataUrl(url)) {
            // For data: urls, we just display 'JavaScript' similar to Safari.
            title = mContext.getString(R.string.js_dialog_title_default);
        } else {
            try {
                URL aUrl = new URL(url);
                // For example: "The page at 'http://www.mit.edu' says:"
                title = mContext.getString(R.string.js_dialog_title,
                        aUrl.getProtocol() + "://" + aUrl.getHost());
            } catch (MalformedURLException ex) {
                // do nothing. just use the url as the title
            }
        }
        return title;
    }

    //--------------------------------------------------------------------------
    // WebViewClient functions.
    // NOTE: shouldOverrideKeyEvent is never called from the WebCore thread so
    // it is not necessary to include it here.
    //--------------------------------------------------------------------------

    // Performance probe
    private long mWebCoreThreadTime;

    public void onPageStarted(String url, Bitmap favicon) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        // Performance probe
        if (false) {
            mWebCoreThreadTime = SystemClock.currentThreadTimeMillis();
            Network.getInstance(mContext).startTiming();
        }
        Message msg = obtainMessage(PAGE_STARTED);
        msg.obj = favicon;
        msg.getData().putString("url", url);
        sendMessage(msg);
    }

    public void onPageFinished(String url) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        // Performance probe
        if (false) {
            Log.d("WebCore", "WebCore thread used " + 
                    (SystemClock.currentThreadTimeMillis() - mWebCoreThreadTime)
                    + " ms");
            Network.getInstance(mContext).stopTiming();
        }
        Message msg = obtainMessage(PAGE_FINISHED, url);
        sendMessage(msg);
    }

    public void onTooManyRedirects(Message cancelMsg, Message continueMsg) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            cancelMsg.sendToTarget();
            return;
        }

        Message msg = obtainMessage(TOO_MANY_REDIRECTS);
        Bundle bundle = msg.getData();
        bundle.putParcelable("cancelMsg", cancelMsg);
        bundle.putParcelable("continueMsg", continueMsg);
        sendMessage(msg);
    }

    public void onReceivedError(int errorCode, String description,
            String failingUrl) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }

        Message msg = obtainMessage(REPORT_ERROR);
        msg.arg1 = errorCode;
        msg.getData().putString("description", description);
        msg.getData().putString("failingUrl", failingUrl);
        sendMessage(msg);
    }

    public void onFormResubmission(Message dontResend, 
            Message resend) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            dontResend.sendToTarget();
            return;
        }

        Message msg = obtainMessage(RESEND_POST_DATA);
        Bundle bundle = msg.getData();
        bundle.putParcelable("resend", resend);
        bundle.putParcelable("dontResend", dontResend);
        sendMessage(msg);
    }

    /**
     * Called by the WebCore side
     */
    public boolean shouldOverrideUrlLoading(String url) {
        // We have a default behavior if no client exists so always send the
        // message.
        ResultTransport<Boolean> res = new ResultTransport<Boolean>();
        Message msg = obtainMessage(OVERRIDE_URL);
        msg.getData().putString("url", url);
        msg.obj = res;
        synchronized (this) {
            sendMessage(msg);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Caught exception while waiting for overrideUrl");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
        return res.getResult().booleanValue();
    }

    public void onReceivedHttpAuthRequest(HttpAuthHandler handler,
            String hostName, String realmName) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            handler.cancel();
            return;
        }
        Message msg = obtainMessage(AUTH_REQUEST, handler);
        msg.getData().putString("host", hostName);
        msg.getData().putString("realm", realmName);
        sendMessage(msg);
    }
    /**
     * @hide - hide this because it contains a parameter of type SslError.
     * SslError is located in a hidden package.
     */
    public void onReceivedSslError(SslErrorHandler handler, SslError error) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            handler.cancel();
            return;
        }
        Message msg = obtainMessage(SSL_ERROR);
        //, handler);
        HashMap<String, Object> map = new HashMap();
        map.put("handler", handler);
        map.put("error", error);
        msg.obj = map;
        sendMessage(msg);
    }
    /**
     * @hide - hide this because it contains a parameter of type SslCertificate,
     * which is located in a hidden package.
     */

    public void onReceivedCertificate(SslCertificate certificate) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        // here, certificate can be null (if the site is not secure)
        sendMessage(obtainMessage(RECEIVED_CERTIFICATE, certificate));
    }

    public void doUpdateVisitedHistory(String url, boolean isReload) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        sendMessage(obtainMessage(UPDATE_VISITED, isReload ? 1 : 0, 0, url));
    }

    public void onLoadResource(String url) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        sendMessage(obtainMessage(LOAD_RESOURCE, url));
    }

    public void onUnhandledKeyEvent(KeyEvent event) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        sendMessage(obtainMessage(ASYNC_KEYEVENTS, event));
    }

    public void onScaleChanged(float oldScale, float newScale) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebViewClient == null) {
            return;
        }
        Message msg = obtainMessage(SCALE_CHANGED);
        Bundle bundle = msg.getData();
        bundle.putFloat("old", oldScale);
        bundle.putFloat("new", newScale);
        sendMessage(msg);
    }

    //--------------------------------------------------------------------------
    // DownloadListener functions.
    //--------------------------------------------------------------------------

    /**
     * Starts a download if a download listener has been registered, otherwise
     * return false.
     */
    public boolean onDownloadStart(String url, String userAgent,
            String contentDisposition, String mimetype, long contentLength) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mDownloadListener == null) {
            // Cancel the download if there is no browser client.
            return false;
        }

        Message msg = obtainMessage(DOWNLOAD_FILE);
        Bundle bundle = msg.getData();
        bundle.putString("url", url);
        bundle.putString("userAgent", userAgent);
        bundle.putString("mimetype", mimetype);
        bundle.putLong("contentLength", contentLength);
        bundle.putString("contentDisposition", contentDisposition);
        sendMessage(msg);
        return true;
    }


    //--------------------------------------------------------------------------
    // WebView specific functions that do not interact with a client. These
    // functions just need to operate within the UI thread.
    //--------------------------------------------------------------------------

    public boolean onSavePassword(String schemePlusHost, String username,
            String password, Message resumeMsg) {
        // resumeMsg should be null at this point because we want to create it
        // within the CallbackProxy.
        if (Config.DEBUG) {
            junit.framework.Assert.assertNull(resumeMsg);
        }
        resumeMsg = obtainMessage(NOTIFY);

        Message msg = obtainMessage(SAVE_PASSWORD, resumeMsg);
        Bundle bundle = msg.getData();
        bundle.putString("host", schemePlusHost);
        bundle.putString("username", username);
        bundle.putString("password", password);
        synchronized (this) {
            sendMessage(msg);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG,
                        "Caught exception while waiting for onSavePassword");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
        // Doesn't matter here
        return false;
    }

    //--------------------------------------------------------------------------
    // WebChromeClient methods
    //--------------------------------------------------------------------------

    public void onProgressChanged(int newProgress) {
        // Synchronize so that mLatestProgress is up-to-date.
        synchronized (this) {
            mLatestProgress = newProgress;
            if (mWebChromeClient == null) {
                return;
            }
            if (!mProgressUpdatePending) {
                sendEmptyMessage(PROGRESS);
                mProgressUpdatePending = true;
            }
        }
    }

    public WebView createWindow(boolean dialog, boolean userGesture) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return null;
        }

        WebView.WebViewTransport transport = mWebView.new WebViewTransport();
        final Message msg = obtainMessage(NOTIFY);
        msg.obj = transport;
        synchronized (this) {
            sendMessage(obtainMessage(CREATE_WINDOW, dialog ? 1 : 0,
                    userGesture ? 1 : 0, msg));
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG,
                        "Caught exception while waiting for createWindow");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }

        WebView w = transport.getWebView();
        if (w != null) {
            w.getWebViewCore().initializeSubwindow();
        }
        return w;
    }

    public void onRequestFocus() {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return;
        }

        sendEmptyMessage(REQUEST_FOCUS);
    }

    public void onCloseWindow(WebView window) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return;
        }
        sendMessage(obtainMessage(CLOSE_WINDOW, window));
    }

    public void onReceivedIcon(Bitmap icon) {
        if (Config.DEBUG && mBackForwardList.getCurrentItem() == null) {
            throw new AssertionError();
        }
        mBackForwardList.getCurrentItem().setFavicon(icon);
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return;
        }
        sendMessage(obtainMessage(RECEIVED_ICON, icon));
    }

    public void onReceivedTitle(String title) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return;
        }
        sendMessage(obtainMessage(RECEIVED_TITLE, title));
    }

    public void onJsAlert(String url, String message) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return;
        }
        JsResult result = new JsResult(this, false);
        Message alert = obtainMessage(JS_ALERT, result);
        alert.getData().putString("message", message);
        alert.getData().putString("url", url);
        synchronized (this) {
            sendMessage(alert);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Caught exception while waiting for jsAlert");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
    }

    public boolean onJsConfirm(String url, String message) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return false;
        }
        JsResult result = new JsResult(this, false);
        Message confirm = obtainMessage(JS_CONFIRM, result);
        confirm.getData().putString("message", message);
        confirm.getData().putString("url", url);
        synchronized (this) {
            sendMessage(confirm);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Caught exception while waiting for jsConfirm");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
        return result.getResult();
    }

    public String onJsPrompt(String url, String message, String defaultValue) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return null;
        }
        JsPromptResult result = new JsPromptResult(this);
        Message prompt = obtainMessage(JS_PROMPT, result);
        prompt.getData().putString("message", message);
        prompt.getData().putString("default", defaultValue);
        prompt.getData().putString("url", url);
        synchronized (this) {
            sendMessage(prompt);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Caught exception while waiting for jsPrompt");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
        return result.getStringResult();
    }

    public boolean onJsBeforeUnload(String url, String message) {
        // Do an unsynchronized quick check to avoid posting if no callback has
        // been set.
        if (mWebChromeClient == null) {
            return true;
        }
        JsResult result = new JsResult(this, true);
        Message confirm = obtainMessage(JS_UNLOAD, result);
        confirm.getData().putString("message", message);
        confirm.getData().putString("url", url);
        synchronized (this) {
            sendMessage(confirm);
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Caught exception while waiting for jsUnload");
                Log.e(LOGTAG, Log.getStackTraceString(e));
            }
        }
        return result.getResult();
    }
}
