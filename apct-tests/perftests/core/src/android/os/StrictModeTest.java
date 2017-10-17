/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class StrictModeTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeVmViolation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
        causeVmViolations(state);
    }

    @Test
    public void timeVmViolationNoStrictMode() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        causeVmViolations(state);
    }

    private static void causeVmViolations(BenchmarkState state) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(Uri.parse("content://com.example/foobar"), "image/jpeg");
        final Context context = InstrumentationRegistry.getTargetContext();
        while (state.keepRunning()) {
            context.startActivity(intent);
        }
    }

    @Test
    public void timeThreadViolation() throws IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
        causeThreadViolations(state);
    }

    @Test
    public void timeThreadViolationNoStrictMode() throws IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        causeThreadViolations(state);
    }

    private static void causeThreadViolations(BenchmarkState state) throws IOException {
        final File test = File.createTempFile("foo", "bar");
        while (state.keepRunning()) {
            test.exists();
        }
        test.delete();
    }

    @Test
    public void timeCrossBinderThreadViolation() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
        causeCrossProcessThreadViolations(state);
    }

    @Test
    public void timeCrossBinderThreadViolationNoStrictMode() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        causeCrossProcessThreadViolations(state);
    }

    private static void causeCrossProcessThreadViolations(BenchmarkState state)
            throws ExecutionException, InterruptedException, RemoteException {
        final Context context = InstrumentationRegistry.getTargetContext();

        SettableFuture<IBinder> binder = SettableFuture.create();
        ServiceConnection connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName className, IBinder service) {
                        binder.set(service);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName arg0) {
                        binder.set(null);
                    }
                };
        context.bindService(
                new Intent(context, SomeService.class), connection, Context.BIND_AUTO_CREATE);
        ISomeService someService = ISomeService.Stub.asInterface(binder.get());
        while (state.keepRunning()) {
            // Violate strictmode heavily.
            someService.readDisk(10);
        }
        context.unbindService(connection);
    }
}
