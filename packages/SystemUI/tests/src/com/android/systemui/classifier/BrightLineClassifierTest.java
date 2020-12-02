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

import static com.android.systemui.util.mockito.KotlinMockitoHelpersKt.any;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManagerFake;

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
public class BrightLineClassifierTest extends SysuiTestCase {
    private BrightLineFalsingManager mBrightLineFalsingManager;
    @Mock
    private FalsingDataProvider mFalsingDataProvider;
    private final DockManagerFake mDockManager = new DockManagerFake();
    private final MetricsLogger mMetricsLogger = new FakeMetricsLogger();
    private final Set<FalsingClassifier> mClassifiers = new HashSet<>();
    @Mock
    private SingleTapClassifier mSingleTapClassfier;
    @Mock
    private DoubleTapClassifier mDoubleTapClassifier;
    @Mock
    private FalsingClassifier mClassifierA;
    @Mock
    private FalsingClassifier mClassifierB;
    private final List<MotionEvent> mMotionEventList = new ArrayList<>();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mClassifiers.add(mClassifierA);
        mClassifiers.add(mClassifierB);
        when(mFalsingDataProvider.isDirty()).thenReturn(true);
        when(mFalsingDataProvider.getRecentMotionEvents()).thenReturn(mMotionEventList);
        mBrightLineFalsingManager = new BrightLineFalsingManager(mFalsingDataProvider, mDockManager,
                mMetricsLogger, mClassifiers, mSingleTapClassfier, mDoubleTapClassifier, false);
    }

    @Test
    public void testRegisterSessionListener() {
        verify(mFalsingDataProvider).addSessionListener(
                any(FalsingDataProvider.SessionListener.class));

        mBrightLineFalsingManager.cleanup();
        verify(mFalsingDataProvider).removeSessionListener(
                any(FalsingDataProvider.SessionListener.class));
    }

    @Test
    public void testIsFalseTouch_NoClassifiers() {
        mClassifiers.clear();

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_ClassffiersPass() {
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_ClassifierARejects() {
        when(mClassifierA.isFalseTouch()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isTrue();
    }

    @Test
    public void testIsFalseTouch_ClassifierBRejects() {
        when(mClassifierB.isFalseTouch()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isTrue();
    }

    @Test
    public void testIsFalseTouch_FaceAuth() {
        // Even when the classifiers report a false, we should allow.
        when(mClassifierA.isFalseTouch()).thenReturn(true);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(true);

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_Docked() {
        // Even when the classifiers report a false, we should allow.
        when(mClassifierA.isFalseTouch()).thenReturn(true);
        mDockManager.setIsDocked(true);

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTap_BasicCheck() {
        when(mSingleTapClassfier.isTap(mMotionEventList)).thenReturn(false);

        assertThat(mBrightLineFalsingManager.isFalseTap(false)).isTrue();

        when(mSingleTapClassfier.isTap(mMotionEventList)).thenReturn(true);

        assertThat(mBrightLineFalsingManager.isFalseTap(false)).isFalse();
    }

    @Test
    public void testIsFalseTap_RobustCheck_NoFaceAuth() {
        when(mSingleTapClassfier.isTap(mMotionEventList)).thenReturn(true);
        mFalsingDataProvider.setJustUnlockedWithFace(false);
        assertThat(mBrightLineFalsingManager.isFalseTap(true)).isTrue();
    }

    @Test
    public void testIsFalseTap_RobustCheck_FaceAuth() {
        when(mSingleTapClassfier.isTap(mMotionEventList)).thenReturn(true);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTap(true)).isFalse();
    }

    @Test
    public void testIsFalseDoubleTap() {
        when(mDoubleTapClassifier.isFalseTouch()).thenReturn(false);

        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isFalse();

        when(mDoubleTapClassifier.isFalseTouch()).thenReturn(true);

        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isTrue();
    }
}
