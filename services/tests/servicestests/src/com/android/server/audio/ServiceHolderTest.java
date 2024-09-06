/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.server.audio;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.IAudioPolicyService;
import android.os.Binder;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class ServiceHolderTest {

    private static final String AUDIO_POLICY_SERVICE_NAME = "media.audio_policy";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    // the actual class under test
    private ServiceHolder<IAudioPolicyService> mServiceHolder;

    @Mock private ServiceHolder.ServiceProviderFacade mServiceProviderFacade;

    @Mock private IAudioPolicyService mAudioPolicyService;

    @Mock private IBinder mBinder;

    @Mock private Consumer<IAudioPolicyService> mTaskOne;
    @Mock private Consumer<IAudioPolicyService> mTaskTwo;

    @Before
    public void setUp() throws Exception {
        mServiceHolder =
                new ServiceHolder(
                        AUDIO_POLICY_SERVICE_NAME,
                        (Function<IBinder, IAudioPolicyService>)
                                binder -> {
                                    if (binder == mBinder) {
                                        return mAudioPolicyService;
                                    } else {
                                        return mock(IAudioPolicyService.class);
                                    }
                                },
                        r -> r.run(),
                        mServiceProviderFacade);
        when(mAudioPolicyService.asBinder()).thenReturn(mBinder);
    }

    @Test
    public void testListenerRegistered_whenConstructed() {
        verify(mServiceProviderFacade)
                .registerForNotifications(eq(AUDIO_POLICY_SERVICE_NAME), ArgumentMatchers.any());
    }

    @Test
    public void testServiceSuccessfullyPopulated_whenCallback() throws RemoteException {
        initializeViaCallback();
        verify(mBinder).linkToDeath(any(), anyInt());
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
    }

    @Test
    public void testCheckServiceCalled_whenUncached() {
        when(mServiceProviderFacade.checkService(eq(AUDIO_POLICY_SERVICE_NAME)))
                .thenReturn(mBinder);
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
    }

    @Test
    public void testCheckServiceTransmitsNull() {
        assertThat(mServiceHolder.checkService()).isEqualTo(null);
    }

    @Test
    public void testWaitForServiceCalled_whenUncached() {
        when(mServiceProviderFacade.waitForService(eq(AUDIO_POLICY_SERVICE_NAME)))
                .thenReturn(mBinder);
        assertThat(mServiceHolder.waitForService()).isEqualTo(mAudioPolicyService);
    }

    @Test
    public void testCheckServiceNotCalled_whenCached() {
        initializeViaCallback();
        mServiceHolder.checkService();
        verify(mServiceProviderFacade, never()).checkService(any());
    }

    @Test
    public void testWaitForServiceNotCalled_whenCached() {
        initializeViaCallback();
        mServiceHolder.waitForService();
        verify(mServiceProviderFacade, never()).waitForService(any());
    }

    @Test
    public void testStartTaskCalled_onStart() {
        mServiceHolder.registerOnStartTask(mTaskOne);
        mServiceHolder.registerOnStartTask(mTaskTwo);
        mServiceHolder.unregisterOnStartTask(mTaskOne);
        when(mServiceProviderFacade.checkService(eq(AUDIO_POLICY_SERVICE_NAME)))
                .thenReturn(mBinder);

        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

        verify(mTaskTwo).accept(eq(mAudioPolicyService));
        verify(mTaskOne, never()).accept(any());
    }

    @Test
    public void testStartTaskCalled_onStartFromCallback() {
        mServiceHolder.registerOnStartTask(mTaskOne);
        mServiceHolder.registerOnStartTask(mTaskTwo);
        mServiceHolder.unregisterOnStartTask(mTaskOne);

        initializeViaCallback();

        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
        verify(mTaskTwo).accept(eq(mAudioPolicyService));
        verify(mTaskOne, never()).accept(any());
    }

    @Test
    public void testStartTaskCalled_onRegisterAfterStarted() {
        initializeViaCallback();
        mServiceHolder.registerOnStartTask(mTaskOne);
        verify(mTaskOne).accept(eq(mAudioPolicyService));
    }

    @Test
    public void testBinderDied_clearsServiceAndUnlinks() {
        initializeViaCallback();
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

        mServiceHolder.binderDied(mBinder);

        verify(mBinder).unlinkToDeath(any(), anyInt());
        assertThat(mServiceHolder.checkService()).isEqualTo(null);
        verify(mServiceProviderFacade).checkService(eq(AUDIO_POLICY_SERVICE_NAME));
    }

    @Test
    public void testBinderDied_callsDeathTasks() {
        mServiceHolder.registerOnDeathTask(mTaskOne);
        mServiceHolder.registerOnDeathTask(mTaskTwo);
        initializeViaCallback();
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
        mServiceHolder.unregisterOnDeathTask(mTaskOne);

        mServiceHolder.binderDied(mBinder);

        verify(mTaskTwo).accept(eq(mAudioPolicyService));
        verify(mTaskOne, never()).accept(any());
    }

    @Test
    public void testAttemptClear_clearsServiceAndUnlinks() {
        initializeViaCallback();
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

        mServiceHolder.attemptClear(mBinder);

        verify(mBinder).unlinkToDeath(any(), anyInt());
        assertThat(mServiceHolder.checkService()).isEqualTo(null);
        verify(mServiceProviderFacade).checkService(eq(AUDIO_POLICY_SERVICE_NAME));
    }

    @Test
    public void testAttemptClear_callsDeathTasks() {
        mServiceHolder.registerOnDeathTask(mTaskOne);
        mServiceHolder.registerOnDeathTask(mTaskTwo);
        initializeViaCallback();
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
        mServiceHolder.unregisterOnDeathTask(mTaskOne);

        mServiceHolder.attemptClear(mBinder);

        verify(mTaskTwo).accept(eq(mAudioPolicyService));
        verify(mTaskOne, never()).accept(any());
    }

    @Test
    public void testSet_whenServiceSet_isIgnored() {
        mServiceHolder.registerOnStartTask(mTaskOne);
        when(mServiceProviderFacade.checkService(eq(AUDIO_POLICY_SERVICE_NAME)))
                .thenReturn(mBinder);
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

        verify(mTaskOne).accept(eq(mAudioPolicyService));

        // get the callback
        ArgumentCaptor<IServiceCallback> cb = ArgumentCaptor.forClass(IServiceCallback.class);
        verify(mServiceProviderFacade)
                .registerForNotifications(eq(AUDIO_POLICY_SERVICE_NAME), cb.capture());

        // Simulate a service callback with a different instance
        try {
            cb.getValue().onRegistration(AUDIO_POLICY_SERVICE_NAME, new Binder());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // No additional start task call (i.e. only the first verify)
        verify(mTaskOne).accept(any());
        // Same instance
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

    }

    @Test
    public void testClear_whenServiceCleared_isIgnored() {
        mServiceHolder.registerOnDeathTask(mTaskOne);
        mServiceHolder.attemptClear(mBinder);
        verify(mTaskOne, never()).accept(any());
    }

    @Test
    public void testClear_withDifferentCookie_isIgnored() {
        mServiceHolder.registerOnDeathTask(mTaskOne);
        initializeViaCallback();
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);

        // Notif for stale cookie
        mServiceHolder.attemptClear(new Binder());

        // Service shouldn't be cleared
        assertThat(mServiceHolder.checkService()).isEqualTo(mAudioPolicyService);
        // No death tasks should fire
        verify(mTaskOne, never()).accept(any());
    }

    private void initializeViaCallback() {
        ArgumentCaptor<IServiceCallback> cb = ArgumentCaptor.forClass(IServiceCallback.class);
        verify(mServiceProviderFacade)
                .registerForNotifications(eq(AUDIO_POLICY_SERVICE_NAME), cb.capture());

        try {
            cb.getValue().onRegistration(AUDIO_POLICY_SERVICE_NAME, mBinder);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
