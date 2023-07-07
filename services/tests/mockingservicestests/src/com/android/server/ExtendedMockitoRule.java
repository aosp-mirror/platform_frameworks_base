/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;

import android.annotation.Nullable;
import android.util.Log;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.testing.StaticMockFixture;
import com.android.modules.utils.testing.StaticMockFixtureRule;

import org.mockito.Mockito;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rule to make it easier to use Extended Mockito.
 *
 * <p>It's derived from {@link StaticMockFixtureRule}, with the additional features:
 *
 * <ul>
 *   <li>Easier to define which classes must be statically mocked or spied
 *   <li>Automatically starts mocking (so tests don't need a mockito runner or rule)
 *   <li>Automatically clears the inlined mocks at the end (to avoid OOM)
 *   <li>Allows other customization like strictness
 * </ul>
 */
public final class ExtendedMockitoRule extends StaticMockFixtureRule {

    private static final String TAG = ExtendedMockitoRule.class.getSimpleName();

    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private final Object mTestClassInstance;
    private final Strictness mStrictness;

    private ExtendedMockitoRule(Builder builder) {
        super(() -> new SimpleStatickMockFixture(builder.mMockedStaticClasses,
                builder.mSpiedStaticClasses, builder.mDynamicSessionBuilderConfigurator,
                builder.mAfterSessionFinishedCallback));
        mTestClassInstance = builder.mTestClassInstance;
        mStrictness = builder.mStrictness;
        if (VERBOSE) {
            Log.v(TAG, "strictness=" + mStrictness + ", testClassInstance" + mTestClassInstance
                    + ", mockedStaticClasses=" + builder.mMockedStaticClasses
                    + ", spiedStaticClasses=" + builder.mSpiedStaticClasses
                    + ", dynamicSessionBuilderConfigurator="
                    + builder.mDynamicSessionBuilderConfigurator
                    + ", afterSessionFinishedCallback=" + builder.mAfterSessionFinishedCallback);
        }
    }

    @Override
    public StaticMockitoSessionBuilder getSessionBuilder() {
        StaticMockitoSessionBuilder sessionBuilder = super.getSessionBuilder();
        if (mStrictness != null) {
            if (VERBOSE) {
                Log.v(TAG, "Setting strictness to " + mStrictness + " on " + sessionBuilder);
            }
            sessionBuilder.strictness(mStrictness);
        }
        return sessionBuilder.initMocks(mTestClassInstance);
    }

    public static final class Builder {
        private final Object mTestClassInstance;
        private @Nullable Strictness mStrictness;
        private final List<Class<?>> mMockedStaticClasses = new ArrayList<>();
        private final List<Class<?>> mSpiedStaticClasses = new ArrayList<>();
        private @Nullable Visitor<StaticMockitoSessionBuilder> mDynamicSessionBuilderConfigurator;
        private @Nullable Runnable mAfterSessionFinishedCallback;

        public Builder(Object testClassInstance) {
            mTestClassInstance = Objects.requireNonNull(testClassInstance);
        }

        public Builder setStrictness(Strictness strictness) {
            mStrictness = Objects.requireNonNull(strictness);
            return this;
        }

        public Builder mockStatic(Class<?> clazz) {
            Objects.requireNonNull(clazz);
            Preconditions.checkState(!mMockedStaticClasses.contains(clazz),
                    "class %s already mocked", clazz);
            mMockedStaticClasses.add(clazz);
            return this;
        }

        public Builder spyStatic(Class<?> clazz) {
            Objects.requireNonNull(clazz);
            Preconditions.checkState(!mSpiedStaticClasses.contains(clazz),
                    "class %s already spied", clazz);
            mSpiedStaticClasses.add(clazz);
            return this;
        }

        public Builder dynamiclyConfigureSessionBuilder(
                Visitor<StaticMockitoSessionBuilder> dynamicSessionBuilderConfigurator) {
            mDynamicSessionBuilderConfigurator = Objects
                    .requireNonNull(dynamicSessionBuilderConfigurator);
            return this;
        }

        public Builder afterSessionFinished(Runnable runnable) {
            mAfterSessionFinishedCallback = Objects.requireNonNull(runnable);
            return this;
        }

        public ExtendedMockitoRule build() {
            return new ExtendedMockitoRule(this);
        }
    }

    private static final class SimpleStatickMockFixture implements StaticMockFixture {

        private final List<Class<?>> mMockedStaticClasses;
        private final List<Class<?>> mSpiedStaticClasses;
        @Nullable
        private final Visitor<StaticMockitoSessionBuilder> mDynamicSessionBuilderConfigurator;
        @Nullable
        private final Runnable mAfterSessionFinishedCallback;

        private SimpleStatickMockFixture(List<Class<?>> mockedStaticClasses,
                List<Class<?>> spiedStaticClasses,
                @Nullable Visitor<StaticMockitoSessionBuilder> dynamicSessionBuilderConfigurator,
                @Nullable Runnable afterSessionFinishedCallback) {
            mMockedStaticClasses = mockedStaticClasses;
            mSpiedStaticClasses = spiedStaticClasses;
            mDynamicSessionBuilderConfigurator = dynamicSessionBuilderConfigurator;
            mAfterSessionFinishedCallback = afterSessionFinishedCallback;
        }

        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                StaticMockitoSessionBuilder sessionBuilder) {
            mMockedStaticClasses.forEach((c) -> sessionBuilder.mockStatic(c));
            mSpiedStaticClasses.forEach((c) -> sessionBuilder.spyStatic(c));
            if (mDynamicSessionBuilderConfigurator != null) {
                mDynamicSessionBuilderConfigurator.visit(sessionBuilder);
            }
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {
        }

        @Override
        public void tearDown() {
            try {
                if (mAfterSessionFinishedCallback != null) {
                    mAfterSessionFinishedCallback.run();
                }
            } finally {
                if (VERBOSE) {
                    Log.v(TAG, "calling Mockito.framework().clearInlineMocks()");
                }
                // When using inline mock maker, clean up inline mocks to prevent OutOfMemory
                // errors. See https://github.com/mockito/mockito/issues/1614 and b/259280359.
                Mockito.framework().clearInlineMocks();
            }
        }
    }
}
