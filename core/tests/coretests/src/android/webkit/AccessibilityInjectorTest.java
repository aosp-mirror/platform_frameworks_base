/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * This is a test for the behavior of the {@link AccessibilityInjector}
 * which is used by {@link WebView} to provide basic accessibility support
 * in case JavaScript is disabled.
 * </p>
 * Note: This test works against the generated {@link AccessibilityEvent}s
 *       to so it also checks if the test for announcing navigation axis and
 *       status messages as appropriate.
 */
public class AccessibilityInjectorTest extends AndroidTestCase {

    /** The timeout to wait for the expected selection. */
    private static final long TIMEOUT_WAIT_FOR_SELECTION_STRING = 1000;

    /** The timeout to wait for accessibility and the mock service to be enabled. */
    private static final long TIMEOUT_ENABLE_ACCESSIBILITY_AND_MOCK_SERVICE = 500;

    /** The count of tests to detect when to shut down the service. */
    private static final int TEST_CASE_COUNT = 8;

    /** The meta state for pressed left ALT. */
    private static final int META_STATE_ALT_LEFT_ON = KeyEvent.META_ALT_ON
            | KeyEvent.META_ALT_LEFT_ON;

    /** The value for not specified selection string since null is a valid value. */
    private static final String SELECTION_STRING_UNKNOWN = "Unknown";

    /** Lock for locking the test. */
    private static final Object sTestLock = new Object();

    /** Handle to the test for use by the mock service. */
    private static AccessibilityInjectorTest sInstance;

    /** Flag indicating if the accessibility service is ready to receive events. */
    private static boolean sIsAccessibilityServiceReady;

    /** The count of executed tests to detect when to toggle accessibility and the service. */
    private static int sExecutedTestCount;

    /** Worker thread with a handler to perform non test thread processing. */
    private Worker mWorker;

    /** Handle to the {@link WebView} to load data in. */
    private WebView mWebView;

    /** The received selection string for assertion checking. */
    private static String sReceivedSelectionString = SELECTION_STRING_UNKNOWN;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mWorker = new Worker();
        sInstance = this;
        if (sExecutedTestCount == 0) {
            // until JUnit4 comes to play with @BeforeTest
            disableAccessibilityAndMockAccessibilityService();
            enableAccessibilityAndMockAccessibilityService();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (mWorker != null) {
            mWorker.stop();
        }
        if (sExecutedTestCount == TEST_CASE_COUNT) {
            // until JUnit4 comes to play with @AfterTest
            disableAccessibilityAndMockAccessibilityService();
        }
        super.tearDown();
    }

    /**
     * Tests navigation by character.
     */
    @LargeTest
    public void testNavigationByCharacter() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<html>" +
               "<head>" +
               "</head>" +
               "<body>" +
                   "<p>" +
                      "a <b>b</b> c" +
                   "</p>" +
                   "<p>" +
                     "d" +
                     "<input>e</input>" +
                   "</p>" +
               "</body>" +
             "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, META_STATE_ALT_LEFT_ON);
        assertSelectionString("1"); // expect the word navigation axis

        // change navigation axis to character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, META_STATE_ALT_LEFT_ON);
        assertSelectionString("0"); // expect the character navigation axis

        // go to the first character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("a");

        // go to the second character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<b>b</b>");

        // go to the third character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("c");

        // go to the fourth character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("d");

        // go to the fifth character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("e");

        // try to go past the last character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the fourth character (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("d");

        // go to the third character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("c");

        // go to the second character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<b>b</b>");

        // go to the first character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("a");

        // try to go before the first character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the second character (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<b>b</b>");
    }

    /**
     * Tests navigation by word.
     */
    @LargeTest
    public void testNavigationByWord() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<html>" +
               "<head>" +
               "</head>" +
               "<body>" +
                   "<p>" +
                      "This is <b>a</b> sentence" +
                   "</p>" +
                   "<p>" +
                     " scattered " +
                     "<input>all</input>" +
                     " over " +
                   "</p>" +
                   "<div>" +
                     "<button>the place.</button>" +
                   "</div>" +
               "</body>" +
             "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, META_STATE_ALT_LEFT_ON);
        assertSelectionString("1"); // expect the word navigation axis

        // go to the first word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This");

        // go to the second word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("is");

        // go to the third word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<b>a</b>");

        // go to the fourth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("sentence");

        // go to the fifth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("scattered");

        // go to the sixth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("all");

        // go to the seventh word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("over");

        // go to the eight word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("the");

        // go to the ninth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("place");

        // NOTE: WebKit selection returns the dot as a word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(".");

        // try to go past the last word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the last word (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("place");

        // go to the eight word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("the");

        // go to the seventh word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("over");

        // go to the sixth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("all");

        // go to the fifth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("scattered");

        // go to the fourth word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("sentence");

        // go to the third word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<b>a</b>");

        // go to the second word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("is");

        // go to the first word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("This");

        // try to go before the first word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the second word (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("is");
    }

    /**
     * Tests navigation by sentence.
     */
    @LargeTest
    public void testNavigationBySentence() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<div>" +
                  "<p>" +
                    "This is the first sentence of the first paragraph and has an <b>inline bold tag</b>." +
                    "This is the second sentence of the first paragraph." +
                  "</p>" +
                  "<h1>This is a heading</h1>" +
                  "<p>" +
                    "This is the first sentence of the second paragraph." +
                    "This is the second sentence of the second paragraph." +
                  "</p>" +
                "</div>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // Sentence axis is the default

        // go to the first sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is the first sentence of the first paragraph and has an "
                + "<b>inline bold tag</b>.");

        // go to the second sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is the second sentence of the first paragraph.");

        // go to the third sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is a heading");

        // go to the fourth sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is the first sentence of the second paragraph.");

        // go to the fifth sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is the second sentence of the second paragraph.");

        // try to go past the last sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the fourth sentence (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("This is the first sentence of the second paragraph.");

        // go to the third sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("This is a heading");

        // go to the second sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("This is the second sentence of the first paragraph.");

        // go to the first sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("This is the first sentence of the first paragraph and has an "
                + "<b>inline bold tag</b>.");

        // try to go before the first sentence
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the second sentence (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("This is the second sentence of the first paragraph.");
    }

    /**
     * Tests navigation by heading.
     */
    @LargeTest
    public void testNavigationByHeading() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<!DOCTYPE html>" +
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<h1>Heading one</h1>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<h2>Heading two</h2>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<h3>Heading three</h3>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<h4>Heading four</h4>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<h5>Heading five</h5>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<h6>Heading six</h6>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("3"); // expect the heading navigation axis

        // go to the first heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h1>Heading one</h1>");

        // go to the second heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h2>Heading two</h2>");

        // go to the third heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h3>Heading three</h3>");

        // go to the fourth heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h4>Heading four</h4>");

        // go to the fifth heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h5>Heading five</h5>");

        // go to the sixth heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h6>Heading six</h6>");

        // try to go past the last heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the fifth heading (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h5>Heading five</h5>");

        // go to the fourth heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h4>Heading four</h4>");

        // go to the third heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h3>Heading three</h3>");

        // go to the second heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h2>Heading two</h2>");

        // go to the first heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h1>Heading one</h1>");

        // try to go before the first heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the second heading (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h2>Heading two</h2>");
    }

    /**
     * Tests navigation by sibling.
     */
    @LargeTest
    public void testNavigationBySibing() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<!DOCTYPE html>" +
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<h1>Heading one</h1>" +
                "<p>" +
                  "This is some text" +
                "</p>" +
                "<div>" +
                  "<button>Input</button>" +
                "</div>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("3"); // expect the heading navigation axis

        // change navigation axis to sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("4"); // expect the sibling navigation axis

        // change navigation axis to parent/first child
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("5"); // expect the parent/first child navigation axis

        // go to the first child of the body
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<h1>Heading one</h1>");

        // change navigation axis to sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_LEFT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("4"); // expect the sibling navigation axis

        // go to the next sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<p>This is some text</p>");

        // go to the next sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<div><button>Input</button></div>");

        // try to go past the last sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the previous sibling (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<p>This is some text</p>");

        // go to the previous sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<h1>Heading one</h1>");

        // try to go before the previous sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the next sibling (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<p>This is some text</p>");
    }

    /**
     * Tests navigation by parent/first child.
     */
    @LargeTest
    public void testNavigationByParentFirstChild() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<!DOCTYPE html>" +
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<div>" +
                  "<button>Input</button>" +
                "</div>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to document
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_LEFT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("6"); // expect the document navigation axis

        // change navigation axis to parent/first child
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_LEFT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("5"); // expect the parent/first child navigation axis

        // go to the first child
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<div><button>Input</button></div>");

        // go to the first child
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<button>Input</button>");

        // try to go to the first child of a leaf element
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString(null);

        // go to the parent (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<div><button>Input</button></div>");

        // go to the parent
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<body><div><button>Input</button></div></body>");

        // try to go to the body parent
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString(null);

        // go to the first child (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<div><button>Input</button></div>");
    }

    /**
     * Tests navigation by document.
     */
    @LargeTest
    public void testNavigationByDocument() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<!DOCTYPE html>" +
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<button>Click</button>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to document
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_LEFT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("6"); // expect the document navigation axis

        // go to the bottom of the document
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("Click");

        // go to the top of the document (reverse)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertSelectionString("<body><button>Click</button></body>");

        // go to the bottom of the document (reverse again)
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("Click");
    }

    /**
     * Tests the sync between the text navigation and navigation by DOM elements.
     */
    @LargeTest
    public void testSyncBetweenTextAndDomNodeNavigation() throws Exception {
        // a bit ugly but helps detect beginning and end of all tests so accessibility
        // and the mock service are not toggled on every test (expensive)
        sExecutedTestCount++;

        String html =
            "<!DOCTYPE html>" +
            "<html>" +
              "<head>" +
              "</head>" +
              "<body>" +
                "<p>" +
                  "First" +
                "</p>" +
                "<button>Second</button>" +
                "<p>" +
                  "Third" +
                "</p>" +
              "</body>" +
            "</html>";

        WebView webView = createWebVewWithHtml(html);

        // change navigation axis to word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, META_STATE_ALT_LEFT_ON);
        assertSelectionString("1"); // expect the word navigation axis

        // go to the first word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("First");

        // change navigation axis to heading
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("3"); // expect the heading navigation axis

        // change navigation axis to sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_RIGHT, META_STATE_ALT_LEFT_ON);
        assertSelectionString("4"); // expect the sibling navigation axis

        // go to the next sibling
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("<button>Second</button>");

        // change navigation axis to character
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, META_STATE_ALT_LEFT_ON);
        assertSelectionString("0"); // expect the character navigation axis

        // change navigation axis to word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_UP, META_STATE_ALT_LEFT_ON);
        assertSelectionString("1"); // expect the word navigation axis

        // go to the next word
        sendKeyEvent(webView, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertSelectionString("Third");
    }

    /**
     * Enable accessibility and the mock accessibility service.
     */
    private void enableAccessibilityAndMockAccessibilityService() {
        // make sure the manager is instantiated so the system initializes it
        AccessibilityManager.getInstance(getContext());

        // enable accessibility and the mock accessibility service
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        String enabledServices = new ComponentName(getContext().getPackageName(),
                MockAccessibilityService.class.getName()).flattenToShortString();
        Settings.Secure.putString(getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices);

        // poll within a timeout and let be interrupted in case of success
        long incrementStep = TIMEOUT_ENABLE_ACCESSIBILITY_AND_MOCK_SERVICE / 5;
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < TIMEOUT_ENABLE_ACCESSIBILITY_AND_MOCK_SERVICE &&
                !sIsAccessibilityServiceReady) {
            synchronized (sTestLock) {
                try {
                    sTestLock.wait(incrementStep);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }

        if (!sIsAccessibilityServiceReady) {
            throw new IllegalStateException("MockAccessibilityService not ready. Did you add " +
                    "tests and forgot to update AccessibilityInjectorTest#TEST_CASE_COUNT?");
        }
    }

    @Override
    protected void scrubClass(Class<?> testCaseClass) {
        /* do nothing - avoid superclass behavior */
    }

    /**
     * Disables accessibility and the mock accessibility service.
     */
    private void disableAccessibilityAndMockAccessibilityService() {
        // disable accessibility and the mock accessibility service
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.putString(getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
    }

    /**
     * Asserts the next <code>expectedSelectionString</code> to be received.
     */
    private void assertSelectionString(String expectedSelectionString) {
        assertTrue("MockAccessibilityService not ready", sIsAccessibilityServiceReady);

        long incrementStep = TIMEOUT_WAIT_FOR_SELECTION_STRING / 5;
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < TIMEOUT_WAIT_FOR_SELECTION_STRING &&
                sReceivedSelectionString == SELECTION_STRING_UNKNOWN) {
            synchronized (sTestLock) {
                try {
                    sTestLock.wait(incrementStep);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
        try {
            if (sReceivedSelectionString == SELECTION_STRING_UNKNOWN) {
                fail("No selection string received. Expected: " + expectedSelectionString);
            }
            assertEquals(expectedSelectionString, sReceivedSelectionString);
        } finally {
            sReceivedSelectionString = SELECTION_STRING_UNKNOWN;
        }
    }

    /**
     * Sends a {@link KeyEvent} (up and down) to the {@link WebView}.
     *
     * @param keyCode The event key code.
     */
    private void sendKeyEvent(WebView webView, int keyCode, int metaState) {
        webView.onKeyDown(keyCode, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 1, metaState));
        webView.onKeyUp(keyCode, new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 1, metaState));
    }

    /**
     * Creates a {@link WebView} with with a given HTML content.
     *
     * @param html The HTML content;
     * @return The created view.
     */
    private WebView createWebVewWithHtml(final String html) {
        mWorker.getHandler().post(new Runnable() {
            public void run() {
                mWebView = new WebView(getContext());
                mWebView.loadData(html, "text/html", "utf-8");
                mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        mWorker.getHandler().post(new Runnable() {
                            public void run() {
                                synchronized (sTestLock) {
                                    sTestLock.notifyAll();
                                }
                            }
                        });
                    }
                });
            }
        });
        synchronized (sTestLock) {
            try {
                sTestLock.wait();
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        return mWebView;
    }

    /**
     * This is a worker thread responsible for creating the {@link WebView}.
     */
    private class Worker implements Runnable {
        private final Object mWorkerLock = new Object();
        private Handler mHandler;

       public Worker() {
            new Thread(this).start();
            synchronized (mWorkerLock) {
                while (mHandler == null) {
                    try {
                        mWorkerLock.wait();
                    } catch (InterruptedException ex) {
                        /* ignore */
                    }
                }
            }
        }

        public void run() {
            synchronized (mWorkerLock) {
                Looper.prepare();
                mHandler = new Handler();
                mWorkerLock.notifyAll();
            }
            Looper.loop();
        }

        public Handler getHandler() {
            return mHandler;
        }

        public void stop() {
            mHandler.getLooper().quit();
        }
    }

    /**
     * Mock accessibility service to receive the accessibility events
     * with the current {@link WebView} selection.
     */
    public static class MockAccessibilityService extends AccessibilityService {
        private boolean mIsServiceInfoSet;

        @Override
        protected void onServiceConnected() {
            if (mIsServiceInfoSet) {
                return;
            }
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_SELECTED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            setServiceInfo(info);
            mIsServiceInfoSet = true;

            sIsAccessibilityServiceReady = true;

            if (sInstance == null) {
                return;
            }
            synchronized (sTestLock) {
                sTestLock.notifyAll();
            }
        }

        @Override
        public void onAccessibilityEvent(AccessibilityEvent event) {
            if (sInstance == null) {
                return;
            }
            if (!event.getText().isEmpty()) {
                CharSequence text = event.getText().get(0);
                sReceivedSelectionString = (text != null) ? text.toString() : null;
            }
            synchronized (sTestLock) {
                sTestLock.notifyAll();
            }
        }

        @Override
        public void onInterrupt() {
            /* do nothing */
        }

        @Override
        public boolean onUnbind(Intent intent) {
            sIsAccessibilityServiceReady = false;
            return false;
        }
    }
}
