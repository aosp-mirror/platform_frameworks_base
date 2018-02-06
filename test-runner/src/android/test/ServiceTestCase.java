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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.test.mock.MockApplication;

import android.test.mock.MockService;
import java.util.Random;

/**
 * This test case provides a framework in which you can test Service classes in
 * a controlled environment.  It provides basic support for the lifecycle of a
 * Service, and hooks with which you can inject various dependencies and control
 * the environment in which your Service is tested.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about application testing, read the
 * <a href="{@docRoot}guide/topics/testing/index.html">Testing</a> developer guide.</p>
 * </div>
 *
 * <p><b>Lifecycle Support.</b>
 * A Service is accessed with a specific sequence of
 * calls, as described in the
 * <a href="http://developer.android.com/guide/topics/fundamentals/services.html">Services</a>
 * document. In order to support the lifecycle of a Service,
 * <code>ServiceTestCase</code> enforces this protocol:
 *
 * <ul>
 *      <li>
 *          The {@link #setUp()} method is called before each test method. The base implementation
 *          gets the system context. If you override <code>setUp()</code>, you must call
 *          <code>super.setUp()</code> as the first statement in your override.
 *      </li>
 *      <li>
 *          The test case waits to call {@link android.app.Service#onCreate()} until one of your
 *          test methods calls {@link #startService} or {@link #bindService}.  This gives you an
 *          opportunity to set up or adjust any additional framework or test logic before you test
 *          the running service.
 *      </li>
 *      <li>
 *          When one of your test methods calls {@link #startService ServiceTestCase.startService()}
 *          or {@link #bindService  ServiceTestCase.bindService()}, the test case calls
 *          {@link android.app.Service#onCreate() Service.onCreate()} and then calls either
 *          {@link android.app.Service#startService(Intent) Service.startService(Intent)} or
 *          {@link android.app.Service#bindService(Intent, ServiceConnection, int)
 *          Service.bindService(Intent, ServiceConnection, int)}, as appropriate. It also stores
 *          values needed to track and support the lifecycle.
 *      </li>
 *      <li>
 *          After each test method finishes, the test case calls the {@link #tearDown} method. This
 *          method stops and destroys the service with the appropriate calls, depending on how the
 *          service was started. If you override <code>tearDown()</code>, your must call the
 *          <code>super.tearDown()</code> as the last statement in your override.
 *      </li>
 * </ul>
 *
 * <p>
 *      <strong>Dependency Injection.</strong>
 *      A service has two inherent dependencies, its {@link android.content.Context Context} and its
 *      associated {@link android.app.Application Application}. The ServiceTestCase framework
 *      allows you to inject modified, mock, or isolated replacements for these dependencies, and
 *      thus perform unit tests with controlled dependencies in an isolated environment.
 * </p>
 * <p>
 *      By default, the test case is injected with a full system context and a generic
 *      {@link android.test.mock.MockApplication MockApplication} object. You can inject
 *      alternatives to either of these by invoking
 *      {@link AndroidTestCase#setContext(Context) setContext()} or
 *      {@link #setApplication setApplication()}.  You must do this <em>before</em> calling
 *      startService() or bindService().  The test framework provides a
 *      number of alternatives for Context, including
 *      {@link android.test.mock.MockContext MockContext},
 *      {@link android.test.RenamingDelegatingContext RenamingDelegatingContext},
 *      {@link android.content.ContextWrapper ContextWrapper}, and
 *      {@link android.test.IsolatedContext}.
 *
 * @deprecated Use
 * <a href="{@docRoot}reference/android/support/test/rule/ServiceTestRule.html">
 * ServiceTestRule</a> instead. New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public abstract class ServiceTestCase<T extends Service> extends AndroidTestCase {

    Class<T> mServiceClass;

    private Context mSystemContext;
    private Application mApplication;

    /**
     * Constructor
     * @param serviceClass The type of the service under test.
     */
    public ServiceTestCase(Class<T> serviceClass) {
        mServiceClass = serviceClass;
    }

    private T mService;
    private boolean mServiceAttached = false;
    private boolean mServiceCreated = false;
    private boolean mServiceStarted = false;
    private boolean mServiceBound = false;
    private Intent mServiceIntent = null;
    private int mServiceId;

    /**
     * @return An instance of the service under test. This instance is created automatically when
     * a test calls {@link #startService} or {@link #bindService}.
     */
    public T getService() {
        return mService;
    }

    /**
     * Gets the current system context and stores it.
     *
     * Extend this method to do your own test initialization. If you do so, you
     * must call <code>super.setUp()</code> as the first statement in your override. The method is
     * called before each test method is executed.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // get the real context, before the individual tests have a chance to muck with it
        mSystemContext = getContext();

    }

    /**
     * Creates the service under test and attaches all injected dependencies
     * (Context, Application) to it.  This is called automatically by {@link #startService} or
     * by {@link #bindService}.
     * If you need to call {@link AndroidTestCase#setContext(Context) setContext()} or
     * {@link #setApplication setApplication()}, do so before calling this method.
     */
    protected void setupService() {
        mService = null;
        try {
            mService = mServiceClass.newInstance();
        } catch (Exception e) {
            assertNotNull(mService);
        }
        if (getApplication() == null) {
            setApplication(new MockApplication());
        }
        MockService.attachForTesting(
                mService, getContext(), mServiceClass.getName(), getApplication());

        assertNotNull(mService);

        mServiceId = new Random().nextInt();
        mServiceAttached = true;
    }

    /**
     * Starts the service under test, in the same way as if it were started by
     * {@link android.content.Context#startService(Intent) Context.startService(Intent)} with
     * an {@link android.content.Intent} that identifies a service.
     * If you use this method to start the service, it is automatically stopped by
     * {@link #tearDown}.
     *
     * @param intent An Intent that identifies a service, of the same form as the Intent passed to
     * {@link android.content.Context#startService(Intent) Context.startService(Intent)}.
     */
    protected void startService(Intent intent) {
        if (!mServiceAttached) {
            setupService();
        }
        assertNotNull(mService);

        if (!mServiceCreated) {
            mService.onCreate();
            mServiceCreated = true;
        }
        mService.onStartCommand(intent, 0, mServiceId);

        mServiceStarted = true;
    }

    /**
     * <p>
     *      Starts the service under test, in the same way as if it were started by
     *      {@link android.content.Context#bindService(Intent, ServiceConnection, int)
     *      Context.bindService(Intent, ServiceConnection, flags)} with an
     *      {@link android.content.Intent} that identifies a service.
     * </p>
     * <p>
     *      Notice that the parameters are different. You do not provide a
     *      {@link android.content.ServiceConnection} object or the flags parameter. Instead,
     *      you only provide the Intent. The method returns an object whose type is a
     *      subclass of {@link android.os.IBinder}, or null if the method fails. An IBinder
     *      object refers to a communication channel between the application and
     *      the service. The flag is assumed to be {@link android.content.Context#BIND_AUTO_CREATE}.
     * </p>
     * <p>
     *      See <a href="{@docRoot}guide/components/aidl.html">Designing a Remote Interface
     *      Using AIDL</a> for more information about the communication channel object returned
     *      by this method.
     * </p>
     * Note:  To be able to use bindService in a test, the service must implement getService()
     * method. An example of this is in the ApiDemos sample application, in the
     * LocalService demo.
     *
     * @param intent An Intent object of the form expected by
     * {@link android.content.Context#bindService}.
     *
     * @return An object whose type is a subclass of IBinder, for making further calls into
     * the service.
     */
    protected IBinder bindService(Intent intent) {
        if (!mServiceAttached) {
            setupService();
        }
        assertNotNull(mService);

        if (!mServiceCreated) {
            mService.onCreate();
            mServiceCreated = true;
        }
        // no extras are expected by unbind
        mServiceIntent = intent.cloneFilter();
        IBinder result = mService.onBind(intent);

        mServiceBound = true;
        return result;
    }

    /**
     * Makes the necessary calls to stop (or unbind) the service under test, and
     * calls onDestroy().  Ordinarily this is called automatically (by {@link #tearDown}, but
     * you can call it directly from your test in order to check for proper shutdown behavior.
     */
    protected void shutdownService() {
        if (mServiceStarted) {
            mService.stopSelf();
            mServiceStarted = false;
        } else if (mServiceBound) {
            mService.onUnbind(mServiceIntent);
            mServiceBound = false;
        }
        if (mServiceCreated) {
            mService.onDestroy();
            mServiceCreated = false;
        }
    }

    /**
     * <p>
     *      Shuts down the service under test.  Ensures all resources are cleaned up and
     *      garbage collected before moving on to the next test. This method is called after each
     *      test method.
     * </p>
     * <p>
     *      Subclasses that override this method must call <code>super.tearDown()</code> as their
     *      last statement.
     * </p>
     *
     * @throws Exception
     */
    @Override
    protected void tearDown() throws Exception {
        shutdownService();
        mService = null;

        // Scrub out members - protects against memory leaks in the case where someone
        // creates a non-static inner class (thus referencing the test case) and gives it to
        // someone else to hold onto
        scrubClass(ServiceTestCase.class);

        super.tearDown();
    }

    /**
     * Sets the application that is used during the test.  If you do not call this method,
     * a new {@link android.test.mock.MockApplication MockApplication} object is used.
     *
     * @param application The Application object that is used by the service under test.
     *
     * @see #getApplication()
     */
    public void setApplication(Application application) {
        mApplication = application;
    }

    /**
     * Returns the Application object in use by the service under test.
     *
     * @return The application object.
     *
     * @see #setApplication
     */
    public Application getApplication() {
        return mApplication;
    }

    /**
     * Returns the real system context that is saved by {@link #setUp()}. Use it to create
     * mock or other types of context objects for the service under test.
     *
     * @return A normal system context.
     */
    public Context getSystemContext() {
        return mSystemContext;
    }

    /**
     * Tests that {@link #setupService()} runs correctly and issues an
     * {@link junit.framework.Assert#assertNotNull(String, Object)} if it does.
     * You can override this test method if you wish.
     *
     * @throws Exception
     */
    public void testServiceTestCaseSetUpProperly() throws Exception {
        setupService();
        assertNotNull("service should be launched successfully", mService);
    }
}
