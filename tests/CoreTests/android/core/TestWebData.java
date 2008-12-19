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

/**
 * Represents test data used by the Request API tests
 */
public class TestWebData {

  /*
   * Simple Html body
   * <html>
   * <body>
   * <h1>Hello World!</h1>
   * </body>
   * </html>
   */
  public final static byte[] test1 = {
    (byte)0x3c, (byte)0x68, (byte)0x74, (byte)0x6d,
    (byte)0x6c, (byte)0x3e, (byte)0x0a, (byte)0x3c,
    (byte)0x62, (byte)0x6f, (byte)0x64, (byte)0x79,
    (byte)0x3e, (byte)0x0a, (byte)0x3c, (byte)0x68,
    (byte)0x31, (byte)0x3e, (byte)0x48, (byte)0x65,
    (byte)0x6c, (byte)0x6c, (byte)0x6f, (byte)0x20,
    (byte)0x57, (byte)0x6f, (byte)0x72, (byte)0x6c,
    (byte)0x64, (byte)0x21, (byte)0x3c, (byte)0x2f,
    (byte)0x68, (byte)0x31, (byte)0x3e, (byte)0x0a,
    (byte)0x3c, (byte)0x2f, (byte)0x62, (byte)0x6f,
    (byte)0x64, (byte)0x79, (byte)0x3e, (byte)0x0a,
    (byte)0x3c, (byte)0x2f, (byte)0x68, (byte)0x74,
    (byte)0x6d, (byte)0x6c, (byte)0x3e, (byte)0x0a
  };

  /*
   * Simple Html body
   * <html>
   * <body>
   * <h1>Hello World!</h1>
   * </body>
   * </html>
   */
  public final static byte[] test2 = {
    (byte)0x3c, (byte)0x68, (byte)0x74, (byte)0x6d,
    (byte)0x6c, (byte)0x3e, (byte)0x0a, (byte)0x3c,
    (byte)0x62, (byte)0x6f, (byte)0x64, (byte)0x79,
    (byte)0x3e, (byte)0x0a, (byte)0x3c, (byte)0x68,
    (byte)0x31, (byte)0x3e, (byte)0x48, (byte)0x65,
    (byte)0x6c, (byte)0x6c, (byte)0x6f, (byte)0x20,
    (byte)0x57, (byte)0x6f, (byte)0x72, (byte)0x6c,
    (byte)0x64, (byte)0x21, (byte)0x3c, (byte)0x2f,
    (byte)0x68, (byte)0x31, (byte)0x3e, (byte)0x0a,
    (byte)0x3c, (byte)0x2f, (byte)0x62, (byte)0x6f,
    (byte)0x64, (byte)0x79, (byte)0x3e, (byte)0x0a,
    (byte)0x3c, (byte)0x2f, (byte)0x68, (byte)0x74,
    (byte)0x6d, (byte)0x6c, (byte)0x3e, (byte)0x0a
  };

  // string for test request post body
  public final static String postContent = "user=111";
  
  // Array of all test data
  public final static byte[][] tests = {
    test1,
    test2
  };

  /**
   * List of static test cases for use with test server
   */
  public static TestWebData[] testParams = {
    new TestWebData(52, 14000000, "test1", "text/html", false),
    new TestWebData(52, 14000002, "test2", "unknown/unknown", false)
  };

  /**
   * List of response strings for use by the test server
   */
  public static String[] testServerResponse = {
    "Redirecting 301",
    "Redirecting 302",
    "Redirecting 303",
    "Redirecting 307"
  };

  // Redirection indices into testServerResponse
  public final static int REDIRECT_301 = 0;
  public final static int REDIRECT_302 = 1;
  public final static int REDIRECT_303 = 2;
  public final static int REDIRECT_307 = 3;

  /**
   * Creates a data package with information used by the server when responding
   * to requests
   */
  TestWebData(int length, int lastModified, String name, String type, boolean isDir) {
    testLength = length;
    testLastModified = lastModified;
    testName = name;
    testType = type;
    testDir = isDir;
  }

  // Length of test entity body
  public int testLength;

  // Last modified date value (milliseconds)
  public int testLastModified;

  // Test identification name
  public String testName;

  // The MIME type to assume for this test
  public String testType;

  // Indicates if this is a directory or not
  public boolean testDir;

}
