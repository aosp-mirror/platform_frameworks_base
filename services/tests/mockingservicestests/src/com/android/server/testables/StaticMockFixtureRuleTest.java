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

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.function.Supplier;

/** Tests that StaticMockFixture manages fixtures and suppliers correctly. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class StaticMockFixtureRuleTest {
    private MockitoSession mMockitoSession;

    @Mock private StaticMockitoSessionBuilder mSessionBuilder;
    @Mock private StaticMockitoSession mSession;
    @Mock private StaticMockFixture mA1;
    @Mock private StaticMockFixture mB1;
    @Mock private StaticMockFixture mA2;
    @Mock private StaticMockFixture mB2;
    @Mock private Supplier<StaticMockFixture> mSupplyA;
    @Mock private Supplier<StaticMockFixture> mSupplyB;
    @Mock private Statement mStatement;
    @Mock private Statement mSkipStatement;
    @Mock private Statement mThrowStatement;
    @Mock private Description mDescription;

    @Before
    public void setUp() throws Throwable {
        mMockitoSession = Mockito.mockitoSession()
                .strictness(LENIENT)
                .initMocks(this)
                .startMocking();
        prepareMockBehaviours();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    private void prepareFixtureMocks(StaticMockFixture... mocks) {
        for (StaticMockFixture mock : mocks) {
            when(mock.setUpMockedClasses(any())).thenAnswer(
                    invocation -> invocation.getArgument(0));
            doNothing().when(mock).setUpMockBehaviors();
        }
    }

    private void prepareMockBehaviours() throws Throwable {
        when(mSessionBuilder.startMocking()).thenReturn(mSession);
        when(mSupplyA.get()).thenReturn(mA1, mA2);
        when(mSupplyB.get()).thenReturn(mB1, mB2);
        prepareFixtureMocks(mA1, mA2, mB1, mB2);
        when(mA1.setUpMockedClasses(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(mA1).setUpMockBehaviors();
        when(mB1.setUpMockedClasses(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(mB1).setUpMockBehaviors();
        doNothing().when(mStatement).evaluate();
        doThrow(new AssumptionViolatedException("bad assumption, test should be skipped"))
                .when(mSkipStatement).evaluate();
        doThrow(new IllegalArgumentException("bad argument, test should be failed"))
                .when(mThrowStatement).evaluate();
        doNothing().when(mA1).tearDown();
        doNothing().when(mB1).tearDown();
    }

    private InOrder mocksInOrder()  {
        return inOrder(mSessionBuilder, mSession, mSupplyA, mSupplyB, mA1, mA2, mB1, mB2,
                mStatement, mSkipStatement, mThrowStatement, mDescription);
    }

    private void verifyNoMoreImportantMockInteractions()  {
        verifyNoMoreInteractions(mSupplyA, mSupplyB, mA1, mA2, mB1, mB2, mStatement,
                mSkipStatement, mThrowStatement);
    }

    @Test
    public void testRuleWorksWithExplicitFixtures() throws Throwable {
        InOrder inOrder = mocksInOrder();

        StaticMockFixtureRule rule = new StaticMockFixtureRule(mA1, mB1) {
            @Override public StaticMockitoSessionBuilder getSessionBuilder() {
                return mSessionBuilder;
            }
        };
        Statement runMe = rule.apply(mStatement, mDescription);

        inOrder.verify(mA1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA1).setUpMockBehaviors();
        inOrder.verify(mB1).setUpMockBehaviors();

        runMe.evaluate();

        inOrder.verify(mStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB1).tearDown();
        inOrder.verify(mA1).tearDown();

        // Round two: use the same fixtures again.
        rule.apply(mStatement, mDescription).evaluate();

        inOrder.verify(mA1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA1).setUpMockBehaviors();
        inOrder.verify(mB1).setUpMockBehaviors();
        inOrder.verify(mStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB1).tearDown();
        inOrder.verify(mA1).tearDown();

        verifyNoMoreImportantMockInteractions();
    }

    @Test
    public void testRuleWorksWithFixtureSuppliers() throws Throwable {
        InOrder inOrder = mocksInOrder();

        StaticMockFixtureRule rule = new StaticMockFixtureRule(mSupplyA, mSupplyB) {
            @Override public StaticMockitoSessionBuilder getSessionBuilder() {
                return mSessionBuilder;
            }
        };
        Statement runMe = rule.apply(mStatement, mDescription);

        inOrder.verify(mSupplyA).get();
        inOrder.verify(mSupplyB).get();
        inOrder.verify(mA1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA1).setUpMockBehaviors();
        inOrder.verify(mB1).setUpMockBehaviors();

        runMe.evaluate();

        inOrder.verify(mStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB1).tearDown();
        inOrder.verify(mA1).tearDown();

        // Round two: use the same suppliers again to retrieve different fixtures: mA2 and mB2
        rule.apply(mStatement, mDescription).evaluate();

        inOrder.verify(mSupplyA).get();
        inOrder.verify(mSupplyB).get();
        inOrder.verify(mA2).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB2).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA2).setUpMockBehaviors();
        inOrder.verify(mB2).setUpMockBehaviors();
        inOrder.verify(mStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB2).tearDown();
        inOrder.verify(mA2).tearDown();

        verifyNoMoreImportantMockInteractions();
    }

    @Test
    public void testTearDownOnSkippedTests() throws Throwable {
        InOrder inOrder = mocksInOrder();

        StaticMockFixtureRule rule = new StaticMockFixtureRule(mA1, mB1) {
            @Override public StaticMockitoSessionBuilder getSessionBuilder() {
                return mSessionBuilder;
            }
        };
        Statement skipStatement = rule.apply(mSkipStatement, mDescription);

        inOrder.verify(mA1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA1).setUpMockBehaviors();
        inOrder.verify(mB1).setUpMockBehaviors();

        try {
            skipStatement.evaluate();
            fail("AssumptionViolatedException should have been thrown");
        } catch (AssumptionViolatedException e) {
            // expected
        }

        inOrder.verify(mSkipStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB1).tearDown();
        inOrder.verify(mA1).tearDown();

        verifyNoMoreImportantMockInteractions();
    }

    @Test
    public void testTearDownOnFailedTests() throws Throwable {
        InOrder inOrder = mocksInOrder();

        StaticMockFixtureRule rule = new StaticMockFixtureRule(mA1, mB1) {
            @Override public StaticMockitoSessionBuilder getSessionBuilder() {
                return mSessionBuilder;
            }
        };
        Statement failStatement = rule.apply(mThrowStatement, mDescription);

        inOrder.verify(mA1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mB1).setUpMockedClasses(any(StaticMockitoSessionBuilder.class));
        inOrder.verify(mA1).setUpMockBehaviors();
        inOrder.verify(mB1).setUpMockBehaviors();

        try {
            failStatement.evaluate();
            fail("IllegalArgumentException should have been thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }

        inOrder.verify(mThrowStatement).evaluate();
        // note: tearDown in reverse order
        inOrder.verify(mB1).tearDown();
        inOrder.verify(mA1).tearDown();

        verifyNoMoreImportantMockInteractions();
    }
}
