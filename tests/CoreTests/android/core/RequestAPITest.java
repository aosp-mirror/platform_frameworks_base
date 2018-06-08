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

package android.core;

import android.net.http.RequestHandle;
import android.net.http.RequestQueue;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;
import android.webkit.CookieSyncManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Container class for all RequestAPI tests
 */
//http://b/issue?id=1200337
@Suppress
public class RequestAPITest extends AndroidTestCase implements HttpConstants {
    private static final String LOGTAG = "http";

    /*
      Other tests to write
      GET, HEAD, POST with differing parameters to RequestQueue
      More reuse and pipelining tests - testing for server closing unexpectedly
    */

    // Sync object for synchronizing end of each test that does communications
    public static Object syncObj = new Object();

    private RequestQueue mRequestQueue;
    private TestWebServer mTestWebServer;

    protected void setUp() throws Exception {
        super.setUp();
        Log.d(LOGTAG, "Base setup context = " + mContext);
        mRequestQueue = new RequestQueue(mContext);
        CookieSyncManager.createInstance(mContext);

        mTestWebServer = new TestWebServer();
        mTestWebServer.initServer(8080, true);
    }

    protected void tearDown() throws Exception {
        Log.d(LOGTAG, "Base tearDown");
        mTestWebServer.close();
        Log.d(LOGTAG, "Base teardown done");

        super.tearDown();
    }

    public void verifyFailure(Map<String, String> headers) {
        try {
            RequestHandle handle =
                    mRequestQueue.queueRequest(
                            "http://localhost:8080/test1", "GET", headers, null,
                            null, 0);

            handle.waitUntilComplete();
            fail("expected exception not thrown");
        } catch (RuntimeException e) {
            // expected
        }
    }

    public void testRequestAddNullHeader() throws Exception {
        /**
         * Test Request.addHeader throws a NullPointerException if a null
         * header is attempted to be set
         */
        Log.d(LOGTAG, "testRequestAddNullHeader start ");
        Map<String, String> headers = new HashMap<>();
        headers.put(null, null);
        verifyFailure(headers);
        Log.d(LOGTAG, "testRequestAddNullHeader - returning");
    }

    public void testRequestAddNullValue() throws Exception {
        /**
         * Test Request.addHeader throws a RuntimeException if a null
         * value is attempted to be set
         */
        Log.d(LOGTAG, "testRequestAddNullValue start ");
        Map<String, String> headers = new HashMap<>();
        headers.put("TestHeader", null);
        verifyFailure(headers);
        Log.d(LOGTAG, "testRequestAddNullValue - returning");
    }

    public void testRequestAddEmptyValue() throws Exception {
        /**
         * Test Request.addEmptyValue throws a RuntimeException if an empty
         * header is attempted to be set
         */
        Log.d(LOGTAG, "testRequestAddEmptyValue start ");
        Map<String, String> headers = new HashMap<>();
        headers.put("TestHeader", "");
        verifyFailure(headers);
        Log.d(LOGTAG, "testRequestAddEmptyValue - returning");
    }

    public void verifySuccess(Map<String, String> headers) {
        mTestWebServer.setKeepAlive(false);
        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", headers, null,
                null, 0);
        handle.waitUntilComplete();
    }

    public void testRequestAddHeader() throws Exception {
        /**
         * Test Request.addHeader with a valid header and value can be set without
         * generating and exception
         */
        Log.d(LOGTAG, "testRequestAddHeader start ");
        Map<String, String> headers = new HashMap<>();
        headers.put("TestHeader", "RequestAddHeader");
        verifySuccess(headers);
        Log.d(LOGTAG, "testRequestAddHeader - returning");
    }

    public void testRequestAddMultiHeader() throws Exception {
        /**
         * Test multiple calls to Request.addHeader with valid headers and values
         * can be set without generating and exception
         */
        Log.d(LOGTAG, "testRequestAddMultiHeader start ");
        Map<String, String> headers = new HashMap<>();
        headers.put("TestHeader", "RequestAddMultiHeader");
        headers.put("TestHeader2", "RequestAddMultiHeader");
        headers.put("TestHeader3", "RequestAddMultiHeader");
        verifySuccess(headers);
        Log.d(LOGTAG, "testRequestAddMultiHeader - returning");
    }

    public void testRequestAddSameHeader() throws Exception {
        /**
         * Test multiple calls to Request.addHeader with valid identical headers
         * and values can be set without generating and exception
         */
        Log.d(LOGTAG, "testRequestAddSameHeader start ");
        Map<String, String> headers = new HashMap<>();
        headers.put("TestHeader", "RequestAddSameHeader");
        headers.put("TestHeader", "RequestAddSameHeader");
        headers.put("TestHeader", "RequestAddSameHeader");
        verifySuccess(headers);
        Log.d(LOGTAG, "testRequestAddSameHeader - returning");
    }

    public void testRequestAddNullHeaders() throws Exception {
        /**
         * Test Request.addHeaders with a null header map. This should not generate
         * any exceptions but accept that there are no headers to add.
         */
        Log.d(LOGTAG, "testRequestAddNullHeaders start ");
        verifySuccess(null);
        Log.d(LOGTAG, "testRequestAddNullHeaders - returning");
    }

    public void testGet() throws Exception {
        /**
         * Test sending a GET request. Test will pass if the events received
         * correspond with the expected response. This should respond with the
         * test data requested.
         */
        TestEventHandler testEventHandler = new TestEventHandler();

        mTestWebServer.setKeepAlive(false);

        Log.d(LOGTAG, "testGet start ");

        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();
        testEventHandler.expectHeaderAdd(requestHeaders[REQ_CONNECTION], "Close");
        testEventHandler.expectHeaderAdd(requestHeaders[REQ_CONTENT_LENGTH], "52");
        testEventHandler.expectHeaderAdd(requestHeaders[REQ_CONTENT_TYPE], "text/html");
        testEventHandler.expectData(52);

        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", null, testEventHandler,
                null, 0);

        Log.d(LOGTAG, "testGet - sent request. Waiting");
        handle.waitUntilComplete();
        Log.d(LOGTAG, "testGet - sent request. Notified");

        if (!testEventHandler.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            fail("expectPassed was false " + testEventHandler.getFailureMessage());
        }
    }

    public void testReuse() throws Exception {
        /**
         * Test sending two GET requests. Test will pass if the events
         * received correspond with the expected response.
         */
        final String TEST_NAME = "testReuse";
        Log.d(LOGTAG, TEST_NAME + " start ");

        TestEventHandler testEventHandler = new TestEventHandler();

        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();

        TestEventHandler testEventHandler2 = new TestEventHandler();
        testEventHandler2.expectStatus(200);
        testEventHandler2.expectHeaders();

        mTestWebServer.setAcceptLimit(2);

        RequestHandle handle0 = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", null, testEventHandler,
                null, 0);
        handle0.waitUntilComplete();
        RequestHandle handle1 = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", null, testEventHandler2,
                null, 0);
        handle1.waitUntilComplete();

        /* It's not correct to use same listener for multiple
           requests.  Otherwise there would be no distiction between
           events delivered for either request. */

        if (!testEventHandler.expectPassed() && !testEventHandler2.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            Log.d(LOGTAG, testEventHandler2.getFailureMessage());
            fail();
        }
        Log.d(LOGTAG, TEST_NAME + " - sent request. Notified");
    }

    public void testHead() throws Exception {
        /**
         * Test sending a HEAD request. Test will pass if the events
         * delivered match the expected response.
         */
        TestEventHandler testEventHandler = new TestEventHandler();

        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();
        testEventHandler.expectNoData();

        mTestWebServer.setKeepAlive(false);
        mTestWebServer.setAcceptLimit(1);


        Log.d(LOGTAG, "testHead start - rq = " + mRequestQueue);

        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "HEAD", null, testEventHandler,
                null, 0);

        Log.d(LOGTAG, "testHead - sent request waiting");
        handle.waitUntilComplete();

        if (!testEventHandler.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            fail("expectPassed was false " + testEventHandler.getFailureMessage());
        }
    }

    public void testChunked() throws Exception {
        TestEventHandler testEventHandler = new TestEventHandler();

        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();

        mTestWebServer.setKeepAlive(false);
        mTestWebServer.setChunked(true);
        mTestWebServer.setAcceptLimit(1);


        Log.d(LOGTAG, "testChunked start - rq = " + mRequestQueue);

        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", null, testEventHandler,
                null, 0);

        Log.d(LOGTAG, "testChunked - sent request waiting");
        handle.waitUntilComplete();

        if (!testEventHandler.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            fail("expectPassed was false " + testEventHandler.getFailureMessage());
        }
    }

    public void verifyRedirect(int statusCode, String testName) throws Exception {
        final String REDIRECT_TO = "http://localhost:8081/test1";

        mTestWebServer.setKeepAlive(false);
        TestWebServer redirectWebServer = new TestWebServer();
        redirectWebServer.initServer(8081, true);
        redirectWebServer.setKeepAlive(false);

        try {
            TestEventHandler testEventHandler = new TestEventHandler();
            // Load up expected response
            testEventHandler.expectStatus(statusCode);
            testEventHandler.expectHeaders();
            testEventHandler.expectHeaderAdd(requestHeaders[REQ_LOCATION], REDIRECT_TO);

            mTestWebServer.setAcceptLimit(1);
            mTestWebServer.setRedirect(REDIRECT_TO, statusCode);
            redirectWebServer.setAcceptLimit(1);

            Log.d(LOGTAG, testName + " start - rq = " + mRequestQueue);

            RequestHandle requestHandle = mRequestQueue.queueRequest(
                    "http://localhost:8080/test1", "GET", null, testEventHandler, null, 0);
            Log.d(LOGTAG, testName + " - sent request waiting");

            requestHandle.waitUntilComplete();

            if (!testEventHandler.expectPassed()) {
                Log.d(LOGTAG, testEventHandler.getFailureMessage());
                fail("expectPassed was false " + testEventHandler.getFailureMessage());
            }

            requestHandle.setupRedirect(REDIRECT_TO, statusCode, new HashMap<String, String>());

            testEventHandler.expectStatus(HttpConstants.HTTP_OK);
            testEventHandler.expectHeaders();
            testEventHandler.expectHeaderAdd(requestHeaders[REQ_CONTENT_LENGTH], "52");
            testEventHandler.expectHeaderAdd(requestHeaders[REQ_CONTENT_TYPE], "text/html");
            // Server name should be TestWebServer+port
            // we ignore the server tag, so don't test it
            // testEventHandler.expectHeaderAdd(requestHeaders[REQ_SERVER], "TestWebServer8081");
            testEventHandler.expectData(52);
            testEventHandler.expectEndData();

            requestHandle.waitUntilComplete();

            if (!testEventHandler.expectPassed()) {
                Log.d(LOGTAG, testEventHandler.getFailureMessage());
                fail("expectPassed was false " + testEventHandler.getFailureMessage());
             }
        } finally {
            Log.d(LOGTAG, testName + " - returning");
            redirectWebServer.close();
        }
    }

    public void testRedirect301() throws Exception {
        verifyRedirect(HttpConstants.HTTP_MOVED_PERM, "testRedirect301");
    }

    public void testRedirect302() throws Exception {
        verifyRedirect(HttpConstants.HTTP_MOVED_TEMP, "testRedirect302");
    }

    public void testRedirect303() throws Exception {
        verifyRedirect(HttpConstants.HTTP_SEE_OTHER, "testRedirect303");
    }

    public void testRedirect307() throws Exception {
        verifyRedirect(307, "testRedirect307");
    }

    public void testGetAndHead() throws Exception {
        /**
         * Test sending a GET and a HEAD request. Test will pass if the
         * event received correspond with the expected response. The two
         * requests should respond the same test data.
         */
        mTestWebServer.setKeepAlive(true);
        mTestWebServer.setAcceptLimit(2);

        TestEventHandler testEventHandler = new TestEventHandler();
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();

        TestEventHandler leh2 = new TestEventHandler();
        leh2.expectStatus(200);
        leh2.expectHeaders();

        RequestHandle handle0 = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "GET", null, testEventHandler, null, 0);
        handle0.waitUntilComplete();
        RequestHandle handle1 = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "HEAD", null, testEventHandler, null, 0);

        Log.d(LOGTAG, "testGetAndHead - sent request. Waiting");
        handle1.waitUntilComplete();

        if (!testEventHandler.expectPassed() && !leh2.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            Log.d(LOGTAG, leh2.getFailureMessage());
            fail();
        }
    }

    public void testPost() throws Exception {
        /**
         * Test sending a POST request with no body data. Test will pass if the event
         * received correspond with the expected response. This should respond with
         * the test data requested.
         */
        TestEventHandler testEventHandler = new TestEventHandler();

        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();
        testEventHandler.expectData(52);

        mTestWebServer.setKeepAlive(false);
        mTestWebServer.setAcceptLimit(1);

        Log.d(LOGTAG, "testPost start - rq = " + mRequestQueue);

        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "POST", null, testEventHandler, null, 0);

        Log.d(LOGTAG, "testPost - sent request waiting");
        handle.waitUntilComplete();

        if (!testEventHandler.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            fail("expectPassed was false " + testEventHandler.getFailureMessage());
        }
    }


    public void testPostWithData() throws Exception {
        /**
         * Test sending a POST request with body data. Test will pass if the event
         * received correspond with the expected response. This should respond with
         * the test data requested.
         */

        TestEventHandler testEventHandler = new TestEventHandler();
        // Load up expected response
        testEventHandler.expectStatus(200);
        testEventHandler.expectHeaders();
        testEventHandler.expectData(52);

        mTestWebServer.setKeepAlive(false);
        mTestWebServer.setAcceptLimit(1);

        Log.d(LOGTAG, "testPostWithData start - rq = " + mRequestQueue);

        String mBody = TestWebData.postContent;
        int bodyLength = mBody.length();
        if (bodyLength > 0) {
            Log.v(LOGTAG, "testPostWithData: body " + mBody);
        }
        InputStream bodyProvider = new ByteArrayInputStream(mBody.getBytes());

        RequestHandle handle = mRequestQueue.queueRequest(
                "http://localhost:8080/test1", "POST", null, testEventHandler, bodyProvider, bodyLength);

        Log.d(LOGTAG, "testPostWithData - sent request waiting");
        handle.waitUntilComplete();

        if (!testEventHandler.expectPassed()) {
            Log.d(LOGTAG, testEventHandler.getFailureMessage());
            fail("expectPassed was false " + testEventHandler.getFailureMessage());
        }
    }
}
