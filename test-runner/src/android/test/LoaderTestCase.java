/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.test;

import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * A convenience class for testing {@link Loader}s. This test case
 * provides a simple way to synchronously get the result from a Loader making
 * it easy to assert that the Loader returns the expected result.
 */
public class LoaderTestCase extends AndroidTestCase {
    static {
        // Force class loading of AsyncTask on the main thread so that it's handlers are tied to
        // the main thread and responses from the worker thread get delivered on the main thread.
        // The tests are run on another thread, allowing them to block waiting on a response from
        // the code running on the main thread. The main thread can't block since the AysncTask
        // results come in via the event loop.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {return null;}
            @Override
            protected void onPostExecute(Void result) {}
        };
    }

    /**
     * Runs a Loader synchronously and returns the result of the load. The loader will
     * be started, stopped, and destroyed by this method so it cannot be reused.
     *
     * @param loader The loader to run synchronously
     * @return The result from the loader
     */
    public <T> T getLoaderResultSynchronously(final Loader<T> loader) {
        // The test thread blocks on this queue until the loader puts it's result in
        final ArrayBlockingQueue<T> queue = new ArrayBlockingQueue<T>(1);

        // This callback runs on the "main" thread and unblocks the test thread
        // when it puts the result into the blocking queue
        final OnLoadCompleteListener<T> listener = new OnLoadCompleteListener<T>() {
            @Override
            public void onLoadComplete(Loader<T> completedLoader, T data) {
                // Shut the loader down
                completedLoader.unregisterListener(this);
                completedLoader.stopLoading();
                completedLoader.reset();

                // Store the result, unblocking the test thread
                queue.add(data);
            }
        };

        // This handler runs on the "main" thread of the process since AsyncTask
        // is documented as needing to run on the main thread and many Loaders use
        // AsyncTask
        final Handler mainThreadHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                loader.registerListener(0, listener);
                loader.startLoading();
            }
        };

        // Ask the main thread to start the loading process
        mainThreadHandler.sendEmptyMessage(0);

        // Block on the queue waiting for the result of the load to be inserted
        T result;
        while (true) {
            try {
                result = queue.take();
                break;
            } catch (InterruptedException e) {
                throw new RuntimeException("waiting thread interrupted", e);
            }
        }

        return result;
    }
}
