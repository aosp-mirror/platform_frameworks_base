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

package android.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import junit.framework.TestCase;

/**
 * A test case that has access to {@link Instrumentation}.
 *
 * @deprecated Use
 * <a href="{@docRoot}reference/androidx/test/platform/app/InstrumentationRegistry.html">
 * InstrumentationRegistry</a> instead. New tests should be written using the
 * <a href="{@docRoot}training/testing/index.html">AndroidX Test Library</a>.
 */
@Deprecated
public class InstrumentationTestCase extends TestCase {

    private Instrumentation mInstrumentation;

    /**
     * Injects instrumentation into this test case. This method is
     * called by the test runner during test setup.
     *
     * @param instrumentation the instrumentation to use with this instance
     */
    public void injectInstrumentation(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }

    /**
     * Injects instrumentation into this test case. This method is
     * called by the test runner during test setup.
     *
     * @param instrumentation the instrumentation to use with this instance
     *
     * @deprecated Incorrect spelling,
     * use {@link #injectInstrumentation(android.app.Instrumentation)} instead.
     */
    @Deprecated
    public void injectInsrumentation(Instrumentation instrumentation) {
        injectInstrumentation(instrumentation);
    }

    /**
     * Inheritors can access the instrumentation using this.
     * @return instrumentation
     */
    public Instrumentation getInstrumentation() {
        return mInstrumentation;
    }

    /**
     * Utility method for launching an activity.  
     * 
     * <p>The {@link Intent} used to launch the Activity is:
     *  action = {@link Intent#ACTION_MAIN}
     *  extras = null, unless a custom bundle is provided here
     * All other fields are null or empty.
     * 
     * <p><b>NOTE:</b> The parameter <i>pkg</i> must refer to the package identifier of the
     * package hosting the activity to be launched, which is specified in the AndroidManifest.xml
     * file.  This is not necessarily the same as the java package name.
     *
     * @param pkg The package hosting the activity to be launched.
     * @param activityCls The activity class to launch.
     * @param extras Optional extra stuff to pass to the activity.
     * @return The activity, or null if non launched.
     */
    public final <T extends Activity> T launchActivity(
            String pkg,
            Class<T> activityCls,
            Bundle extras) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        if (extras != null) {
            intent.putExtras(extras);
        }
        return launchActivityWithIntent(pkg, activityCls, intent);
    }

    /**
     * Utility method for launching an activity with a specific Intent.
     * 
     * <p><b>NOTE:</b> The parameter <i>pkg</i> must refer to the package identifier of the
     * package hosting the activity to be launched, which is specified in the AndroidManifest.xml
     * file.  This is not necessarily the same as the java package name.
     *
     * @param pkg The package hosting the activity to be launched.
     * @param activityCls The activity class to launch.
     * @param intent The intent to launch with
     * @return The activity, or null if non launched.
     */
    @SuppressWarnings("unchecked")
    public final <T extends Activity> T launchActivityWithIntent(
            String pkg,
            Class<T> activityCls,
            Intent intent) {
        intent.setClassName(pkg, activityCls.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        T activity = (T) getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        return activity;
    }
    
    /**
     * Helper for running portions of a test on the UI thread.
     * 
     * Note, in most cases it is simpler to annotate the test method with 
     * {@link android.test.UiThreadTest}, which will run the entire test method on the UI thread.
     * Use this method if you need to switch in and out of the UI thread to perform your test.
     * 
     * @param r runnable containing test code in the {@link Runnable#run()} method
     */
    public void runTestOnUiThread(final Runnable r) throws Throwable {
        final Throwable[] exceptions = new Throwable[1];
        getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable throwable) {
                    exceptions[0] = throwable;
                }
            }
        });
        if (exceptions[0] != null) {
            throw exceptions[0];
        }
    }

    /**
     * Runs the current unit test. If the unit test is annotated with
     * {@link android.test.UiThreadTest}, the test is run on the UI thread.
     */
    @Override
    protected void runTest() throws Throwable {
        String fName = getName();
        assertNotNull(fName);
        Method method = null;
        try {
            // use getMethod to get all public inherited
            // methods. getDeclaredMethods returns all
            // methods of this class but excludes the
            // inherited ones.
            method = getClass().getMethod(fName, (Class[]) null);
        } catch (NoSuchMethodException e) {
            fail("Method \""+fName+"\" not found");
        }

        if (!Modifier.isPublic(method.getModifiers())) {
            fail("Method \""+fName+"\" should be public");
        }

        int runCount = 1;
        boolean isRepetitive = false;
        if (method.isAnnotationPresent(FlakyTest.class)) {
            runCount = method.getAnnotation(FlakyTest.class).tolerance();
        } else if (method.isAnnotationPresent(RepetitiveTest.class)) {
            runCount = method.getAnnotation(RepetitiveTest.class).numIterations();
            isRepetitive = true;
        }

        if (method.isAnnotationPresent(UiThreadTest.class)) {
            final int tolerance = runCount;
            final boolean repetitive = isRepetitive;
            final Method testMethod = method;
            final Throwable[] exceptions = new Throwable[1];
            getInstrumentation().runOnMainSync(new Runnable() {
                public void run() {
                    try {
                        runMethod(testMethod, tolerance, repetitive);
                    } catch (Throwable throwable) {
                        exceptions[0] = throwable;
                    }
                }
            });
            if (exceptions[0] != null) {
                throw exceptions[0];
            }
        } else {
            runMethod(method, runCount, isRepetitive);
        }
    }

    // For backwards-compatibility after adding isRepetitive
    private void runMethod(Method runMethod, int tolerance) throws Throwable {
        runMethod(runMethod, tolerance, false);
    }

    private void runMethod(Method runMethod, int tolerance, boolean isRepetitive) throws Throwable {
        Throwable exception = null;

        int runCount = 0;
        do {
            try {
                runMethod.invoke(this, (Object[]) null);
                exception = null;
            } catch (InvocationTargetException e) {
                e.fillInStackTrace();
                exception = e.getTargetException();
            } catch (IllegalAccessException e) {
                e.fillInStackTrace();
                exception = e;
            } finally {
                runCount++;
                // Report current iteration number, if test is repetitive
                if (isRepetitive) {
                    Bundle iterations = new Bundle();
                    iterations.putInt("currentiterations", runCount);
                    getInstrumentation().sendStatus(2, iterations);
                }
            }
        } while ((runCount < tolerance) && (isRepetitive || exception != null));

        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Sends a series of key events through instrumentation and waits for idle. The sequence
     * of keys is a string containing the key names as specified in KeyEvent, without the
     * KEYCODE_ prefix. For instance: sendKeys("DPAD_LEFT A B C DPAD_CENTER"). Each key can
     * be repeated by using the N* prefix. For instance, to send two KEYCODE_DPAD_LEFT, use
     * the following: sendKeys("2*DPAD_LEFT").
     *
     * @param keysSequence The sequence of keys.
     */
    public void sendKeys(String keysSequence) {
        final String[] keys = keysSequence.split(" ");
        final int count = keys.length;

        final Instrumentation instrumentation = getInstrumentation();

        for (int i = 0; i < count; i++) {
            String key = keys[i];
            int repeater = key.indexOf('*');

            int keyCount;
            try {
                keyCount = repeater == -1 ? 1 : Integer.parseInt(key.substring(0, repeater));
            } catch (NumberFormatException e) {
                Log.w("ActivityTestCase", "Invalid repeat count: " + key);
                continue;
            }

            if (repeater != -1) {
                key = key.substring(repeater + 1);
            }

            for (int j = 0; j < keyCount; j++) {
                try {
                    final Field keyCodeField = KeyEvent.class.getField("KEYCODE_" + key);
                    final int keyCode = keyCodeField.getInt(null);
                    try {
                        instrumentation.sendKeyDownUpSync(keyCode);
                    } catch (SecurityException e) {
                        // Ignore security exceptions that are now thrown
                        // when trying to send to another app, to retain
                        // compatibility with existing tests.
                    }
                } catch (NoSuchFieldException e) {
                    Log.w("ActivityTestCase", "Unknown keycode: KEYCODE_" + key);
                    break;
                } catch (IllegalAccessException e) {
                    Log.w("ActivityTestCase", "Unknown keycode: KEYCODE_" + key);
                    break;
                }
            }
        }

        instrumentation.waitForIdleSync();
    }

    /**
     * Sends a series of key events through instrumentation and waits for idle. For instance:
     * sendKeys(KEYCODE_DPAD_LEFT, KEYCODE_DPAD_CENTER).
     *
     * @param keys The series of key codes to send through instrumentation.
     */
    public void sendKeys(int... keys) {
        final int count = keys.length;
        final Instrumentation instrumentation = getInstrumentation();

        for (int i = 0; i < count; i++) {
            try {
                instrumentation.sendKeyDownUpSync(keys[i]);
            } catch (SecurityException e) {
                // Ignore security exceptions that are now thrown
                // when trying to send to another app, to retain
                // compatibility with existing tests.
            }
        }

        instrumentation.waitForIdleSync();
    }

    /**
     * Sends a series of key events through instrumentation and waits for idle. Each key code
     * must be preceded by the number of times the key code must be sent. For instance:
     * sendRepeatedKeys(1, KEYCODE_DPAD_CENTER, 2, KEYCODE_DPAD_LEFT).
     *
     * @param keys The series of key repeats and codes to send through instrumentation.
     */
    public void sendRepeatedKeys(int... keys) {
        final int count = keys.length;
        if ((count & 0x1) == 0x1) {
            throw new IllegalArgumentException("The size of the keys array must "
                    + "be a multiple of 2");
        }

        final Instrumentation instrumentation = getInstrumentation();

        for (int i = 0; i < count; i += 2) {
            final int keyCount = keys[i];
            final int keyCode = keys[i + 1];
            for (int j = 0; j < keyCount; j++) {
                try {
                    instrumentation.sendKeyDownUpSync(keyCode);
                } catch (SecurityException e) {
                    // Ignore security exceptions that are now thrown
                    // when trying to send to another app, to retain
                    // compatibility with existing tests.
                }
            }
        }

        instrumentation.waitForIdleSync();
    }
    
    /**
     * Make sure all resources are cleaned up and garbage collected before moving on to the next
     * test. Subclasses that override this method should make sure they call super.tearDown()
     * at the end of the overriding method.
     * 
     * @throws Exception
     */
    @Override
    protected void tearDown() throws Exception {
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        Runtime.getRuntime().gc();
        super.tearDown();
    }
}