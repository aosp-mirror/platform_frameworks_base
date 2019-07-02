/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.autofill;

import android.view.View;
import android.view.autofill.AutofillManager.AutofillCallback;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static android.view.autofill.AutofillManager.AutofillCallback.EVENT_INPUT_HIDDEN;
import static android.view.autofill.AutofillManager.AutofillCallback.EVENT_INPUT_SHOWN;
import static android.view.autofill.AutofillManager.AutofillCallback.EVENT_INPUT_UNAVAILABLE;

import android.os.CancellationSignal;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.util.Log;

/**
 * Custom {@link AutofillCallback} used to recover events during tests.
 */
public final class MyAutofillCallback extends AutofillCallback {

    private static final String TAG = "MyAutofillCallback";
    private static final int TIMEOUT_MS = 5000;

    private final BlockingQueue<MyEvent> mEvents = new LinkedBlockingQueue<>(2);
    private final List<String> mAsyncErrors = new ArrayList<>();

    @Override
    public void onAutofillEvent(View view, int event) {
        boolean offered = false;
        try {
            offered = mEvents.offer(new MyEvent(view, event), TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!offered) {
            String error = "could not offer " + toString(view, event) + " in " + TIMEOUT_MS + "ms";
            Log.e(TAG, error);
            mAsyncErrors.add(error);
        }
    }

    /**
     * Asserts the callback is called for the given view and event, or fail if it times out.
     */
    public void expectEvent(@NonNull View view, int event) {
        MyEvent myEvent;
        try {
            myEvent = mEvents.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (myEvent == null) {
                throw new IllegalStateException("no event received in " + TIMEOUT_MS
                        + "ms while waiting for " + toString(view, event));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + toString(view, event));
        }
        if (!myEvent.view.equals(view) || myEvent.event != event) {
            throw new AssertionError("Invalid event: expected " + myEvent + ", got "
                    + toString(view, event));
        }
    }

    /**
     * Throws an exception if an error happened asynchronously while handing
     * {@link #onAutofillEvent(View, int)}.
     */
    public void assertNoAsyncErrors() {
       if (!mAsyncErrors.isEmpty()) {
           throw new IllegalStateException(mAsyncErrors.size() + " errors: " + mAsyncErrors);
       }
    }

    private static String eventToString(int event) {
        switch (event) {
            case EVENT_INPUT_HIDDEN:
                return "HIDDEN";
            case EVENT_INPUT_SHOWN:
                return "SHOWN";
            case EVENT_INPUT_UNAVAILABLE:
                return "UNAVAILABLE";
            default:
                throw new IllegalArgumentException("invalid event: " + event);
        }
    }

    private static String toString(View view, int event) {
        return eventToString(event) + ": " + view + ")";
    }

    private static final class MyEvent {
        public final View view;
        public final int event;

        MyEvent(View view, int event) {
            this.view = view;
            this.event = event;
        }

        @Override
        public String toString() {
            return MyAutofillCallback.toString(view, event);
        }
    }
}
