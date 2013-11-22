/*
 * Copyright (C) 2013 The Android Open Source Project
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
package androidx.media.filterfw;

import android.os.Handler;
import android.os.HandlerThread;
import android.test.AndroidTestCase;

import junit.framework.TestCase;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * A {@link TestCase} for testing objects requiring {@link MffContext}. This test case can only be
 * used to test the functionality that does not rely on GL support and camera.
 */
public class MffTestCase extends AndroidTestCase {

    private HandlerThread mMffContextHandlerThread;
    private MffContext mMffContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // MffContext needs to be created on a separate thread to allow MFF to post Runnable's.
        mMffContextHandlerThread = new HandlerThread("MffContextThread");
        mMffContextHandlerThread.start();
        Handler handler = new Handler(mMffContextHandlerThread.getLooper());
        FutureTask<MffContext> task = new FutureTask<MffContext>(new Callable<MffContext>() {
            @Override
            public MffContext call() throws Exception {
                MffContext.Config config = new MffContext.Config();
                config.requireCamera = false;
                config.requireOpenGL = false;
                config.forceNoGL = true;
                return new MffContext(getContext(), config);
            }
        });
        handler.post(task);
        // Wait for the context to be created on the handler thread.
        mMffContext = task.get();
    }

    @Override
    protected void tearDown() throws Exception {
        mMffContextHandlerThread.getLooper().quit();
        mMffContextHandlerThread = null;
        mMffContext.release();
        mMffContext = null;
        super.tearDown();
    }

    protected MffContext getMffContext() {
        return mMffContext;
    }

}
