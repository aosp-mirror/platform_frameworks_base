/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.statusbar.commandline.Command;
import com.android.systemui.statusbar.commandline.CommandRegistry;

import kotlin.jvm.functions.Function0;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ForceLowLightConditionTest extends SysuiTestCase {
    @Mock
    private CommandRegistry mCommandRegistry;

    @Mock
    private Condition.Callback mCallback;

    @Mock
    private PrintWriter mPrintWriter;

    @Mock
    CoroutineScope mScope;

    private ForceLowLightCondition mCondition;
    private Command mCommand;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCondition = new ForceLowLightCondition(mScope, mCommandRegistry);
        mCondition.addCallback(mCallback);
        ArgumentCaptor<Function0<Command>> commandCaptor =
                ArgumentCaptor.forClass(Function0.class);
        verify(mCommandRegistry).registerCommand(eq(ForceLowLightCondition.COMMAND_ROOT),
                commandCaptor.capture());
        mCommand = commandCaptor.getValue().invoke();
    }

    @Test
    public void testEnableLowLight() {
        mCommand.execute(mPrintWriter,
                Arrays.asList(ForceLowLightCondition.COMMAND_ENABLE_LOW_LIGHT));
        verify(mCallback).onConditionChanged(mCondition);
        assertThat(mCondition.isConditionSet()).isTrue();
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void testDisableLowLight() {
        mCommand.execute(mPrintWriter,
                Arrays.asList(ForceLowLightCondition.COMMAND_DISABLE_LOW_LIGHT));
        verify(mCallback).onConditionChanged(mCondition);
        assertThat(mCondition.isConditionSet()).isTrue();
        assertThat(mCondition.isConditionMet()).isFalse();
    }

    @Test
    public void testClearEnableLowLight() {
        mCommand.execute(mPrintWriter,
                Arrays.asList(ForceLowLightCondition.COMMAND_ENABLE_LOW_LIGHT));
        verify(mCallback).onConditionChanged(mCondition);
        assertThat(mCondition.isConditionSet()).isTrue();
        assertThat(mCondition.isConditionMet()).isTrue();
        Mockito.clearInvocations(mCallback);
        mCommand.execute(mPrintWriter,
                Arrays.asList(ForceLowLightCondition.COMMAND_CLEAR_LOW_LIGHT));
        verify(mCallback).onConditionChanged(mCondition);
        assertThat(mCondition.isConditionSet()).isFalse();
        assertThat(mCondition.isConditionMet()).isFalse();
    }
}
