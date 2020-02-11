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

package android.app.compat;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.compat.testing.PlatformCompatChangeRule;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * {@link CompatChanges} tests.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class CompatChangesTest {
    static final long CHANGE_ID = 1L;

    private Instrumentation mInstrumentation;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }


    private String getPackageName() {
        return mInstrumentation.getTargetContext().getPackageName();
    }

    @Test
    @EnableCompatChanges(CHANGE_ID)
    public void testEnabledChange() {
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID)).isTrue();
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID, Process.myUid())).isTrue();
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID, getPackageName(),
                UserHandle.of(UserHandle.myUserId()))).isTrue();
    }

    @Test
    @DisableCompatChanges(CHANGE_ID)
    public void testDisabledChange() {
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID)).isFalse();
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID, Process.myUid())).isFalse();
        assertThat(CompatChanges.isChangeEnabled(CHANGE_ID, getPackageName(),
                UserHandle.of(UserHandle.myUserId()))).isFalse();
    }
}
