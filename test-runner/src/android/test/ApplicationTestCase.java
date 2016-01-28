/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

/**
 * This test case provides a framework in which you can test Application classes in
 * a controlled environment.  It provides basic support for the lifecycle of a
 * Application, and hooks by which you can inject various dependencies and control
 * the environment in which your Application is tested.
 *
 * <p><b>Lifecycle Support.</b>
 * Every Application is designed to be accessed within a specific sequence of
 * method calls (see {@link android.app.Application} for more details).
 * In order to support the lifecycle of a Application, this test case will make the
 * following calls at the following times.
 *
 * <ul><li>The test case will not call onCreate() until your test calls
 * {@link #createApplication()}.  This gives you a chance
 * to set up or adjust any additional framework or test logic before
 * onCreate().</li>
 * <li>After your test completes, the test case {@link #tearDown} method is
 * automatically called, and it will stop & destroy your application by calling its
 * onDestroy() method.</li>
 * </ul>
 *
 * <p><b>Dependency Injection.</b>
 * Every Application has one inherent dependency, the {@link android.content.Context Context} in
 * which it runs.
 * This framework allows you to inject a modified, mock, or isolated replacement for this
 * dependencies, and thus perform a true unit test.
 *
 * <p>If simply run your tests as-is, your Application will be injected with a fully-functional
 * Context.
 * You can create and inject alternative types of Contexts by calling
 * {@link AndroidTestCase#setContext(Context) setContext()}.  You must do this <i>before</i> calling
 * {@link #createApplication()}.  The test framework provides a
 * number of alternatives for Context, including {@link android.test.mock.MockContext MockContext},
 * {@link android.test.RenamingDelegatingContext RenamingDelegatingContext}, and
 * {@link android.content.ContextWrapper ContextWrapper}.
 *
 * @deprecated Use
 * <a href="{@docRoot}reference/android/support/test/InstrumentationRegistry.html">
 * InstrumentationRegistry</a> instead. New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public abstract class ApplicationTestCase<T extends Application> extends AndroidTestCase {

    Class<T> mApplicationClass;

    private Context mSystemContext;

    public ApplicationTestCase(Class<T> applicationClass) {
        mApplicationClass = applicationClass;
    }

    private T mApplication;
    private boolean mAttached = false;
    private boolean mCreated = false;

    /**
     * @return Returns the actual Application under test.
     */
    public T getApplication() {
        return mApplication;
    }

    /**
     * This will do the work to instantiate the Application under test.  After this, your test
     * code must also start and stop the Application.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // get the real context, before the individual tests have a chance to muck with it
        mSystemContext = getContext();
    }

    /**
     * Load and attach the application under test.
     */
    private void setupApplication() {
        mApplication = null;
        try {
            mApplication = (T) Instrumentation.newApplication(mApplicationClass, getContext());
        } catch (Exception e) {
            assertNotNull(mApplication);
        }
        mAttached = true;
    }

    /**
     * Start the Application under test, in the same way as if it was started by the system.
     * If you use this method to start the Application, it will automatically
     * be stopped by {@link #tearDown}.  If you wish to inject a specialized Context for your
     * test, by calling {@link AndroidTestCase#setContext(Context) setContext()},
     * you must do so  before calling this method.
     */
    final protected void createApplication() {
        assertFalse(mCreated);

        if (!mAttached) {
            setupApplication();
        }
        assertNotNull(mApplication);

        mApplication.onCreate();
        mCreated = true;
    }

    /**
     * This will make the necessary calls to terminate the Application under test (it will
     * call onTerminate().  Ordinarily this will be called automatically (by {@link #tearDown}, but
     * you can call it directly from your test in order to check for proper shutdown behaviors.
     */
    final protected void terminateApplication() {
        if (mCreated) {
            mApplication.onTerminate();
        }
    }

    /**
     * Shuts down the Application under test.  Also makes sure all resources are cleaned up and
     * garbage collected before moving on to the next
     * test.  Subclasses that override this method should make sure they call super.tearDown()
     * at the end of the overriding method.
     *
     * @throws Exception
     */
    @Override
    protected void tearDown() throws Exception {
        terminateApplication();
        mApplication = null;

        // Scrub out members - protects against memory leaks in the case where someone
        // creates a non-static inner class (thus referencing the test case) and gives it to
        // someone else to hold onto
        scrubClass(ApplicationTestCase.class);

        super.tearDown();
    }

    /**
     * Return a real (not mocked or instrumented) system Context that can be used when generating
     * Mock or other Context objects for your Application under test.
     *
     * @return Returns a reference to a normal Context.
     */
    public Context getSystemContext() {
        return mSystemContext;
    }

    /**
     * This test simply confirms that the Application class can be instantiated properly.
     *
     * @throws Exception
     */
    final public void testApplicationTestCaseSetUpProperly() throws Exception {
        setupApplication();
        assertNotNull("Application class could not be instantiated successfully", mApplication);
    }
}
