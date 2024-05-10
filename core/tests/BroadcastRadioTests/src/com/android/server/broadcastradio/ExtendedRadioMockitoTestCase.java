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
package com.android.server.broadcastradio;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import android.util.Log;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Base class to make it easier to write tests that uses {@code ExtendedMockito} for radio.
 *
 */
public abstract class ExtendedRadioMockitoTestCase {

    private static final String TAG = "RadioMockitoTestCase";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private MockitoSession mSession;

    @Before
    public void startSession() {
        StaticMockitoSessionBuilder builder = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT);
        initializeSession(builder);
        mSession = builder.startMocking();
    }

    /**
     * Initializes the mockito session for radio test.
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
}
