/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.test;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import junit.framework.TestCase;

import java.lang.reflect.Field;

/**
 * Extend this if you need to access Resources or other things that depend on Activity Context.
 */
public class AndroidTestCase extends TestCase {

    protected Context mContext;
    private Context mTestContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testAndroidTestCaseSetupProperly() {
        assertNotNull("Context is null. setContext should be called before tests are run",
                mContext);
    }

    public void setContext(Context context) {
        mContext = context;
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * Test context can be used to access resources from the test's own package
     * as opposed to the resources from the test target package. Access to the
     * latter is provided by the context set with the {@link #setContext}
     * method.
     *
     * @hide
     */
    public void setTestContext(Context context) {
        mTestContext = context;
    }

    /**
     * @hide
     */
    public Context getTestContext() {
        return mTestContext;
    }

    /**
     * Asserts that launching a given activity is protected by a particular permission by
     * attempting to start the activity and validating that a {@link SecurityException}
     * is thrown that mentions the permission in its error message.
     *
     * Note that an instrumentation isn't needed because all we are looking for is a security error
     * and we don't need to wait for the activity to launch and get a handle to the activity.
     *
     * @param packageName The package name of the activity to launch.
     * @param className The class of the activity to launch.
     * @param permission The name of the permission.
     */
    public void assertActivityRequiresPermission(
            String packageName, String className, String permission) {
        final Intent intent = new Intent();
        intent.setClassName(packageName, className);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            getContext().startActivity(intent);
            fail("expected security exception for " + permission);
        } catch (SecurityException expected) {
            assertNotNull("security exception's error message.", expected.getMessage());
            assertTrue("error message should contain " + permission + ".",
                    expected.getMessage().contains(permission));
        }
    }


    /**
     * Asserts that reading from the content uri requires a particular permission by querying the
     * uri and ensuring a {@link SecurityException} is thrown mentioning the particular permission.
     *
     * @param uri The uri that requires a permission to query.
     * @param permission The permission that should be required.
     */
    public void assertReadingContentUriRequiresPermission(Uri uri, String permission) {
        try {
            getContext().getContentResolver().query(uri, null, null, null, null);
            fail("expected SecurityException requiring " + permission);
        } catch (SecurityException expected) {
            assertNotNull("security exception's error message.", expected.getMessage());
            assertTrue("error message should contain " + permission + ".",
                    expected.getMessage().contains(permission));
        }
    }

    /**
     * Asserts that writing to the content uri requires a particular permission by inserting into
     * the uri and ensuring a {@link SecurityException} is thrown mentioning the particular
     * permission.
     *
     * @param uri The uri that requires a permission to query.
     * @param permission The permission that should be required.
     */
    public void assertWritingContentUriRequiresPermission(Uri uri, String permission) {
        try {
            getContext().getContentResolver().insert(uri, new ContentValues());
            fail("expected SecurityException requiring " + permission);
        } catch (SecurityException expected) {
            assertNotNull("security exception's error message.", expected.getMessage());
            assertTrue("error message should contain \"" + permission + "\". Got: \""
                    + expected.getMessage() + "\".",
                    expected.getMessage().contains(permission));
        }
    }

    /**
     * This function is called by various TestCase implementations, at tearDown() time, in order
     * to scrub out any class variables.  This protects against memory leaks in the case where a
     * test case creates a non-static inner class (thus referencing the test case) and gives it to
     * someone else to hold onto.
     *
     * @param testCaseClass The class of the derived TestCase implementation.
     *
     * @throws IllegalAccessException
     */
    protected void scrubClass(final Class<?> testCaseClass)
    throws IllegalAccessException {
        final Field[] fields = getClass().getDeclaredFields();
        for (Field field : fields) {
            final Class<?> fieldClass = field.getDeclaringClass();
            if (testCaseClass.isAssignableFrom(fieldClass) && !field.getType().isPrimitive()) {
                try {
                    field.setAccessible(true);
                    field.set(this, null);
                } catch (Exception e) {
                    android.util.Log.d("TestCase", "Error: Could not nullify field!");
                }

                if (field.get(this) != null) {
                    android.util.Log.d("TestCase", "Error: Could not nullify field!");
                }
            }
        }
    }


}
