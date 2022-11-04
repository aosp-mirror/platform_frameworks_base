/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import android.util.Log;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import com.google.common.truth.Expect;
import com.google.common.truth.StandardSubjectBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Base class to make it easier to write tests that uses {@code ExtendedMockito}.
 *
 */
public abstract class ExtendedMockitoTestCase {

    private static final String TAG = ExtendedMockitoTestCase.class.getSimpleName();

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private MockitoSession mSession;

    private final Expect mExpect = Expect.create();
    protected final DumpableDumperRule mDumpableDumperRule = new DumpableDumperRule();

    @Rule
    public final RuleChain mTwoRingsOfPowerAndOneChainToRuleThemAll = RuleChain
            .outerRule(mDumpableDumperRule)
            .around(mExpect);

    @Before
    public void startSession() {
        if (DEBUG) {
            Log.d(TAG, "startSession()");
        }
        StaticMockitoSessionBuilder builder = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT);
        initializeSession(builder);
        mSession = builder.startMocking();
    }

    /**
     * Initializes the mockito session.
     *
     * <p>Typically used to define which classes should have static methods mocked or spied.
     */
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        if (DEBUG) {
            Log.d(TAG, "initializeSession()");
        }
    }

    @After
    public final void finishSession() {
        if (mSession == null) {
            Log.w(TAG, "finishSession(): no session");
            return;
        }
        try {
            if (DEBUG) {
                Log.d(TAG, "finishSession()");
            }
        } finally {
            // mSession.finishMocking() must ALWAYS be called (hence the over-protective try/finally
            // statements), otherwise it would cause failures on future tests as mockito
            // cannot start a session when a previous one is not finished
            mSession.finishMocking();
        }
    }

    protected final StandardSubjectBuilder expectWithMessage(String msg) {
        return mExpect.withMessage(msg);
    }

    protected final StandardSubjectBuilder expectWithMessage(String format, Object...args) {
        return mExpect.withMessage(format, args);
    }
}
