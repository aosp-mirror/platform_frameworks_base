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

package com.android.media.mediatestutils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;


@RunWith(JUnit4.class)
public class CancelAllFuturesRuleTest {

    public static class TestException extends Throwable { }

    public static class CheckFutureStatusRule implements TestRule {
        private final List<CompletableFuture> mFutures = Arrays.asList(new CompletableFuture<>(),
                new CompletableFuture<>());

        private boolean mCompleted;

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    try {
                        base.evaluate();
                    } finally {
                        // Intentionally suppresses original exception
                        if (mCompleted) {
                            assertThat(mFutures.get(0).isDone())
                                .isTrue();
                            assertThat(mFutures.get(0).isCancelled())
                                .isFalse();
                        } else {
                            assertThat(mFutures.get(0).isCancelled())
                                .isTrue();
                        }
                        assertThat(mFutures.get(1).isCancelled())
                            .isTrue();
                    }
                }
            };
        }

        Future getFuture(int idx) {
            return mFutures.get(idx);
        }

        void completeFirstFuture(boolean exceptionally) {
            assertThat(mFutures.get(0).complete(null))
                .isTrue();
            mCompleted = true;
        }
    }

    @Rule(order = 0)
    public ExpectedException mExpectedThrownRule = ExpectedException.none();

    @Rule(order = 1)
    public CheckFutureStatusRule mRuleVerifyerRule = new CheckFutureStatusRule();

    @Rule(order = 2)
    public CancelAllFuturesRule mCancelRule = new CancelAllFuturesRule();

    @Test
    public void testRuleCancelsFutures_whenFinishesNormally() {
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(0));
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(1));
        // return normally
    }

    @Test
    public void testRuleCancelsFutures_whenFinishesExceptionally() throws TestException {
        mExpectedThrownRule.expect(TestException.class);
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(0));
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(1));
        throw new TestException();
    }

    @Test
    public void testRuleDoesNotThrow_whenCompletesNormally() {
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(0));
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(1));
        mRuleVerifyerRule.completeFirstFuture(false);
    }

    @Test
    public void testRuleDoesNotThrow_whenCompletesExceptionally() {
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(0));
        mCancelRule.registerFuture(mRuleVerifyerRule.getFuture(1));
        mRuleVerifyerRule.completeFirstFuture(false);
    }
}
