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

import android.annotation.Nullable;
import android.util.Log;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import com.google.common.truth.Expect;
import com.google.common.truth.StandardSubjectBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;

/**
 * Base class to make it easier to write tests that uses {@code ExtendedMockito}.
 *
 */
public abstract class ExtendedMockitoTestCase {

    private static final String TAG = ExtendedMockitoTestCase.class.getSimpleName();

    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * Number of invocations, used to force a failure on {@link #forceFailure(int, Class, String)}.
     */
    private static int sInvocationsCounter;

    /**
     * Sessions follow the "Highlander Rule": There can be only one!
     *
     * <p>So, we keep track of that and force-close it if needed.
     */
    @Nullable
    private static MockitoSession sHighlanderSession;

    /**
     * Points to where the current session was created.
     */
    private static Exception sSessionCreationLocation;

    private MockitoSession mSession;

    protected final Expect mExpect = Expect.create();
    protected final DumpableDumperRule mDumpableDumperRule = new DumpableDumperRule();

    @Rule
    public final RuleChain mTwoRingsOfPowerAndOneChainToRuleThemAll = RuleChain
            .outerRule(mDumpableDumperRule)
            .around(mExpect);

    public ExtendedMockitoTestCase() {
        sInvocationsCounter++;
    }

    @Before
    public final void startSession() {
        if (VERBOSE) {
            Log.v(TAG, "startSession() for " + getTestName() + " on thread "
                    + Thread.currentThread() + "; sHighlanderSession=" + sHighlanderSession);
        }
        createSessionLocation();
        finishHighlanderSessionIfNeeded("startSession()");
        StaticMockitoSessionBuilder builder = mockitoSession()
                .initMocks(this)
                .strictness(getSessionStrictness());
        initializeSession(builder);
        sHighlanderSession = mSession = builder.startMocking();
    }

    private void createSessionLocation() {
        try {
            sSessionCreationLocation = new Exception(getTestName());
        } catch (Exception e) {
            // Better safe than sorry...
            Log.e(TAG, "Could not create sSessionCreationLocation with " + getTestName()
                    + " on thread " + Thread.currentThread(), e);
            sSessionCreationLocation = e;
        }
    }

    /**
     * Gets the session strictness.
     *
     * @return {@link Strictness.LENIENT} by default; subclasses can override.
     */
    protected Strictness getSessionStrictness() {
        return Strictness.LENIENT;
    }

    /**
     * Initializes the mockito session.
     *
     * <p>Typically used to define which classes should have static methods mocked or spied.
     */
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        if (VERBOSE) {
            Log.v(TAG, "initializeSession()");
        }
    }

    @After
    public final void finishSession() throws Exception {
        if (false) { // For obvious reasons, should NEVER be merged as true
            forceFailure(1, RuntimeException.class, "to simulate an unfinished session");
        }

        // mSession.finishMocking() must ALWAYS be called (hence the over-protective try/finally
        // statements), otherwise it would cause failures on future tests as mockito
        // cannot start a session when a previous one is not finished
        try {
            if (VERBOSE) {
                Log.v(TAG, "finishSession() for " + getTestName() + " on thread "
                        + Thread.currentThread() + "; sHighlanderSession=" + sHighlanderSession);

            }
        } finally {
            sHighlanderSession = null;
            finishSessionMocking();
            afterSessionFinished();
        }
    }

    /**
     * Called after the mockito session was finished
     *
     * <p>This method should be used by subclasses that MUST do their cleanup after the session is
     * finished (as methods marked with {@link After} in the subclasses would be called BEFORE
     * that).
     */
    protected void afterSessionFinished() {
        if (VERBOSE) {
            Log.v(TAG, "afterSessionFinished()");
        }
    }

    private void finishSessionMocking() {
        if (mSession == null) {
            Log.w(TAG, getClass().getSimpleName() + ".finishSession(): no session");
            return;
        }
        try {
            mSession.finishMocking();
        } finally {
            // Shouldn't need to set mSession to null as JUnit always instantiate a new object,
            // but it doesn't hurt....
            mSession = null;
            // When using inline mock maker, clean up inline mocks to prevent OutOfMemory
            // errors. See https://github.com/mockito/mockito/issues/1614 and b/259280359.
            Mockito.framework().clearInlineMocks();
        }
    }

    private void finishHighlanderSessionIfNeeded(String where) {
        if (sHighlanderSession == null) {
            if (VERBOSE) {
                Log.v(TAG, "finishHighlanderSessionIfNeeded(): sHighlanderSession already null");
            }
            return;
        }

        if (sSessionCreationLocation != null) {
            if (VERBOSE) {
                Log.e(TAG, where + ": There can be only one! Closing unfinished session, "
                        + "created at", sSessionCreationLocation);
            } else {
                Log.e(TAG, where + ": There can be only one! Closing unfinished session, "
                        + "created at " +  sSessionCreationLocation);
            }
        } else {
            Log.e(TAG, where + ": There can be only one! Closing unfinished session created at "
                    + "unknown location");
        }
        try {
            sHighlanderSession.finishMocking();
        } catch (Throwable t) {
            if (VERBOSE) {
                Log.e(TAG, "Failed to close unfinished session on " + getTestName(), t);
            } else {
                Log.e(TAG, "Failed to close unfinished session on " + getTestName() + ": " + t);
            }
        } finally {
            if (VERBOSE) {
                Log.v(TAG, "Resetting sHighlanderSession at finishHighlanderSessionIfNeeded()");
            }
            sHighlanderSession = null;
        }
    }

    /**
     * Forces a failure at the given invocation of a test method by throwing an exception.
     */
    protected final <T extends Throwable> void forceFailure(int invocationCount,
            Class<T> failureClass, String reason) throws T {
        if (sInvocationsCounter != invocationCount) {
            Log.d(TAG, "forceFailure(" + invocationCount + "): no-op on invocation #"
                    + sInvocationsCounter);
            return;
        }
        String message = "Throwing on invocation #" + sInvocationsCounter + ": " + reason;
        Log.e(TAG, message);
        T throwable;
        try {
            Constructor<T> constructor = failureClass.getConstructor(String.class);
            throwable = constructor.newInstance("Throwing on invocation #" + sInvocationsCounter
                    + ": " + reason);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not create exception of class " + failureClass
                    + " using msg='" + message + "' as constructor");
        }
        throw throwable;
    }

    protected final @Nullable String getTestName() {
        return mDumpableDumperRule.getTestName();
    }

    protected final StandardSubjectBuilder expectWithMessage(String msg) {
        return mExpect.withMessage(msg);
    }

    protected final StandardSubjectBuilder expectWithMessage(String format, Object...args) {
        return mExpect.withMessage(format, args);
    }
}
