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

import java.util.ArrayList;
import java.util.Map;

import org.apache.http.protocol.HTTP;
import android.util.Log;
import android.net.http.*;

/**
 * Implements EventHandler and provides test functionality to validate
 * responses to requests from the test server
 */
public class TestEventHandler implements EventHandler {

    /**
     * Status variables
     */
    private int majorVersion = -1;
    private int minorVersion = -1;
    private int responseCode = -1;
    private String reasonPhrase;

    /* List of headers received */
    private Map<String, String> headerMap;

    /* Used to sync low level delayed requests */
    public static final Object syncObj = new Object();

    /* Indicates whether the low level request testing is in operation */
    private boolean useLowLevel = false;

    /* Indicates whether responses should be automatically generated or
     * delayed
     */
    private boolean delayResponse = false;

    /* Test method expectation identifiers */
    public final static int TEST_REQUEST_SENT = 0;
    public final static int TEST_STATUS = 1;
    public final static int TEST_HEADERS = 2;
    public final static int TEST_LOCATION_CHANGED = 3;
    public final static int TEST_DATA = 4;
    public final static int TEST_ENDDATA = 5;
    public final static int TEST_ERROR = 6;
    public final static int TEST_SSL_CERTIFICATE_ERROR = 7;

    public final static int TEST_NUM_EXPECTS = 8;

    /* Expected status codes */
    private int expectMajor = -1;
    private int expectMinor = -1;
    private int expectCode = -1;

    /* Array indicating which event types are expected */
    private boolean[] expects = new boolean[TEST_NUM_EXPECTS];

    /* Array indicating which event types are not expected */
    private boolean[] notExpecting = new boolean[TEST_NUM_EXPECTS];

    /* Indicates which events have been received */
    private boolean[] eventsReceived = new boolean[TEST_NUM_EXPECTS];

    /* Redirection variables */
    private String expectLocation;
    private int expectPermanent = -1;

    /* Content data expected to be received */
    private byte[] expectData;
    private int expectDataLength = -1;

    private int expectErrorId = -1;

    private int expectSslErrors = -1;
    private SslCertificate expectCertificate;

    public class TestHeader {
        public TestHeader(String n, String v) {
            name = n;
            value = v;
        }
        public String name;
        public String value;
    }
    
    private ArrayList<TestHeader> expectHeaders = new ArrayList<TestHeader>();

    /* Holds failure details */
    private StringBuffer expectDetails = new StringBuffer();

    /* If we use a request handle, we retain a reference here for redirects
     * using setupRedirect
     */
    private RequestHandle mRequestHandle;

    /* The low level API uses this reference also for non-delayed requests */
    private LowLevelNetRunner netRunner;

    public TestEventHandler() {
        for (int i = 0; i < TEST_NUM_EXPECTS; i++) {
            expects[i] = false;
            notExpecting[i] = false;
            eventsReceived[i] = false;
        }
    }

    /**
     * Implementation of EventHandler method called when a request has been
     * sent. If the test is waiting for this call, it will be signalled,
     * otherwise this method will trigger the response to be read
     * automatically.
     */
    public void requestSent() {
      Log.v(LOGTAG, "TestEventHandler:requestSent()");
      expects[TEST_REQUEST_SENT] = false;
      eventsReceived[TEST_REQUEST_SENT] = true;
      if (notExpecting[TEST_REQUEST_SENT]) {
          expectDetails.append("Request sent event received but not expected");
          expectDetails.append("\r\n");
      }

      if (useLowLevel) {
        if (delayResponse) {
          synchronized (syncObj) {
            syncObj.notifyAll();
          }
        } else {
            // mRequest.startReadingResponse();
        }
      }
    }

    /**
     * Implements the EventHandler status method called when a server status
     * response is received.
     * @param major_version The HTTP major version
     * @param minor_version The HTTP minor version
     * @param code The status code
     * @param reason_phrase A reason phrase passed to us by the server
     */
    public void status(int major_version, int minor_version,
        int code, String reason_phrase) {
      if (false) {
        Log.v(LOGTAG, "TestEventHandler:status() major: " + major_version +
            " minor: " + minor_version +
            " code: " + code +
            " reason: " + reason_phrase);
      }

      eventsReceived[TEST_STATUS] = true;
      if (notExpecting[TEST_STATUS]) {
        expectDetails.append("Status event received but not expected");
        expectDetails.append("\r\n");
      }

      majorVersion = major_version;
      minorVersion = minor_version;
      responseCode = code;
      reasonPhrase = reason_phrase;

      if (expectMajor != -1) {
        if (expectMajor == major_version) {
          expectMajor = -1;
        } else {
          expectDetails.append("Major version expected:"+expectMajor+
              " got:"+major_version);
          expectDetails.append("\r\n");
        }
      }

      if (expectMinor != -1) {
        if (expectMinor == minor_version) {
          expectMinor = -1;
        } else {
          expectDetails.append("Minor version expected:"+expectMinor+
              " got:"+minor_version);
          expectDetails.append("\r\n");
        }
      }

      if (expectCode != -1) {
        if (expectCode == code) {
          expectCode = -1;
        } else {
          expectDetails.append("Status code expected:"+expectCode+
              " got:"+code);
          expectDetails.append("\r\n");
        }
      }


      if ((expectMajor == -1) && (expectMinor == -1) && (expectCode == -1)) {
        expects[TEST_STATUS] = false;
      } else {
        System.out.println("MAJOR = "+expectMajor+" MINOR = "+expectMinor+
            " CODE = "+expectCode);
      }
    }

    /**
     * Implements the EventHandler headers method called when a server
     * sends header fields
     */
    public void headers(Headers headers) {
        if (false) {
            Log.v(LOGTAG, "TestEventHandler:headers()");
        }
        expects[TEST_HEADERS] = false;

        if (notExpecting[TEST_HEADERS]) {
            expectDetails.append("Header event received but not expected");
            expectDetails.append("\r\n");
        }

        /* Check through headers received for matches with expected
         * headers */
        if (expectHeaders.isEmpty()) {
            return;
        }      
        
        for (int i = expectHeaders.size() - 1; i >= 0; i--) {
            TestHeader h =  expectHeaders.get(i);
            System.out.println("Expected header name: " + h.name);
            String s = null;
            switch (h.name.hashCode()) {
            case -1132779846:
                s = Long.toString(headers.getContentLength());
                break;
            case 785670158:
                s = headers.getContentType();
                break;
            case 2095084583:
                s = headers.getContentEncoding();
                break;
            case 1901043637:
                s = headers.getLocation();
                break;
            case -243037365:
                s = headers.getWwwAuthenticate();
                break;
            case -301767724:
                s = headers.getProxyAuthenticate();
                break;
            case -1267267485:
                s = headers.getContentDisposition();
                break;
            case 1397189435:
                s = headers.getAcceptRanges();
                break;
            case -1309235404:
                s = headers.getExpires();
                break;
            case -208775662:
                s = headers.getCacheControl();
                break;
            case 150043680:
                s = headers.getLastModified();
                break;
            case 3123477:
                s = headers.getEtag();
                break;
            case -775651618:
                int ct = headers.getConnectionType();
                if (ct == Headers.CONN_CLOSE) {
                    s = HTTP.CONN_CLOSE;
                } else if (ct == Headers.CONN_KEEP_ALIVE) {
                    s = HTTP.CONN_KEEP_ALIVE;
                }
                break;
            default:
                s = null;
                
            }
            if (evaluateHeader(h, s)) {
                expectHeaders.remove(i);
            }
        }
            
    }

    public boolean evaluateHeader(TestHeader h, String value) {
        if (value == null) {
            expects[TEST_HEADERS] = true;
            System.out.print(" Missing!  ");
            expectDetails.append(" missing header " + h.name);
            return false;
        }
        if (h.value == null) {
            System.out.println("Expect value = null");
            return true;
        }
        System.out.println("Expect value = " +
                (h.value.toLowerCase()) + " got " +
                value.toLowerCase());
        
        if (!h.value.equalsIgnoreCase(value)) {
            expectDetails.append("expect header value " + h.value +
                    " got " + value);
            expects[TEST_HEADERS] = true;
            return false;
        }
        return true;
    }
    /**
     * Implements the EventHandler locationChanged method called when a server
     * sends a redirect message
     * @param newLocation The URL to the new server
     * @param permanent Indicator of whether this is a permanent change
     */
    public void locationChanged(String newLocation, boolean permanent) {
      if (false) {
        Log.v(LOGTAG, "TestEventHandler: locationChanged() " +
            newLocation + " permanent " + permanent);
      }

      eventsReceived[TEST_LOCATION_CHANGED] = true;
      if (notExpecting[TEST_LOCATION_CHANGED]) {
        expectDetails.append("Location changed event received but "+
            "not expected");
        expectDetails.append("\r\n");
      }

      if (expectLocation != null) {
        if (expectLocation.equals(newLocation)) {
          expectLocation = null;
        } else {
          expectDetails.append("Location expected:"+expectLocation+
              " got:"+newLocation);
          expectDetails.append("\r\n");
        }
      }

      if (expectPermanent != -1) {
        if (((expectPermanent == 0) && !permanent) ||
            ((expectPermanent == 1) && permanent)){
          expectPermanent = -1;
        } else {
          expectDetails.append("Location permanent expected:"+
              expectPermanent+" got"+permanent);
          expectDetails.append("\r\n");
        }
      }

      if ((expectLocation == null) && (expectPermanent == -1))
        expects[TEST_LOCATION_CHANGED] = false;
    }

    /**
     * Implements the EventHandler data method called when a server
     * sends content data
     * @param data The byte array content
     * @param len The length of the data
     */
    public void data(byte[] data, int len) {
      boolean mismatch = false;

      if (false) {
        Log.v(LOGTAG, "TestEventHandler: data() " + len + " bytes");
      }

      eventsReceived[TEST_DATA] = true;
      if (notExpecting[TEST_DATA]) {
        expectDetails.append("Data event received but not expected");
        expectDetails.append("\r\n");
      }

      Log.v(LOGTAG, new String(data, 0, len));

      if (expectDataLength != -1) {
        if (expectDataLength == len) {
          expectDataLength = -1;
        } else {
          expectDetails.append("expect data length mismatch expected:"+
              expectDataLength+" got:"+len);
          expectDetails.append("\r\n");
        }

        /* Check data only if length is the same */
        if ((expectDataLength == -1) && expectData != null) {
          for (int i = 0; i < len; i++) {
            if (expectData[i] != data[i]) {
              mismatch = true;
              expectDetails.append("Expect data mismatch at byte "+
                  i+" expected:"+expectData[i]+" got:"+data[i]);
              expectDetails.append("\r\n");
              break;
            }
          }
        }
      }

      if ((expectDataLength == -1) || !mismatch)
        expects[TEST_DATA] = false;
    }

    /**
     * Implements the EventHandler endData method called to
     * indicate completion or a request
     */
    public void endData() {
      if (false) {
        Log.v(LOGTAG, "TestEventHandler: endData() called");
      }

      eventsReceived[TEST_ENDDATA] = true;
      if (notExpecting[TEST_ENDDATA]) {
        expectDetails.append("End data event received but not expected");
        expectDetails.append("\r\n");
      }

      expects[TEST_ENDDATA] = false;

      if (useLowLevel) {
        if (delayResponse) {
          synchronized (syncObj) {
            syncObj.notifyAll();
          }
        } else {
          if (netRunner != null) {
            System.out.println("TestEventHandler: endData() stopping "+
                netRunner);
            netRunner.decrementRunCount();
          }
        }
      }
    }

    /**
     * Implements the EventHandler certificate method called every
     * time a resource is loaded via a secure connection
     */
    public void certificate(SslCertificate certificate) {}

    /**
     * Implements the EventHandler error method called when a server
     * sends header fields
     * @param id Status code of the error
     * @param description Brief description of the error
     */
    public void error(int id, String description) {
      if (false) {
        Log.v(LOGTAG, "TestEventHandler: error() called Id:" + id +
            " description " + description);
      }

      eventsReceived[TEST_ERROR] = true;
      if (notExpecting[TEST_ERROR]) {
        expectDetails.append("Error event received but not expected");
        expectDetails.append("\r\n");
      }
      if (expectErrorId != -1) {
        if (expectErrorId == id) {
          expectErrorId = -1;
        } else {
          expectDetails.append("Error Id expected:"+expectErrorId+
              " got:"+id);
          expectDetails.append("\r\n");
        }
      }

      if (expectErrorId == -1)
        expects[TEST_ERROR] = false;

      if (useLowLevel) {
        if (delayResponse) {
          synchronized (syncObj) {
            syncObj.notifyAll();
          }
        } else {
          if (netRunner != null) {
            System.out.println("TestEventHandler: endData() stopping "+
                netRunner);
            netRunner.decrementRunCount();
          }
        }
      }
    }

    /**
     * SSL certificate error callback. Handles SSL error(s) on the way
     * up to the user.
     */
    public boolean handleSslErrorRequest(SslError error) {
      int primaryError = error.getPrimaryError();

      if (false) {
        Log.v(LOGTAG, "TestEventHandler: handleSslErrorRequest(): "+
              " primary error:" + primaryError +
              " certificate: " + error.getCertificate());
      }

      eventsReceived[TEST_SSL_CERTIFICATE_ERROR] = true;
      if (notExpecting[TEST_SSL_CERTIFICATE_ERROR]) {
        expectDetails.append("SSL Certificate error event received "+
            "but not expected");
        expectDetails.append("\r\n");
      }

      if (expectSslErrors != -1) {
        if (expectSslErrors == primaryError) {
            expectSslErrors = -1;
        } else {
            expectDetails.append("SslCertificateError id expected:"+
                expectSslErrors+" got: " + primaryError);
            expectDetails.append("\r\n");
        }
      }

      // SslCertificate match here?

      if (expectSslErrors == -1) // && expectSslCertificate == certificate?
        expects[TEST_SSL_CERTIFICATE_ERROR] = false;

      // return false so that we won't block the thread
      return false;
    }

    /**
     * Use the low level net runner with no delayed response
     * @param runner The LowLevelNetRunner object
     */
    public void setNetRunner(LowLevelNetRunner runner) {
      setNetRunner(runner, false);
    }

    /**
     * Use the low level net runner and specify if the response
     * should be delayed
     * @param runner The LowLevelNetRunner object
     * @param delayedResponse Set to true is you will use the
     * waitForRequestSent/waitForRequestResponse routines
     */
    public void setNetRunner(LowLevelNetRunner runner,
        boolean delayedResponse) {
      netRunner = runner;
      useLowLevel = true;
      delayResponse = delayedResponse;

      if (!delayResponse)
        netRunner.incrementRunCount();
    }

    /**
     * Enable this listeners Request object to read server responses.
     * This should only be used in conjunction with setDelayResponse(true)
     */
    public void waitForRequestResponse() {
      if (!delayResponse || !useLowLevel) {
        Log.d(LOGTAG, " Cant do this without delayReponse set ");
        return;
      }

      //if (mRequest != null) {
          // mRequest.startReadingResponse();
      // }
      /* Now wait for the response to be completed either through endData
       * or an error
       */
      synchronized (syncObj) {
        try {
          syncObj.wait();
        } catch (InterruptedException e) {
        }
      }
    }

    /**
     * Enable this listeners Request object to read server responses.
     * This should only be used in conjunction with setDelayResponse(true)
     */
    public void waitForRequestSent() {
      if (!delayResponse || !useLowLevel) {
        Log.d(LOGTAG, " Cant do this without delayReponse set ");
        return;
      }

      /* Now wait for the response to be completed either through endData
       * or an error
       */
      synchronized (syncObj) {
        try {
          syncObj.wait();
        } catch (InterruptedException e) {
        }
      }
    }

    /* Test expected values - these routines set the tests expectations */

    public void expectRequestSent() {
        expects[TEST_REQUEST_SENT] = true;
    }

    public void expectNoRequestSent() {
        notExpecting[TEST_REQUEST_SENT] = true;
    }

    public void expectStatus() {
        expects[TEST_STATUS] = true;
    }

    public void expectNoStatus() {
        notExpecting[TEST_STATUS] = true;
    }

    public void expectStatus(int major, int minor, int code) {
        expects[TEST_STATUS] = true;
        expectMajor = major;
        expectMinor = minor;
        expectCode = code;
    }

    public void expectStatus(int code) {
        expects[TEST_STATUS] = true;
        expectCode = code;
    }

    public void expectHeaders() {
        expects[TEST_HEADERS] = true;
    }

    public void expectNoHeaders() {
        notExpecting[TEST_HEADERS] = true;
    }

    public void expectHeaderAdd(String name) {
        expects[TEST_HEADERS] = true;
        TestHeader h = new TestHeader(name.toLowerCase(), null);
        expectHeaders.add(h);
    }

    public void expectHeaderAdd(String name, String value) {
        expects[TEST_HEADERS] = true;
        TestHeader h = new TestHeader(name.toLowerCase(), value);
        expectHeaders.add(h);
    }

    public void expectLocationChanged() {
        expects[TEST_LOCATION_CHANGED] = true;
    }

    public void expectNoLocationChanged() {
            notExpecting[TEST_LOCATION_CHANGED] = true;
    }

    public void expectLocationChanged(String newLocation) {
        expects[TEST_LOCATION_CHANGED] = true;
        expectLocation = newLocation;
    }

    public void expectLocationChanged(String newLocation, boolean permanent) {
        expects[TEST_LOCATION_CHANGED] = true;
        expectLocation = newLocation;
        expectPermanent = permanent ? 1 : 0;
    }

    public void expectData() {
        expects[TEST_DATA] = true;
    }

    public void expectNoData() {
        notExpecting[TEST_DATA] = true;
    }

    public void expectData(int len) {
        expects[TEST_DATA] = true;
        expectDataLength = len;
    }

    public void expectData(byte[] data, int len) {
        expects[TEST_DATA] = true;
        expectData = new byte[len];
        expectDataLength = len;

        for (int i = 0; i < len; i++) {
            expectData[i] = data[i];
        }
    }

    public void expectEndData() {
        expects[TEST_ENDDATA] = true;
    }

    public void expectNoEndData() {
            notExpecting[TEST_ENDDATA] = true;
    }

    public void expectError() {
        expects[TEST_ERROR] = true;
    }

    public void expectNoError() {
        notExpecting[TEST_ERROR] = true;
    }

    public void expectError(int errorId) {
        expects[TEST_ERROR] = true;
        expectErrorId = errorId;
    }

    public void expectSSLCertificateError() {
        expects[TEST_SSL_CERTIFICATE_ERROR] = true;
    }

    public void expectNoSSLCertificateError() {
            notExpecting[TEST_SSL_CERTIFICATE_ERROR] = true;
    }

    public void expectSSLCertificateError(int errors) {
        expects[TEST_SSL_CERTIFICATE_ERROR] = true;
        expectSslErrors = errors;
    }

    public void expectSSLCertificateError(SslCertificate certificate) {
        expects[TEST_SSL_CERTIFICATE_ERROR] = true;
        expectCertificate = certificate;
    }

    /**
     * Test to see if current expectations match recieved information
     * @return True is all expected results have been matched
     */
    public boolean expectPassed() {
        for (int i = 0; i < TEST_NUM_EXPECTS; i++) {
            if (expects[i] == true) {
                return false;
            }
        }

        for (int i = 0; i < TEST_NUM_EXPECTS; i++) {
            if (eventsReceived[i] && notExpecting[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return message indicating expectation failures
     */
    public String getFailureMessage() {
        return expectDetails.toString();
    }

    /**
     * Reset all expectation values for re-use
     */
    public void resetExpects() {
        expectMajor = -1;
        expectMinor = -1;
        expectCode = -1;
        expectLocation = null;
        expectPermanent = -1;
        expectErrorId = -1;
        expectSslErrors = -1;
        expectCertificate = null;
        expectDetails.setLength(0);
        expectHeaders.clear();

        for (int i = 0; i < TEST_NUM_EXPECTS; i++) {
            expects[i] = false;
            notExpecting[i] = false;
            eventsReceived[i] = false;
        }

        for (int i = 0; i < expectDataLength; i++) {
            expectData[i] = 0;
        }

        expectDataLength = -1;
    }

    /**
     * Attach the RequestHandle to this handler
     * @param requestHandle The RequestHandle
     */
    public void attachRequestHandle(RequestHandle requestHandle) {
        if (false) {
            Log.v(LOGTAG, "TestEventHandler.attachRequestHandle(): " +
                    "requestHandle: " +  requestHandle);
        }
        mRequestHandle = requestHandle;
    }

    /**
     * Detach the RequestHandle
     */
    public void detachRequestHandle() {
        if (false) {
            Log.v(LOGTAG, "TestEventHandler.detachRequestHandle(): " +
                    "requestHandle: " + mRequestHandle);
        }
        mRequestHandle = null;
    }

    protected final static String LOGTAG = "http";
}
