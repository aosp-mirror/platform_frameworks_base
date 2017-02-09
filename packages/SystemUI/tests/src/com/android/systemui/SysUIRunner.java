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

package com.android.systemui;

import android.support.test.internal.runner.junit4.statement.RunAfters;
import android.support.test.internal.runner.junit4.statement.RunBefores;
import android.support.test.internal.runner.junit4.statement.UiThreadStatement;

import com.android.systemui.utils.TestableLooper.LooperStatement;
import com.android.systemui.utils.TestableLooper.RunWithLooper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.internal.runners.statements.FailOnTimeout;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.util.List;

public class SysUIRunner extends BlockJUnit4ClassRunner {

    private final long mTimeout;
    private final Class<?> mKlass;

    public SysUIRunner(Class<?> klass) throws InitializationError {
        super(klass);
        mKlass = klass;
        // Can't seem to get reference to timeout parameter from here, so set default to 10 mins.
        mTimeout = 10 * 60 * 1000;
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        return UiThreadStatement.shouldRunOnUiThread(method) ? new UiThreadStatement(
                methodInvokerInt(method, test), true) : methodInvokerInt(method, test);
    }

    protected Statement methodInvokerInt(FrameworkMethod method, Object test) {
        RunWithLooper annotation = method.getAnnotation(RunWithLooper.class);
        if (annotation == null) annotation = mKlass.getAnnotation(RunWithLooper.class);
        if (annotation != null) {
            return new LooperStatement(super.methodInvoker(method, test),
                    annotation.setAsMainLooper(), test);
        }
        return super.methodInvoker(method, test);
    }

    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        List befores = this.getTestClass().getAnnotatedMethods(Before.class);
        return befores.isEmpty() ? statement : new RunBefores(method, statement,
                befores, target);
    }

    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        List afters = this.getTestClass().getAnnotatedMethods(After.class);
        return afters.isEmpty() ? statement : new RunAfters(method, statement, afters,
                target);
    }

    protected Statement withPotentialTimeout(FrameworkMethod method, Object test, Statement next) {
        long timeout = this.getTimeout(method.getAnnotation(Test.class));
        if (timeout <= 0L && mTimeout > 0L) {
            timeout = mTimeout;
        }

        return timeout <= 0L ? next : new FailOnTimeout(next, timeout);
    }

    private long getTimeout(Test annotation) {
        return annotation == null ? 0L : annotation.timeout();
    }
}
