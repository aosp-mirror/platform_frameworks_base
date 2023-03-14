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

import static com.android.systemui.plugins.FalsingManager.HIGH_PENALTY;
import static com.android.systemui.plugins.FalsingManager.NO_PENALTY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingDataProvider.GestureFinalizedListener;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
    private final MetricsLogger mMetricsLogger = new FakeMetricsLogger();
    private final Set<FalsingClassifier> mClassifiers = new HashSet<>();
    @Mock
    private SingleTapClassifier mSingleTapClassfier;
    @Mock
    private LongTapClassifier mLongTapClassifier;
    @Mock
    private DoubleTapClassifier mDoubleTapClassifier;
    @Mock
    private FalsingClassifier mClassifierA;
    @Mock
    private FalsingClassifier mClassifierB;
    private final List<MotionEvent> mMotionEventList = new ArrayList<>();
    @Mock
    private HistoryTracker mHistoryTracker;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private AccessibilityManager mAccessibilityManager;

    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private final FakeFeatureFlags mFakeFeatureFlags = new FakeFeatureFlags();

    private final FalsingClassifier.Result mFalsedResult =
            FalsingClassifier.Result.falsed(1, getClass().getSimpleName(), "");
    private final FalsingClassifier.Result mPassedResult = FalsingClassifier.Result.passed(1);
    private GestureFinalizedListener mGestureFinalizedListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);
        when(mClassifierB.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);
        when(mSingleTapClassfier.isTap(any(List.class), anyDouble())).thenReturn(mPassedResult);
        when(mLongTapClassifier.isTap(any(List.class), anyDouble())).thenReturn(mFalsedResult);
        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);
        mClassifiers.add(mClassifierA);
        mClassifiers.add(mClassifierB);
        when(mFalsingDataProvider.getRecentMotionEvents()).thenReturn(mMotionEventList);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mBrightLineFalsingManager = new BrightLineFalsingManager(mFalsingDataProvider,
                mMetricsLogger, mClassifiers, mSingleTapClassfier, mLongTapClassifier,
                mDoubleTapClassifier, mHistoryTracker, mKeyguardStateController,
                mAccessibilityManager, false, mFakeFeatureFlags);


        ArgumentCaptor<GestureFinalizedListener> gestureCompleteListenerCaptor =
                ArgumentCaptor.forClass(GestureFinalizedListener.class);

        verify(mFalsingDataProvider).addGestureCompleteListener(
                gestureCompleteListenerCaptor.capture());

        mGestureFinalizedListener = gestureCompleteListenerCaptor.getValue();
        mFakeFeatureFlags.set(Flags.FALSING_FOR_LONG_TAPS, true);
        mFakeFeatureFlags.set(Flags.MEDIA_FALSING_PENALTY, true);
    }

    @Test
    public void testRegisterSessionListener() {
        verify(mFalsingDataProvider).addSessionListener(
                any(FalsingDataProvider.SessionListener.class));

        mBrightLineFalsingManager.cleanupInternal();
        verify(mFalsingDataProvider).removeSessionListener(
                any(FalsingDataProvider.SessionListener.class));
    }

    @Test
    public void testIsFalseTouch_NoClassifiers() {
        mClassifiers.clear();

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_ClassifiersPass() {
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_ClassifierARejects() {
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isTrue();
    }

    @Test
    public void testIsFalseTouch_SeekBar_FalseTouch() {
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        when(mSingleTapClassfier.isTap(any(List.class), anyDouble())).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.MEDIA_SEEKBAR)).isTrue();
    }

    @Test
    public void testIsFalseTouch_SeekBar_RealTouch() {
        when(mSingleTapClassfier.isTap(any(List.class), anyDouble())).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.MEDIA_SEEKBAR)).isFalse();
    }

    @Test
    public void testIsFalseTouch_SeekBar_FalseTap() {
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        when(mSingleTapClassfier.isTap(any(List.class), anyDouble())).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.MEDIA_SEEKBAR)).isTrue();
    }

    @Test
    public void testIsFalseTouch_SeekBar_RealTap() {
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(Classifier.MEDIA_SEEKBAR)).isFalse();
    }

    @Test
    public void testIsFalseTouch_ClassifierBRejects() {
        when(mClassifierB.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isTrue();
    }

    @Test
    public void testIsFalseTouch_FaceAuth() {
        // Even when the classifiers report a false, we should allow.
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(true);

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTouch_Docked() {
        // Even when the classifiers report a false, we should allow.
        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);
        when(mFalsingDataProvider.isDocked()).thenReturn(true);

        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();
    }

    @Test
    public void testIsFalseTap_BasicCheck() {
        when(mSingleTapClassfier.isTap(mMotionEventList, 0)).thenReturn(mFalsedResult);

        assertThat(mBrightLineFalsingManager.isSimpleTap()).isFalse();

        when(mSingleTapClassfier.isTap(mMotionEventList, 0)).thenReturn(mPassedResult);

        assertThat(mBrightLineFalsingManager.isSimpleTap()).isTrue();
    }

    @Test
    public void testIsFalseSingleTap_EmptyRecentEvents() {
        // Ensure we look at prior events if recent events has already been emptied.
        when(mFalsingDataProvider.getRecentMotionEvents()).thenReturn(new ArrayList<>());
        when(mFalsingDataProvider.getPriorMotionEvents()).thenReturn(mMotionEventList);

        mBrightLineFalsingManager.isFalseTap(0);
        verify(mSingleTapClassfier).isTap(mMotionEventList, 0);
    }


    @Test
    public void testIsFalseSingleTap_RobustCheck_NoFaceAuth() {
        when(mSingleTapClassfier.isTap(mMotionEventList, 0)).thenReturn(mPassedResult);
        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        when(mHistoryTracker.falseBelief()).thenReturn(1.0);
        mFalsingDataProvider.setJustUnlockedWithFace(false);
        assertThat(mBrightLineFalsingManager.isFalseTap(NO_PENALTY)).isTrue();
    }

    @Test
    public void testIsFalseSingleTap_RobustCheck_FaceAuth() {
        when(mSingleTapClassfier.isTap(mMotionEventList, 0)).thenReturn(mPassedResult);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseTap(NO_PENALTY)).isFalse();
    }

    @Test
    public void testIsFalseLongTap_EmptyRecentEvents() {
        // Ensure we look at prior events if recent events has already been emptied.
        when(mFalsingDataProvider.getRecentMotionEvents()).thenReturn(new ArrayList<>());
        when(mFalsingDataProvider.getPriorMotionEvents()).thenReturn(mMotionEventList);

        mBrightLineFalsingManager.isFalseLongTap(0);
        verify(mLongTapClassifier).isTap(mMotionEventList, 0);
    }

    @Test
    public void testIsFalseLongTap_FalseLongTap_NotFlagged() {
        mFakeFeatureFlags.set(Flags.FALSING_FOR_LONG_TAPS, false);
        when(mLongTapClassifier.isTap(mMotionEventList, 0)).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseLongTap(NO_PENALTY)).isFalse();
    }

    @Test
    public void testIsFalseLongTap_FalseLongTap() {
        when(mLongTapClassifier.isTap(mMotionEventList, 0)).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseLongTap(NO_PENALTY)).isTrue();
    }

    @Test
    public void testIsFalseLongTap_RobustCheck_NoFaceAuth() {
        when(mLongTapClassifier.isTap(mMotionEventList, 0)).thenReturn(mPassedResult);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(false);
        assertThat(mBrightLineFalsingManager.isFalseLongTap(NO_PENALTY)).isFalse();
    }

    @Test
    public void testIsFalseLongTap_RobustCheck_FaceAuth() {
        when(mLongTapClassifier.isTap(mMotionEventList, 0)).thenReturn(mPassedResult);
        when(mFalsingDataProvider.isJustUnlockedWithFace()).thenReturn(true);
        assertThat(mBrightLineFalsingManager.isFalseLongTap(NO_PENALTY)).isFalse();
    }

    @Test
    public void testIsFalseDoubleTap() {
        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mPassedResult);

        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isFalse();

        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);

        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isTrue();
    }

    @Test
    public void testHistory() {
        mGestureFinalizedListener.onGestureFinalized(1000);

        verify(mHistoryTracker).addResults(anyCollection(), eq(1000L));
    }

    @Test
    public void testHistory_singleTap() {
        // When trying to classify single taps, we don't immediately add results to history.
        mBrightLineFalsingManager.isFalseTap(HIGH_PENALTY);
        mGestureFinalizedListener.onGestureFinalized(1000);
        verify(mHistoryTracker).addResults(anyCollection(), eq(1000L));
    }

    @Test
    public void testHistory_multipleSingleTaps() {
        // When trying to classify single taps, we don't immediately add results to history.
        mBrightLineFalsingManager.isFalseTap(HIGH_PENALTY);
        mGestureFinalizedListener.onGestureFinalized(1000);
        mBrightLineFalsingManager.isFalseTap(HIGH_PENALTY);
        mGestureFinalizedListener.onGestureFinalized(2000);
        verify(mHistoryTracker).addResults(anyCollection(), eq(1000L));
        verify(mHistoryTracker).addResults(anyCollection(), eq(2000L));
    }

    @Test
    public void testHistory_doubleTap() {
        // When trying to classify single taps, we don't immediately add results to history.
        mBrightLineFalsingManager.isFalseTap(HIGH_PENALTY);
        mGestureFinalizedListener.onGestureFinalized(1000);
        // Before checking for double tap, we may check for single-tap on the second gesture.
        mBrightLineFalsingManager.isFalseTap(HIGH_PENALTY);
        mBrightLineFalsingManager.isFalseDoubleTap();
        mGestureFinalizedListener.onGestureFinalized(2000);

        // Double tap is immediately added to history. Single tap is never added.
        verify(mHistoryTracker).addResults(anyCollection(), eq(2000L));

        assertThat(mFakeExecutor.numPending()).isEqualTo(0);
    }

    @Test
    public void testNoFalsingUnlocked() {
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        when(mClassifierA.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseTouch(0)).isFalse();

        when(mSingleTapClassfier.isTap(mMotionEventList, 0)).thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isSimpleTap()).isFalse();

        when(mDoubleTapClassifier.classifyGesture(anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mFalsedResult);
        assertThat(mBrightLineFalsingManager.isFalseDoubleTap()).isFalse();
    }
}
