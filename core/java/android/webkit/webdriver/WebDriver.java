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

import android.os.Handler;
import android.os.Message;
import android.webkit.WebView;

import com.android.internal.R;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

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

    // Commands posted to the handler
    private static final int CMD_GET_URL = 1;
    private static final int CMD_EXECUTE_SCRIPT = 2;

    private static final String ELEMENT_KEY = "ELEMENT";
    private static final String STATUS = "status";
    private static final String VALUE = "value";

    private static final long MAIN_THREAD = Thread.currentThread().getId();

    // This is updated by a callabck from JavaScript when the result is ready.
    private String mJsResult;

    // Used for synchronization
    private final Object mSyncObject;

    // Updated when the command is done executing in the main thread.
    private volatile boolean mCommandDone;

    private WebView mWebView;

    // This WebElement represents the object document.documentElement
    private WebElement mDocumentElement;

    // This Handler runs in the main UI thread.
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == CMD_GET_URL) {
                final String url = (String) msg.obj;
                mWebView.loadUrl(url);
            } else if (msg.what == CMD_EXECUTE_SCRIPT) {
                mWebView.loadUrl("javascript:" + (String) msg.obj);
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
            throw new IllegalArgumentException(intValue
                    + " does not map to any ErrorCode.");
        }
    }

    public WebDriver(WebView webview) {
        this.mWebView = webview;
        if (mWebView == null) {
            throw new IllegalArgumentException("WebView cannot be null");
        }
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
        executeCommand(CMD_GET_URL, url, LOADING_TIMEOUT);
        mDocumentElement = (WebElement) executeScript("return document.body;");
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
     * Clears the WebView.
     */
    public void quit() {
        mWebView.clearCache(true);
        mWebView.clearFormData();
        mWebView.clearHistory();
        mWebView.clearSslPreferences();
        mWebView.clearView();
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

    /**
     * Converts the arguments passed to a JavaScript friendly format.
     *
     * @param args The arguments to convert.
     * @return Comma separated Strings containing the arguments.
     */
    /*package*/ String convertToJsArgs(final Object... args) {
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

    /*package*/ Object executeRawJavascript(final String script) {
        String result = executeCommand(CMD_EXECUTE_SCRIPT,
                "window.webdriver.resultReady(" + script + ")",
                JS_EXECUTION_TIMEOUT);
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

    /*package*/ String getResourceAsString(final int resourceId) {
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
                    throw new RuntimeException("Timeout executing command.");
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
