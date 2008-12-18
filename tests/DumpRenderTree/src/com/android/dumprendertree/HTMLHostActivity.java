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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Vector;
import java.util.Stack;

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
import android.test.TestRecorder;

// SQLite3 in android has a bunch of bugs which
// is causing TestRecorder to not record the results
// properly. This class is a wrapper around it and records
// results in a file as well.
class TestRecorderV2 extends TestRecorder {
    @Override
    public void passed(String layout_file) {
      try {
          mBufferedOutputPassedStream.write(layout_file.getBytes());
          mBufferedOutputPassedStream.write('\n');
          mBufferedOutputPassedStream.flush();
      } catch(Exception e) {
          e.printStackTrace();
      }
      super.passed(layout_file);
   }

    @Override
    public void failed(String layout_file, String reason) {
      try {
          mBufferedOutputFailedStream.write(layout_file.getBytes());
          mBufferedOutputFailedStream.write('\n');
          mBufferedOutputFailedStream.flush();
      } catch(Exception e) {
          e.printStackTrace();
      }
      super.failed(layout_file, reason);
    }

    public TestRecorderV2() {
      super();
      try {
      File resultsPassedFile = new File("/sdcard/layout_test_presults.txt");
      File resultsFailedFile = new File("/sdcard/layout_test_fresults.txt");
 
      mBufferedOutputPassedStream =
          new BufferedOutputStream(new FileOutputStream(resultsPassedFile, true));
      mBufferedOutputFailedStream =
          new BufferedOutputStream(new FileOutputStream(resultsFailedFile, true));
 
      } catch (Exception e) {
          e.printStackTrace();
      }
    }
    
    protected void finalize() throws Throwable {
        mBufferedOutputPassedStream.flush();
        mBufferedOutputFailedStream.flush();
        mBufferedOutputPassedStream.close();
        mBufferedOutputFailedStream.close();
    }

    private static BufferedOutputStream mBufferedOutputPassedStream;
    private static BufferedOutputStream mBufferedOutputFailedStream;
}

public class HTMLHostActivity extends Activity 
     implements LayoutTestController {
 
    private TestRecorderV2 mResultRecorder = new TestRecorderV2();
    private HTMLHostCallbackInterface mCallback = null;
    private CallbackProxy mCallbackProxy;

    public class FileEntry {
        public FileEntry(String path, int index) {
            mPath = path; mIndex=index;
        }
        String mPath;
        int mIndex;
    }

    public class AsyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_DUMP) {
                this.removeMessages(MSG_TIMEOUT);
                mTimedOut = false;
                requestWebKitData();
                return;
            } else if (msg.what == MSG_TIMEOUT) {
                mTimedOut = true;
                requestWebKitData();
                return;
            } else if (msg.what == MSG_WEBKIT_DATA) {
                HTMLHostActivity.this.dump(mTimedOut, (String)msg.obj);
                return;
            }
            
            super.handleMessage(msg);
        }

        void requestWebKitData() {
            Message callback = obtainMessage(MSG_WEBKIT_DATA);
            if (dumpAsText) { 
                mWebView.documentAsText(callback);
            } else {
                mWebView.externalRepresentation(callback);
            }
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
        eventSender = new WebViewEventSender(mWebView);
        mCallbackProxy = new CallbackProxy(eventSender, this);

        mWebView.addJavascriptInterface(mCallbackProxy, "layoutTestController");
        mWebView.addJavascriptInterface(mCallbackProxy, "eventSender");
        contentView.addView(mWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 0.0f));
 
        mHandler = new AsyncHandler();
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
    }

    protected void onResume() {
        super.onResume();
        if (mProcessStack == null || mProcessStack.isEmpty() ) {
            mOutstandingLoads = 0;
            dumpAsText = false;
            pageComplete = false;

            mWebView.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

            mFinishedStack = new Stack();

            Intent intent = getIntent();
            if (intent.getData() != null) {
                File f = new File(intent.getData().toString());

                if (f.isDirectory()) {
                    mProcessStack = new Vector();
                    mProcessStack.add(new FileEntry(intent.getData().toString(), 0));
                    Log.v(LOGTAG, "Initial dir: "+intent.getData().toString());
                    loadNextPage();
                } else {
                    mCurrentFile = intent.getData().toString();
                    mWebView.loadUrl("file://"+intent.getData().toString());
                }

            }
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
        mWebView.destroy();
        mWebView = null;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        // Log key strokes as they don't seem to be matched
        //Log.e(LOGTAG, "Event: "+event);
        return super.dispatchKeyEvent(event);
    }

    // Functions
    
    protected void loadNextPage() {
        dumpAsText = false;
        pageComplete = false;
        dumpTitleChanges = false;
        eventSender.resetMouse();
        while (!mProcessStack.isEmpty()) {
            FileEntry fe = (FileEntry)mProcessStack.remove(0);
            if (fe.mIndex == 0) {
                System.out.println();
                System.out.print(fe.mPath);
            }
            Log.v(LOGTAG, "Processing dir: "+fe.mPath+" size: "+mProcessStack.size());
            File f = new File(fe.mPath);
            String [] files = f.list();
            for (int i = fe.mIndex; i < files.length; i++) {
                if (FileFilter.ignoreTest(files[i])) {
                    continue;
                }
                File c = new File(f.getPath(), files[i]);
                if (c.isDirectory()) {
                    Log.v(LOGTAG, "Adding dir: "+fe.mPath+"/"+files[i]);
                    mProcessStack.add(new FileEntry(fe.mPath+"/"+files[i], 0));
                } else if (files[i].toLowerCase().endsWith("ml")) {
                    mProcessStack.add(0, new FileEntry(fe.mPath, i+1));
                    mCurrentFile = fe.mPath+"/"+files[i];
                    Log.e(LOGTAG, "Processing: "+mCurrentFile);
                    mWebView.loadUrl("file://"+mCurrentFile);

                    // Create a timeout timer
                    Message m = mHandler.obtainMessage(MSG_TIMEOUT);
                    // Some tests can take up to 5secs to run.
                    mHandler.sendMessageDelayed(m, 6000); 
                    return;
                }
            }
            Log.v(LOGTAG, "Finished dir: "+fe.mPath+" size: "+mProcessStack.size()); 
        }
        // If we got to here, then we must have finished completely
        finished();
    }
    
    public void scheduleDump() {
        // Only schedule if we really are ready
        if (waitToDump || mOutstandingLoads > 0 || mDumpRequested) {
            return;
        }
        mDumpRequested = true;
        mHandler.obtainMessage(MSG_DUMP).sendToTarget();
    }
    
    // Dump the page
    public void dump(boolean timeout, String webkitData) {
        mDumpRequested = false;
        System.out.print('.');

        // remove the extension
        String resultFile = mCurrentFile.substring(0, mCurrentFile.lastIndexOf('.'));

        // store the finished file on the stack so that we can do a diff at the end.
        mFinishedStack.push(resultFile);

        // dumpAsText version can be directly compared to expected results
        if (dumpAsText) {
            resultFile += "-results.txt";
        } else {
            resultFile += "-android-results.txt";
        }
        try {
            FileOutputStream os = new FileOutputStream(resultFile);
            if (timeout) {
                Log.i("Layout test: Timeout", resultFile);
                os.write("**Test timeout\n".getBytes());
            }
            if (dumpTitleChanges)
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

        if (mProcessStack != null)
            loadNextPage();
        else
            finished();
    }

    // Wrap up
    public void failedCase(String file, String reason) {
        Log.i("Layout test:", file + " failed" + reason);
        mResultRecorder.failed(file, reason);
 
        file = file + ".html";
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
        // Add the result to the sqlite database
        Log.i("Layout test:", file + " passed");
        mResultRecorder.passed(file);

        file = file + ".html";
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
 
    public void setCallback(HTMLHostCallbackInterface callback) {
        mCallback = callback;
    }
        
    public void finished() {
        int passed = 0;
        while (!mFinishedStack.empty()) {
            Log.v(LOGTAG, "Comparing dump and reference");
            String file = (String)mFinishedStack.pop();

            // Only check results that we can check, ie dumpAsText results
            String dumpFile = file + "-results.txt";
            File f = new File(dumpFile);
            if (f.exists()) {
                try {
                    FileInputStream fr = new FileInputStream(file+"-results.txt");
                    FileInputStream fe = new FileInputStream(file+"-expected.txt");
                    
                    mResultRecorder.started(file);
                    
                    // If the length is different then they are different
                    int diff = fe.available() - fr.available();
                    if (diff > 1 || diff < 0) {
                        failedCase(file, " different length");
                        fr.close();
                        fe.close();
                        
                        mResultRecorder.finished(file);                        
                        continue;
                    }
                    byte[] br = new byte[fr.available()];
                    byte[] be = new byte[fe.available()];
                    fr.read(br);
                    fe.read(be);
                    boolean fail = false;
                    for (int i = 0; i < br.length; i++) {
                        if (br[i] != be[i]) {
                            failedCase(file, "  @offset: "+i);
                            fail = true;
                            break;
                        }
                    }
                    if (br.length != be.length && be[be.length-1] == '\n') {
                        Log.d(LOGTAG, "Extra new line being ignore:" + file);
                    }
                    fr.close();
                    fe.close();
                    if (!fail) {
                       passed++;
                       passedCase(file);
                    }
                } catch (FileNotFoundException ex) {
                    // TODO do something here
                } catch (IOException ex) {
                    // Failed on available() or read()
                }
                mResultRecorder.finished(file);
            }
        }                        
        
        if (mCallback != null) {        
            mCallback.waitForFinish();
        }  
        
        finish();
    }
    
    // LayoutTestController Functions
    public void dumpAsText() {
        dumpAsText = true;
        String url = mWebView.getUrl();
        Log.v(LOGTAG, "dumpAsText called:"+url);
        if (url.length() > 60)
            url = url.substring(60);
    }

    public void waitUntilDone() {
        waitToDump = true;
    }
    public void notifyDone() {
        waitToDump = false;
        mChromeClient.onProgressChanged(mWebView, 100);
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
        if (!dumpTitleChanges) {
            mTitleChanges = new StringBuffer();
        }
        dumpTitleChanges = true;
    }

    public void keepWebHistory() {
        if (!keepWebHistory) {
            mWebHistory = new Vector();
        }
        keepWebHistory = true;

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
    public boolean hasFinishedRunning() {
        if( mProcessStack == null || mFinishedStack == null)
            return false;

        if (mProcessStack.isEmpty() && mFinishedStack.empty()) {
            return true;
        }

        return false;
    }

    private final WebChromeClient mChromeClient = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress == 100) {
                pageComplete = true;
                String url = mWebView.getUrl();
                if (url != null) {
                    Log.v(LOGTAG, "Finished: "+ url);
                    if (url.length() > 60)
                        url = url.substring(60);
                    scheduleDump();
                }
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title.length() > 30)
                title = "..."+title.substring(title.length()-30);
            setTitle(title);
            if (dumpTitleChanges) {
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
            return false;
        }
    };

    private WebView mWebView;
    private WebViewEventSender eventSender;
    private Vector mProcessStack;
    private Stack mFinishedStack;
    static final String LOGTAG="DumpRenderTree";
    private String mCurrentFile;
    private int mOutstandingLoads;
    private AsyncHandler mHandler;
    private boolean mDumpRequested;

    private boolean dumpAsText;
    private boolean waitToDump;
    private boolean pageComplete;

    private boolean dumpTitleChanges;
    private StringBuffer mTitleChanges;

    private StringBuffer mDialogStrings;

    private boolean keepWebHistory;
    private Vector mWebHistory;

    private boolean mTimedOut;

    static final int MSG_DUMP = 0;
    static final int MSG_TIMEOUT = 1;
    static final int MSG_WEBKIT_DATA = 2;

}
