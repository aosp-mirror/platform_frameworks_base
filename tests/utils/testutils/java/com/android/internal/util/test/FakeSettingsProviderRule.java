/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.util.test;

import android.content.Context;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit Rule helps keeping test {@link FakeSettingsProvider} clean.
 *
 * <p>It clears {@link FakeSettingsProvider} before and after each test. Example use:
 * <pre class="code"><code class="java">
 * public class ExampleTest {
 *
 *     &#064;Rule public FakeSettingsProviderRule rule = FakeSettingsProvider.rule();
 *
 *     &#064;Test
 *     public void shouldDoSomething() {
 *         ContextResolver cr = rule.mockContentResolver(mContext);
 *         Settings.Global.putInt(cr, "my_setting_name", 1);
 *         // Test code relying on my_setting_name value using cr
 *     }
 * }
 * </code></pre>
 *
 * @see FakeSettingsProvider
 */
public final class FakeSettingsProviderRule implements TestRule {

    /** Prevent initialization outside {@link FakeSettingsProvider}. */
    FakeSettingsProviderRule() {
    }

    /**
     * Creates a {@link MockContentResolver} that uses the {@link FakeSettingsProvider} as the
     * {@link Settings#AUTHORITY} provider.
     */
    public MockContentResolver mockContentResolver(Context context) {
        MockContentResolver contentResolver = new MockContentResolver(context);
        contentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        return contentResolver;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            public void evaluate() throws Throwable {
                FakeSettingsProvider.clearSettingsProvider();
                try {
                    base.evaluate();
                } finally {
                    FakeSettingsProvider.clearSettingsProvider();
                }
            }
        };
    }
}
