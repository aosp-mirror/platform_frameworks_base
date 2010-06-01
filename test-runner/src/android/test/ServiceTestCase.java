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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.test.mock.MockApplication;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * This test case provides a framework in which you can test Service classes in
 * a controlled environment.  It provides basic support for the lifecycle of a
 * Service, and hooks by which you can inject various dependencies and control
 * the environment in which your Service is tested.
 *
 * <p><b>Lifecycle Support.</b>
 * Every Service is designed to be accessed within a specific sequence of
 * calls.  <insert link to Service lifecycle doc here>. 
 * In order to support the lifecycle of a Service, this test case will make the
 * following calls at the following times.
 *
 * <ul><li>The test case will not call onCreate() until your test calls 
 * {@link #startService} or {@link #bindService}.  This gives you a chance
 * to set up or adjust any additional framework or test logic before
 * onCreate().</li>
 * <li>When your test calls {@link #startService} or {@link #bindService}
 * the test case will call onCreate(), and then call the corresponding entry point in your service.
 * It will record any parameters or other support values necessary to support the lifecycle.</li>
 * <li>After your test completes, the test case {@link #tearDown} function is
 * automatically called, and it will stop and destroy your service with the appropriate
 * calls (depending on how your test invoked the service.)</li>
 * </ul>
 * 
 * <p><b>Dependency Injection.</b>
 * Every service has two inherent dependencies, the {@link android.content.Context Context} in
 * which it runs, and the {@link android.app.Application Application} with which it is associated.
 * This framework allows you to inject modified, mock, or isolated replacements for these 
 * dependencies, and thus perform a true unit test.
 * 
 * <p>If simply run your tests as-is, your Service will be injected with a fully-functional
 * Context, and a generic {@link android.test.mock.MockApplication MockApplication} object.
 * You can create and inject alternatives to either of these by calling 
 * {@link AndroidTestCase#setContext(Context) setContext()} or 
 * {@link #setApplication setApplication()}.  You must do this <i>before</i> calling
 * startService() or bindService().  The test framework provides a
 * number of alternatives for Context, including {link android.test.mock.MockContext MockContext}, 
 * {@link android.test.RenamingDelegatingContext RenamingDelegatingContext}, and 
 * {@link android.content.ContextWrapper ContextWrapper}.
 */
public abstract class ServiceTestCase<T extends Service> extends AndroidTestCase {

    Class<T> mServiceClass;

    private Context mSystemContext;
    private Application mApplication;

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
     * @return Returns the actual service under test.
     */
    public T getService() {
        return mService;
    }

    /**
     * This will do the work to instantiate the Service under test.  After this, your test 
     * code must also start and stop the service.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // get the real context, before the individual tests have a chance to muck with it
        mSystemContext = getContext();

    }
    
    /**
     * Create the service under test and attach all injected dependencies (Context, Application) to
     * it.  This will be called automatically by {@link #startService} or by {@link #bindService}.
     * If you wish to call {@link AndroidTestCase#setContext(Context) setContext()} or 
     * {@link #setApplication setApplication()}, you must do so  before calling this function.
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
        mService.attach(
                getContext(),
                null,               // ActivityThread not actually used in Service
                mServiceClass.getName(),
                null,               // token not needed when not talking with the activity manager
                getApplication(),
                null                // mocked services don't talk with the activity manager
                );
        
        assertNotNull(mService);
        
        mServiceId = new Random().nextInt();
        mServiceAttached = true;
    }
    
    /**
     * Start the service under test, in the same way as if it was started by
     * {@link android.content.Context#startService Context.startService()}, providing the 
     * arguments it supplied.  If you use this method to start the service, it will automatically
     * be stopped by {@link #tearDown}.
     *  
     * @param intent The Intent as if supplied to {@link android.content.Context#startService}.
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
     * Start the service under test, in the same way as if it was started by
     * {@link android.content.Context#bindService Context.bindService()}, providing the 
     * arguments it supplied.
     *  
     * Return the communication channel to the service.  May return null if 
     * clients can not bind to the service.  The returned
     * {@link android.os.IBinder} is usually for a complex interface
     * that has been <a href="{@docRoot}guide/developing/tools/aidl.html">described using
     * aidl</a>. 
     * 
     * Note:  In order to test with this interface, your service must implement a getService()
     * method, as shown in samples.ApiDemos.app.LocalService.

     * @param intent The Intent as if supplied to {@link android.content.Context#bindService}.
     * 
     * @return Return an IBinder for making further calls into the Service.
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
     * This will make the necessary calls to stop (or unbind) the Service under test, and
     * call onDestroy().  Ordinarily this will be called automatically (by {@link #tearDown}, but
     * you can call it directly from your test in order to check for proper shutdown behaviors.
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
        }
    }
    
    /**
     * Shuts down the Service under test.  Also makes sure all resources are cleaned up and 
     * garbage collected before moving on to the next
     * test.  Subclasses that override this method should make sure they call super.tearDown()
     * at the end of the overriding method.
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
     * Set the application for use during the test.  If your test does not call this function,
     * a new {@link android.test.mock.MockApplication MockApplication} object will be generated.
     * 
     * @param application The Application object that will be injected into the Service under test.
     */
    public void setApplication(Application application) {
        mApplication = application;
    }

    /**
     * Return the Application object being used by the Service under test.
     * 
     * @return Returns the application object.
     * 
     * @see #setApplication
     */
    public Application getApplication() {
        return mApplication;
    }
    
    /**
     * Return a real (not mocked or instrumented) system Context that can be used when generating
     * Mock or other Context objects for your Service under test.
     * 
     * @return Returns a reference to a normal Context.
     */
    public Context getSystemContext() {
        return mSystemContext;
    }

    public void testServiceTestCaseSetUpProperly() throws Exception {
        setupService();
        assertNotNull("service should be launched successfully", mService);
    }
}
