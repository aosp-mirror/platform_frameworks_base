/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.IForegroundServiceObserver;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.util.Pair;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.test.filters.MediumTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.RunningFgsController;
import com.android.systemui.statusbar.policy.RunningFgsController.UserPackageTime;
import com.android.systemui.statusbar.policy.RunningFgsControllerImpl;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

@MediumTest
@RunWith(AndroidTestingRunner.class)
public class RunningFgsControllerTest extends SysuiTestCase {

    private RunningFgsController mController;

    private FakeSystemClock mSystemClock = new FakeSystemClock();
    private FakeExecutor mExecutor = new FakeExecutor(mSystemClock);
    private TestCallback mCallback = new TestCallback();

    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private LifecycleOwner mLifecycleOwner;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mLifecycleOwner.getLifecycle()).thenReturn(mLifecycle);
        mController = new RunningFgsControllerImpl(mExecutor, mSystemClock, mActivityManager);
    }

    @Test
    public void testInitRegistersListenerInImpl() throws RemoteException {
        ((RunningFgsControllerImpl) mController).init();
        verify(mActivityManager, times(1)).registerForegroundServiceObserver(any());
    }

    @Test
    public void testAddCallbackCallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.addCallback(mCallback));
    }

    @Test
    public void testRemoveCallbackCallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.removeCallback(mCallback));
    }

    @Test
    public void testObserve1CallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.observe(mLifecycle, mCallback));
    }

    @Test
    public void testObserve2CallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.observe(mLifecycleOwner, mCallback));
    }

    @Test
    public void testGetPackagesWithFgsCallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.getPackagesWithFgs());
    }

    @Test
    public void testStopFgsCallsInitInImpl() {
        verifyInitIsCalled(controller -> controller.stopFgs(0, ""));
    }

    /**
     * Tests that callbacks can be added
     */
    @Test
    public void testAddCallback() throws RemoteException {
        String testPackageName = "testPackageName";
        int testUserId = 0;

        IForegroundServiceObserver observer = prepareObserver();
        mController.addCallback(mCallback);

        observer.onForegroundStateChanged(new Binder(), testPackageName, testUserId, true);

        mExecutor.advanceClockToLast();
        mExecutor.runAllReady();

        assertEquals("Callback should have been invoked exactly once.",
                1, mCallback.mInvocations.size());

        List<UserPackageTime> userPackageTimes = mCallback.mInvocations.get(0);
        assertEquals("There should have only been one package in callback. packages="
                        + userPackageTimes,
                1, userPackageTimes.size());

        UserPackageTime upt = userPackageTimes.get(0);
        assertEquals(testPackageName, upt.getPackageName());
        assertEquals(testUserId, upt.getUserId());
    }

    /**
     * Tests that callbacks can be removed. This test is only meaningful if
     * {@link #testAddCallback()} can pass.
     */
    @Test
    public void testRemoveCallback() throws RemoteException {
        String testPackageName = "testPackageName";
        int testUserId = 0;

        IForegroundServiceObserver observer = prepareObserver();
        mController.addCallback(mCallback);
        mController.removeCallback(mCallback);

        observer.onForegroundStateChanged(new Binder(), testPackageName, testUserId, true);

        mExecutor.advanceClockToLast();
        mExecutor.runAllReady();

        assertEquals("Callback should not have been invoked.",
                0, mCallback.mInvocations.size());
    }

    /**
     * Tests packages are added when the controller receives a callback from activity manager for
     * a foreground service start.
     */
    @Test
    public void testGetPackagesWithFgsAddingPackages() throws RemoteException {
        int numPackages = 20;
        int numUsers = 3;

        IForegroundServiceObserver observer = prepareObserver();

        assertEquals("List should be empty", 0, mController.getPackagesWithFgs().size());

        List<Pair<Integer, String>> addedPackages = new ArrayList<>();
        for (int pkgNumber = 0; pkgNumber < numPackages; pkgNumber++) {
            for (int userId = 0; userId < numUsers; userId++) {
                String packageName = "package.name." + pkgNumber;
                addedPackages.add(new Pair(userId, packageName));

                observer.onForegroundStateChanged(new Binder(), packageName, userId, true);

                containsAllAddedPackages(addedPackages, mController.getPackagesWithFgs());
            }
        }
    }

    /**
     * Tests packages are removed when the controller receives a callback from activity manager for
     * a foreground service ending.
     */
    @Test
    public void testGetPackagesWithFgsRemovingPackages() throws RemoteException {
        int numPackages = 20;
        int numUsers = 3;
        int arrayLength = numPackages * numUsers;

        String[] packages = new String[arrayLength];
        int[] users = new int[arrayLength];
        IBinder[] tokens = new IBinder[arrayLength];
        for (int pkgNumber = 0; pkgNumber < numPackages; pkgNumber++) {
            for (int userId = 0; userId < numUsers; userId++) {
                int i = pkgNumber * numUsers + userId;
                packages[i] =  "package.name." + pkgNumber;
                users[i] = userId;
                tokens[i] = new Binder();
            }
        }

        IForegroundServiceObserver observer = prepareObserver();

        for (int i = 0; i < packages.length; i++) {
            observer.onForegroundStateChanged(tokens[i], packages[i], users[i], true);
        }

        assertEquals(packages.length, mController.getPackagesWithFgs().size());

        List<Integer> removeOrder = new ArrayList<>();
        for (int i = 0; i < packages.length; i++) {
            removeOrder.add(i);
        }
        Collections.shuffle(removeOrder, new Random(12345));

        for (int idx : removeOrder) {
            removePackageAndAssertRemovedFromList(observer, tokens[idx], packages[idx], users[idx]);
        }

        assertEquals(0, mController.getPackagesWithFgs().size());
    }

    /**
     * Tests a call on stopFgs forwards to activity manager.
     */
    @Test
    public void testStopFgs() throws RemoteException {
        String pkgName = "package.name";
        mController.stopFgs(0, pkgName);
        verify(mActivityManager).makeServicesNonForeground(pkgName, 0);
    }

    /**
     * Tests a package which starts multiple services is only listed once and is only removed once
     * all services are stopped.
     */
    @Test
    public void testSinglePackageWithMultipleServices() throws RemoteException {
        String packageName = "package.name";
        int userId = 0;
        IBinder serviceToken1 = new Binder();
        IBinder serviceToken2 = new Binder();

        IForegroundServiceObserver observer = prepareObserver();

        assertEquals(0, mController.getPackagesWithFgs().size());

        observer.onForegroundStateChanged(serviceToken1, packageName, userId, true);
        assertSinglePackage(packageName, userId);

        observer.onForegroundStateChanged(serviceToken2, packageName, userId, true);
        assertSinglePackage(packageName, userId);

        observer.onForegroundStateChanged(serviceToken2, packageName, userId, false);
        assertSinglePackage(packageName, userId);

        observer.onForegroundStateChanged(serviceToken1, packageName, userId, false);
        assertEquals(0, mController.getPackagesWithFgs().size());
    }

    private IForegroundServiceObserver prepareObserver()
            throws RemoteException {
        mController.getPackagesWithFgs();

        ArgumentCaptor<IForegroundServiceObserver> argumentCaptor =
                ArgumentCaptor.forClass(IForegroundServiceObserver.class);
        verify(mActivityManager).registerForegroundServiceObserver(argumentCaptor.capture());

        return argumentCaptor.getValue();
    }

    private void verifyInitIsCalled(Consumer<RunningFgsControllerImpl> c) {
        RunningFgsControllerImpl spiedController = Mockito.spy(
                ((RunningFgsControllerImpl) mController));
        c.accept(spiedController);
        verify(spiedController, atLeastOnce()).init();
    }

    private void containsAllAddedPackages(List<Pair<Integer, String>> addedPackages,
            List<UserPackageTime> runningFgsPackages) {
        for (Pair<Integer, String> userPkg : addedPackages) {
            assertTrue(userPkg + " was not found in returned list",
                    runningFgsPackages.stream().anyMatch(
                            upt -> userPkg.first == upt.getUserId()
                                    && Objects.equals(upt.getPackageName(), userPkg.second)));
        }
        for (UserPackageTime upt : runningFgsPackages) {
            int userId = upt.getUserId();
            String packageName = upt.getPackageName();
            assertTrue("Unknown <user=" + userId + ", package=" + packageName + ">"
                            + " in returned list",
                    addedPackages.stream().anyMatch(userPkg -> userPkg.first == userId
                            && Objects.equals(packageName, userPkg.second)));
        }
    }

    private void removePackageAndAssertRemovedFromList(IForegroundServiceObserver observer,
            IBinder token, String pkg, int userId) throws RemoteException {
        observer.onForegroundStateChanged(token, pkg, userId, false);
        List<UserPackageTime> packagesWithFgs = mController.getPackagesWithFgs();
        assertFalse("Package \"" + pkg + "\" was not removed",
                packagesWithFgs.stream().anyMatch(upt ->
                        Objects.equals(upt.getPackageName(), pkg) && upt.getUserId() == userId));
    }

    private void assertSinglePackage(String packageName, int userId) {
        assertEquals(1, mController.getPackagesWithFgs().size());
        assertEquals(packageName, mController.getPackagesWithFgs().get(0).getPackageName());
        assertEquals(userId, mController.getPackagesWithFgs().get(0).getUserId());
    }

    private static class TestCallback implements RunningFgsController.Callback {

        private List<List<UserPackageTime>> mInvocations = new ArrayList<>();

        @Override
        public void onFgsPackagesChanged(List<UserPackageTime> packages) {
            mInvocations.add(packages);
        }
    }
}
