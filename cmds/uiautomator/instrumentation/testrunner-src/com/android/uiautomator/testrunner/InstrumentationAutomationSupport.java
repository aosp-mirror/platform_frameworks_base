/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.uiautomator.testrunner;

import android.app.Instrumentation;
import android.os.Bundle;

/**
 * A wrapper around {@link Instrumentation} to provide sendStatus function
 *
 * Provided for backwards compatibility purpose. New code should use
 * {@link Instrumentation#sendStatus(int, Bundle)} instead.
 *
 */
class InstrumentationAutomationSupport implements IAutomationSupport {

    private Instrumentation mInstrumentation;

    InstrumentationAutomationSupport(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }

    @Override
    public void sendStatus(int resultCode, Bundle status) {
        mInstrumentation.sendStatus(resultCode, status);
    }
}
