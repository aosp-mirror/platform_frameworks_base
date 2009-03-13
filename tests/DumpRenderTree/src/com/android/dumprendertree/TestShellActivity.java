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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Vector;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.os.*;

public class TestShellActivity extends Activity implements LayoutTestController {
    public class AsyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_TIMEOUT) {
                mTimedOut = true;
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
        if (mDumpAsText) { 
            mWebView.documentAsText(callback);
        } else {
            mWebView.externalRepresentation(callback);
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
        if (mTestUrl == null)
            return;
        
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
        if (mCallback != null) {
            mCallback.finished();
        }
    }
   
    // .......................................
    // LayoutTestController Functions
    public void dumpAsText() {
        mDumpAsText = true;
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
        mDumpAsText = false;
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

    // States
    private boolean mTimedOut;
    private boolean mRequestedWebKitData;
    private boolean mFinishedRunning;

    // Layout test controller variables.
    private boolean mDumpAsText;
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
}
