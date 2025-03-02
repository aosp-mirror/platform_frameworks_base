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

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.platform.test.annotations.RavenwoodTestRunnerInitializing;
import android.platform.test.annotations.internal.InnerRunner;
import android.util.Log;

import com.android.ravenwood.common.RavenwoodCommonUtils;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.Suite;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BiConsumer;

/**
 * A test runner used for Ravenwood.
 *
 * It will delegate to another runner specified with {@link InnerRunner}
 * (default = {@link BlockJUnit4ClassRunner}) with the following features.
 * - Add a called before the inner runner gets a chance to run. This can be used to initialize
 *   stuff used by the inner runner.
 * - Add hook points with help from the four test rules such as {@link #sImplicitClassOuterRule},
 *   which are also injected by the ravenizer tool.
 *
 * We use this runner to:
 * - Initialize the Ravenwood environment.
 * - Handle {@link android.platform.test.annotations.DisabledOnRavenwood}.
 */
public final class RavenwoodAwareTestRunner extends RavenwoodAwareTestRunnerBase {
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

    private record HookRule(Scope scope, Order order) implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return getCurrentRunner().wrapWithHooks(base, description, scope, order);
        }
    }

    // The following four rule instances will be injected to tests by the Ravenizer tool.
    public static final TestRule sImplicitClassOuterRule = new HookRule(Scope.Class, Order.Outer);
    public static final TestRule sImplicitClassInnerRule = new HookRule(Scope.Class, Order.Inner);
    public static final TestRule sImplicitInstOuterRule = new HookRule(Scope.Instance, Order.Outer);
    public static final TestRule sImplicitInstInnerRule = new HookRule(Scope.Instance, Order.Inner);

    /** Keeps track of the runner on the current thread. */
    private static final ThreadLocal<RavenwoodAwareTestRunner> sCurrentRunner = new ThreadLocal<>();

    private static RavenwoodAwareTestRunner getCurrentRunner() {
        var runner = sCurrentRunner.get();
        if (runner == null) {
            throw new RuntimeException("Current test runner not set!");
        }
        return runner;
    }

    final Class<?> mTestJavaClass;
    private final Runner mRealRunner;
    private TestClass mTestClass = null;

    /**
     * Stores internal states / methods associated with this runner that's only needed in
     * junit-impl.
     */
    final RavenwoodRunnerState mState = new RavenwoodRunnerState(this);

    /**
     * Constructor.
     */
    public RavenwoodAwareTestRunner(Class<?> testClass) {
        RavenwoodRuntimeEnvironmentController.globalInitOnce();
        mTestJavaClass = testClass;

        /*
         * If the class has @DisabledOnRavenwood, then we'll delegate to
         * ClassSkippingTestRunner, which simply skips it.
         *
         * We need to do it before instantiating TestClass for b/367694651.
         */
        if (!RavenwoodEnablementChecker.shouldRunClassOnRavenwood(testClass, true)) {
            mRealRunner = new ClassSkippingTestRunner(testClass);
            return;
        }

        mTestClass = new TestClass(testClass);

        Log.v(TAG, "RavenwoodAwareTestRunner initializing for " + testClass.getCanonicalName());

        // Hook point to allow more customization.
        runAnnotatedMethodsOnRavenwood(RavenwoodTestRunnerInitializing.class, null);

        mRealRunner = instantiateRealRunner(mTestClass);

        mState.enterTestRunner();
    }

    @Override
    Runner getRealRunner() {
        return mRealRunner;
    }

    private void runAnnotatedMethodsOnRavenwood(Class<? extends Annotation> annotationClass,
            Object instance) {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "runAnnotatedMethodsOnRavenwood() " + annotationClass.getName());
        }

        for (var method : mTestClass.getAnnotatedMethods(annotationClass)) {
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
    public void run(RunNotifier realNotifier) {
        final var notifier = new RavenwoodRunNotifier(realNotifier);
        final var description = getDescription();

        RavenwoodTestStats.getInstance().attachToRunNotifier(notifier);

        if (mRealRunner instanceof ClassSkippingTestRunner) {
            Log.v(TAG, "onClassSkipped: description=" + description);
            mRealRunner.run(notifier);
            return;
        }

        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "Running " + mTestJavaClass.getCanonicalName());
        }
        if (RAVENWOOD_VERBOSE_LOGGING) {
            dumpDescription(description);
        }

        // TODO(b/365976974): handle nested classes better
        final boolean skipRunnerHook =
                mRealRunnerTakesRunnerBuilder && mRealRunner instanceof Suite;

        sCurrentRunner.set(this);
        try {
            if (!skipRunnerHook) {
                try {
                    mState.enterTestClass();
                } catch (Throwable th) {
                    notifier.reportBeforeTestFailure(description, th);
                    return;
                }
            }

            // Delegate to the inner runner.
            mRealRunner.run(notifier);
        } finally {
            sCurrentRunner.remove();

            if (!skipRunnerHook) {
                try {
                    mState.exitTestClass();
                } catch (Throwable th) {
                    notifier.reportAfterTestFailure(th);
                }
            }
        }
    }

    private Statement wrapWithHooks(Statement base, Description description, Scope scope,
            Order order) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                runWithHooks(description, scope, order, base);
            }
        };
    }

    private void runWithHooks(Description description, Scope scope, Order order, Statement s)
            throws Throwable {
        assumeTrue(onBefore(description, scope, order));
        try {
            s.evaluate();
            onAfter(description, scope, order, null);
        } catch (Throwable t) {
            var shouldReportFailure = RavenwoodCommonUtils.runIgnoringException(
                    () -> onAfter(description, scope, order, t));
            if (shouldReportFailure == null || shouldReportFailure) {
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

    /**
     * Called before a test / class.
     *
     * Return false if it should be skipped.
     */
    private boolean onBefore(Description description, Scope scope, Order order) {
        Log.v(TAG, "onBefore: description=" + description + ", " + scope + ", " + order);

        final var classDescription = getDescription();

        // Class-level annotations are checked by the runner already, so we only check
        // method-level annotations here.
        if (scope == Scope.Instance && order == Order.Outer) {
            if (!RavenwoodEnablementChecker.shouldEnableOnRavenwood(description, true)) {
                return false;
            }
        }

        if (scope == Scope.Instance && order == Order.Outer) {
            // Start of a test method.
            mState.enterTestMethod(description);
        }

        return true;
    }

    /**
     * Called after a test / class.
     *
     * Return false if the exception should be ignored.
     */
    private boolean onAfter(Description description, Scope scope, Order order, Throwable th) {
        Log.v(TAG, "onAfter: description=" + description + ", " + scope + ", " + order + ", " + th);

        final var classDescription = getDescription();

        if (scope == Scope.Instance && order == Order.Outer) {
            // End of a test method.
            mState.exitTestMethod(description);
        }

        // If RUN_DISABLED_TESTS is set, and the method did _not_ throw, make it an error.
        if (RavenwoodRule.private$ravenwood().isRunningDisabledTests()
                && scope == Scope.Instance && order == Order.Outer) {

            boolean isTestEnabled = RavenwoodEnablementChecker.shouldEnableOnRavenwood(
                    description, false);
            if (th == null) {
                // Test passed. Is the test method supposed to be enabled?
                if (isTestEnabled) {
                    // Enabled and didn't throw, okay.
                    return true;
                } else {
                    // Disabled and didn't throw. We should report it.
                    fail("Test wasn't included under Ravenwood, but it actually "
                            + "passed under Ravenwood; consider updating annotations");
                    return true; // unreachable.
                }
            } else {
                // Test failed.
                if (isTestEnabled) {
                    // Enabled but failed. We should throw the exception.
                    return true;
                } else {
                    // Disabled and failed. Expected. Don't throw.
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Called by RavenwoodRule.
     */
    static void onRavenwoodRuleEnter(Description description, RavenwoodRule rule) {
        Log.v(TAG, "onRavenwoodRuleEnter: description=" + description);
        getCurrentRunner().mState.enterRavenwoodRule(rule);
    }

    /**
     * Called by RavenwoodRule.
     */
    static void onRavenwoodRuleExit(Description description, RavenwoodRule rule) {
        Log.v(TAG, "onRavenwoodRuleExit: description=" + description);
        getCurrentRunner().mState.exitRavenwoodRule(rule);
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

    static volatile BiConsumer<String, Throwable> sCriticalErrorHandler = null;

    static void onCriticalError(@NonNull String message, @Nullable Throwable th) {
        Log.e(TAG, "Critical error! " + message, th);
        var handler = sCriticalErrorHandler;
        if (handler == null) {
            Log.e(TAG, "Ravenwood cannot continue. Killing self process.", th);
            System.exit(1);
        }
        handler.accept(message, th);
    }
}
