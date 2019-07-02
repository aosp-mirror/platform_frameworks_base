/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import static android.os.Process.myTid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.HandlerThread;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SmallTest
public class ViewRootSurfaceCallbackTest implements SurfaceHolder.Callback2 {
    private static final String TAG = ViewRootSurfaceCallbackTest.class.getSimpleName();
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(3);

    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    private final CompletableFuture<Integer> mTidOfSurfaceCreated = new CompletableFuture<>();
    private final CompletableFuture<Integer> mTidOfSurfaceDestroyed = new CompletableFuture<>();

    private boolean mDuplicatedSurfaceDestroyed;

    /**
     * Verifies that the calling thread of {@link SurfaceHolder.Callback2} should be the same as the
     * thread that created the view root.
     */
    @Test
    public void testCallingTidOfSurfaceCallback() throws Exception {
        final Activity activity = mActivityRule.getActivity();
        activity.setTurnScreenOn(true);
        activity.setShowWhenLocked(true);

        // Create a dialog that runs on another thread and let it handle surface by itself.
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        thread.getThreadHandler().runWithScissors(() -> {
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(TAG).setMessage(TAG).create();
            dialog.getWindow().takeSurface(this);
            dialog.show();
        }, TIMEOUT_MS);
        final int attachedTid = thread.getThreadId();

        assertEquals(attachedTid,
                mTidOfSurfaceCreated.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).intValue());

        // Make the activity invisible.
        activity.moveTaskToBack(true /* nonRoot */);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertEquals(attachedTid,
                mTidOfSurfaceDestroyed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).intValue());
        assertFalse("surfaceDestroyed should not be called twice", mDuplicatedSurfaceDestroyed);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mTidOfSurfaceCreated.complete(myTid());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if  (!mTidOfSurfaceDestroyed.isDone()) {
            mTidOfSurfaceDestroyed.complete(myTid());
        } else {
            mDuplicatedSurfaceDestroyed = true;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
    }
}
