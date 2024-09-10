/*
 * Copyright 2024 The Android Open Source Project
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

import android.content.Context;
import android.media.AudioManager;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Barrier to wait for permission updates to propagate to audioserver, to avoid flakiness when using
 * {@code com.android.compatability.common.util.AdoptShellPermissionsRule}. Note, this rule should
 * <b> always </b> be placed after the adopt permission rule. Don't use rule when changing
 * permission state in {@code @Before}, since that executes after all rules.
 */
public class PermissionUpdateBarrierRule implements TestRule {

    private final Context mContext;

    /**
     * @param context the context to use
     */
    public PermissionUpdateBarrierRule(Context context) {
        mContext = context;
    }

    public PermissionUpdateBarrierRule() {
        this(InstrumentationRegistry.getInstrumentation().getContext());
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                mContext.getSystemService(AudioManager.class).permissionUpdateBarrier();
                base.evaluate();
            }
        };
    }
}
