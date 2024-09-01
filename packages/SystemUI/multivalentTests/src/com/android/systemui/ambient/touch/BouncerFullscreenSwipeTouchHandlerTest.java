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

package com.android.systemui.ambient.touch;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.ambient.touch.scrim.ScrimController;
import com.android.systemui.ambient.touch.scrim.ScrimManager;
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
@EnableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
@DisableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
public class BouncerFullscreenSwipeTouchHandlerTest extends SysuiTestCase {
    private KosmosJavaAdapter mKosmos;

    @Mock
    CentralSurfaces mCentralSurfaces;

    @Mock
    ScrimManager mScrimManager;

    @Mock
    ScrimController mScrimController;

    @Mock
    NotificationShadeWindowController mNotificationShadeWindowController;

    @Mock
    FlingAnimationUtils mFlingAnimationUtils;

    @Mock
    FlingAnimationUtils mFlingAnimationUtilsClosing;

    @Mock
    TouchHandler.TouchSession mTouchSession;

    BouncerSwipeTouchHandler mTouchHandler;

    @Mock
    BouncerSwipeTouchHandler.ValueAnimatorCreator mValueAnimatorCreator;

    @Mock
    ValueAnimator mValueAnimator;

    @Mock
    BouncerSwipeTouchHandler.VelocityTrackerFactory mVelocityTrackerFactory;

    @Mock
    VelocityTracker mVelocityTracker;

    @Mock
    UiEventLogger mUiEventLogger;

    @Mock
    ActivityStarter mActivityStarter;

    @Mock
    CommunalViewModel mCommunalViewModel;

    @Mock
    KeyguardInteractor mKeyguardInteractor;

    private static final float TOUCH_REGION = .3f;
    private static final float MIN_BOUNCER_HEIGHT = .05f;

    private static final Rect SCREEN_BOUNDS = new Rect(0, 0, 1024, 100);
    private static final UserInfo CURRENT_USER_INFO = new UserInfo(
            10,
            /* name= */ "user10",
            /* flags= */ 0
    );

    @Before
    public void setup() {
        mKosmos = new KosmosJavaAdapter(this);
        MockitoAnnotations.initMocks(this);
        mTouchHandler = new BouncerSwipeTouchHandler(
                mKosmos.getTestScope(),
                mScrimManager,
                Optional.of(mCentralSurfaces),
                mNotificationShadeWindowController,
                mValueAnimatorCreator,
                mVelocityTrackerFactory,
                mCommunalViewModel,
                mFlingAnimationUtils,
                mFlingAnimationUtilsClosing,
                TOUCH_REGION,
                MIN_BOUNCER_HEIGHT,
                mUiEventLogger,
                mActivityStarter,
                mKeyguardInteractor);

        when(mScrimManager.getCurrentController()).thenReturn(mScrimController);
        when(mValueAnimatorCreator.create(anyFloat(), anyFloat())).thenReturn(mValueAnimator);
        when(mVelocityTrackerFactory.obtain()).thenReturn(mVelocityTracker);
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn(Float.MAX_VALUE);
        when(mTouchSession.getBounds()).thenReturn(SCREEN_BOUNDS);
        when(mKeyguardInteractor.isKeyguardDismissible()).thenReturn(MutableStateFlow(false));
    }

    /**
     * Ensures expansion does not happen for full vertical swipes when touch is not available.
     */
    @Test
    public void testFullSwipe_notInitiatedWhenNotAvailable() {
        mTouchHandler.onGlanceableTouchAvailable(false);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isFalse();
    }

    /**
     * Ensures expansion only happens for full vertical swipes when touch is available.
     */
    @Test
    public void testFullSwipe_initiatedWhenAvailable() {
        mTouchHandler.onGlanceableTouchAvailable(true);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isTrue();
    }

    @Test
    public void testFullSwipe_motionUpResetsTouchState() {
        mTouchHandler.onGlanceableTouchAvailable(true);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(OnGestureListener.class);
        ArgumentCaptor<InputChannelCompat.InputEventListener> inputListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());
        verify(mTouchSession).registerInputListener(inputListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isTrue();

        MotionEvent upEvent = Mockito.mock(MotionEvent.class);
        when(upEvent.getAction()).thenReturn(MotionEvent.ACTION_UP);
        inputListenerCaptor.getValue().onInputEvent(upEvent);
        verify(mCommunalViewModel).onResetTouchState();
    }

    @Test
    public void testFullSwipe_motionCancelResetsTouchState() {
        mTouchHandler.onGlanceableTouchAvailable(true);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(OnGestureListener.class);
        ArgumentCaptor<InputChannelCompat.InputEventListener> inputListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());
        verify(mTouchSession).registerInputListener(inputListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isTrue();

        MotionEvent upEvent = Mockito.mock(MotionEvent.class);
        when(upEvent.getAction()).thenReturn(MotionEvent.ACTION_CANCEL);
        inputListenerCaptor.getValue().onInputEvent(upEvent);
        verify(mCommunalViewModel).onResetTouchState();
    }
}
