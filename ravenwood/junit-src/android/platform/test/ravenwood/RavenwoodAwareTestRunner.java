/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.ensureIsPublicVoidMethod;
import static com.android.ravenwood.common.RavenwoodCommonUtils.isOnRavenwood;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import com.android.ravenwood.common.RavenwoodCommonUtils;
import com.android.ravenwood.common.SneakyThrow;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.InvalidOrderingException;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.manipulation.Orderable;
import org.junit.runner.manipulation.Orderer;
import org.junit.runner.manipulation.Sortable;
import org.junit.runner.manipulation.Sorter;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;

/**
 * A test runner used for Ravenwood.
 *
 * TODO: Handle ENABLE_PROBE_IGNORED
 *
 * It will delegate to another runner specified with {@link InnerRunner}
 * (default = {@link BlockJUnit4ClassRunner}) with the following features.
 * - Add a {@link RavenwoodAwareTestRunnerHook#onRunnerInitializing} hook, which is called before
 *   the inner runner gets a chance to run. This can be used to initialize stuff used by the
 *   inner runner.
 * - Add hook points, which are handed by RavenwoodAwareTestRunnerHook, with help from
 *   the four test rules such as {@link #sImplicitClassMinRule}, which are also injected by
 *   the ravenizer tool.
 *
 * We use this runner to:
 * - Initialize the bare minimum environmnet just to be enough to make the actual test runners
 *   happy.
 * - Handle {@link android.platform.test.annotations.DisabledOnRavenwood}.
 *
 * This class is built such that it can also be used on a real device, but in that case
 * it will basically just delegate to the inner wrapper, and won't do anything special.
 * (no hooks, etc.)
 */
public class RavenwoodAwareTestRunner extends Runner implements Filterable, Orderable {
    private static final String TAG = "RavenwoodAwareTestRunner";

    @Inherited
    @Target({TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface InnerRunner {
        Class<? extends Runner> value();
    }

    /**
     * An annotation similar to JUnit's BeforeClass, but this gets executed before
     * the inner runner is instantiated, and only on Ravenwood.
     * It can be used to initialize what's needed by the inner runner.
     */
    @Target({METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RavenwoodTestRunnerInitializing {
    }

    /** Scope of a hook. */
    public enum Scope {
        Runner,
        Class,
        Instance,
    }

    /** Order of a hook. */
    public enum Order {
        First,
        Last,
    }

    // The following four rule instances will be injected to tests by the Ravenizer tool.

    public static final TestRule sImplicitClassMinRule = (base, description) ->
            getCurrentRunner().updateStatement(base, description, Scope.Class, Order.First);

    public static final TestRule sImplicitClassMaxRule = (base, description) ->
            getCurrentRunner().updateStatement(base, description, Scope.Class, Order.Last);

    public static final TestRule sImplicitInstMinRule = (base, description) ->
            getCurrentRunner().updateStatement(base, description, Scope.Instance, Order.First);

    public static final TestRule sImplicitInstMaxRule = (base, description) ->
            getCurrentRunner().updateStatement(base, description, Scope.Instance, Order.Last);

    public static final String IMPLICIT_CLASS_MIN_RULE_NAME = "sImplicitClassMinRule";
    public static final String IMPLICIT_CLASS_MAX_RULE_NAME = "sImplicitClassMaxRule";
    public static final String IMPLICIT_INST_MIN_RULE_NAME = "sImplicitInstMinRule";
    public static final String IMPLICIT_INST_MAX_RULE_NAME = "sImplicitInstMaxRule";

    /** Keeps track of the runner on the current thread. */
    private static final ThreadLocal<RavenwoodAwareTestRunner> sCurrentRunner = new ThreadLocal<>();

    private static RavenwoodAwareTestRunner getCurrentRunner() {
        var runner = sCurrentRunner.get();
        if (runner == null) {
            throw new RuntimeException("Current test runner not set!");
        }
        return runner;
    }

    private final TestClass mTestClsas;
    private final Runner mRealRunner;

    /** Simple logging method. */
    private void log(String message) {
        RavenwoodCommonUtils.log(TAG, "[" + getTestClass() + "  @" + this + "] " + message);
    }

    private Error logAndFail(String message, Throwable innerException) {
        log(message);
        log("    Exception=" + innerException);
        throw new AssertionError(message, innerException);
    }

    public TestClass getTestClass() {
        return mTestClsas;
    }

    /**
     * Constructor.
     */
    public RavenwoodAwareTestRunner(Class<?> testClass) {
        mTestClsas = new TestClass(testClass);

        /*
         * If the class has @DisabledOnRavenwood, then we'll delegate to ClassSkippingTestRunner,
         * which simply skips it.
         */
        if (isOnRavenwood() && !RavenwoodAwareTestRunnerHook.shouldRunClassOnRavenwood(
                mTestClsas.getJavaClass())) {
            mRealRunner = new ClassSkippingTestRunner(mTestClsas);
            return;
        }

        // Find the real runner.
        final Class<? extends Runner> realRunner;
        final InnerRunner innerRunnerAnnotation = mTestClsas.getAnnotation(InnerRunner.class);
        if (innerRunnerAnnotation != null) {
            realRunner = innerRunnerAnnotation.value();
        } else {
            // Default runner.
            realRunner = BlockJUnit4ClassRunner.class;
        }

        onRunnerInitializing();

        try {
            log("Initializing the inner runner: " + realRunner);

            mRealRunner = realRunner.getConstructor(Class.class).newInstance(testClass);

        } catch (InstantiationException | IllegalAccessException
                 | InvocationTargetException | NoSuchMethodException e) {
            throw logAndFail("Failed to instantiate " + realRunner, e);
        }
    }

    /**
     * Run the bare minimum setup to initialize the wrapped runner.
     */
    // This method is called by the ctor, so never make it virtual.
    private void onRunnerInitializing() {
        if (!isOnRavenwood()) {
            return;
        }

        log("onRunnerInitializing");

        RavenwoodAwareTestRunnerHook.onRunnerInitializing(this, mTestClsas);

        // Hook point to allow more customization.
        runAnnotatedMethodsOnRavenwood(RavenwoodTestRunnerInitializing.class, null);
    }

    private void runAnnotatedMethodsOnRavenwood(Class<? extends Annotation> annotationClass,
            Object instance) {
        if (!isOnRavenwood()) {
            return;
        }
        log("runAnnotatedMethodsOnRavenwood() " + annotationClass.getName());

        for (var method : getTestClass().getAnnotatedMethods(annotationClass)) {
            ensureIsPublicVoidMethod(method.getMethod(), /* isStatic=*/ instance == null);

            var methodDesc = method.getDeclaringClass().getName() + "."
                    + method.getMethod().toString();
            try {
                method.getMethod().invoke(instance);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw logAndFail("Caught exception while running method " + methodDesc, e);
            }
        }
    }

    @Override
    public Description getDescription() {
        return mRealRunner.getDescription();
    }

    @Override
    public void run(RunNotifier notifier) {
        if (mRealRunner instanceof ClassSkippingTestRunner) {
            mRealRunner.run(notifier);
            RavenwoodAwareTestRunnerHook.onClassSkipped(getDescription());
            return;
        }

        sCurrentRunner.set(this);
        try {
            runWithHooks(getDescription(), Scope.Runner, Order.First,
                    () -> mRealRunner.run(notifier));
        } finally {
            sCurrentRunner.remove();
        }
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
        if (mRealRunner instanceof Filterable r) {
            r.filter(filter);
        }
    }

    @Override
    public void order(Orderer orderer) throws InvalidOrderingException {
        if (mRealRunner instanceof Orderable r) {
            r.order(orderer);
        }
    }

    @Override
    public void sort(Sorter sorter) {
        if (mRealRunner instanceof Sortable r) {
            r.sort(sorter);
        }
    }

    private Statement updateStatement(Statement base, Description description, Scope scope,
            Order order) {
        if (!isOnRavenwood()) {
            return base;
        }
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                runWithHooks(description, scope, order, base);
            }
        };
    }

    private void runWithHooks(Description description, Scope scope, Order order, Runnable r) {
        runWithHooks(description, scope, order, new Statement() {
            @Override
            public void evaluate() throws Throwable {
                r.run();
            }
        });
    }

    private void runWithHooks(Description description, Scope scope, Order order, Statement s) {
        if (isOnRavenwood()) {
            Assume.assumeTrue(
                    RavenwoodAwareTestRunnerHook.onBefore(this, description, scope, order));
        }
        try {
            s.evaluate();
            if (isOnRavenwood()) {
                RavenwoodAwareTestRunnerHook.onAfter(this, description, scope, order, null);
            }
        } catch (Throwable t) {
            boolean shouldThrow = true;
            if (isOnRavenwood()) {
                shouldThrow = RavenwoodAwareTestRunnerHook.onAfter(
                        this, description, scope, order, t);
            }
            if (shouldThrow) {
                SneakyThrow.sneakyThrow(t);
            }
        }
    }

    /**
     * A runner that simply skips a class. It still has to support {@link Filterable}
     * because otherwise the result still says "SKIPPED" even when it's not included in the
     * filter.
     */
    private static class ClassSkippingTestRunner extends Runner implements Filterable {
        private final TestClass mTestClass;
        private final Description mDescription;
        private boolean mFilteredOut;

        ClassSkippingTestRunner(TestClass testClass) {
            mTestClass = testClass;
            mDescription = Description.createTestDescription(
                    testClass.getJavaClass(), testClass.getJavaClass().getSimpleName());
            mFilteredOut = false;
        }

        @Override
        public Description getDescription() {
            return mDescription;
        }

        @Override
        public void run(RunNotifier notifier) {
            if (mFilteredOut) {
                return;
            }
            notifier.fireTestSuiteStarted(mDescription);
            notifier.fireTestIgnored(mDescription);
            notifier.fireTestSuiteFinished(mDescription);
        }

        @Override
        public void filter(Filter filter) throws NoTestsRemainException {
            if (filter.shouldRun(mDescription)) {
                mFilteredOut = false;
            } else {
                throw new NoTestsRemainException();
            }
        }
    }
}
