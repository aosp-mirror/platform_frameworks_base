/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.webkit.webdriver;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;

/**
 * Drives a web application by controlling the WebView. This class
 * provides a DOM-like API allowing to get information about the page,
 * navigate, and interact with the web application. This is particularly useful
 * for testing a web application.
 *
 * <p/>{@link android.webkit.webdriver.WebDriver} should be created in the main
 * thread, and invoked from another thread. Here is a sample usage:
 *
 * public class WebDriverStubActivity extends Activity {
 *   private WebDriver mDriver;
 *
 *   public void onCreate(Bundle savedInstanceState) {
 *       super.onCreate(savedInstanceState);
 *       WebView view = new WebView(this);
 *       mDriver = new WebDriver(view);
 *       setContentView(view);
 *   }
 *
 *
 *   public WebDriver getDriver() {
 *       return mDriver;
 *   }
 *}
 *
 * public class WebDriverTest extends
 *       ActivityInstrumentationTestCase2<WebDriverStubActivity>{
 *   private WebDriver mDriver;
 *
 *   public WebDriverTest() {
 *       super(WebDriverStubActivity.class);
 *   }
 *
 *   protected void setUp() throws Exception {
 *       super.setUp();
 *       mDriver = getActivity().getDriver();
 *   }
 *
 *   public void testGetIsBlocking() {
 *       mDriver.get("http://google.com");
 *       assertTrue(mDriver.getPageSource().startsWith("<html"));
 *   }
 *}
 *
 * @hide
 */
public class WebDriver {
    // Timeout for page load in milliseconds
    private static final int LOADING_TIMEOUT = 30000;
    // Timeout for executing JavaScript in the WebView in milliseconds
    private static final int JS_EXECUTION_TIMEOUT = 10000;

    // Commands posted to the handler
    private static final int GET_URL = 1;
    private static final int EXECUTE_SCRIPT = 2;

    private static final long MAIN_THREAD = Thread.currentThread().getId();

    // This is updated by a callabck from JavaScript when the result is ready
    private String mJsResult;

    // Used for synchronization
    private final Object mSyncObject;

    // Updated when the command is done executing in the main thread.
    private volatile boolean mCommandDone;

    private WebView mWebView;

    // This Handler runs in the main UI thread
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == GET_URL) {
                final String url = (String) msg.obj;
                mWebView.loadUrl(url);
            } else if (msg.what == EXECUTE_SCRIPT) {
                executeScript((String) msg.obj);
            }
        }
    };

    public WebDriver(WebView webview) {
        if (!mWebView.getSettings().getJavaScriptEnabled()) {
            throw new RuntimeException("Javascript is disabled in the WebView. "
                    + "Enable it to use WebDriver");
        }
        shouldRunInMainThread(true);
        mSyncObject = new Object();
        this.mWebView = webview;
        WebchromeClientWrapper chromeWrapper = new WebchromeClientWrapper(
                webview.getWebChromeClient(), this);
        mWebView.setWebChromeClient(chromeWrapper);
        mWebView.addJavascriptInterface(new JavascriptResultReady(),
                "webdriver");
    }

    /**
     * Loads a URL in the WebView. This function is blocking and will return
     * when the page has finished loading.
     *
     * @param url The URL to load.
     */
    public void get(String url) {
        executeCommand(GET_URL, url, LOADING_TIMEOUT);
    }

    /**
     * @return The source page of the currently loaded page in WebView.
     */
    public String getPageSource() {
        executeCommand(EXECUTE_SCRIPT, "return (new XMLSerializer())"
                + ".serializeToString(document.documentElement);",
                JS_EXECUTION_TIMEOUT);
        return mJsResult;
    }

    private void executeScript(String script) {
        mWebView.loadUrl("javascript:" + script);
    }

    private void shouldRunInMainThread(boolean value) {
        assert (value == (MAIN_THREAD == Thread.currentThread().getId()));
    }

    /**
     * Interface called from JavaScript when the result is ready.
     */
    private class JavascriptResultReady {

        /**
         * A callback from JavaScript to Java that passes the result as a
         * parameter. This method is available from the WebView's
         * JavaScript DOM as window.webdriver.resultReady().
         *
         * @param result The result that should be sent to Java from Javascript.
         */
        public void resultReady(String result) {
            synchronized (mSyncObject) {
                mJsResult = result;
                mCommandDone = true;
                mSyncObject.notify();
            }
        }
    }

    /* package */ void notifyCommandDone() {
        synchronized (mSyncObject) {
            mCommandDone = true;
            mSyncObject.notify();
        }
    }

    /**
     * Executes the given command by posting a message to mHandler. This thread
     * will block until the command which runs in the main thread is done.
     *
     * @param command The command to run.
     * @param arg The argument for that command.
     * @param timeout A timeout in milliseconds.
     */
    private void executeCommand(int command, String arg, long timeout) {
        shouldRunInMainThread(false);

        synchronized (mSyncObject) {
            mCommandDone = false;
            Message msg = mHandler.obtainMessage(command);
            msg.obj = arg;
            mHandler.sendMessage(msg);

            long end = System.currentTimeMillis() + timeout;
            while (!mCommandDone) {
                if (System.currentTimeMillis() >= end) {
                    throw new RuntimeException("Timeout executing command.");
                }
                try {
                    mSyncObject.wait(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
