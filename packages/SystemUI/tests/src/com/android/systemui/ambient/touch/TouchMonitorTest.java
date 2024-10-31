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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.GestureDetector;
import android.view.IWindowManager;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleCoroutineScope;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.ambient.touch.dagger.InputSessionComponent;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.log.LogBufferHelperKt;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.display.DisplayHelper;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class TouchMonitorTest extends SysuiTestCase {
    private KosmosJavaAdapter mKosmos;
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mKosmos = new KosmosJavaAdapter(this);
    }

    private static class SimpleLifecycleOwner implements LifecycleOwner {
        LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mLifecycle;
        }

        public void setState(Lifecycle.State state) {
            mLifecycle.setCurrentState(state);
        }
    }

    private static class Environment {
        private final InputSessionComponent.Factory mInputFactory;
        private final InputSession mInputSession;
        private final SimpleLifecycleOwner mLifecycleOwner;

        private final LifecycleRegistry mLifecycleRegistry;
        private final TouchMonitor mMonitor;
        private final InputChannelCompat.InputEventListener mEventListener;
        private final GestureDetector.OnGestureListener mGestureListener;
        private final DisplayHelper mDisplayHelper;
        private final DisplayManager mDisplayManager;
        private final WindowManager mWindowManager;
        private final WindowMetrics mWindowMetrics;
        private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
        private final FakeExecutor mBackgroundExecutor = new FakeExecutor(new FakeSystemClock());

        private final Rect mDisplayBounds = Mockito.mock(Rect.class);
        private final IWindowManager mIWindowManager;

        private final KosmosJavaAdapter mKosmos;

        private ArrayList<LifecycleObserver> mLifecycleObservers = new ArrayList<>();


        Environment(Set<TouchHandler> handlers, KosmosJavaAdapter kosmos) {
            mLifecycleOwner = new SimpleLifecycleOwner();
            mLifecycleRegistry = spy(new LifecycleRegistry(mLifecycleOwner));

            mIWindowManager = Mockito.mock(IWindowManager.class);
            mDisplayManager = Mockito.mock(DisplayManager.class);
            mWindowManager = Mockito.mock(WindowManager.class);
            mKosmos = kosmos;

            mInputFactory = Mockito.mock(InputSessionComponent.Factory.class);
            final InputSessionComponent inputComponent = Mockito.mock(InputSessionComponent.class);
            mInputSession = Mockito.mock(InputSession.class);

            when(mInputFactory.create(any(), any(), any(), anyBoolean()))
                    .thenReturn(inputComponent);
            when(inputComponent.getInputSession()).thenReturn(mInputSession);

            mDisplayHelper = Mockito.mock(DisplayHelper.class);
            when(mDisplayHelper.getMaxBounds(anyInt(), anyInt()))
                    .thenReturn(mDisplayBounds);

            mWindowMetrics = Mockito.mock(WindowMetrics.class);
            when(mWindowMetrics.getBounds()).thenReturn(mDisplayBounds);
            when(mWindowManager.getMaximumWindowMetrics()).thenReturn(mWindowMetrics);
            mMonitor = new TouchMonitor(mExecutor, mBackgroundExecutor, mLifecycleRegistry,
                    mInputFactory, mDisplayHelper, mKosmos.getConfigurationInteractor(),
                    handlers, mIWindowManager, 0, "TouchMonitorTest",
                    LogBufferHelperKt.logcatLogBuffer("TouchMonitorTest"));
            clearInvocations(mLifecycleRegistry);
            mMonitor.init();

            ArgumentCaptor<LifecycleObserver> observerCaptor =
                    ArgumentCaptor.forClass(LifecycleObserver.class);
            verify(mLifecycleRegistry, atLeast(1)).addObserver(observerCaptor.capture());
            mLifecycleObservers.addAll(observerCaptor.getAllValues().stream().filter(
                    lifecycleObserver -> !(lifecycleObserver instanceof LifecycleCoroutineScope))
                    .toList());

            updateLifecycle(Lifecycle.State.RESUMED);

            // Capture creation request.
            final ArgumentCaptor<InputChannelCompat.InputEventListener> inputEventListenerCaptor =
                    ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
            final ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                    ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
            verify(mInputFactory).create(any(), inputEventListenerCaptor.capture(),
                    gestureListenerCaptor.capture(),
                    eq(true));
            mEventListener = inputEventListenerCaptor.getValue();
            mGestureListener = gestureListenerCaptor.getValue();
        }

        public Rect getDisplayBounds() {
            return mDisplayBounds;
        }

        void executeAll() {
            mExecutor.runAllReady();
        }

        void publishInputEvent(InputEvent event) {
            mEventListener.onInputEvent(event);
        }

        void publishGestureEvent(Consumer<GestureDetector.OnGestureListener> listenerConsumer) {
            listenerConsumer.accept(mGestureListener);
        }

        void updateLifecycle(Lifecycle.State state) {
            mLifecycleRegistry.setCurrentState(state);
        }

        void verifyInputSessionDispose() {
            verify(mInputSession).dispose();
            Mockito.clearInvocations(mInputSession);
        }

        void destroyMonitor() {
            mMonitor.destroy();
        }

        void verifyLifecycleObserversUnregistered() {
            for (LifecycleObserver observer : mLifecycleObservers) {
                verify(mLifecycleRegistry).removeObserver(observer);
            }
        }
    }

    @Test
    public void testSessionResetOnLifecycle() {
        final TouchHandler touchHandler = createTouchHandler();
        final Rect touchArea = new Rect(4, 4, 8 , 8);

        doAnswer(invocation -> {
            final Region region = (Region) invocation.getArguments()[1];
            region.set(touchArea);
            return null;
        }).when(touchHandler).getTouchInitiationRegion(any(), any(), any());

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        // Ensure touch outside specified region is not delivered.
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);

        // Make sure touch inside region causes session start.
        when(initialEvent.getX()).thenReturn(5.0f);
        when(initialEvent.getY()).thenReturn(5.0f);
        environment.publishInputEvent(initialEvent);
        verify(touchHandler).onSessionStart(any());

        Mockito.clearInvocations(touchHandler);

        // Reset lifecycle, forcing monitoring to be reset
        environment.updateLifecycle(Lifecycle.State.STARTED);
        environment.updateLifecycle(Lifecycle.State.RESUMED);
        environment.executeAll();

        environment.publishInputEvent(initialEvent);
        verify(touchHandler).onSessionStart(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_AMBIENT_TOUCH_MONITOR_LISTEN_TO_DISPLAY_CHANGES)
    public void testConfigurationListenerUpdatesBounds() {
        final TouchHandler touchHandler = createTouchHandler();
        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);
        ArgumentCaptor<DisplayManager.DisplayListener> listenerCaptor =
                ArgumentCaptor.forClass(DisplayManager.DisplayListener.class);
        final Rect testRect = new Rect(0, 0, 2, 2);
        final Configuration configuration = new Configuration();
        configuration.windowConfiguration.setMaxBounds(testRect);

        mKosmos.getConfigurationRepository().onConfigurationChange(configuration);
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(0.0f);
        environment.publishInputEvent(initialEvent);

        // Verify display bounds passed into TouchHandler#getTouchInitiationRegion
        verify(touchHandler).getTouchInitiationRegion(eq(testRect), any(), any());
    }

    @Test
    @DisableFlags(Flags.FLAG_AMBIENT_TOUCH_MONITOR_LISTEN_TO_DISPLAY_CHANGES)
    public void testReportedDisplayBounds() {
        final TouchHandler touchHandler = createTouchHandler();
        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(0.0f);
        environment.publishInputEvent(initialEvent);

        // Verify display bounds passed into TouchHandler#getTouchInitiationRegion
        verify(touchHandler).getTouchInitiationRegion(
                eq(environment.getDisplayBounds()), any(), any());
        final ArgumentCaptor<TouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);
        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        // Verify that display bounds provided from TouchSession#getBounds
        assertThat(touchSessionArgumentCaptor.getValue().getBounds())
                .isEqualTo(environment.getDisplayBounds());
    }

    @Test
    public void testEntryTouchZone() {
        final TouchHandler touchHandler = createTouchHandler();
        final Rect touchArea = new Rect(4, 4, 8 , 8);

        doAnswer(invocation -> {
            final Region region = (Region) invocation.getArguments()[1];
            region.set(touchArea);
            return null;
        }).when(touchHandler).getTouchInitiationRegion(any(), any(), any());

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        // Ensure touch outside specified region is not delivered.
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(1.0f);
        environment.publishInputEvent(initialEvent);
        verify(touchHandler, never()).onSessionStart(any());

        // Make sure touch inside region causes session start.
        when(initialEvent.getX()).thenReturn(5.0f);
        when(initialEvent.getY()).thenReturn(5.0f);
        environment.publishInputEvent(initialEvent);
        verify(touchHandler).onSessionStart(any());
    }

    @Test
    public void testSessionCount() {
        final TouchHandler touchHandler = createTouchHandler();
        final Rect touchArea = new Rect(4, 4, 8 , 8);

        final TouchHandler unzonedTouchHandler = createTouchHandler();
        doAnswer(invocation -> {
            final Region region = (Region) invocation.getArguments()[1];
            region.set(touchArea);
            return null;
        }).when(touchHandler).getTouchInitiationRegion(any(), any(), any());

        final Environment environment = new Environment(Stream.of(touchHandler, unzonedTouchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        // Ensure touch outside specified region is delivered to unzoned touch handler.
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(1.0f);
        environment.publishInputEvent(initialEvent);

        ArgumentCaptor<TouchHandler.TouchSession> touchSessionCaptor = ArgumentCaptor.forClass(
                TouchHandler.TouchSession.class);

        // Make sure only one active session.
        {
            verify(unzonedTouchHandler).onSessionStart(touchSessionCaptor.capture());
            final TouchHandler.TouchSession touchSession = touchSessionCaptor.getValue();
            assertThat(touchSession.getActiveSessionCount()).isEqualTo(1);
            touchSession.pop();
            environment.executeAll();
        }

        // Make sure touch inside the touch region.
        when(initialEvent.getX()).thenReturn(5.0f);
        when(initialEvent.getY()).thenReturn(5.0f);
        environment.publishInputEvent(initialEvent);

        // Make sure there are two active sessions.
        {
            verify(touchHandler).onSessionStart(touchSessionCaptor.capture());
            final TouchHandler.TouchSession touchSession = touchSessionCaptor.getValue();
            assertThat(touchSession.getActiveSessionCount()).isEqualTo(2);
            touchSession.pop();
        }
    }

    @Test
    public void testNoActiveSessionWhenHandlerDisabled() {
        final TouchHandler touchHandler = Mockito.mock(TouchHandler.class);
        // disable the handler
        when(touchHandler.isEnabled()).thenReturn(false);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(5.0f);
        when(initialEvent.getY()).thenReturn(5.0f);
        environment.publishInputEvent(initialEvent);

        // Make sure there is no active session.
        verify(touchHandler, never()).onSessionStart(any());
        verify(touchHandler, never()).getTouchInitiationRegion(any(), any(), any());
    }

    @Test
    public void testInputEventPropagation() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));
    }

    @Test
    public void testInputEventPropagationAfterRemoval() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final TouchHandler.TouchSession session = captureSession(touchHandler);
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(session);

        session.pop();
        environment.executeAll();

        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);

        verify(eventListener, never()).onInputEvent(eq(event));
    }

    @Test
    public void testInputGesturePropagation() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final GestureDetector.OnGestureListener gestureListener =
                registerGestureListener(touchHandler);

        final MotionEvent event = Mockito.mock(MotionEvent.class);
        environment.publishGestureEvent(onGestureListener -> onGestureListener.onShowPress(event));
        verify(gestureListener).onShowPress(eq(event));
    }

    @Test
    public void testGestureConsumption() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final GestureDetector.OnGestureListener gestureListener =
                registerGestureListener(touchHandler);

        when(gestureListener.onDown(any())).thenReturn(true);
        final MotionEvent event = Mockito.mock(MotionEvent.class);
        environment.publishGestureEvent(onGestureListener -> {
            assertThat(onGestureListener.onDown(event)).isTrue();
        });

        verify(gestureListener).onDown(eq(event));
    }

    @Test
    public void testBroadcast() {
        final TouchHandler touchHandler = createTouchHandler();
        final TouchHandler touchHandler2 = createTouchHandler();
        when(touchHandler2.isEnabled()).thenReturn(true);

        final Environment environment = new Environment(Stream.of(touchHandler, touchHandler2)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final HashSet<InputChannelCompat.InputEventListener> inputListeners = new HashSet<>();

        inputListeners.add(registerInputEventListener(touchHandler));
        inputListeners.add(registerInputEventListener(touchHandler));
        inputListeners.add(registerInputEventListener(touchHandler2));

        final MotionEvent event = Mockito.mock(MotionEvent.class);
        environment.publishInputEvent(event);

        inputListeners
                .stream()
                .forEach(inputEventListener -> verify(inputEventListener).onInputEvent(event));
    }

    @Test
    public void testPush() throws InterruptedException, ExecutionException {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final TouchHandler.TouchSession session = captureSession(touchHandler);
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(session);

        final ListenableFuture<TouchHandler.TouchSession> frontSessionFuture = session.push();
        environment.executeAll();
        final TouchHandler.TouchSession frontSession = frontSessionFuture.get();
        final InputChannelCompat.InputEventListener frontEventListener =
                registerInputEventListener(frontSession);

        final MotionEvent event = Mockito.mock(MotionEvent.class);
        environment.publishInputEvent(event);

        verify(frontEventListener).onInputEvent(eq(event));
        verify(eventListener, never()).onInputEvent(any());

        Mockito.clearInvocations(eventListener, frontEventListener);

        ListenableFuture<TouchHandler.TouchSession> sessionFuture = frontSession.pop();
        environment.executeAll();

        TouchHandler.TouchSession returnedSession = sessionFuture.get();
        assertThat(session == returnedSession).isTrue();

        environment.executeAll();

        final MotionEvent followupEvent = Mockito.mock(MotionEvent.class);
        environment.publishInputEvent(followupEvent);

        verify(eventListener).onInputEvent(eq(followupEvent));
        verify(frontEventListener, never()).onInputEvent(any());
    }

    @Test
    public void testPop() {
        final TouchHandler touchHandler = createTouchHandler();

        final TouchHandler.TouchSession.Callback callback =
                Mockito.mock(TouchHandler.TouchSession.Callback.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final TouchHandler.TouchSession session = captureSession(touchHandler);
        session.registerCallback(callback);
        session.pop();
        environment.executeAll();

        verify(callback).onRemoved();
    }

    @Test
    public void testPauseWithNoActiveSessions() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        environment.updateLifecycle(Lifecycle.State.STARTED);

        environment.verifyInputSessionDispose();
    }

    @Test
    public void testDeferredPauseWithActiveSessions() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));

        final ArgumentCaptor<TouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);

        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        environment.updateLifecycle(Lifecycle.State.STARTED);

        verify(environment.mInputSession, never()).dispose();

        // End session
        touchSessionArgumentCaptor.getValue().pop();
        environment.executeAll();

        // Check to make sure the input session is now disposed.
        environment.verifyInputSessionDispose();
    }

    @Test
    public void testDestroyWithActiveSessions() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));

        final ArgumentCaptor<TouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);

        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        environment.updateLifecycle(Lifecycle.State.DESTROYED);

        // Check to make sure the input session is now disposed.
        environment.verifyInputSessionDispose();
    }

    @Test
    public void testSessionPopAfterDestroy() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));

        final ArgumentCaptor<TouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);

        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        environment.updateLifecycle(Lifecycle.State.DESTROYED);

        // Check to make sure the input session is now disposed.
        environment.verifyInputSessionDispose();

        clearInvocations(environment.mInputFactory);

        // Pop the session
        touchSessionArgumentCaptor.getValue().pop();

        environment.executeAll();

        // Ensure no input sessions were created due to the session reset.
        verifyNoMoreInteractions(environment.mInputFactory);
    }


    @Test
    public void testPilfering() {
        final TouchHandler touchHandler1 = createTouchHandler();
        final TouchHandler touchHandler2 = createTouchHandler();
        final Environment environment = new Environment(Stream.of(touchHandler1, touchHandler2)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final TouchHandler.TouchSession session1 = captureSession(touchHandler1);
        final GestureDetector.OnGestureListener gestureListener1 =
                registerGestureListener(session1);

        final TouchHandler.TouchSession session2 = captureSession(touchHandler2);
        final GestureDetector.OnGestureListener gestureListener2 =
                registerGestureListener(session2);
        when(gestureListener2.onDown(any())).thenReturn(true);

        final MotionEvent gestureEvent = Mockito.mock(MotionEvent.class);
        environment.publishGestureEvent(
                onGestureListener -> onGestureListener.onDown(gestureEvent));

        Mockito.clearInvocations(gestureListener1, gestureListener2);

        final MotionEvent followupEvent = Mockito.mock(MotionEvent.class);
        environment.publishGestureEvent(
                onGestureListener -> onGestureListener.onDown(followupEvent));

        verify(gestureListener1, never()).onDown(any());
        verify(gestureListener2).onDown(eq(followupEvent));
    }

    @Test
    public void testOnRemovedCallbackOnStopMonitoring() {
        final TouchHandler touchHandler = createTouchHandler();

        final TouchHandler.TouchSession.Callback callback =
                Mockito.mock(TouchHandler.TouchSession.Callback.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final TouchHandler.TouchSession session = captureSession(touchHandler);
        session.registerCallback(callback);

        environment.executeAll();

        environment.updateLifecycle(Lifecycle.State.DESTROYED);

        environment.executeAll();

        verify(callback).onRemoved();
    }

    @Test
    public void testDestroy_cleansUpLifecycleObserver() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);
        environment.destroyMonitor();
        environment.verifyLifecycleObserversUnregistered();
    }

    @Test
    public void testDestroy_cleansUpHandler() {
        final TouchHandler touchHandler = createTouchHandler();

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);
        environment.destroyMonitor();
        verify(touchHandler).onDestroy();
    }

    @Test
    public void testLastSessionPop_createsNewInputSession() {
        final TouchHandler touchHandler = createTouchHandler();

        final TouchHandler.TouchSession.Callback callback =
                Mockito.mock(TouchHandler.TouchSession.Callback.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)), mKosmos);

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final TouchHandler.TouchSession session = captureSession(touchHandler);
        session.registerCallback(callback);

        // Clear invocations on input session and factory.
        clearInvocations(environment.mInputFactory);
        clearInvocations(environment.mInputSession);

        // Pop only active touch session.
        session.pop();
        environment.executeAll();

        // Verify that input session disposed and new session requested from factory.
        verify(environment.mInputSession).dispose();
        verify(environment.mInputFactory).create(any(), any(), any(), anyBoolean());
    }

    private GestureDetector.OnGestureListener registerGestureListener(TouchHandler handler) {
        final GestureDetector.OnGestureListener gestureListener = Mockito.mock(
                GestureDetector.OnGestureListener.class);
        final ArgumentCaptor<TouchHandler.TouchSession> sessionCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);
        verify(handler).onSessionStart(sessionCaptor.capture());
        sessionCaptor.getValue().registerGestureListener(gestureListener);

        return gestureListener;
    }

    private GestureDetector.OnGestureListener registerGestureListener(
            TouchHandler.TouchSession session) {
        final GestureDetector.OnGestureListener gestureListener = Mockito.mock(
                GestureDetector.OnGestureListener.class);
        session.registerGestureListener(gestureListener);

        return gestureListener;
    }

    private InputChannelCompat.InputEventListener registerInputEventListener(
            TouchHandler.TouchSession session) {
        final InputChannelCompat.InputEventListener eventListener = Mockito.mock(
                InputChannelCompat.InputEventListener.class);
        session.registerInputListener(eventListener);

        return eventListener;
    }

    private TouchHandler.TouchSession captureSession(TouchHandler handler) {
        final ArgumentCaptor<TouchHandler.TouchSession> sessionCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.class);
        verify(handler).onSessionStart(sessionCaptor.capture());
        return sessionCaptor.getValue();
    }

    private InputChannelCompat.InputEventListener registerInputEventListener(
            TouchHandler handler) {
        return registerInputEventListener(captureSession(handler));
    }

    private TouchHandler createTouchHandler() {
        final TouchHandler touchHandler = Mockito.mock(TouchHandler.class);
        // enable the handler by default
        when(touchHandler.isEnabled()).thenReturn(true);
        return touchHandler;
    }
}
