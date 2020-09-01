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

package com.android.server.testables;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.quality.Strictness;

import java.util.function.Supplier;

/**
 * <p>StaticMockFixtureRule is a {@link TestRule} that wraps one or more {@link StaticMockFixture}s
 * to set them up and tear it down automatically. This works well when you have no other static
 * mocks than the ones supported by their respective {@link StaticMockFixture}s.</p>
 *
 * <p>StaticMockFixtureRule should be defined as a rule on your test so it can clean up after
 * itself. Like the following:</p>
 * <pre class="prettyprint">
*  public final StaticMockFixture mStaticMockFixtures = ...;
 * &#064;Rule
 * public final StaticMockFixtureRule mStaticMockFixtureRule =
 *     new StaticMockFixtureRule(mStaticMockFixtures);
 * </pre>
 */
public class StaticMockFixtureRule implements TestRule {
    private StaticMockitoSession mMockitoSession;
    private StaticMockFixture[] mStaticMockFixtures;
    private Supplier<? extends StaticMockFixture>[] mSupplier;

    /**
     * Constructs a StaticMockFixtureRule that always uses the same {@link StaticMockFixture}
     * instance(s).
     *
     * @param staticMockFixtures the {@link StaticMockFixture}(s) to use.
     */
    public StaticMockFixtureRule(StaticMockFixture... staticMockFixtures) {
        mStaticMockFixtures = staticMockFixtures;
        mSupplier = null;
    }

    /**
     * Constructs a StaticMockFixtureRule that retrieves a new {@link StaticMockFixture} instance
     * from one or more {@link Supplier<? extends   StaticMockFixture  >}s for each test invocation.
     *
     * @param supplier the {@link Supplier<? extends   StaticMockFixture  >}(s) that will supply the
     * {@link StaticMockFixture}(s).
     */
    @SafeVarargs
    public StaticMockFixtureRule(Supplier<? extends StaticMockFixture>... supplier) {
        mStaticMockFixtures = null;
        mSupplier = supplier;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        StaticMockitoSessionBuilder sessionBuilder = getSessionBuilder();

        if (mSupplier != null) {
            mStaticMockFixtures = new StaticMockFixture[mSupplier.length];
            for (int i = 0; i < mSupplier.length; i++) {
                mStaticMockFixtures[i] = mSupplier[i].get();
            }
        }

        for (int i = 0; i < mStaticMockFixtures.length; i++) {
            sessionBuilder = mStaticMockFixtures[i].setUpMockedClasses(sessionBuilder);
        }

        mMockitoSession = sessionBuilder.startMocking();

        for (int i = 0; i < mStaticMockFixtures.length; i++) {
            mStaticMockFixtures[i].setUpMockBehaviors();
        }

        return new TestWatcher() {
            @Override
            protected void succeeded(Description description) {
                tearDown(null);
            }

            @Override
            protected void skipped(AssumptionViolatedException e, Description description) {
                tearDown(e);
            }

            @Override
            protected void failed(Throwable e, Description description) {
                tearDown(e);
            }
        }.apply(base, description);
    }

    /**
     * This allows overriding the creation of the builder for a new {@link StaticMockitoSession}.
     * Mainly for testing, but also useful if you have other requirements for the session.
     *
     * @return a new {@link StaticMockitoSessionBuilder}.
     */
    public StaticMockitoSessionBuilder getSessionBuilder() {
        return mockitoSession().strictness(Strictness.LENIENT);
    }

    private void tearDown(Throwable e) {
        mMockitoSession.finishMocking(e);

        for (int i = mStaticMockFixtures.length - 1; i >= 0; i--) {
            mStaticMockFixtures[i].tearDown();
            if (mSupplier != null) {
                mStaticMockFixtures[i] = null;
            }
        }

        if (mSupplier != null) {
            mStaticMockFixtures = null;
        }

        mMockitoSession = null;
    }
}
