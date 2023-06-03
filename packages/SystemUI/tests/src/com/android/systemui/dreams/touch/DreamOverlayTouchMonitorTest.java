/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.graphics.Region;
import android.testing.AndroidTestingRunner;
import android.util.Pair;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.touch.dagger.InputSessionComponent;
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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayTouchMonitorTest extends SysuiTestCase {
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    private static class Environment {
        private final InputSessionComponent.Factory mInputFactory;
        private final InputSession mInputSession;
        private final Lifecycle mLifecycle;
        private final LifecycleOwner mLifecycleOwner;
        private final DreamOverlayTouchMonitor mMonitor;
        private final DefaultLifecycleObserver mLifecycleObserver;
        private final InputChannelCompat.InputEventListener mEventListener;
        private final GestureDetector.OnGestureListener mGestureListener;
        private final DisplayHelper mDisplayHelper;
        private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
        private final Rect mDisplayBounds = Mockito.mock(Rect.class);

        Environment(Set<DreamTouchHandler> handlers) {
            mLifecycle = Mockito.mock(Lifecycle.class);
            mLifecycleOwner = Mockito.mock(LifecycleOwner.class);

            mInputFactory = Mockito.mock(InputSessionComponent.Factory.class);
            final InputSessionComponent inputComponent = Mockito.mock(InputSessionComponent.class);
            mInputSession = Mockito.mock(InputSession.class);

            when(mInputFactory.create(any(), any(), any(), anyBoolean()))
                    .thenReturn(inputComponent);
            when(inputComponent.getInputSession()).thenReturn(mInputSession);

            mDisplayHelper = Mockito.mock(DisplayHelper.class);
            when(mDisplayHelper.getMaxBounds(anyInt(), anyInt()))
                    .thenReturn(mDisplayBounds);
            mMonitor = new DreamOverlayTouchMonitor(mExecutor, mLifecycle, mInputFactory,
                    mDisplayHelper, handlers);
            mMonitor.init();

            final ArgumentCaptor<LifecycleObserver> lifecycleObserverCaptor =
                    ArgumentCaptor.forClass(LifecycleObserver.class);
            verify(mLifecycle).addObserver(lifecycleObserverCaptor.capture());
            assertThat(lifecycleObserverCaptor.getValue() instanceof DefaultLifecycleObserver)
                    .isTrue();
            mLifecycleObserver = (DefaultLifecycleObserver) lifecycleObserverCaptor.getValue();

            updateLifecycle(observer -> observer.first.onResume(observer.second));

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

        void updateLifecycle(Consumer<Pair<DefaultLifecycleObserver, LifecycleOwner>> consumer) {
            consumer.accept(Pair.create(mLifecycleObserver, mLifecycleOwner));
        }

        void verifyInputSessionDispose() {
            verify(mInputSession).dispose();
            Mockito.clearInvocations(mInputSession);
        }
    }

    @Test
    public void testReportedDisplayBounds() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(0.0f);
        environment.publishInputEvent(initialEvent);

        // Verify display bounds passed into TouchHandler#getTouchInitiationRegion
        verify(touchHandler).getTouchInitiationRegion(eq(environment.getDisplayBounds()), any());
        final ArgumentCaptor<DreamTouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(DreamTouchHandler.TouchSession.class);
        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        // Verify that display bounds provided from TouchSession#getBounds
        assertThat(touchSessionArgumentCaptor.getValue().getBounds())
                .isEqualTo(environment.getDisplayBounds());
    }

    @Test
    public void testEntryTouchZone() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final Rect touchArea = new Rect(4, 4, 8 , 8);

        doAnswer(invocation -> {
            final Region region = (Region) invocation.getArguments()[1];
            region.set(touchArea);
            return null;
        }).when(touchHandler).getTouchInitiationRegion(any(), any());

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

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
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final Rect touchArea = new Rect(4, 4, 8 , 8);

        final DreamTouchHandler unzonedTouchHandler = Mockito.mock(DreamTouchHandler.class);
        doAnswer(invocation -> {
            final Region region = (Region) invocation.getArguments()[1];
            region.set(touchArea);
            return null;
        }).when(touchHandler).getTouchInitiationRegion(any(), any());

        final Environment environment = new Environment(Stream.of(touchHandler, unzonedTouchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        // Ensure touch outside specified region is delivered to unzoned touch handler.
        final MotionEvent initialEvent = Mockito.mock(MotionEvent.class);
        when(initialEvent.getX()).thenReturn(0.0f);
        when(initialEvent.getY()).thenReturn(1.0f);
        environment.publishInputEvent(initialEvent);

        ArgumentCaptor<DreamTouchHandler.TouchSession> touchSessionCaptor = ArgumentCaptor.forClass(
                DreamTouchHandler.TouchSession.class);

        // Make sure only one active session.
        {
            verify(unzonedTouchHandler).onSessionStart(touchSessionCaptor.capture());
            final DreamTouchHandler.TouchSession touchSession = touchSessionCaptor.getValue();
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
            final DreamTouchHandler.TouchSession touchSession = touchSessionCaptor.getValue();
            assertThat(touchSession.getActiveSessionCount()).isEqualTo(2);
            touchSession.pop();
        }
    }

    @Test
    public void testInputEventPropagation() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

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
    public void testInputGesturePropagation() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

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
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

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
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final DreamTouchHandler touchHandler2 = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler, touchHandler2)
                .collect(Collectors.toCollection(HashSet::new)));

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
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final DreamTouchHandler.TouchSession session = captureSession(touchHandler);
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(session);

        final ListenableFuture<DreamTouchHandler.TouchSession> frontSessionFuture = session.push();
        environment.executeAll();
        final DreamTouchHandler.TouchSession frontSession = frontSessionFuture.get();
        final InputChannelCompat.InputEventListener frontEventListener =
                registerInputEventListener(frontSession);

        final MotionEvent event = Mockito.mock(MotionEvent.class);
        environment.publishInputEvent(event);

        verify(frontEventListener).onInputEvent(eq(event));
        verify(eventListener, never()).onInputEvent(any());

        Mockito.clearInvocations(eventListener, frontEventListener);

        ListenableFuture<DreamTouchHandler.TouchSession> sessionFuture = frontSession.pop();
        environment.executeAll();

        DreamTouchHandler.TouchSession returnedSession = sessionFuture.get();
        assertThat(session == returnedSession).isTrue();

        environment.executeAll();

        final MotionEvent followupEvent = Mockito.mock(MotionEvent.class);
        environment.publishInputEvent(followupEvent);

        verify(eventListener).onInputEvent(eq(followupEvent));
        verify(frontEventListener, never()).onInputEvent(any());
    }

    @Test
    public void testPop() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final DreamTouchHandler.TouchSession.Callback callback =
                Mockito.mock(DreamTouchHandler.TouchSession.Callback.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final DreamTouchHandler.TouchSession session = captureSession(touchHandler);
        session.registerCallback(callback);
        session.pop();
        environment.executeAll();

        verify(callback).onRemoved();
    }

    @Test
    public void testPauseWithNoActiveSessions() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        environment.updateLifecycle(observerOwnerPair -> {
            observerOwnerPair.first.onPause(observerOwnerPair.second);
        });

        environment.verifyInputSessionDispose();
    }

    @Test
    public void testDeferredPauseWithActiveSessions() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));

        final ArgumentCaptor<DreamTouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(DreamTouchHandler.TouchSession.class);

        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        environment.updateLifecycle(observerOwnerPair -> {
            observerOwnerPair.first.onPause(observerOwnerPair.second);
        });

        verify(environment.mInputSession, never()).dispose();

        // End session
        touchSessionArgumentCaptor.getValue().pop();
        environment.executeAll();

        // Check to make sure the input session is now disposed.
        environment.verifyInputSessionDispose();
    }

    @Test
    public void testDestroyWithActiveSessions() {
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        // Ensure session started
        final InputChannelCompat.InputEventListener eventListener =
                registerInputEventListener(touchHandler);

        // First event will be missed since we register after the execution loop,
        final InputEvent event = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(event);
        verify(eventListener).onInputEvent(eq(event));

        final ArgumentCaptor<DreamTouchHandler.TouchSession> touchSessionArgumentCaptor =
                ArgumentCaptor.forClass(DreamTouchHandler.TouchSession.class);

        verify(touchHandler).onSessionStart(touchSessionArgumentCaptor.capture());

        environment.updateLifecycle(observerOwnerPair -> {
            observerOwnerPair.first.onDestroy(observerOwnerPair.second);
        });

        // Check to make sure the input session is now disposed.
        environment.verifyInputSessionDispose();
    }


    @Test
    public void testPilfering() {
        final DreamTouchHandler touchHandler1 = Mockito.mock(DreamTouchHandler.class);
        final DreamTouchHandler touchHandler2 = Mockito.mock(DreamTouchHandler.class);

        final Environment environment = new Environment(Stream.of(touchHandler1, touchHandler2)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final DreamTouchHandler.TouchSession session1 = captureSession(touchHandler1);
        final GestureDetector.OnGestureListener gestureListener1 =
                registerGestureListener(session1);

        final DreamTouchHandler.TouchSession session2 = captureSession(touchHandler2);
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
        final DreamTouchHandler touchHandler = Mockito.mock(DreamTouchHandler.class);
        final DreamTouchHandler.TouchSession.Callback callback =
                Mockito.mock(DreamTouchHandler.TouchSession.Callback.class);

        final Environment environment = new Environment(Stream.of(touchHandler)
                .collect(Collectors.toCollection(HashSet::new)));

        final InputEvent initialEvent = Mockito.mock(InputEvent.class);
        environment.publishInputEvent(initialEvent);

        final DreamTouchHandler.TouchSession session = captureSession(touchHandler);
        session.registerCallback(callback);

        environment.executeAll();

        environment.updateLifecycle(observerOwnerPair -> {
            observerOwnerPair.first.onDestroy(observerOwnerPair.second);
        });

        environment.executeAll();

        verify(callback).onRemoved();
    }

    public GestureDetector.OnGestureListener registerGestureListener(DreamTouchHandler handler) {
        final GestureDetector.OnGestureListener gestureListener = Mockito.mock(
                GestureDetector.OnGestureListener.class);
        final ArgumentCaptor<DreamTouchHandler.TouchSession> sessionCaptor =
                ArgumentCaptor.forClass(DreamTouchHandler.TouchSession.class);
        verify(handler).onSessionStart(sessionCaptor.capture());
        sessionCaptor.getValue().registerGestureListener(gestureListener);

        return gestureListener;
    }

    public GestureDetector.OnGestureListener registerGestureListener(
            DreamTouchHandler.TouchSession session) {
        final GestureDetector.OnGestureListener gestureListener = Mockito.mock(
                GestureDetector.OnGestureListener.class);
        session.registerGestureListener(gestureListener);

        return gestureListener;
    }

    public InputChannelCompat.InputEventListener registerInputEventListener(
            DreamTouchHandler.TouchSession session) {
        final InputChannelCompat.InputEventListener eventListener = Mockito.mock(
                InputChannelCompat.InputEventListener.class);
        session.registerInputListener(eventListener);

        return eventListener;
    }

    public DreamTouchHandler.TouchSession captureSession(DreamTouchHandler handler) {
        final ArgumentCaptor<DreamTouchHandler.TouchSession> sessionCaptor =
                ArgumentCaptor.forClass(DreamTouchHandler.TouchSession.class);
        verify(handler).onSessionStart(sessionCaptor.capture());
        return sessionCaptor.getValue();
    }

    public InputChannelCompat.InputEventListener registerInputEventListener(
            DreamTouchHandler handler) {
        return registerInputEventListener(captureSession(handler));
    }
}
