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
package android.view.contentcapture;

import android.content.ComponentName;
import android.service.contentcapture.ActivityEvent;
import android.service.contentcapture.ContentCaptureService;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MyContentCaptureService extends ContentCaptureService {

    private static final String TAG = MyContentCaptureService.class.getSimpleName();
    private static final String MY_PACKAGE = "com.android.perftests.contentcapture";
    public static final String SERVICE_NAME = MY_PACKAGE + "/"
            + MyContentCaptureService.class.getName();

    private static ServiceWatcher sServiceWatcher;

    @NonNull
    public static ServiceWatcher setServiceWatcher() {
        if (sServiceWatcher != null) {
            throw new IllegalStateException("There Can Be Only One!");
        }
        sServiceWatcher = new ServiceWatcher();
        return sServiceWatcher;
    }

    public static void resetStaticState() {
        sServiceWatcher = null;
    }

    private static void clearServiceWatcher() {
        final ServiceWatcher sw = sServiceWatcher;
        if (sw != null) {
            if (sw.mReadyToClear) {
                sw.mService = null;
                sServiceWatcher = null;
            } else {
                sw.mReadyToClear = true;
            }
        }
    }

    @Override
    public void onConnected() {
        Log.i(TAG, "onConnected: sServiceWatcher=" + sServiceWatcher);

        if (sServiceWatcher == null) {
            Log.e(TAG, "onConnected() without a watcher");
            return;
        }

        if (!sServiceWatcher.mReadyToClear && sServiceWatcher.mService != null) {
            Log.e(TAG, "onConnected(): already created: " + sServiceWatcher);
            return;
        }

        sServiceWatcher.mService = this;
        sServiceWatcher.mCreated.countDown();
        sServiceWatcher.mReadyToClear = false;
    }

    @Override
    public void onDisconnected() {
        final ServiceWatcher sw = sServiceWatcher;
        Log.i(TAG, "onDisconnected: sServiceWatcher=" + sw);
        if (sw == null) {
            Log.e(TAG, "onDisconnected() without a watcher");
            return;
        }
        if (sw.mService == null) {
            Log.e(TAG, "onDisconnected(): no service on " + sw);
            return;
        }

        sw.mDestroyed.countDown();
        clearServiceWatcher();
    }

    @Override
    public void onCreateContentCaptureSession(ContentCaptureContext context,
            ContentCaptureSessionId sessionId) {
        Log.i(TAG, "onCreateContentCaptureSession(ctx=" + context + ", session=" + sessionId);
    }

    @Override
    public void onDestroyContentCaptureSession(ContentCaptureSessionId sessionId) {
        Log.i(TAG, "onDestroyContentCaptureSession(session=" + sessionId + ")");
    }

    @Override
    public void onContentCaptureEvent(ContentCaptureSessionId sessionId,
            ContentCaptureEvent event) {
        Log.i(TAG, "onContentCaptureEventsRequest(session=" + sessionId + "): " + event);
        if (sServiceWatcher != null
                && event.getType() == ContentCaptureEvent.TYPE_SESSION_PAUSED) {
            sServiceWatcher.mSessionPaused.countDown();
        }
    }

    @Override
    public void onActivityEvent(ActivityEvent event) {
        Log.i(TAG, "onActivityEvent(): " + event);
    }

    public static final class ServiceWatcher {

        private static final long GENERIC_TIMEOUT_MS = 10_000;
        private final CountDownLatch mCreated = new CountDownLatch(1);
        private final CountDownLatch mDestroyed = new CountDownLatch(1);
        private final CountDownLatch mSessionPaused = new CountDownLatch(1);
        private boolean mReadyToClear = true;
        private Pair<Set<String>, Set<ComponentName>> mAllowList;

        private MyContentCaptureService mService;

        @NonNull
        public MyContentCaptureService waitOnCreate() throws InterruptedException {
            await(mCreated, "not created");

            if (mService == null) {
                throw new IllegalStateException("not created");
            }

            if (mAllowList != null) {
                Log.d(TAG, "Allow after created: " + mAllowList);
                mService.setContentCaptureWhitelist(mAllowList.first, mAllowList.second);
            }

            return mService;
        }

        public void waitOnDestroy() throws InterruptedException {
            await(mDestroyed, "not destroyed");
        }

        /** Wait for session paused. */
        public void waitSessionPaused() throws InterruptedException {
            await(mSessionPaused, "no Paused");
        }

        /**
         * Allow just this package.
         */
        public void setAllowSelf() {
            final ArraySet<String> pkgs = new ArraySet<>(1);
            pkgs.add(MY_PACKAGE);
            mAllowList = new Pair<>(pkgs, null);
        }

        @Override
        public String toString() {
            return "mService: " + mService + " created: " + (mCreated.getCount() == 0)
                    + " destroyed: " + (mDestroyed.getCount() == 0);
        }

        /**
         * Awaits for a latch to be counted down.
         */
        private static void await(@NonNull CountDownLatch latch, @NonNull String fmt,
                @Nullable Object... args)
                throws InterruptedException {
            final boolean called = latch.await(GENERIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!called) {
                throw new IllegalStateException(String.format(fmt, args)
                        + " in " + GENERIC_TIMEOUT_MS + "ms");
            }
        }
    }
}
