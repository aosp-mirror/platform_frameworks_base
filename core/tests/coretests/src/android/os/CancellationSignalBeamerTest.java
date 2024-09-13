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

package android.os;

import static android.os.CancellationSignalBeamer.Sender;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.CancellationSignalBeamer.Receiver;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.PollingCheck;
import android.util.PollingCheck.PollingCheckCondition;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.concurrent.CountDownLatch;

@RunWith(AndroidJUnit4.class)
@SmallTest
@IgnoreUnderRavenwood(blockedBy = CancellationSignalBeamer.class)
public class CancellationSignalBeamerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private CancellationSignal mSenderSignal;
    private CancellationSignal mReceivedSignal;
    private Context mContext;

    @Before
    public void setUp() {
        mSenderSignal = new CancellationSignal();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test
    public void testBeam_null() {
        try (var token = mSender.beam(null)) {
            assertThat(token).isNull();
            invokeGenericService(token);
        }
        assertThat(mReceivedSignal).isNull();
    }

    @Test
    public void testBeam_nonNull() {
        try (var token = mSender.beam(mSenderSignal)) {
            assertThat(token).isNotNull();
            invokeGenericService(token);
        }
        assertThat(mReceivedSignal).isNotNull();
    }

    @Test
    public void testBeam_async() {
        IBinder outerToken;
        try (var token = mSender.beam(mSenderSignal)) {
            assertThat(token).isNotNull();
            outerToken = token;
        }
        invokeGenericService(outerToken);
        assertThat(mReceivedSignal).isNotNull();
    }

    @Test
    public void testCancelOnSentSignal_cancelsReceivedSignal() {
        try (var token = mSender.beam(mSenderSignal)) {
            invokeGenericService(token);
        }
        mSenderSignal.cancel();
        assertThat(mReceivedSignal.isCanceled()).isTrue();
    }

    @Test
    public void testSendingCancelledSignal_cancelsReceivedSignal() {
        mSenderSignal.cancel();
        try (var token = mSender.beam(mSenderSignal)) {
            invokeGenericService(token);
        }
        assertThat(mReceivedSignal.isCanceled()).isTrue();
    }

    @Test
    public void testUnbeam_null() {
        assertThat(mReceiver.unbeam(null)).isNull();
    }

    @Test
    public void testForget_null() {
        mReceiver.forget(null);
    }

    @Test
    public void testCancel_null() {
        mReceiver.cancel(null);
    }

    @Test
    public void testForget_withUnknownToken() {
        mReceiver.forget(new Binder());
    }

    @Test
    public void testCancel_withUnknownToken() {
        mReceiver.cancel(new Binder());
    }

    @Test
    public void testBinderDied_withUnknownToken() {
        mReceiver.binderDied(new Binder());
    }

    @Test
    public void testReceiverWithCancelOnSenderDead_cancelsOnSenderDeath() {
        var receiver = new Receiver(true /* cancelOnSenderDeath */);
        var token = new Binder();
        var signal = receiver.unbeam(token);
        receiver.binderDied(token);
        assertThat(signal.isCanceled()).isTrue();
    }

    @Test
    public void testReceiverWithoutCancelOnSenderDead_doesntCancelOnSenderDeath() {
        var receiver = new Receiver(false /* cancelOnSenderDeath */);
        var token = new Binder();
        var signal = receiver.unbeam(token);
        receiver.binderDied(token);
        assertThat(signal.isCanceled()).isFalse();
    }

    @Test
    public void testDroppingSentSignal_dropsReceivedSignal() throws Exception {
        // In a multiprocess scenario, sending token over Binder might leak the token
        // on both ends if we create a reference cycle. Simulate that worst-case scenario
        // here by leaking it directly, then test that cleanup of the signals still works.
        var receivedSignalCleaned = new CountDownLatch(1);
        var tokenRef = new Object[1];
        // Reference the cancellation signals in a separate method scope, so we don't
        // accidentally leak them on the stack / in a register.
        Runnable r = () -> {
            try (var token = mSender.beam(mSenderSignal)) {
                tokenRef[0] = token;
                invokeGenericService(token);
            }
            mSenderSignal = null;

            Cleaner.create().register(mReceivedSignal, receivedSignalCleaned::countDown);
            mReceivedSignal = null;
        };
        r.run();

        waitForWithGc(() -> receivedSignalCleaned.getCount() == 0);

        Reference.reachabilityFence(tokenRef[0]);
    }

    @Test
    public void testRepeatedBeaming_doesntLeak() throws Exception {
        var receivedSignalCleaned = new CountDownLatch(1);
        var tokenRef = new Object[1];
        // Reference the cancellation signals in a separate method scope, so we don't
        // accidentally leak them on the stack / in a register.
        Runnable r = () -> {
            try (var token = mSender.beam(mSenderSignal)) {
                tokenRef[0] = token;
                invokeGenericService(token);
            }
            // Beaming again leaves mReceivedSignal dangling, so it should be collected.
            mSender.beam(mSenderSignal).close();

            Cleaner.create().register(mReceivedSignal, receivedSignalCleaned::countDown);
            mReceivedSignal = null;
        };
        r.run();

        waitForWithGc(() -> receivedSignalCleaned.getCount() == 0);

        Reference.reachabilityFence(tokenRef[0]);
    }

    private void waitForWithGc(PollingCheckCondition condition) throws IOException {
        try {
            PollingCheck.waitFor(() -> {
                Runtime.getRuntime().gc();
                return condition.canProceed();
            });
        } catch (AssertionError e) {
            File heap = new File(mContext.getExternalFilesDir(null), "dump.hprof");
            Debug.dumpHprofData(heap.getAbsolutePath());
            throw e;
        }
    }

    private void invokeGenericService(IBinder cancellationSignalToken) {
        mReceivedSignal = mReceiver.unbeam(cancellationSignalToken);
    }

    private Sender mSender;
    private Receiver mReceiver;

    @Before
    public void setUpSenderReceiver() {
        mSender = new Sender() {
            @Override
            public void onCancel(IBinder token) {
                mReceiver.cancel(token);
            }

            @Override
            public void onForget(IBinder token) {
                mReceiver.forget(token);
            }
        };
        mReceiver = new Receiver(false);
    }
}
