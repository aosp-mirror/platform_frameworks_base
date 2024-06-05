/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.app.Instrumentation;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.BaseFragmentTest;
import android.testing.DexmakerShareClassLoaderRule;

import androidx.test.InstrumentationRegistry;

import com.android.systemui.assist.AssistManager;
import com.android.systemui.utils.leaks.LeakCheckedTest;
import com.android.systemui.utils.leaks.LeakCheckedTest.SysuiLeakCheck;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.mockito.Mockito;

import java.util.concurrent.ExecutionException;

public abstract class SysuiBaseFragmentTest extends BaseFragmentTest {

    public static final Class<?>[] ALL_SUPPORTED_CLASSES = LeakCheckedTest.ALL_SUPPORTED_CLASSES;

    @Rule
    public final SysuiLeakCheck mLeakCheck = new SysuiLeakCheck();

    @ClassRule
    public static final SetFlagsRule.ClassRule mSetFlagsClassRule =
            new SetFlagsRule.ClassRule(
                    com.android.systemui.Flags.class);
    @Rule public final SetFlagsRule mSetFlagsRule = mSetFlagsClassRule.createSetFlagsRule();

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    protected TestableDependency mDependency;
    protected SysuiTestableContext mSysuiContext;
    private Instrumentation mRealInstrumentation;

    public SysuiBaseFragmentTest(Class<? extends Fragment> cls) {
        super(cls);
    }

    @Before
    public void sysuiSetup() throws ExecutionException, InterruptedException {
        SystemUIInitializer initializer = new SystemUIInitializerImpl(mContext);
        initializer.init(true);
        mDependency = new TestableDependency(initializer.getSysUIComponent().createDependency());
        Dependency.setInstance(mDependency);

        // TODO: Figure out another way to give reference to a SysuiTestableContext.
        mSysuiContext = (SysuiTestableContext) mContext;

        mRealInstrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation inst = spy(mRealInstrumentation);
        when(inst.getContext()).thenThrow(new RuntimeException(
                "SysUI Tests should use SysuiTestCase#getContext or SysuiTestCase#mContext"));
        when(inst.getTargetContext()).thenThrow(new RuntimeException(
                "SysUI Tests should use SysuiTestCase#getContext or SysuiTestCase#mContext"));
        InstrumentationRegistry.registerInstance(inst, InstrumentationRegistry.getArguments());
        mDependency.injectMockDependency(AssistManager.class);
    }

    @After
    public void SysuiTeardown() {
        InstrumentationRegistry.registerInstance(mRealInstrumentation,
                InstrumentationRegistry.getArguments());
    }

    @AfterClass
    public static void mockitoTeardown() {
        Mockito.framework().clearInlineMocks();
    }

    @Override
    protected SysuiTestableContext getContext() {
        return new SysuiTestableContext(InstrumentationRegistry.getContext(), mLeakCheck);
    }

    public void injectLeakCheckedDependencies(Class<?>... cls) {
        for (Class<?> c : cls) {
            injectLeakCheckedDependency(c);
        }
    }

    public <T> void injectLeakCheckedDependency(Class<T> c) {
        mDependency.injectTestDependency(c, mLeakCheck.getLeakChecker(c));
    }
}
