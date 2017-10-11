/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive.util.perf;

import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * JUnit {@link Runner} that times the test execution and produces some stats.
 */
public class PerformanceRunner extends BlockJUnit4ClassRunner {
    private static final int DEFAULT_WARMUP_ITERATIONS = 50;
    private static final int DEFAULT_RUNS = 100;

    private final int mWarmUpIterations;
    private final int mRuns;

    public PerformanceRunner(Class<?> testClass) throws InitializationError {
        super(testClass);

        Configuration classConfig = testClass.getAnnotation(Configuration.class);
        mWarmUpIterations = classConfig != null && classConfig.warmUpIterations() != -1 ?
                classConfig.warmUpIterations() :
                DEFAULT_WARMUP_ITERATIONS;
        mRuns = classConfig != null && classConfig.runs() != -1 ?
                classConfig.runs() :
                DEFAULT_RUNS;
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        int warmUpIterations;
        int runs;

        Configuration methodConfig = method.getAnnotation(Configuration.class);
        warmUpIterations = methodConfig != null && methodConfig.warmUpIterations() != -1 ?
                methodConfig.warmUpIterations() :
                mWarmUpIterations;
        runs = methodConfig != null && methodConfig.runs() != -1 ?
                methodConfig.runs() :
                mRuns;
        return new TimedStatement(super.methodInvoker(method, test), warmUpIterations, runs,
                (result) -> System.out.println(result.toString()));
    }

    @Override
    public void run(RunNotifier notifier) {
        super.run(notifier);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    public @interface Configuration {
        int warmUpIterations() default -1;

        int runs() default -1;
    }
}
