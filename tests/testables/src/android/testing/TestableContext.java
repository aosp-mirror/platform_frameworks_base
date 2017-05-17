/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.view.LayoutInflater;

import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A ContextWrapper with utilities specifically designed to make Testing easier.
 *
 * <ul>
 * <li>System services can be mocked out with {@link #addMockSystemService}</li>
 * <li>Service binding can be mocked out with {@link #addMockService}</li>
 * <li>Resources can be mocked out using {@link #getOrCreateTestableResources()}</li>
 * <li>Settings support {@link TestableSettingsProvider}</li>
 * <li>Has support for {@link LeakCheck} for services and receivers</li>
 * </ul>
 *
 * <p>TestableContext should be defined as a rule on your test so it can clean up after itself.
 * Like the following:</p>
 * <pre class="prettyprint">
 * &#064;Rule
 * private final TestableContext mContext = new TestableContext(InstrumentationRegister.getContext());
 * </pre>
 */
public class TestableContext extends ContextWrapper implements TestRule {

    private final TestableContentResolver mTestableContentResolver;
    private final TestableSettingsProvider mSettingsProvider;

    private ArrayMap<String, Object> mMockSystemServices;
    private ArrayMap<ComponentName, IBinder> mMockServices;
    private ArrayMap<ServiceConnection, ComponentName> mActiveServices;

    private PackageManager mMockPackageManager;
    private LeakCheck.Tracker mReceiver;
    private LeakCheck.Tracker mService;
    private LeakCheck.Tracker mComponent;
    private TestableResources mTestableResources;

    public TestableContext(Context base) {
        this(base, null);
    }

    public TestableContext(Context base, LeakCheck check) {
        super(base);
        mTestableContentResolver = new TestableContentResolver(base);
        ContentProviderClient settings = base.getContentResolver()
                .acquireContentProviderClient(Settings.AUTHORITY);
        mSettingsProvider = TestableSettingsProvider.getFakeSettingsProvider(settings);
        mTestableContentResolver.addProvider(Settings.AUTHORITY, mSettingsProvider);
        mReceiver = check != null ? check.getTracker("receiver") : null;
        mService = check != null ? check.getTracker("service") : null;
        mComponent = check != null ? check.getTracker("component") : null;
    }

    public void setMockPackageManager(PackageManager mock) {
        mMockPackageManager = mock;
    }

    @Override
    public PackageManager getPackageManager() {
        if (mMockPackageManager != null) {
            return mMockPackageManager;
        }
        return super.getPackageManager();
    }

    /**
     * Makes sure the resources being returned by this TestableContext are a version of
     * TestableResources.
     * @see #getResources()
     */
    public void ensureTestableResources() {
        if (mTestableResources == null) {
            mTestableResources = new TestableResources(super.getResources());
        }
    }

    /**
     * Get (and create if necessary) {@link TestableResources} for this TestableContext.
     */
    public TestableResources getOrCreateTestableResources() {
        ensureTestableResources();
        return mTestableResources;
    }

    /**
     * Returns a Resources instance for the test.
     *
     * By default this returns the same resources object that would come from the
     * {@link ContextWrapper}, but if {@link #ensureTestableResources()} or
     * {@link #getOrCreateTestableResources()} has been called, it will return resources gotten from
     * {@link TestableResources}.
     */
    @Override
    public Resources getResources() {
        return mTestableResources != null ? mTestableResources.getResources()
                : super.getResources();
    }

    /**
     * @see #getSystemService(String)
     */
    public <T> void addMockSystemService(Class<T> service, T mock) {
        addMockSystemService(getSystemServiceName(service), mock);
    }

    /**
     * @see #getSystemService(String)
     */
    public void addMockSystemService(String name, Object service) {
        if (mMockSystemServices == null) mMockSystemServices = new ArrayMap<>();
        mMockSystemServices.put(name, service);
    }

    /**
     * If a matching mock service has been added through {@link #addMockSystemService} then
     * that will be returned, otherwise the real service will be acquired from the base
     * context.
     */
    @Override
    public Object getSystemService(String name) {
        if (mMockSystemServices != null && mMockSystemServices.containsKey(name)) {
            return mMockSystemServices.get(name);
        }
        if (name.equals(LAYOUT_INFLATER_SERVICE)) {
            return getBaseContext().getSystemService(LayoutInflater.class).cloneInContext(this);
        }
        return super.getSystemService(name);
    }

    TestableSettingsProvider getSettingsProvider() {
        return mSettingsProvider;
    }

    @Override
    public TestableContentResolver getContentResolver() {
        return mTestableContentResolver;
    }

    /**
     * Will always return itself for a TestableContext to ensure the testable effects extend
     * to the application context.
     */
    @Override
    public Context getApplicationContext() {
        // Return this so its always a TestableContext.
        return this;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (mReceiver != null) mReceiver.getLeakInfo(receiver).addAllocation(new Throwable());
        return super.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        if (mReceiver != null) mReceiver.getLeakInfo(receiver).addAllocation(new Throwable());
        return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        if (mReceiver != null) mReceiver.getLeakInfo(receiver).addAllocation(new Throwable());
        return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (mReceiver != null) mReceiver.getLeakInfo(receiver).clearAllocations();
        super.unregisterReceiver(receiver);
    }

    /**
     * Adds a mock service to be connected to by a bindService call.
     * <p>
     *     Normally a TestableContext will pass through all bind requests to the base context
     *     but when addMockService has been called for a ComponentName being bound, then
     *     TestableContext will immediately trigger a {@link ServiceConnection#onServiceConnected}
     *     with the specified service, and will call {@link ServiceConnection#onServiceDisconnected}
     *     when the service is unbound.
     * </p>
     */
    public void addMockService(ComponentName component, IBinder service) {
        if (mMockServices == null) mMockServices = new ArrayMap<>();
        mMockServices.put(component, service);
    }

    /**
     * @see #addMockService(ComponentName, IBinder)
     */
    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        if (mService != null) mService.getLeakInfo(conn).addAllocation(new Throwable());
        if (checkMocks(service.getComponent(), conn)) return true;
        return super.bindService(service, conn, flags);
    }

    /**
     * @see #addMockService(ComponentName, IBinder)
     */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
        if (mService != null) mService.getLeakInfo(conn).addAllocation(new Throwable());
        if (checkMocks(service.getComponent(), conn)) return true;
        return super.bindServiceAsUser(service, conn, flags, handler, user);
    }

    /**
     * @see #addMockService(ComponentName, IBinder)
     */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        if (mService != null) mService.getLeakInfo(conn).addAllocation(new Throwable());
        if (checkMocks(service.getComponent(), conn)) return true;
        return super.bindServiceAsUser(service, conn, flags, user);
    }

    private boolean checkMocks(ComponentName component, ServiceConnection conn) {
        if (mMockServices != null && component != null && mMockServices.containsKey(component)) {
            if (mActiveServices == null) mActiveServices = new ArrayMap<>();
            mActiveServices.put(conn, component);
            conn.onServiceConnected(component, mMockServices.get(component));
            return true;
        }
        return false;
    }

    /**
     * @see #addMockService(ComponentName, IBinder)
     */
    @Override
    public void unbindService(ServiceConnection conn) {
        if (mService != null) mService.getLeakInfo(conn).clearAllocations();
        if (mActiveServices != null && mActiveServices.containsKey(conn)) {
            conn.onServiceDisconnected(mActiveServices.get(conn));
            mActiveServices.remove(conn);
            return;
        }
        super.unbindService(conn);
    }

    /**
     * Check if the TestableContext has a mock binding for a specified component. Will return
     * true between {@link ServiceConnection#onServiceConnected} and
     * {@link ServiceConnection#onServiceDisconnected} callbacks for a mock service.
     *
     * @see #addMockService(ComponentName, IBinder)
     */
    public boolean isBound(ComponentName component) {
        return mActiveServices != null && mActiveServices.containsValue(component);
    }

    @Override
    public void registerComponentCallbacks(ComponentCallbacks callback) {
        if (mComponent != null) mComponent.getLeakInfo(callback).addAllocation(new Throwable());
        super.registerComponentCallbacks(callback);
    }

    @Override
    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        if (mComponent != null) mComponent.getLeakInfo(callback).clearAllocations();
        super.unregisterComponentCallbacks(callback);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new TestWatcher() {
            @Override
            protected void succeeded(Description description) {
                mSettingsProvider.clearValuesAndCheck(TestableContext.this);
            }

            @Override
            protected void failed(Throwable e, Description description) {
                mSettingsProvider.clearValuesAndCheck(TestableContext.this);
            }
        }.apply(base, description);
    }
}
