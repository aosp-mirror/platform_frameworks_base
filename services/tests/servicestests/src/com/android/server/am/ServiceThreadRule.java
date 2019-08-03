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

package com.android.server.am;

import android.os.Process;

import com.android.server.ServiceThread;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

class ServiceThreadRule implements TestRule {

    private ServiceThread mThread;

    ServiceThread getThread() {
        return mThread;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                mThread = new ServiceThread("TestServiceThread", Process.THREAD_PRIORITY_DEFAULT,
                        true /* allowIo */);
                mThread.start();
                try {
                    base.evaluate();
                } finally {
                    mThread.getThreadHandler().runWithScissors(mThread::quit, 0 /* timeout */);
                }
            }
        };
    }
}
