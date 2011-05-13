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

import android.graphics.Point;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;
import android.webkit.WebViewCore;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import com.android.internal.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
 *   public void testGoogle() {
 *       mDriver.get("http://google.com");
 *       WebElement searchBox = mDriver.findElement(By.name("q"));
 *       q.sendKeys("Cheese!");
 *       q.submit();
 *       assertTrue(mDriver.findElements(By.partialLinkText("Cheese")).size() > 0);
 *   }
 *}
 *
 * @hide
 */
public class WebDriver {
    // Timeout for page load in milliseconds.
    private static final int LOADING_TIMEOUT = 30000;
    // Timeout for executing JavaScript in the WebView in milliseconds.
    private static final int JS_EXECUTION_TIMEOUT = 10000;
    // Timeout for the MotionEvent to be completely handled
    private static final int MOTION_EVENT_TIMEOUT = 1000;
    // Timeout for detecting a new page load
    private static final int PAGE_STARTED_LOADING = 500;
    // Timeout for handling KeyEvents
    private static final int KEY_EVENT_TIMEOUT = 2000;

    // Commands posted to the handler
    private static final int CMD_GET_URL = 1;
    private static final int CMD_EXECUTE_SCRIPT = 2;
    private static final int CMD_SEND_TOUCH = 3;
    private static final int CMD_SEND_KEYS = 4;
    private static final int CMD_NAV_REFRESH = 5;
    private static final int CMD_NAV_BACK = 6;
    private static final int CMD_NAV_FORWARD = 7;
    private static final int CMD_SEND_KEYCODE = 8;
    private static final int CMD_MOVE_CURSOR_RIGHTMOST_POS = 9;
    private static final int CMD_MESSAGE_RELAY_ECHO = 10;

    private static final String ELEMENT_KEY = "ELEMENT";
    private static final String STATUS = "status";
    private static final String VALUE = "value";

    private static final long MAIN_THREAD = Thread.currentThread().getId();

    // This is updated by a callabck from JavaScript when the result is ready.
    private String mJsResult;

    // Used for synchronization
    private final Object mSyncObject;
    private final Object mSyncPageLoad;

    // Updated when the command is done executing in the main thread.
    private volatile boolean mCommandDone;
    // Used by WebViewClientWrapper.onPageStarted() to notify that
    // a page started loading.
    private volatile boolean mPageStartedLoading;
    // Used by WebChromeClientWrapper.onProgressChanged to notify when
    // a page finished loading.
    private volatile boolean mPageFinishedLoading;
    private WebView mWebView;
    private Navigation mNavigation;
    // This WebElement represents the object document.documentElement
    private WebElement mDocumentElement;


    // This Handler runs in the main UI thread.
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_GET_URL:
                    final String url = (String) msg.obj;
                    mWebView.loadUrl(url);
                    break;
                case CMD_EXECUTE_SCRIPT:
                    mWebView.loadUrl("javascript:" + (String) msg.obj);
                    break;
                case CMD_MESSAGE_RELAY_ECHO:
                    notifyCommandDone();
                    break;
                case CMD_SEND_TOUCH:
                    touchScreen((Point) msg.obj);
                    notifyCommandDone();
                    break;
                case CMD_SEND_KEYS:
                    dispatchKeys((CharSequence[]) msg.obj);
                    notifyCommandDone();
                    break;
                case CMD_NAV_REFRESH:
                    mWebView.reload();
                    break;
                case CMD_NAV_BACK:
                    mWebView.goBack();
                    break;
                case CMD_NAV_FORWARD:
                    mWebView.goForward();
                    break;
                case CMD_SEND_KEYCODE:
                    dispatchKeyCodes((int[]) msg.obj);
                    notifyCommandDone();
                    break;
                case CMD_MOVE_CURSOR_RIGHTMOST_POS:
                    moveCursorToLeftMostPos((String) msg.obj);
                    notifyCommandDone();
                    break;
            }
        }
    };

    /**
     * Error codes from the WebDriver wire protocol
     * http://code.google.com/p/selenium/wiki/JsonWireProtocol#Response_Status_Codes
     */
    private enum ErrorCode {
        SUCCESS(0),
        NO_SUCH_ELEMENT(7),
        NO_SUCH_FRAME(8),
        UNKNOWN_COMMAND(9),
        UNSUPPORTED_OPERATION(9),  // Alias
        STALE_ELEMENT_REFERENCE(10),
        ELEMENT_NOT_VISISBLE(11),
        INVALID_ELEMENT_STATE(12),
        UNKNOWN_ERROR(13),
        ELEMENT_NOT_SELECTABLE(15),
        XPATH_LOOKUP_ERROR(19),
        NO_SUCH_WINDOW(23),
        INVALID_COOKIE_DOMAIN(24),
        UNABLE_TO_SET_COOKIE(25),
        MODAL_DIALOG_OPENED(26),
        MODAL_DIALOG_OPEN(27),
        SCRIPT_TIMEOUT(28);

        private final int mCode;
        private static ErrorCode[] values = ErrorCode.values();

        ErrorCode(int code) {
            this.mCode = code;
        }

        public int getCode() {
            return mCode;
        }

        public static ErrorCode get(final int intValue) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].getCode() == intValue) {
                    return values[i];
                }
            }
            return UNKNOWN_ERROR;
        }
    }

    public WebDriver(WebView webview) {
        this.mWebView = webview;
        mWebView.requestFocus();
        if (mWebView == null) {
            throw new IllegalArgumentException("WebView cannot be null");
        }
        if (!mWebView.getSettings().getJavaScriptEnabled()) {
            throw new RuntimeException("Javascript is disabled in the WebView. "
                    + "Enable it to use WebDriver");
        }
        shouldRunInMainThread(true);

        mSyncObject = new Object();
        mSyncPageLoad = new Object();
        this.mWebView = webview;
        WebChromeClientWrapper chromeWrapper = new WebChromeClientWrapper(
                webview.getWebChromeClient(), this);
        mWebView.setWebChromeClient(chromeWrapper);
        WebViewClientWrapper viewWrapper = new WebViewClientWrapper(
                webview.getWebViewClient(), this);
        mWebView.setWebViewClient(viewWrapper);
        mWebView.addJavascriptInterface(new JavascriptResultReady(),
                "webdriver");
        mDocumentElement = new WebElement(this, "");
        mNavigation = new Navigation();
    }

    /**
     * @return The title of the current page, null if not set.
     */
    public String getTitle() {
        return mWebView.getTitle();
    }

    /**
     * Loads a URL in the WebView. This function is blocking and will return
     * when the page has finished loading.
     *
     * @param url The URL to load.
     */
    public void get(String url) {
        mNavigation.to(url);
    }

    /**
     * @return The source page of the currently loaded page in WebView.
     */
    public String getPageSource() {
        return (String) executeScript("return new XMLSerializer()."
                + "serializeToString(document);");
    }

    /**
     * Find the first {@link android.webkit.webdriver.WebElement} using the
     * given method.
     *
     * @param by The locating mechanism to use.
     * @return The first matching element on the current context.
     * @throws {@link android.webkit.webdriver.WebElementNotFoundException} if
     * no matching element was found.
     */
    public WebElement findElement(By by) {
        checkNotNull(mDocumentElement, "Load a page using WebDriver.get() "
                + "before looking for elements.");
        return by.findElement(mDocumentElement);
    }

    /**
     * Finds all {@link android.webkit.webdriver.WebElement} within the page
     * using the given method.
     *
     * @param by The locating mechanism to use.
     * @return A list of all {@link android.webkit.webdriver.WebElement} found,
     * or an empty list if nothing matches.
     */
    public List<WebElement> findElements(By by) {
        checkNotNull(mDocumentElement, "Load a page using WebDriver.get() "
                + "before looking for elements.");
        return by.findElements(mDocumentElement);
    }

    /**
     * Clears the WebView's state and closes associated views.
     */
    public void quit() {
        mWebView.clearCache(true);
        mWebView.clearFormData();
        mWebView.clearHistory();
        mWebView.clearSslPreferences();
        mWebView.clearView();
        mWebView.removeAllViewsInLayout();
    }

    /**
     * Executes javascript in the context of the main frame.
     *
     * If the script has a return value the following happens:
     * <ul>
     * <li>For an HTML element, this method returns a WebElement</li>
     * <li>For a decimal, a Double is returned</li>
     * <li>For non-decimal number, a Long is returned</li>
     * <li>For a boolean, a Boolean is returned</li>
     * <li>For all other cases, a String is returned</li>
     * <li>For an array, this returns a List<Object> with each object
     * following the rules above.</li>
     * <li>For an object literal this returns a Map<String, Object>. Note that
     * Object literals keys can only be Strings. Non Strings keys will
     * be filtered out.</li>
     * </ul>
     *
     * <p> Arguments must be a number, a boolean, a string a WebElement or
     * a list of any combination of the above. The arguments will be made
     * available to the javascript via the "arguments" magic variable,
     * as if the function was called via "Function.apply".
     *
     * @param script The JavaScript to execute.
     * @param args The arguments to the script. Can be any of a number, boolean,
     * string, WebElement or a List of those.
     * @return A Boolean, Long, Double, String, WebElement, List or null.
     */
    public Object executeScript(final String script, final Object... args) {
        String scriptArgs = "[" + convertToJsArgs(args) + "]";
        String injectScriptJs = getResourceAsString(R.raw.execute_script_android);
        return executeRawJavascript("(" + injectScriptJs +
                ")(" + escapeAndQuote(script) + ", " + scriptArgs + ", true)");
    }

    public Navigation navigate() {
        return mNavigation;
    }


    /**
     * @hide
     */
    public class Navigation {
        /* package */ Navigation () {}

        public void back() {
            navigate(CMD_NAV_BACK, null);
        }

        public void forward() {
            navigate(CMD_NAV_FORWARD, null);
        }

        public void to(String url) {
            navigate(CMD_GET_URL, url);
        }

        public void refresh() {
            navigate(CMD_NAV_REFRESH, null);
        }

        private void navigate(int command, String url) {
            synchronized (mSyncPageLoad) {
                mPageFinishedLoading = false;
                Message msg = mHandler.obtainMessage(command);
                msg.obj = url;
                mHandler.sendMessage(msg);
                waitForPageLoad();
            }
        }
    }

    /**
     * Converts the arguments passed to a JavaScript friendly format.
     *
     * @param args The arguments to convert.
     * @return Comma separated Strings containing the arguments.
     */
    /* package */ String convertToJsArgs(final Object... args) {
        StringBuilder toReturn = new StringBuilder();
        int length = args.length;
        for (int i = 0; i < length; i++) {
            toReturn.append((i > 0) ? "," : "");
            if (args[i] instanceof List<?>) {
                toReturn.append("[");
                List<Object> aList = (List<Object>) args[i];
                for (int j = 0 ; j < aList.size(); j++) {
                    String comma = ((j == 0) ? "" : ",");
                    toReturn.append(comma + convertToJsArgs(aList.get(j)));
                }
                toReturn.append("]");
            } else if (args[i] instanceof Map<?, ?>) {
                Map<Object, Object> aMap = (Map<Object, Object>) args[i];
                String toAdd = "{";
                for (Object key: aMap.keySet()) {
                    toAdd += key + ":"
                            + convertToJsArgs(aMap.get(key)) + ",";
                }
                toReturn.append(toAdd.substring(0, toAdd.length() -1) + "}");
            } else if (args[i] instanceof WebElement) {
                // WebElement are represented in JavaScript by Objects as
                // follow: {ELEMENT:"id"} where "id" refers to the id
                // of the HTML element in the javascript cache that can
                // be accessed throught bot.inject.cache.getCache_()
                toReturn.append("{\"" + ELEMENT_KEY + "\":\""
                        + ((WebElement) args[i]).getId() + "\"}");
            } else if (args[i] instanceof Number || args[i] instanceof Boolean) {
                toReturn.append(String.valueOf(args[i]));
            } else if (args[i] instanceof String) {
                toReturn.append(escapeAndQuote((String) args[i]));
            } else {
                throw new IllegalArgumentException(
                        "Javascript arguments can be "
                        + "a Number, a Boolean, a String, a WebElement, "
                        + "or a List or a Map of those. Got: "
                        + ((args[i] == null) ? "null" : args[i].getClass()
                        + ", value: " + args[i].toString()));
            }
        }
        return toReturn.toString();
    }

    /* package */ Object executeRawJavascript(final String script) {
        if (mWebView.getUrl() == null) {
            throw new WebDriverException("Cannot operate on a blank page. "
                    + "Load a page using WebDriver.get().");
        }
        String result = executeCommand(CMD_EXECUTE_SCRIPT,
                "if (!window.webdriver || !window.webdriver.resultReady) {" +
                "  return;" +
                "}" +
                "window.webdriver.resultReady(" + script + ")",
                JS_EXECUTION_TIMEOUT);
        if (result == null || "undefined".equals(result)) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(result);
            throwIfError(json);
            Object value = json.get(VALUE);
            return convertJsonToJavaObject(value);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse JavaScript result: "
                    + result.toString(), e);
        }
    }

    /* package */ String getResourceAsString(final int resourceId) {
        InputStream is = mWebView.getResources().openRawResource(resourceId);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            is.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open JavaScript resource.", e);
        }
        return sb.toString();
    }

    /* package */ void sendTouchScreen(Point coords) {
        // Reset state
        resetPageLoadState();
        executeCommand(CMD_SEND_TOUCH, coords,LOADING_TIMEOUT);
        // Wait for the events to be fully handled
        waitForMessageRelay(MOTION_EVENT_TIMEOUT);

        // If a page started loading, block until page finishes loading
        waitForPageLoadIfNeeded();
    }

    /* package */ void resetPageLoadState() {
        synchronized (mSyncPageLoad) {
            mPageStartedLoading = false;
            mPageFinishedLoading = false;
        }
    }

    /* package */ void waitForPageLoadIfNeeded() {
        synchronized (mSyncPageLoad) {
            Long end = System.currentTimeMillis() + PAGE_STARTED_LOADING;
            // Wait PAGE_STARTED_LOADING milliseconds to see if we detect a
            // page load.
            while (!mPageStartedLoading && (System.currentTimeMillis() <= end)) {
                try {
                    // This is notified by WebChromeClientWrapper#onProgressChanged
                    // when the page finished loading.
                    mSyncPageLoad.wait(PAGE_STARTED_LOADING);
                } catch (InterruptedException e) {
                    new RuntimeException(e);
                }
            }
            if (mPageStartedLoading) {
                waitForPageLoad();
            }
        }
    }

    private void touchScreen(Point coords) {
        // Convert to screen coords
        // screen = JS x zoom - offset
        float zoom = mWebView.getScale();
        float xOffset = mWebView.getX();
        float yOffset = mWebView.getY();
        Point screenCoords = new Point( (int)(coords.x*zoom - xOffset),
                (int)(coords.y*zoom - yOffset));

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime,
                SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, screenCoords.x,
                screenCoords.y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up = MotionEvent.obtain(downTime,
                SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, screenCoords.x,
                screenCoords.y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // Dispatch the events to WebView
        mWebView.dispatchTouchEvent(down);
        mWebView.dispatchTouchEvent(up);
    }

    /* package */ void notifyPageStartedLoading() {
        synchronized (mSyncPageLoad) {
            mPageStartedLoading = true;
            mSyncPageLoad.notify();
        }
    }

    /* package */ void notifyPageFinishedLoading() {
        synchronized (mSyncPageLoad) {
            mPageFinishedLoading = true;
            mSyncPageLoad.notify();
        }
    }

    /**
     *
     * @param keys The first element of the CharSequence should be the
     * existing value in the text input, or the empty string if none.
     */
    /* package */ void sendKeys(CharSequence[] keys) {
        executeCommand(CMD_SEND_KEYS, keys, KEY_EVENT_TIMEOUT);
        // Wait for all KeyEvents to be handled
        waitForMessageRelay(KEY_EVENT_TIMEOUT);
    }

    /* package */ void sendKeyCodes(int[] keycodes) {
        executeCommand(CMD_SEND_KEYCODE, keycodes, KEY_EVENT_TIMEOUT);
        // Wait for all KeyEvents to be handled
        waitForMessageRelay(KEY_EVENT_TIMEOUT);
    }

    /* package */ void moveCursorToRightMostPosition(String value) {
        executeCommand(CMD_MOVE_CURSOR_RIGHTMOST_POS, value, KEY_EVENT_TIMEOUT);
        waitForMessageRelay(KEY_EVENT_TIMEOUT);
    }

    private void moveCursorToLeftMostPos(String value) {
        // If there is text, move the cursor to the rightmost position
        if (value != null && !value.equals("")) {
            long downTime = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(downTime, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0);
            KeyEvent up = new KeyEvent(downTime, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT,
                    value.length());
            mWebView.dispatchKeyEvent(down);
            mWebView.dispatchKeyEvent(up);
        }
    }

    private void dispatchKeyCodes(int[] keycodes) {
        for (int i = 0; i < keycodes.length; i++) {
            KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, keycodes[i]);
            KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, keycodes[i]);
            mWebView.dispatchKeyEvent(down);
            mWebView.dispatchKeyEvent(up);
        }
    }

    private void dispatchKeys(CharSequence[] keys) {
        KeyCharacterMap chararcterMap = KeyCharacterMap.load(
                KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < keys.length; i++) {
            CharSequence s = keys[i];
            for (int j = 0; j < s.length(); j++) {
                KeyEvent[] events =
                        chararcterMap.getEvents(new char[]{s.charAt(j)});
                for (KeyEvent e : events) {
                    mWebView.dispatchKeyEvent(e);
                }
            }
        }
    }

    private void waitForMessageRelay(long timeout) {
        synchronized (mSyncObject) {
            mCommandDone = false;
        }
        Message msg = Message.obtain();
        msg.what = WebViewCore.EventHub.MESSAGE_RELAY;
        Message echo = mHandler.obtainMessage(CMD_MESSAGE_RELAY_ECHO);
        msg.obj = echo;

        mWebView.getWebViewCore().sendMessage(msg);
        synchronized (mSyncObject) {
            long end  = System.currentTimeMillis() + timeout;
            while (!mCommandDone && (System.currentTimeMillis() <= end)) {
                try {
                    // This is notifed by the mHandler when it receives the
                    // MESSAGE_RELAY back
                    mSyncObject.wait(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void waitForPageLoad() {
        long endLoad = System.currentTimeMillis() + LOADING_TIMEOUT;
        while (!mPageFinishedLoading
                && (System.currentTimeMillis() <= endLoad)) {
            try {
                mSyncPageLoad.wait(LOADING_TIMEOUT);
            } catch (InterruptedException e) {
                throw new RuntimeException();
            }
        }
    }

    /**
     * Wraps the given string into quotes and escape existing quotes
     * and backslashes.
     * "foo" -> "\"foo\""
     * "foo\"" -> "\"foo\\\"\""
     * "fo\o" -> "\"fo\\o\""
     *
     * @param toWrap The String to wrap in quotes
     * @return a String wrapping the original String in quotes
     */
    private static String escapeAndQuote(final String toWrap) {
        StringBuilder toReturn = new StringBuilder("\"");
        for (int i = 0; i < toWrap.length(); i++) {
            char c = toWrap.charAt(i);
            if (c == '\"') {
                toReturn.append("\\\"");
            } else if (c == '\\') {
                toReturn.append("\\\\");
            } else {
                toReturn.append(c);
            }
        }
        toReturn.append("\"");
        return toReturn.toString();
    }

    private Object convertJsonToJavaObject(final Object toConvert) {
        try {
            if (toConvert == null
                    || toConvert.equals(null)
                    || "undefined".equals(toConvert)
                    || "null".equals(toConvert)) {
                return null;
            } else if (toConvert instanceof Boolean) {
                return toConvert;
            } else if (toConvert instanceof Double
                    || toConvert instanceof Float) {
                return Double.valueOf(String.valueOf(toConvert));
            } else if (toConvert instanceof Integer
                    || toConvert instanceof Long) {
              return Long.valueOf(String.valueOf(toConvert));
            } else if (toConvert instanceof JSONArray) { // List
                return convertJsonArrayToList((JSONArray) toConvert);
            } else if (toConvert instanceof JSONObject) { // Map or WebElment
                JSONObject map = (JSONObject) toConvert;
                if (map.opt(ELEMENT_KEY) != null) { // WebElement
                    return new WebElement(this, (String) map.get(ELEMENT_KEY));
                } else { // Map
                    return convertJsonObjectToMap(map);
                }
            } else {
                return toConvert.toString();
            }
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse JavaScript result: "
                    + toConvert.toString(), e);
        }
    }

    private List<Object> convertJsonArrayToList(final JSONArray json) {
        List<Object> toReturn = Lists.newArrayList();
        for (int i = 0; i < json.length(); i++) {
            try {
                toReturn.add(convertJsonToJavaObject(json.get(i)));
            } catch (JSONException e) {
                throw new RuntimeException("Failed to parse JSON: "
                        + json.toString(), e);
            }
        }
        return toReturn;
    }

    private Map<Object, Object> convertJsonObjectToMap(final JSONObject json) {
        Map<Object, Object> toReturn = Maps.newHashMap();
        for (Iterator it = json.keys(); it.hasNext();) {
            String key = (String) it.next();
            try {
                Object value = json.get(key);
                toReturn.put(convertJsonToJavaObject(key),
                        convertJsonToJavaObject(value));
            } catch (JSONException e) {
                throw new RuntimeException("Failed to parse JSON:"
                        + json.toString(), e);
            }
        }
        return toReturn;
    }

    private void throwIfError(final JSONObject jsonObject) {
        ErrorCode status;
        String errorMsg;
        try {
            status = ErrorCode.get((Integer) jsonObject.get(STATUS));
            errorMsg  = String.valueOf(jsonObject.get(VALUE));
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse JSON Object: "
                    + jsonObject, e);
        }
        switch (status) {
            case SUCCESS:
                return;
            case NO_SUCH_ELEMENT:
                throw new WebElementNotFoundException("Could not find "
                        + "WebElement.");
            case STALE_ELEMENT_REFERENCE:
                throw new WebElementStaleException("WebElement is stale.");
            default:
                throw new WebDriverException("Error: " + errorMsg);
        }
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
        public void resultReady(final String result) {
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
    private String executeCommand(int command, final Object arg, long timeout) {
        shouldRunInMainThread(false);
        synchronized (mSyncObject) {
            mCommandDone = false;
            Message msg = mHandler.obtainMessage(command);
            msg.obj = arg;
            mHandler.sendMessage(msg);

            long end = System.currentTimeMillis() + timeout;
            while (!mCommandDone) {
                if (System.currentTimeMillis() >= end) {
                    throw new RuntimeException("Timeout executing command: "
                            + command);
                }
                try {
                    mSyncObject.wait(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return mJsResult;
    }

    private void checkNotNull(Object obj, String errosMsg) {
        if (obj == null) {
            throw new NullPointerException(errosMsg);
        }
    }
}
