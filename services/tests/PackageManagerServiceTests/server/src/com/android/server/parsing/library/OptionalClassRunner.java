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

package com.android.server.pm.parsing.library;

import org.junit.Assume;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.JUnit4;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.List;

/**
 * Will run a test class, iff a class, specified by name in the {@link OptionalClass} annotation,
 * exists on the class path.
 *
 * <p>It is an {@link InitializationError} if no {@link OptionalClass} annotation is specified on
 * the class that has {@code @RunWith(OptionalClassRunner.class)}.
 *
 * <p>If the named class cannot be found then the test class is reported as having been ignored.
 */
public class OptionalClassRunner extends Runner {

    private final Runner mDelegate;

    public OptionalClassRunner(Class<?> testClass) throws InitializationError {
        OptionalClass annotation = testClass.getAnnotation(OptionalClass.class);
        if (annotation == null) {
            throw new InitializationError(
                    "No " + OptionalClass.class.getName() + " annotation found on " + testClass);
        }

        String className = annotation.value();
        Runner delegate;
        try {
            Class.forName(className);
            // The class could be found so create a JUnit4 delegate for the class to run.
            delegate = new JUnit4(testClass);
        } catch (ClassNotFoundException e) {
            // The class could not be found so create a Runner delegate that will treat the
            // test as having failed a test assumption.
            delegate = new ClassNotFoundRunner(testClass, className);
        }

        this.mDelegate = delegate;
    }

    @Override
    public Description getDescription() {
        return mDelegate.getDescription();
    }

    @Override
    public void run(RunNotifier notifier) {
        mDelegate.run(notifier);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface OptionalClass {
        String value();
    }

    /**
     * Emulates a class containing a single test that fails due to an invalid assumption caused by
     * the missing class.
     */
    private static class ClassNotFoundRunner extends ParentRunner<Runner> {

        private List<Runner> mChildren;

        ClassNotFoundRunner(Class<?> testClass, String className)
                throws InitializationError {
            super(testClass);
            this.mChildren = Collections.singletonList(new ChildRunner(testClass, className));
        }

        @Override
        protected List<Runner> getChildren() {
            return mChildren;
        }

        @Override
        protected Description describeChild(Runner child) {
            return child.getDescription();
        }

        @Override
        protected void runChild(Runner child, RunNotifier notifier) {
            child.run(notifier);
        }

        private class ChildRunner extends Runner {

            private final Class<?> mTestClass;

            private final String mClassName;

            ChildRunner(Class<?> testClass, String className) {
                this.mTestClass = testClass;
                this.mClassName = className;
            }

            @Override
            public Description getDescription() {
                return Description.createTestDescription(mTestClass, "classNotFound");
            }

            @Override
            public void run(RunNotifier notifier) {
                runLeaf(new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        Assume.assumeTrue("Could not find class: " + mClassName, false);
                    }
                }, getDescription(), notifier);
            }
        }
    }
}
