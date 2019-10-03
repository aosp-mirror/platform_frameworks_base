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

import android.os.Handler;
import android.testing.DexmakerShareClassLoaderRule;

import org.junit.Rule;

import java.util.concurrent.Callable;

/** The base class which provides the common rule for test classes under wm package. */
class SystemServiceTestsBase {
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();
    @Rule
    public final SystemServicesTestRule mSystemServicesTestRule = new SystemServicesTestRule();

    @WindowTestRunner.MethodWrapperRule
    public final WindowManagerGlobalLockRule mLockRule =
            new WindowManagerGlobalLockRule(mSystemServicesTestRule);

    /** Waits until the main handler for WM has processed all messages. */
    void waitUntilHandlersIdle() {
        mLockRule.waitForLocked(mSystemServicesTestRule::waitUntilWindowManagerHandlersIdle);
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
}
