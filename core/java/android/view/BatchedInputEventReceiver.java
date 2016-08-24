/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.view;

import android.os.Looper;

/**
 * Similar to {@link InputEventReceiver}, but batches events to vsync boundaries when possible.
 * @hide
 */
public class BatchedInputEventReceiver extends InputEventReceiver {
    Choreographer mChoreographer;
    private boolean mBatchedInputScheduled;

    public BatchedInputEventReceiver(
            InputChannel inputChannel, Looper looper, Choreographer choreographer) {
        super(inputChannel, looper);
        mChoreographer = choreographer;
    }

    @Override
    public void onBatchedInputEventPending() {
        scheduleBatchedInput();
    }

    @Override
    public void dispose() {
        unscheduleBatchedInput();
        super.dispose();
    }

    void doConsumeBatchedInput(long frameTimeNanos) {
        if (mBatchedInputScheduled) {
            mBatchedInputScheduled = false;
            if (consumeBatchedInputEvents(frameTimeNanos) && frameTimeNanos != -1) {
                // If we consumed a batch here, we want to go ahead and schedule the
                // consumption of batched input events on the next frame. Otherwise, we would
                // wait until we have more input events pending and might get starved by other
                // things occurring in the process. If the frame time is -1, however, then
                // we're in a non-batching mode, so there's no need to schedule this.
                scheduleBatchedInput();
            }
        }
    }

    private void scheduleBatchedInput() {
        if (!mBatchedInputScheduled) {
            mBatchedInputScheduled = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_INPUT, mBatchedInputRunnable, null);
        }
    }

    private void unscheduleBatchedInput() {
        if (mBatchedInputScheduled) {
            mBatchedInputScheduled = false;
            mChoreographer.removeCallbacks(
                    Choreographer.CALLBACK_INPUT, mBatchedInputRunnable, null);
        }
    }

    private final class BatchedInputRunnable implements Runnable {
        @Override
        public void run() {
            doConsumeBatchedInput(mChoreographer.getFrameTimeNanos());
        }
    }
    private final BatchedInputRunnable mBatchedInputRunnable = new BatchedInputRunnable();
}
