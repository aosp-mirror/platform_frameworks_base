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

package com.android.systemui.classifier;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BrightLineFalsingManagerTest extends SysuiTestCase {
    private BrightLineFalsingManager mBrightLineFalsingManager;
    @Mock
    private FalsingDataProvider mFalsingDataProvider;
    private final MetricsLogger mMetricsLogger = new FakeMetricsLogger();
    private final Set<FalsingClassifier> mClassifiers = new HashSet<>();
    @Mock
    private SingleTapClassifier mSingleTapClassifier;
    @Mock
    private DoubleTapClassifier mDoubleTapClassifier;
    @Mock
    private FalsingClassifier mClassifierA;
    private final List<MotionEvent> mMotionEventList = new ArrayList<>();
    @Mock
    private HistoryTracker mHistoryTracker;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private AccessibilityManager mAccessibilityManager;

    private final FalsingClassifier.Result mPassedResult = FalsingClassifier.Result.passed(1);
    private final FalsingClassifier.Result mFalsedResult =
            FalsingClassifier.Result.falsed(1, getClass().getSimpleName(), "");

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        when(mSingleTapClassifier.isTap(any(List.class), anyDouble())).thenReturn(mFalsedResult);
        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        mClassifiers.add(mClassifierA);
        when(mFalsingDataProvider.getRecentMotionEvents()).thenReturn(mMotionEventList);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mBrightLineFalsingManager = new BrightLineFalsingManager(mFalsingDataProvider,
                mMetricsLogger, mClassifiers, mSingleTapClassifier, mDoubleTapClassifier,
                mHistoryTracker, mKeyguardStateController, mAccessibilityManager,
                false);
    }

    @Test
    public void testA11yDisablesGesture() {
        assertThat(mBrightLineFalsingManager.isFalseTap(1)).isTrue();
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTap(1)).isFalse();
    }


    @Test
    public void testA11yDisablesTap() {
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.GENERIC)).isTrue();
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.GENERIC)).isFalse();
    }


    @Test
    public void testA11yDisablesDoubleTap() {
        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isTrue();
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isFalse();
    }

    @Test
    public void testIsProxNear_noProxEvents_defaultsToFalse() {
        assertThat(mBrightLineFalsingManager.isProximityNear()).isFalse();
    }

    @Test
    public void testIsProxNear_receivesNearEvent() {
        mBrightLineFalsingManager.onProximityEvent(new FalsingManager.ProximityEvent() {
            @Override
            public boolean getCovered() {
                return true;
            }

            @Override
            public long getTimestampNs() {
                return 0;
            }
        });
        assertThat(mBrightLineFalsingManager.isProximityNear()).isTrue();
    }

    @Test
    public void testIsProxNear_receivesNearAndThenFarEvent() {
        mBrightLineFalsingManager.onProximityEvent(new FalsingManager.ProximityEvent() {
            @Override
            public boolean getCovered() {
                return true;
            }

            @Override
            public long getTimestampNs() {
                return 0;
            }
        });
        mBrightLineFalsingManager.onProximityEvent(new FalsingManager.ProximityEvent() {
            @Override
            public boolean getCovered() {
                return false;
            }

            @Override
            public long getTimestampNs() {
                return 5;
            }
        });
        assertThat(mBrightLineFalsingManager.isProximityNear()).isFalse();
    }
}
