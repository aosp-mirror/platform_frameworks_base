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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Vector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.os.*;

// TestRecorder creates two files, one for passing tests
// and another for failing tests and writes the paths to
// layout tests one line at a time. TestRecorder does not
// have ability to clear the results.
class TestRecorder {
    public void passed(String layout_file) {
      try {
          mBufferedOutputPassedStream.write(layout_file.getBytes());
          mBufferedOutputPassedStream.write('\n');
          mBufferedOutputPassedStream.flush();
      } catch(Exception e) {
          e.printStackTrace();
      }
   }

    public void failed(String layout_file, String reason) {
      try {
          mBufferedOutputFailedStream.write(layout_file.getBytes());
          mBufferedOutputFailedStream.write(" : ".getBytes());
          mBufferedOutputFailedStream.write(reason.getBytes());
          mBufferedOutputFailedStream.write('\n');
          mBufferedOutputFailedStream.flush();
      } catch(Exception e) {
          e.printStackTrace();
      }
    }

    public void nontext(String layout_file, boolean has_results) {
      try {
          mBufferedOutputNontextStream.write(layout_file.getBytes());
          if (has_results) {
              mBufferedOutputNontextStream.write(" : has expected results".getBytes());
          }
          mBufferedOutputNontextStream.write('\n');
          mBufferedOutputNontextStream.flush();
      } catch(Exception e) {
          e.printStackTrace();
      }
    }

    public TestRecorder(boolean resume) {
      try {
      File resultsPassedFile = new File("/sdcard/layout_tests_passed.txt");
      File resultsFailedFile = new File("/sdcard/layout_tests_failed.txt");
      File resultsNontextFile = new File("/sdcard/layout_tests_nontext.txt");
 
      mBufferedOutputPassedStream =
          new BufferedOutputStream(new FileOutputStream(resultsPassedFile, resume));
      mBufferedOutputFailedStream =
          new BufferedOutputStream(new FileOutputStream(resultsFailedFile, resume));
      mBufferedOutputNontextStream =
          new BufferedOutputStream(new FileOutputStream(resultsNontextFile, resume));
      } catch (Exception e) {
          e.printStackTrace();
      }
    }

    public void close() {
        try {
            mBufferedOutputPassedStream.close();
            mBufferedOutputFailedStream.close();
            mBufferedOutputNontextStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private BufferedOutputStream mBufferedOutputPassedStream;
    private BufferedOutputStream mBufferedOutputFailedStream;
    private BufferedOutputStream mBufferedOutputNontextStream;
}

public class HTMLHostActivity extends Activity 
     implements LayoutTestController {

    public class AsyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_TIMEOUT) {
                mTimedOut = true;
                requestWebKitData();
                return;
            } else if (msg.what == MSG_WEBKIT_DATA) {
                HTMLHostActivity.this.dump(mTimedOut, (String)msg.obj);
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
    // Activity methods
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        LinearLayout contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        setContentView(contentView);

        mWebView = new WebView(this);
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.setWebChromeClient(mChromeClient);
        mEventSender = new WebViewEventSender(mWebView);
        mCallbackProxy = new CallbackProxy(mEventSender, this);
        mFinishedRunning = false;

        mWebView.addJavascriptInterface(mCallbackProxy, "layoutTestController");
        mWebView.addJavascriptInterface(mCallbackProxy, "eventSender");
        contentView.addView(mWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 0.0f));
 
        mHandler = new AsyncHandler();
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
    }

    private void getTestList() {
        // Read test list.
        try {
            BufferedReader inReader = new BufferedReader(new FileReader(LAYOUT_TESTS_LIST_FILE));
            String line = inReader.readLine();
            while (line != null) {
                if (line.startsWith(mTestPathPrefix))
                    mTestList.add(line);
                line = inReader.readLine();
            }
            inReader.close();
            Log.v(LOGTAG, "Test list has " + mTestList.size() + " test(s).");
        } catch (Exception e) {
            Log.e(LOGTAG, "Error while reading test list : " + e.getMessage());
        }
    }
    
    private void resumeTestList() {
        // read out the test name it stoped last time.
        try {
            BufferedReader inReader = new BufferedReader(new FileReader(TEST_STATUS_FILE));
            String line = inReader.readLine();
            for (int i = 0; i < mTestList.size(); i++) {
                if (mTestList.elementAt(i).equals(line)) {
                    mTestList = new Vector<String>(mTestList.subList(i+1, mTestList.size()));
                    break;
                }
            }
            inReader.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Error reading " + TEST_STATUS_FILE);
        }
    }
    
    private void clearTestStatus() {
        // Delete TEST_STATUS_FILE
        try {
            File f = new File(TEST_STATUS_FILE);
            if (f.delete())
                Log.v(LOGTAG, "Deleted " + TEST_STATUS_FILE);
            else
                Log.e(LOGTAG, "Fail to delete " + TEST_STATUS_FILE);
        } catch (Exception e) {
            Log.e(LOGTAG, "Fail to delete " + TEST_STATUS_FILE + " : " + e.getMessage());
        }
    }
    
    private void updateTestStatus(String s) {
        // Write TEST_STATUS_FILE
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(TEST_STATUS_FILE));
            bos.write(s.getBytes());
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_STATUS_FILE);
        }
    }
    
    protected void onResume() {
        super.onResume();
        if (mTestList == null)
            mTestList = new Vector<String>();
        
        if (mTestList.isEmpty()) {
            // Read settings
            Intent intent = getIntent();
            mTestPathPrefix = intent.getStringExtra(TEST_PATH_PREFIX);
            mSingleTestMode = intent.getBooleanExtra(SINGLE_TEST_MODE, false);
            boolean resume = intent.getBooleanExtra(RESUME_FROM_CRASH, false);
            mTimeoutInMillis = intent.getIntExtra(TIMEOUT_IN_MILLIS, 8000);
            
            mWebView.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            
            if (mTestPathPrefix == null)
                throw new AssertionError("mTestPathPrefix cannot be null");
            
            Log.v(LOGTAG, "Run tests with prefix: " + mTestPathPrefix);

            mResultRecorder = new TestRecorder(resume);
            
            if (!resume)
                clearTestStatus();
            
            if (!mSingleTestMode) {
                getTestList();
                if (resume)
                    resumeTestList();
            } else {
                mTestList.add(mTestPathPrefix);
            }
            
            if (!mTestList.isEmpty())
                runTestAtIndex(0);
            else
                mWebView.loadUrl("about:");
        }
    }

    protected void onStop() {
        super.onStop();
        mWebView.stopLoading();
    }

    protected void onDestroy() {
        super.onDestroy();
        mResultRecorder.close();
        mWebView.destroy();
        mWebView = null;
    }
    
    public void onLowMemory() {
        super.onLowMemory();
        // Simulate a crash
        Log.e(LOGTAG, "Low memory, killing self");
        System.exit(1);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        // Log key strokes as they don't seem to be matched
        //Log.e(LOGTAG, "Event: "+event);
        return super.dispatchKeyEvent(event);
    }

    // Run a test at specified index in the test list.
    // Stops activity if run out of tests.
    protected void runTestAtIndex(int testIndex) {
        mTestIndex = testIndex;
        
        resetTestStatus();

        if (testIndex == mTestList.size()) {
            if (!mSingleTestMode) {
                updateTestStatus("#DONE");
            }
            finished();
            return;
        }
        String s = mTestList.elementAt(testIndex);
        if (!mSingleTestMode)
            updateTestStatus(s);
        
        Log.v(LOGTAG, "  Running test: "+s);
        mWebView.loadUrl("file://"+s);
        
        if (!mSingleTestMode) {
            // Create a timeout timer
            Message m = mHandler.obtainMessage(MSG_TIMEOUT);
            mHandler.sendMessageDelayed(m, mTimeoutInMillis);
        }
    }

    // Dump the page
    public void dump(boolean timeout, String webkitData) {
        String currentTest = mTestList.elementAt(mTestIndex);
        String resultFile = currentTest.substring(0, currentTest.lastIndexOf('.'));

        // dumpAsText version can be directly compared to expected results
        if (mDumpAsText) {
            resultFile += "-results.txt";
        } else {
            resultFile += "-android-results.txt";
        }

        try {
            FileOutputStream os = new FileOutputStream(resultFile);
            if (timeout) {
                Log.w("Layout test: Timeout", resultFile);
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
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        processResult(timeout, currentTest);
        runTestAtIndex(mTestIndex + 1);
    }

    // Wrap up
    public void failedCase(String file, String reason) {
        Log.w("Layout test: ", file + " failed " + reason);
        mResultRecorder.failed(file, reason);

        String bugNumber = FileFilter.isKnownBug(file);
        if (bugNumber != null) {
            System.out.println("FAIL known:"+bugNumber+ " "+file+reason);
            return;
        }
        if (FileFilter.ignoreResults(file)) {
            return;
        }
        System.out.println("FAIL: "+file+reason);        
    }

    public void passedCase(String file) {
        Log.v("Layout test:", file + " passed");
        mResultRecorder.passed(file);

        String bugNumber = FileFilter.isKnownBug(file);
        if (bugNumber != null) {
            System.out.println("Bug Fixed: "+bugNumber+ " "+file);
            return;
        }
 
        if (FileFilter.ignoreResults(file)) {
            System.out.println("Ignored test passed: "+file);
            return;
        }
    }

    public void nontextCase(String file, boolean has_expected_results) {
        Log.v("Layout test:", file + " nontext");
        mResultRecorder.nontext(file, has_expected_results);
    }
 
    public void setCallback(HTMLHostCallbackInterface callback) {
        mCallback = callback;
    }

    public void processResult(boolean timeout, String test_path) {
        Log.v(LOGTAG, "  Processing result: " + test_path);
        // remove the extension
        String short_file = test_path.substring(0, test_path.lastIndexOf('.'));
        if (timeout) {
            failedCase(test_path, "TIMEDOUT");
            return;
        }
        // Only check results that we can check, ie dumpAsText results
        String dumpFile = short_file + "-results.txt";
        File f = new File(dumpFile);
        if (f.exists()) {
            try {
                FileInputStream fr = new FileInputStream(short_file+"-results.txt");
                FileInputStream fe = new FileInputStream(short_file+"-expected.txt");
              
                // If the length is different then they are different
                int diff = fe.available() - fr.available();
                if (diff > 1 || diff < 0) {
                    failedCase(test_path, " different length");
                    fr.close();
                    fe.close();
                    return;
                }
                byte[] br = new byte[fr.available()];
                byte[] be = new byte[fe.available()];
                fr.read(br);
                fe.read(be);
                boolean fail = false;
                for (int i = 0; i < br.length; i++) {
                    if (br[i] != be[i]) {
                        failedCase(test_path, "  @offset: "+i);
                        fr.close();
                        fe.close();
                        return;
                    }
                }
                if (br.length != be.length && be[be.length-1] == '\n') {
                    Log.d(LOGTAG, "Extra new line being ignore:" + test_path);
                }
                fr.close();
                fe.close();
                passedCase(test_path);
            } catch (FileNotFoundException ex) {
              // TODO do something here
            } catch (IOException ex) {
              // Failed on available() or read()
            }
            
            return;
        }
        
        File nontext_result = new File(short_file + "-android-results.txt");
        if (nontext_result.exists()) {
            // Check if the test has expected results.
            File expected = new File(short_file + "-expected.txt");
            nontextCase(test_path, expected.exists());
        }
    }
    
    public void finished() {
        if (mCallback != null) {
            mCallback.waitForFinish();
        }

        mFinishedRunning = true;
        finish();
    }
    
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

    // Instrumentation calls this to find
    // if the activity has finished running the layout tests
    // TODO(fqian): need to sync on mFinisheRunning
    public boolean hasFinishedRunning() {
        return mFinishedRunning;
    }
    
    private final WebChromeClient mChromeClient = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress == 100) {
                if (!mSingleTestMode && !mTimedOut && !mWaitUntilDone && !mRequestedWebKitData) {
                    String url = mWebView.getUrl();
                    Log.v(LOGTAG, "Finished: "+ url);
                    mHandler.removeMessages(MSG_TIMEOUT);
                    requestWebKitData();
                } else {
                    String url = mWebView.getUrl();
                    if (mSingleTestMode) {
                        Log.v(LOGTAG, "Single test mode: " + url);
                    } else if (mTimedOut) {
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
    
    private TestRecorder mResultRecorder;
    private HTMLHostCallbackInterface mCallback = null;
    private CallbackProxy mCallbackProxy;
    
    private WebView mWebView;
    private WebViewEventSender mEventSender;
    
    private Vector<String> mTestList;
    private int mTestIndex;

    private int mTimeoutInMillis;
    private String mTestPathPrefix;
    private boolean mSingleTestMode;
    
    private AsyncHandler mHandler;
    private boolean mFinishedRunning;

    private boolean mTimedOut;
    private boolean mRequestedWebKitData;
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

    static final String LOGTAG="DumpRenderTree";

    static final String LAYOUT_TESTS_ROOT = "/sdcard/android/layout_tests/";
    static final String LAYOUT_TESTS_LIST_FILE = "/sdcard/layout_tests_list.txt";
    static final String TEST_STATUS_FILE = "/sdcard/running_test.txt";
    
    static final String RESUME_FROM_CRASH = "ResumeFromCrash";
    static final String TEST_PATH_PREFIX = "TestPathPrefix";
    static final String TIMEOUT_IN_MILLIS = "TimeoutInMillis";
    static final String SINGLE_TEST_MODE = "SingleTestMode";
}
