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

package com.android.server.soundtrigger_middleware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class SoundTriggerHalConcurrentCaptureHandlerTest {
    private ISoundTriggerHal mUnderlying;
    private CaptureStateNotifier mNotifier;
    private ISoundTriggerHal.GlobalCallback mGlobalCallback;
    private SoundTriggerHalConcurrentCaptureHandler mHandler;

    @Before
    public void setUp() {
        mNotifier = new CaptureStateNotifier();
        mUnderlying = mock(ISoundTriggerHal.class);
        mGlobalCallback = mock(ISoundTriggerHal.GlobalCallback.class);
        mHandler = new SoundTriggerHalConcurrentCaptureHandler(mUnderlying, mNotifier);
        mHandler.registerCallback(mGlobalCallback);
    }

    @Test
    public void testBasic() throws Exception {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        verify(mUnderlying).loadSoundModel(any(), any());

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        mNotifier.setActive(true);
        verify(mUnderlying).stopRecognition(handle);
        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        Thread.sleep(50);
        verify(callback).recognitionCallback(eq(handle), eventCaptor.capture());
        RecognitionEvent event = eventCaptor.getValue();
        assertEquals(event.status, RecognitionStatus.ABORTED);
        assertFalse(event.recognitionStillActive);
        verifyZeroInteractions(mGlobalCallback);
        clearInvocations(callback, mUnderlying);

        mNotifier.setActive(false);
        Thread.sleep(50);
        verify(mGlobalCallback).onResourcesAvailable();
        verifyNoMoreInteractions(callback, mUnderlying);

        mNotifier.setActive(true);
        verifyNoMoreInteractions(callback, mUnderlying);
    }

    @Test
    public void testStopBeforeActive() throws Exception {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        verify(mUnderlying).loadSoundModel(any(), any());

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());
        mHandler.stopRecognition(handle);
        verify(mUnderlying).stopRecognition(handle);
        clearInvocations(mUnderlying);

        mNotifier.setActive(true);
        Thread.sleep(50);
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void testStopAfterActive() {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        verify(mUnderlying).loadSoundModel(any(), any());

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        mNotifier.setActive(true);
        verify(mUnderlying, times(1)).stopRecognition(handle);
        mHandler.stopRecognition(handle);
        verify(callback, times(1)).recognitionCallback(eq(handle), any());
    }

    @Test(timeout = 200)
    public void testAbortWhileStop() {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> modelCallbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHal.ModelCallback.class);
        verify(mUnderlying).loadSoundModel(any(), modelCallbackCaptor.capture());
        ISoundTriggerHal.ModelCallback modelCallback = modelCallbackCaptor.getValue();

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        doAnswer(invocation -> {
            RecognitionEvent event = TestUtil.createRecognitionEvent(RecognitionStatus.ABORTED,
                    false);
            // Call the callback from a different thread to detect deadlocks by preventing recursive
            // locking from working.
            runOnSeparateThread(() -> modelCallback.recognitionCallback(handle, event));
            return null;
        }).when(mUnderlying).stopRecognition(handle);
        mHandler.stopRecognition(handle);
        verify(mUnderlying, times(1)).stopRecognition(handle);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback, atMost(1)).recognitionCallback(eq(handle), eventCaptor.capture());
    }

    @Test(timeout = 200)
    public void testActiveWhileStop() {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> modelCallbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHal.ModelCallback.class);
        verify(mUnderlying).loadSoundModel(any(), modelCallbackCaptor.capture());
        ISoundTriggerHal.ModelCallback modelCallback = modelCallbackCaptor.getValue();

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        doAnswer(invocation -> {
            // The stop request causes a callback to be flushed.
            RecognitionEvent event = TestUtil.createRecognitionEvent(RecognitionStatus.FORCED,
                    true);
            // Call the callback from a different thread to detect deadlocks by preventing recursive
            // locking from working.
            runOnSeparateThread(() -> modelCallback.recognitionCallback(handle, event));
            // While the HAL is processing the stop request, capture state becomes active.
            new Thread(() -> mNotifier.setActive(true)).start();
            Thread.sleep(50);
            return null;
        }).when(mUnderlying).stopRecognition(handle);
        mHandler.stopRecognition(handle);
        // We only expect one underlying invocation of stop().
        verify(mUnderlying, times(1)).stopRecognition(handle);

        // The callback shouldn't be invoked in this case.
        verify(callback, never()).recognitionCallback(eq(handle), any());
    }

    @Test(timeout = 200)
    public void testStopWhileActive() {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> modelCallbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHal.ModelCallback.class);
        verify(mUnderlying).loadSoundModel(any(), modelCallbackCaptor.capture());
        ISoundTriggerHal.ModelCallback modelCallback = modelCallbackCaptor.getValue();

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        doAnswer(invocation -> {
            // The stop request causes a callback to be flushed.
            RecognitionEvent event = TestUtil.createRecognitionEvent(RecognitionStatus.FORCED,
                    true);
            // Call the callback from a different thread to detect deadlocks by preventing recursive
            // locking from working.
            runOnSeparateThread(() -> modelCallback.recognitionCallback(handle, event));
            // While the HAL is processing the stop request, client requests stop.
            new Thread(() -> mHandler.stopRecognition(handle)).start();
            Thread.sleep(50);
            return null;
        }).when(mUnderlying).stopRecognition(handle);
        mNotifier.setActive(true);
        // We only expect one underlying invocation of stop().
        verify(mUnderlying, times(1)).stopRecognition(handle);
        verify(callback, atMost(1)).recognitionCallback(eq(handle), any());
    }

    @Test(timeout = 200)
    public void testEventWhileActive() throws Exception {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> modelCallbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHal.ModelCallback.class);
        verify(mUnderlying).loadSoundModel(any(), modelCallbackCaptor.capture());
        ISoundTriggerHal.ModelCallback modelCallback = modelCallbackCaptor.getValue();

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        doAnswer(invocation -> {
            RecognitionEvent event = TestUtil.createRecognitionEvent(RecognitionStatus.SUCCESS,
                    false);
            // Call the callback from a different thread to detect deadlocks by preventing recursive
            // locking from working.
            runOnSeparateThread(() -> modelCallback.recognitionCallback(handle, event));
            return null;
        }).when(mUnderlying).stopRecognition(handle);
        mNotifier.setActive(true);
        verify(mUnderlying, times(1)).stopRecognition(handle);
        Thread.sleep(50);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback, atMost(2)).recognitionCallback(eq(handle), eventCaptor.capture());
        RecognitionEvent lastEvent = eventCaptor.getValue();
        assertEquals(lastEvent.status, RecognitionStatus.ABORTED);
        assertFalse(lastEvent.recognitionStillActive);
    }


    @Test(timeout = 200)
    public void testNonFinalEventWhileActive() throws Exception {
        ISoundTriggerHal.ModelCallback callback = mock(ISoundTriggerHal.ModelCallback.class);
        int handle = mHandler.loadSoundModel(TestUtil.createGenericSoundModel(), callback);
        ArgumentCaptor<ISoundTriggerHal.ModelCallback> modelCallbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHal.ModelCallback.class);
        verify(mUnderlying).loadSoundModel(any(), modelCallbackCaptor.capture());
        ISoundTriggerHal.ModelCallback modelCallback = modelCallbackCaptor.getValue();

        mHandler.startRecognition(handle, 101, 102, TestUtil.createRecognitionConfig());
        verify(mUnderlying).startRecognition(eq(handle), eq(101), eq(102), any());

        doAnswer(invocation -> {
            RecognitionEvent event = TestUtil.createRecognitionEvent(RecognitionStatus.FORCED,
                    true);
            // Call the callback from a different thread to detect deadlocks by preventing recursive
            // locking from working.
            runOnSeparateThread(() -> modelCallback.recognitionCallback(handle, event));

            return null;
        }).when(mUnderlying).stopRecognition(handle);
        mNotifier.setActive(true);
        verify(mUnderlying, times(1)).stopRecognition(handle);

        Thread.sleep(50);
        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback, atMost(2)).recognitionCallback(eq(handle), eventCaptor.capture());
        RecognitionEvent lastEvent = eventCaptor.getValue();
        assertEquals(lastEvent.status, RecognitionStatus.ABORTED);
        assertFalse(lastEvent.recognitionStillActive);
    }

    private static void runOnSeparateThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static class CaptureStateNotifier implements ICaptureStateNotifier {
        boolean mActive = false;
        Listener mListener;

        @Override
        public boolean registerListener(@NonNull Listener listener) {
            mListener = listener;
            return mActive;
        }

        @Override
        public void unregisterListener(@NonNull Listener listener) {
            mListener = null;
        }

        public void setActive(boolean active) {
            mActive = active;
            if (mListener != null) {
                // Call the callback from a different thread to detect deadlocks by preventing
                // recursive locking from working.
                runOnSeparateThread(() -> mListener.onCaptureStateChange(mActive));
            }
        }
    }
}
