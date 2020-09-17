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


import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.internal.runners.statements.RunAfters;
import org.junit.internal.runners.statements.RunBefores;
import org.junit.rules.TestRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

/**
 * A runner with support to bind additional operations with test method tightly.
 *
 * @see MethodWrapperRule
 */
public class WindowTestRunner extends AndroidJUnit4ClassRunner {
    private final List<FrameworkMethod> mBefores;
    private final List<FrameworkMethod> mAfters;

    public WindowTestRunner(Class<?> klass) throws InitializationError {
        super(klass);
        mBefores = getTestClass().getAnnotatedMethods(Before.class);
        mAfters = getTestClass().getAnnotatedMethods(After.class);
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        return wrapStatement(super.methodInvoker(method, test), method, test);
    }

    private Statement wrapStatement(Statement statement, FrameworkMethod method, Object target) {
        for (MethodWrapper wrapper : getMethodWrappers(target)) {
            statement = wrapper.apply(statement, describeChild(method));
        }
        return statement;
    }

    /**
     * Constructs the test statement with {@link Before}.
     *
     * @param method The test method.
     * @param target The instance of test class.
     * @param statement The next statement. It is usually the test method.
     */
    @Override
    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        if (mBefores.isEmpty()) {
            return statement;
        }

        final List<FrameworkMethod> befores = new ArrayList<>(mBefores.size());
        for (FrameworkMethod before : mBefores) {
            befores.add(wrapMethod(before, target));
        }
        return new RunBefores(statement, befores, target);
    }

    /**
     * Constructs the test statement with {@link After}.
     *
     * @param method The test method.
     * @param target The instance of test class.
     * @param statement The next statement. If there are "before" methods, then it is the
     *                  before-statement for the next test.
     */
    @Override
    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        if (mAfters.isEmpty()) {
            return statement;
        }

        final List<FrameworkMethod> afters = new ArrayList<>(mAfters.size());
        for (FrameworkMethod after : mAfters) {
            afters.add(wrapMethod(after, target));
        }
        return new RunAfters(statement, afters, target);
    }

    private FrameworkMethod wrapMethod(FrameworkMethod method, Object target) {
        for (MethodWrapper wrapper : getMethodWrappers(target)) {
            method = wrapper.apply(method);
        }
        return method;
    }

    private List<MethodWrapper> getMethodWrappers(Object target) {
        return getTestClass().getAnnotatedFieldValues(
                target, MethodWrapperRule.class, MethodWrapper.class);
    }

    /**
     * If a {@link TestRule} is annotated with this, it can ensure the operation of the rule runs
     * with the test method on the same path and thread.
     * <p>
     * The traditional {@link org.junit.Rule} may run on another thread if timeout is set. And if
     * the rule will hold a lock which will be used in test method, it will cause deadlock such as
     * "Instr: androidx.test.runner.AndroidJUnitRunner" and "Time-limited test" wait for each other.
     * <p>
     * This annotation only takes effect if the test runner is {@link WindowTestRunner}.
     *
     * @see org.junit.internal.runners.statements.FailOnTimeout
     * @see org.junit.runners.BlockJUnit4ClassRunner#methodBlock
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD })
    @interface MethodWrapperRule {}

    /**
     * The interface to support wrapping test method, including {@link Before} and {@link After}.
     */
    interface MethodWrapper extends TestRule {
        FrameworkMethod apply(FrameworkMethod base);
    }
}
