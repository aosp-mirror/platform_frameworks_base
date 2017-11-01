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

import android.support.test.internal.runner.junit4.statement.RunAfters;
import android.support.test.internal.runner.junit4.statement.RunBefores;
import android.support.test.internal.runner.junit4.statement.UiThreadStatement;

import android.testing.TestableLooper.LooperFrameworkMethod;
import android.testing.TestableLooper.RunWithLooper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.internal.runners.statements.FailOnTimeout;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * A runner with support for extra annotations provided by the Testables library.
 * @see UiThreadTest
 * @see TestableLooper.RunWithLooper
 */
public class AndroidTestingRunner extends BlockJUnit4ClassRunner {

    private final long mTimeout;
    private final Class<?> mKlass;

    public AndroidTestingRunner(Class<?> klass) throws InitializationError {
        super(klass);
        mKlass = klass;
        // Can't seem to get reference to timeout parameter from here, so set default to 10 mins.
        mTimeout = 10 * 60 * 1000;
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        method = looperWrap(method, test, method);
        final Statement statement = super.methodInvoker(method, test);
        return shouldRunOnUiThread(method) ? new UiThreadStatement(statement, true) : statement;
    }

    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        List befores = looperWrap(method, target,
                this.getTestClass().getAnnotatedMethods(Before.class));
        return befores.isEmpty() ? statement : new RunBefores(method, statement,
                befores, target);
    }

    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        List afters = looperWrap(method, target,
                this.getTestClass().getAnnotatedMethods(After.class));
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

    protected List<FrameworkMethod> looperWrap(FrameworkMethod method, Object test,
            List<FrameworkMethod> methods) {
        RunWithLooper annotation = method.getAnnotation(RunWithLooper.class);
        if (annotation == null) annotation = mKlass.getAnnotation(RunWithLooper.class);
        if (annotation != null) {
            methods = new ArrayList<>(methods);
            for (int i = 0; i < methods.size(); i++) {
                methods.set(i, LooperFrameworkMethod.get(methods.get(i),
                        annotation.setAsMainLooper(), test));
            }
        }
        return methods;
    }

    protected FrameworkMethod looperWrap(FrameworkMethod method, Object test,
            FrameworkMethod base) {
        RunWithLooper annotation = method.getAnnotation(RunWithLooper.class);
        if (annotation == null) annotation = mKlass.getAnnotation(RunWithLooper.class);
        if (annotation != null) {
            return LooperFrameworkMethod.get(base, annotation.setAsMainLooper(), test);
        }
        return base;
    }

    public boolean shouldRunOnUiThread(FrameworkMethod method) {
        if (mKlass.getAnnotation(UiThreadTest.class) != null) {
            return true;
        } else {
            return UiThreadStatement.shouldRunOnUiThread(method);
        }
    }
}
