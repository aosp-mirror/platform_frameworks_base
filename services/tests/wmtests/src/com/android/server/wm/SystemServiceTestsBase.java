/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import android.os.Handler;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.DexmakerShareClassLoaderRule;

import org.junit.Rule;

import java.util.concurrent.Callable;

/** The base class which provides the common rule for test classes under wm package. */
class SystemServiceTestsBase {
    @Rule(order = 0)
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Rule(order = 1)
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Rule(order = 2)
    public final SystemServicesTestRule mSystemServicesTestRule = new SystemServicesTestRule(
            this::onBeforeSystemServicesCreated);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @WindowTestRunner.MethodWrapperRule
    public final WindowManagerGlobalLockRule mLockRule =
            new WindowManagerGlobalLockRule(mSystemServicesTestRule);

    /** Waits until the main handler for WM has processed all messages. */
    void waitUntilHandlersIdle() {
        mLockRule.waitForLocked(mSystemServicesTestRule::waitUntilWindowManagerHandlersIdle);
    }

    /** Waits until the choreographer of WindowAnimator has processed all callbacks. */
    void waitUntilWindowAnimatorIdle() {
        mLockRule.waitForLocked(mSystemServicesTestRule::waitUntilWindowAnimatorIdle);
    }

    boolean waitHandlerIdle(Handler handler) {
        return waitHandlerIdle(handler, 0 /* timeout */);
    }

    boolean waitHandlerIdle(Handler handler, long timeout) {
        return runWithScissors(handler, () -> { }, timeout);
    }

    boolean runWithScissors(Handler handler, Runnable r, long timeout) {
        return mLockRule.runWithScissors(handler, r, timeout);
    }

    /** It is used when we want to wait for a result inside {@link WindowManagerGlobalLock}. */
    <T> T awaitInWmLock(Callable<T> callable) {
        return mLockRule.waitForLocked(callable);
    }

    /**
     * Called before system services are created
     */
    protected void onBeforeSystemServicesCreated() {}

    /**
     * Make the system booted, so that {@link ActivityStack#resumeTopActivityInnerLocked} can really
     * be executed to update activity state and configuration when resuming the current top.
     */
    static void setBooted(ActivityTaskManagerService atmService) {
        doReturn(false).when(atmService).isBooting();
        doReturn(true).when(atmService).isBooted();
    }

    /**
     * Utility class to compare the output of T#toString. It is convenient to have readable output
     * of assertion if the string content can represent the expected states.
     */
    static class ToStringComparatorWrapper<T> {
        final T mObject;

        ToStringComparatorWrapper(T object) {
            mObject = object;
        }

        @Override
        public boolean equals(Object obj) {
            return mObject.toString().equals(obj.toString());
        }

        @Override
        public String toString() {
            return mObject.toString();
        }
    }
}
