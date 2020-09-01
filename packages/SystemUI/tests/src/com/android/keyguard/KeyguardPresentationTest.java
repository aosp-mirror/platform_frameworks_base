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

package com.android.keyguard;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardDisplayManager.KeyguardPresentation;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.InjectionInflationController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardPresentationTest extends SysuiTestCase {

    @Mock
    KeyguardClockSwitch mMockKeyguardClockSwitch;
    @Mock
    KeyguardSliceView mMockKeyguardSliceView;
    @Mock
    KeyguardStatusView mMockKeyguardStatusView;

    LayoutInflater mLayoutInflater;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(KeyguardUpdateMonitor.class);
        when(mMockKeyguardClockSwitch.getContext()).thenReturn(mContext);
        when(mMockKeyguardSliceView.getContext()).thenReturn(mContext);
        when(mMockKeyguardStatusView.getContext()).thenReturn(mContext);
        when(mMockKeyguardStatusView.findViewById(R.id.clock)).thenReturn(mMockKeyguardStatusView);
        allowTestableLooperAsMainThread();

        InjectionInflationController inflationController = new InjectionInflationController(
                SystemUIFactory.getInstance().getRootComponent());
        mLayoutInflater = inflationController.injectable(LayoutInflater.from(mContext));
        mLayoutInflater.setPrivateFactory(new LayoutInflater.Factory2() {

            @Override
            public View onCreateView(View parent, String name, Context context,
                    AttributeSet attrs) {
                return onCreateView(name, context, attrs);
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                if ("com.android.keyguard.KeyguardStatusView".equals(name)) {
                    return mMockKeyguardStatusView;
                } else if ("com.android.keyguard.KeyguardClockSwitch".equals(name)) {
                    return mMockKeyguardClockSwitch;
                } else if ("com.android.keyguard.KeyguardSliceView".equals(name)) {
                    return mMockKeyguardStatusView;
                }
                return null;
            }
        });
    }

    @After
    public void tearDown() {
        disallowTestableLooperAsMainThread();
    }

    @Test
    public void testInflation_doesntCrash() {
        KeyguardPresentation keyguardPresentation = new KeyguardPresentation(mContext,
                mContext.getDisplayNoVerify(), mLayoutInflater);
        keyguardPresentation.onCreate(null /*savedInstanceState */);
    }
}
