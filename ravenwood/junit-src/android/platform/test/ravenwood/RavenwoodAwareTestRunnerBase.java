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

import android.platform.test.annotations.internal.InnerRunner;
import android.util.Log;

import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
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
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.TestClass;

abstract class RavenwoodAwareTestRunnerBase extends Runner implements Filterable, Orderable {
    public static final String TAG = com.android.ravenwood.common.RavenwoodCommonUtils.TAG;

    boolean mRealRunnerTakesRunnerBuilder = false;

    abstract Runner getRealRunner();

    final Runner instantiateRealRunner(TestClass testClass) {
        // Find the real runner.
        final Class<? extends Runner> runnerClass;
        final InnerRunner innerRunnerAnnotation = testClass.getAnnotation(InnerRunner.class);
        if (innerRunnerAnnotation != null) {
            runnerClass = innerRunnerAnnotation.value();
        } else {
            // Default runner.
            runnerClass = BlockJUnit4ClassRunner.class;
        }

        try {
            if (RAVENWOOD_VERBOSE_LOGGING) {
                Log.v(TAG, "Initializing the inner runner: " + runnerClass);
            }
            try {
                return runnerClass.getConstructor(Class.class)
                        .newInstance(testClass.getJavaClass());
            } catch (NoSuchMethodException e) {
                var constructor = runnerClass.getConstructor(Class.class, RunnerBuilder.class);
                mRealRunnerTakesRunnerBuilder = true;
                return constructor.newInstance(
                        testClass.getJavaClass(), new AllDefaultPossibilitiesBuilder());
            }
        } catch (ReflectiveOperationException e) {
            throw logAndFail("Failed to instantiate " + runnerClass, e);
        }
    }

    final Error logAndFail(String message, Throwable exception) {
        Log.e(TAG, message, exception);
        return new AssertionError(message, exception);
    }

    @Override
    public final Description getDescription() {
        return getRealRunner().getDescription();
    }

    @Override
    public final void filter(Filter filter) throws NoTestsRemainException {
        if (getRealRunner() instanceof Filterable r) {
            r.filter(filter);
        }
    }

    @Override
    public final void order(Orderer orderer) throws InvalidOrderingException {
        if (getRealRunner() instanceof Orderable r) {
            r.order(orderer);
        }
    }

    @Override
    public final void sort(Sorter sorter) {
        if (getRealRunner() instanceof Sortable r) {
            r.sort(sorter);
        }
    }
}
