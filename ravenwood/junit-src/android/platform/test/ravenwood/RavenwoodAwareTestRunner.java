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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING;
import static com.android.ravenwood.common.RavenwoodCommonUtils.ensureIsPublicVoidMethod;
import static com.android.ravenwood.common.RavenwoodCommonUtils.isOnRavenwood;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.InvalidOrderingException;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.manipulation.Orderable;
import org.junit.runner.manipulation.Orderer;
import org.junit.runner.manipulation.Sortable;
import org.junit.runner.manipulation.Sorter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.Suite;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Stack;
import java.util.function.BiConsumer;

/**
 * A test runner used for Ravenwood.
 *
 * It will delegate to another runner specified with {@link InnerRunner}
 * (default = {@link BlockJUnit4ClassRunner}) with the following features.
 * - Add a {@link RavenwoodAwareTestRunnerHook#onRunnerInitializing} hook, which is called before
 *   the inner runner gets a chance to run. This can be used to initialize stuff used by the
 *   inner runner.
 * - Add hook points, which are handed by RavenwoodAwareTestRunnerHook, with help from
 *   the four test rules such as {@link #sImplicitClassOuterRule}, which are also injected by
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
public final class RavenwoodAwareTestRunner extends Runner implements Filterable, Orderable {
    public static final String TAG = "Ravenwood";

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
        Class,
        Instance,
    }

    /** Order of a hook. */
    public enum Order {
        Outer,
        Inner,
    }

    // The following four rule instances will be injected to tests by the Ravenizer tool.
    private static class RavenwoodClassOuterRule implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return getCurrentRunner().wrapWithHooks(base, description, Scope.Class, Order.Outer);
        }
    }

    private static class RavenwoodClassInnerRule implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return getCurrentRunner().wrapWithHooks(base, description, Scope.Class, Order.Inner);
        }
    }

    private static class RavenwoodInstanceOuterRule implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return getCurrentRunner().wrapWithHooks(
                    base, description, Scope.Instance, Order.Outer);
        }
    }

    private static class RavenwoodInstanceInnerRule implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return getCurrentRunner().wrapWithHooks(
                    base, description, Scope.Instance, Order.Inner);
        }
    }

    public static final TestRule sImplicitClassOuterRule = new RavenwoodClassOuterRule();
    public static final TestRule sImplicitClassInnerRule = new RavenwoodClassInnerRule();
    public static final TestRule sImplicitInstOuterRule = new RavenwoodInstanceOuterRule();
    public static final TestRule sImplicitInstInnerRule = new RavenwoodInstanceOuterRule();

    public static final String IMPLICIT_CLASS_OUTER_RULE_NAME = "sImplicitClassOuterRule";
    public static final String IMPLICIT_CLASS_INNER_RULE_NAME = "sImplicitClassInnerRule";
    public static final String IMPLICIT_INST_OUTER_RULE_NAME = "sImplicitInstOuterRule";
    public static final String IMPLICIT_INST_INNER_RULE_NAME = "sImplicitInstInnerRule";

    /** Keeps track of the runner on the current thread. */
    private static final ThreadLocal<RavenwoodAwareTestRunner> sCurrentRunner = new ThreadLocal<>();

    static RavenwoodAwareTestRunner getCurrentRunner() {
        var runner = sCurrentRunner.get();
        if (runner == null) {
            throw new RuntimeException("Current test runner not set!");
        }
        return runner;
    }

    private final Class<?> mTestJavaClass;
    private TestClass mTestClass = null;
    private Runner mRealRunner = null;
    private Description mDescription = null;
    private Throwable mExceptionInConstructor = null;
    private boolean mRealRunnerTakesRunnerBuilder = false;

    /**
     * Stores internal states / methods associated with this runner that's only needed in
     * junit-impl.
     */
    final RavenwoodRunnerState mState = new RavenwoodRunnerState(this);

    private Error logAndFail(String message, Throwable exception) {
        Log.e(TAG, message, exception);
        throw new AssertionError(message, exception);
    }

    public TestClass getTestClass() {
        return mTestClass;
    }

    /**
     * Constructor.
     */
    public RavenwoodAwareTestRunner(Class<?> testClass) {
        mTestJavaClass = testClass;
        try {
            performGlobalInitialization();

            /*
             * If the class has @DisabledOnRavenwood, then we'll delegate to
             * ClassSkippingTestRunner, which simply skips it.
             *
             * We need to do it before instantiating TestClass for b/367694651.
             */
            if (isOnRavenwood() && !RavenwoodAwareTestRunnerHook.shouldRunClassOnRavenwood(
                    testClass)) {
                mRealRunner = new ClassSkippingTestRunner(testClass);
                mDescription = mRealRunner.getDescription();
                return;
            }

            mTestClass = new TestClass(testClass);

            Log.v(TAG, "RavenwoodAwareTestRunner starting for " + testClass.getCanonicalName());

            onRunnerInitializing();

            // Find the real runner.
            final Class<? extends Runner> realRunnerClass;
            final InnerRunner innerRunnerAnnotation = mTestClass.getAnnotation(InnerRunner.class);
            if (innerRunnerAnnotation != null) {
                realRunnerClass = innerRunnerAnnotation.value();
            } else {
                // Default runner.
                realRunnerClass = BlockJUnit4ClassRunner.class;
            }

            try {
                Log.i(TAG, "Initializing the inner runner: " + realRunnerClass);

                mRealRunner = instantiateRealRunner(realRunnerClass, testClass);
                mDescription = mRealRunner.getDescription();

            } catch (InstantiationException | IllegalAccessException
                     | InvocationTargetException | NoSuchMethodException e) {
                throw logAndFail("Failed to instantiate " + realRunnerClass, e);
            }
        } catch (Throwable th) {
            // If we throw in the constructor, Tradefed may not report it and just ignore the class,
            // so record it and throw it when the test actually started.
            Log.e(TAG, "Fatal: Exception detected in constructor", th);
            mExceptionInConstructor = new RuntimeException("Exception detected in constructor",
                    th);
            mDescription = Description.createTestDescription(testClass, "Constructor");

            // This is for testing if tradefed is fixed.
            if ("1".equals(System.getenv("RAVENWOOD_THROW_EXCEPTION_IN_TEST_RUNNER"))) {
                throw th;
            }
        }
    }

    private Runner instantiateRealRunner(
            Class<? extends Runner> realRunnerClass,
            Class<?> testClass)
            throws NoSuchMethodException, InvocationTargetException, InstantiationException,
            IllegalAccessException {
        try {
            return realRunnerClass.getConstructor(Class.class).newInstance(testClass);
        } catch (NoSuchMethodException e) {
            var constructor = realRunnerClass.getConstructor(Class.class, RunnerBuilder.class);
            mRealRunnerTakesRunnerBuilder = true;
            return constructor.newInstance(testClass, new AllDefaultPossibilitiesBuilder());
        }
    }

    private void performGlobalInitialization() {
        if (!isOnRavenwood()) {
            return;
        }
        RavenwoodAwareTestRunnerHook.performGlobalInitialization();
    }

    /**
     * Run the bare minimum setup to initialize the wrapped runner.
     */
    // This method is called by the ctor, so never make it virtual.
    private void onRunnerInitializing() {
        if (!isOnRavenwood()) {
            return;
        }

        RavenwoodAwareTestRunnerHook.onRunnerInitializing(this, mTestClass);

        // Hook point to allow more customization.
        runAnnotatedMethodsOnRavenwood(RavenwoodTestRunnerInitializing.class, null);
    }

    private void runAnnotatedMethodsOnRavenwood(Class<? extends Annotation> annotationClass,
            Object instance) {
        if (!isOnRavenwood()) {
            return;
        }
        Log.v(TAG, "runAnnotatedMethodsOnRavenwood() " + annotationClass.getName());

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
        return mDescription;
    }

    @Override
    public void run(RunNotifier realNotifier) {
        final RavenwoodRunNotifier notifier = new RavenwoodRunNotifier(realNotifier);

        if (mRealRunner instanceof ClassSkippingTestRunner) {
            mRealRunner.run(notifier);
            RavenwoodAwareTestRunnerHook.onClassSkipped(getDescription());
            return;
        }

        Log.v(TAG, "Starting " + mTestJavaClass.getCanonicalName());
        if (RAVENWOOD_VERBOSE_LOGGING) {
            dumpDescription(getDescription());
        }

        if (maybeReportExceptionFromConstructor(notifier)) {
            return;
        }

        // TODO(b/365976974): handle nested classes better
        final boolean skipRunnerHook =
                mRealRunnerTakesRunnerBuilder && mRealRunner instanceof Suite;

        sCurrentRunner.set(this);
        try {
            if (!skipRunnerHook) {
                try {
                    RavenwoodAwareTestRunnerHook.onBeforeInnerRunnerStart(
                            this, getDescription());
                } catch (Throwable th) {
                    notifier.reportBeforeTestFailure(getDescription(), th);
                    return;
                }
            }

            // Delegate to the inner runner.
            mRealRunner.run(notifier);
        } finally {
            sCurrentRunner.remove();

            if (!skipRunnerHook) {
                try {
                    RavenwoodAwareTestRunnerHook.onAfterInnerRunnerFinished(
                            this, getDescription());
                } catch (Throwable th) {
                    notifier.reportAfterTestFailure(th);
                }
            }
        }
    }

    /** Throw the exception detected in the constructor, if any. */
    private boolean maybeReportExceptionFromConstructor(RunNotifier notifier) {
        if (mExceptionInConstructor == null) {
            return false;
        }
        notifier.fireTestStarted(mDescription);
        notifier.fireTestFailure(new Failure(mDescription, mExceptionInConstructor));
        notifier.fireTestFinished(mDescription);

        return true;
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

    private Statement wrapWithHooks(Statement base, Description description, Scope scope,
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

    private void runWithHooks(Description description, Scope scope, Order order, Runnable r)
            throws Throwable {
        runWithHooks(description, scope, order, new Statement() {
            @Override
            public void evaluate() throws Throwable {
                r.run();
            }
        });
    }

    private void runWithHooks(Description description, Scope scope, Order order, Statement s)
            throws Throwable {
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
                throw t;
            }
        }
    }

    /**
     * A runner that simply skips a class. It still has to support {@link Filterable}
     * because otherwise the result still says "SKIPPED" even when it's not included in the
     * filter.
     */
    private static class ClassSkippingTestRunner extends Runner implements Filterable {
        private final Description mDescription;
        private boolean mFilteredOut;

        ClassSkippingTestRunner(Class<?> testClass) {
            mDescription = Description.createTestDescription(testClass, testClass.getSimpleName());
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

    private void dumpDescription(Description desc) {
        dumpDescription(desc, "[TestDescription]=", "  ");
    }

    private void dumpDescription(Description desc, String header, String indent) {
        Log.v(TAG, indent + header + desc);

        var children = desc.getChildren();
        var childrenIndent = "  " + indent;
        for (int i = 0; i < children.size(); i++) {
            dumpDescription(children.get(i), "#" + i + ": ", childrenIndent);
        }
    }

    /**
     * A run notifier that wraps another notifier and provides the following features:
     * - Handle a failure that happened before testStarted and testEnded (typically that means
     *   it's from @BeforeClass or @AfterClass, or a @ClassRule) and deliver it as if
     *   individual tests in the class reported it. This is for b/364395552.
     *
     * - Logging.
     */
    private class RavenwoodRunNotifier extends RunNotifier {
        private final RunNotifier mRealNotifier;

        private final Stack<Description> mSuiteStack = new Stack<>();
        private Description mCurrentSuite = null;
        private final ArrayList<Throwable> mOutOfTestFailures = new ArrayList<>();

        private boolean mBeforeTest = true;
        private boolean mAfterTest = false;

        private RavenwoodRunNotifier(RunNotifier realNotifier) {
            mRealNotifier = realNotifier;
        }

        private boolean isInTest() {
            return !mBeforeTest && !mAfterTest;
        }

        @Override
        public void addListener(RunListener listener) {
            mRealNotifier.addListener(listener);
        }

        @Override
        public void removeListener(RunListener listener) {
            mRealNotifier.removeListener(listener);
        }

        @Override
        public void addFirstListener(RunListener listener) {
            mRealNotifier.addFirstListener(listener);
        }

        @Override
        public void fireTestRunStarted(Description description) {
            Log.i(TAG, "testRunStarted: " + description);
            mRealNotifier.fireTestRunStarted(description);
        }

        @Override
        public void fireTestRunFinished(Result result) {
            Log.i(TAG, "testRunFinished: "
                    + result.getRunCount() + ","
                    + result.getFailureCount() + ","
                    + result.getAssumptionFailureCount() + ","
                    + result.getIgnoreCount());
            mRealNotifier.fireTestRunFinished(result);
        }

        @Override
        public void fireTestSuiteStarted(Description description) {
            Log.i(TAG, "testSuiteStarted: " + description);
            mRealNotifier.fireTestSuiteStarted(description);

            mBeforeTest = true;
            mAfterTest = false;

            // Keep track of the current suite, needed if the outer test is a Suite,
            // in which case its children are test classes. (not test methods)
            mCurrentSuite = description;
            mSuiteStack.push(description);

            mOutOfTestFailures.clear();
        }

        @Override
        public void fireTestSuiteFinished(Description description) {
            Log.i(TAG, "testSuiteFinished: " + description);
            mRealNotifier.fireTestSuiteFinished(description);

            maybeHandleOutOfTestFailures();

            mBeforeTest = true;
            mAfterTest = false;

            // Restore the upper suite.
            mSuiteStack.pop();
            mCurrentSuite = mSuiteStack.size() == 0 ? null : mSuiteStack.peek();
        }

        @Override
        public void fireTestStarted(Description description) throws StoppedByUserException {
            Log.i(TAG, "testStarted: " + description);
            mRealNotifier.fireTestStarted(description);

            mAfterTest = false;
            mBeforeTest = false;
        }

        @Override
        public void fireTestFailure(Failure failure) {
            Log.i(TAG, "testFailure: " + failure);

            if (isInTest()) {
                mRealNotifier.fireTestFailure(failure);
            } else {
                mOutOfTestFailures.add(failure.getException());
            }
        }

        @Override
        public void fireTestAssumptionFailed(Failure failure) {
            Log.i(TAG, "testAssumptionFailed: " + failure);

            if (isInTest()) {
                mRealNotifier.fireTestAssumptionFailed(failure);
            } else {
                mOutOfTestFailures.add(failure.getException());
            }
        }

        @Override
        public void fireTestIgnored(Description description) {
            Log.i(TAG, "testIgnored: " + description);
            mRealNotifier.fireTestIgnored(description);
        }

        @Override
        public void fireTestFinished(Description description) {
            Log.i(TAG, "testFinished: " + description);
            mRealNotifier.fireTestFinished(description);

            mAfterTest = true;
        }

        @Override
        public void pleaseStop() {
            Log.w(TAG, "pleaseStop:");
            mRealNotifier.pleaseStop();
        }

        /**
         * At the end of each Suite, we handle failures happened out of test methods.
         * (typically in @BeforeClass or @AfterClasses)
         *
         * This is to work around b/364395552.
         */
        private boolean maybeHandleOutOfTestFailures() {
            if (mOutOfTestFailures.size() == 0) {
                return false;
            }
            Throwable th;
            if (mOutOfTestFailures.size() == 1) {
                th = mOutOfTestFailures.get(0);
            } else {
                th = new MultipleFailureException(mOutOfTestFailures);
            }
            if (mBeforeTest) {
                reportBeforeTestFailure(mCurrentSuite, th);
                return true;
            }
            if (mAfterTest) {
                reportAfterTestFailure(th);
                return true;
            }
            return false;
        }

        public void reportBeforeTestFailure(Description suiteDesc, Throwable th) {
            // If a failure happens befere running any tests, we'll need to pretend
            // as if each test in the suite reported the failure, to work around b/364395552.
            for (var child : suiteDesc.getChildren()) {
                if (child.isSuite()) {
                    // If the chiil is still a "parent" -- a test class or a test suite
                    // -- propagate to its children.
                    mRealNotifier.fireTestSuiteStarted(child);
                    reportBeforeTestFailure(child, th);
                    mRealNotifier.fireTestSuiteFinished(child);
                } else {
                    mRealNotifier.fireTestStarted(child);
                    Failure f = new Failure(child, th);
                    if (th instanceof AssumptionViolatedException) {
                        mRealNotifier.fireTestAssumptionFailed(f);
                    } else {
                        mRealNotifier.fireTestFailure(f);
                    }
                    mRealNotifier.fireTestFinished(child);
                }
            }
        }

        public void reportAfterTestFailure(Throwable th) {
            // Unfortunately, there's no good way to report it, so kill the own process.
            onCriticalError(
                    "Failures detected in @AfterClass, which would be swallowed by tradefed",
                    th);
        }
    }

    private static volatile BiConsumer<String, Throwable> sCriticalErrorHanler;

    private void onCriticalError(@NonNull String message, @Nullable Throwable th) {
        Log.e(TAG, "Critical error! " + message, th);
        var handler = sCriticalErrorHanler;
        if (handler == null) {
            handler = sDefaultCriticalErrorHandler;
        }
        handler.accept(message, th);
    }

    private static BiConsumer<String, Throwable> sDefaultCriticalErrorHandler = (message, th) -> {
        Log.e(TAG, "Ravenwood cannot continue. Killing self process.", th);
        System.exit(1);
    };

    /**
     * Contains Ravenwood private APIs.
     */
    public static class RavenwoodPrivate {
        private RavenwoodPrivate() {
        }

        /**
         * Set a listener for onCriticalError(), for testing. If a listener is set, we won't call
         * System.exit().
         */
        public void setCriticalErrorHandler(
                @Nullable BiConsumer<String, Throwable> handler) {
            sCriticalErrorHanler = handler;
        }
    }

    private static final RavenwoodPrivate sRavenwoodPrivate = new RavenwoodPrivate();

    public static RavenwoodPrivate private$ravenwood() {
        return sRavenwoodPrivate;
    }
}
