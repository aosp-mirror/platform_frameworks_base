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

package com.android.dumprendertree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Vector;

public class TestShellActivity extends Activity implements LayoutTestController {

    static enum DumpDataType {DUMP_AS_TEXT, EXT_REPR, NO_OP}

    public class AsyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_TIMEOUT) {
                mTimedOut = true;
                if(mCallback != null)
                    mCallback.timedOut(mWebView.getUrl());
                requestWebKitData();
                return;
            } else if (msg.what == MSG_WEBKIT_DATA) {
                TestShellActivity.this.dump(mTimedOut, (String)msg.obj);
                return;
            }

            super.handleMessage(msg);
        }
    }

    public void requestWebKitData() {
        Message callback = mHandler.obtainMessage(MSG_WEBKIT_DATA);

        if (mRequestedWebKitData)
            throw new AssertionError("Requested webkit data twice: " + mWebView.getUrl());

        mRequestedWebKitData = true;
        switch (mDumpDataType) {
            case DUMP_AS_TEXT:
                mWebView.documentAsText(callback);
                break;
            case EXT_REPR:
                mWebView.externalRepresentation(callback);
                break;
            default:
                finished();
                break;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        LinearLayout contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        setContentView(contentView);

        mWebView = new WebView(this);
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.setWebChromeClient(mChromeClient);
        mWebView.setWebViewClient(new WebViewClient(){

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.v(LOGTAG, "onPageFinished, url=" + url);
                super.onPageFinished(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.v(LOGTAG, "onPageStarted, url=" + url);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                    String failingUrl) {
                Log.v(LOGTAG, "onReceivedError, errorCode=" + errorCode
                        + ", desc=" + description + ", url=" + failingUrl);
                super.onReceivedError(view, errorCode, description, failingUrl);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                    String host, String realm) {
                handler.cancel();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                    SslError error) {
                handler.proceed();
            }

        });
        mEventSender = new WebViewEventSender(mWebView);
        mCallbackProxy = new CallbackProxy(mEventSender, this);

        mWebView.addJavascriptInterface(mCallbackProxy, "layoutTestController");
        mWebView.addJavascriptInterface(mCallbackProxy, "eventSender");
        contentView.addView(mWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 0.0f));

        mWebView.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        mHandler = new AsyncHandler();

        Intent intent = getIntent();
        if (intent != null) {
            executeIntent(intent);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        executeIntent(intent);
    }

    private void executeIntent(Intent intent) {
        resetTestStatus();
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }

        mTestUrl = intent.getStringExtra(TEST_URL);
        if (mTestUrl == null) {
            mUiAutoTestPath = intent.getStringExtra(UI_AUTO_TEST);
            if(mUiAutoTestPath != null) {
                beginUiAutoTest();
            }
            return;
        }

        mResultFile = intent.getStringExtra(RESULT_FILE);
        mTimeoutInMillis = intent.getIntExtra(TIMEOUT_IN_MILLIS, 0);

        Log.v(LOGTAG, "  Loading " + mTestUrl);
        mWebView.loadUrl(mTestUrl);

        if (mTimeoutInMillis > 0) {
            // Create a timeout timer
            Message m = mHandler.obtainMessage(MSG_TIMEOUT);
            mHandler.sendMessageDelayed(m, mTimeoutInMillis);
        }
    }

    private void beginUiAutoTest() {
        try {
            mTestListReader = new BufferedReader(
                    new FileReader(mUiAutoTestPath));
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Failed to open test list for read.", ioe);
            finishUiAutoTest();
            return;
        }
        moveToNextTest();
    }

    private void finishUiAutoTest() {
        try {
            if(mTestListReader != null)
                mTestListReader.close();
        } catch (IOException ioe) {
            Log.w(LOGTAG, "Failed to close test list file.", ioe);
        }
        finished();
    }

    private void moveToNextTest() {
        String url = null;
        try {
            url = mTestListReader.readLine();
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Failed to read next test.", ioe);
            finishUiAutoTest();
            return;
        }
        if (url == null) {
            mUiAutoTestPath = null;
            finishUiAutoTest();
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("All tests finished. Exit?")
                   .setCancelable(false)
                   .setPositiveButton("Yes", new OnClickListener(){
                       public void onClick(DialogInterface dialog, int which) {
                           TestShellActivity.this.finish();
                       }
                   })
                   .setNegativeButton("No", new OnClickListener(){
                       public void onClick(DialogInterface dialog, int which) {
                           dialog.cancel();
                       }
                   });
            builder.create().show();
            return;
        }
        url = "file://" + url;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, url);
        intent.putExtra(TIMEOUT_IN_MILLIS, 10000);
        executeIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mWebView.stopLoading();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWebView.destroy();
        mWebView = null;
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.e(LOGTAG, "Low memory, kill self");
        System.exit(1);
    }

    // Dump the page
    public void dump(boolean timeout, String webkitData) {
        if (mResultFile == null || mResultFile.length() == 0) {
            finished();
            return;
        }

        try {
            File parentDir = new File(mResultFile).getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            FileOutputStream os = new FileOutputStream(mResultFile);
            if (timeout) {
                Log.w("Layout test: Timeout", mResultFile);
                os.write(TIMEOUT_STR.getBytes());
                os.write('\n');
            }
            if (mDumpTitleChanges)
                os.write(mTitleChanges.toString().getBytes());
            if (mDialogStrings != null)
                os.write(mDialogStrings.toString().getBytes());
            mDialogStrings = null;
            if (webkitData != null)
                os.write(webkitData.getBytes());
            os.flush();
            os.close();
        } catch (IOException ex) {
            Log.e(LOGTAG, "Cannot write to " + mResultFile + ", " + ex.getMessage());
        }

        finished();
    }

    public void setCallback(TestShellCallback callback) {
        mCallback = callback;
    }

    public void finished() {
        if (mUiAutoTestPath != null) {
            //don't really finish here
            moveToNextTest();
        } else {
            if (mCallback != null) {
                mCallback.finished();
            }
        }
    }

    public void setDefaultDumpDataType(DumpDataType defaultDumpDataType) {
        mDefaultDumpDataType = defaultDumpDataType;
    }

    // .......................................
    // LayoutTestController Functions
    public void dumpAsText() {
        mDumpDataType = DumpDataType.DUMP_AS_TEXT;
        if (mWebView != null) {
            String url = mWebView.getUrl();
            Log.v(LOGTAG, "dumpAsText called: "+url);
        }
    }

    public void waitUntilDone() {
        mWaitUntilDone = true;
        String url = mWebView.getUrl();
        Log.v(LOGTAG, "waitUntilDone called: " + url);
    }

    public void notifyDone() {
        String url = mWebView.getUrl();
        Log.v(LOGTAG, "notifyDone called: " + url);
        if (mWaitUntilDone) {
            mWaitUntilDone = false;
            mChromeClient.onProgressChanged(mWebView, 100);
        }
    }

    public void display() {
        mWebView.invalidate();
    }

    public void clearBackForwardList() {
        mWebView.clearHistory();

    }

    public void dumpBackForwardList() {
        //printf("\n============== Back Forward List ==============\n");
        // mWebHistory
        //printf("===============================================\n");

    }

    public void dumpChildFrameScrollPositions() {
        // TODO Auto-generated method stub

    }

    public void dumpEditingCallbacks() {
        // TODO Auto-generated method stub

    }

    public void dumpSelectionRect() {
        // TODO Auto-generated method stub

    }

    public void dumpTitleChanges() {
        if (!mDumpTitleChanges) {
            mTitleChanges = new StringBuffer();
        }
        mDumpTitleChanges = true;
    }

    public void keepWebHistory() {
        if (!mKeepWebHistory) {
            mWebHistory = new Vector();
        }
        mKeepWebHistory = true;
    }

    public void queueBackNavigation(int howfar) {
        // TODO Auto-generated method stub

    }

    public void queueForwardNavigation(int howfar) {
        // TODO Auto-generated method stub

    }

    public void queueLoad(String Url, String frameTarget) {
        // TODO Auto-generated method stub

    }

    public void queueReload() {
        mWebView.reload();
    }

    public void queueScript(String scriptToRunInCurrentContext) {
        mWebView.loadUrl("javascript:"+scriptToRunInCurrentContext);
    }

    public void repaintSweepHorizontally() {
        // TODO Auto-generated method stub

    }

    public void setAcceptsEditing(boolean b) {
        // TODO Auto-generated method stub

    }

    public void setMainFrameIsFirstResponder(boolean b) {
        // TODO Auto-generated method stub

    }

    public void setWindowIsKey(boolean b) {
        // This is meant to show/hide the window. The best I can find
        // is setEnabled()
        mWebView.setEnabled(b);
    }

    public void testRepaint() {
        mWebView.invalidate();
    }

    private final WebChromeClient mChromeClient = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress == 100) {
                if (!mTimedOut && !mWaitUntilDone && !mRequestedWebKitData) {
                    String url = mWebView.getUrl();
                    Log.v(LOGTAG, "Finished: "+ url);
                    mHandler.removeMessages(MSG_TIMEOUT);
                    requestWebKitData();
                } else {
                    String url = mWebView.getUrl();
                    if (mTimedOut) {
                        Log.v(LOGTAG, "Timed out before finishing: " + url);
                    } else if (mWaitUntilDone) {
                        Log.v(LOGTAG, "Waiting for notifyDone: " + url);
                    } else if (mRequestedWebKitData) {
                        Log.v(LOGTAG, "Requested webkit data ready: " + url);
                    }
                }
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title.length() > 30)
                title = "..."+title.substring(title.length()-30);
            setTitle(title);
            if (mDumpTitleChanges) {
                mTitleChanges.append("TITLE CHANGED: ");
                mTitleChanges.append(title);
                mTitleChanges.append("\n");
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                JsResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("ALERT: ");
            mDialogStrings.append(message);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                JsResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("CONFIRM: ");
            mDialogStrings.append(message);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message,
                String defaultValue, JsPromptResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("PROMPT: ");
            mDialogStrings.append(message);
            mDialogStrings.append(", default text: ");
            mDialogStrings.append(defaultValue);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }
    };

    private void resetTestStatus() {
        mWaitUntilDone = false;
        mDumpDataType = mDefaultDumpDataType;
        mTimedOut = false;
        mDumpTitleChanges = false;
        mRequestedWebKitData = false;
        mEventSender.resetMouse();
    }

    private WebView mWebView;
    private WebViewEventSender mEventSender;
    private AsyncHandler mHandler;
    private TestShellCallback mCallback;

    private CallbackProxy mCallbackProxy;

    private String mTestUrl;
    private String mResultFile;
    private int mTimeoutInMillis;
    private String mUiAutoTestPath;
    private BufferedReader mTestListReader;

    // States
    private boolean mTimedOut;
    private boolean mRequestedWebKitData;
    private boolean mFinishedRunning;

    // Layout test controller variables.
    private DumpDataType mDumpDataType;
    private DumpDataType mDefaultDumpDataType = DumpDataType.EXT_REPR;
    private boolean mWaitUntilDone;
    private boolean mDumpTitleChanges;
    private StringBuffer mTitleChanges;
    private StringBuffer mDialogStrings;
    private boolean mKeepWebHistory;
    private Vector mWebHistory;

    static final String TIMEOUT_STR = "**Test timeout";

    static final int MSG_TIMEOUT = 0;
    static final int MSG_WEBKIT_DATA = 1;

    static final String LOGTAG="TestShell";

    static final String TEST_URL = "TestUrl";
    static final String RESULT_FILE = "ResultFile";
    static final String TIMEOUT_IN_MILLIS = "TimeoutInMillis";
    static final String UI_AUTO_TEST = "UiAutoTest";
}
