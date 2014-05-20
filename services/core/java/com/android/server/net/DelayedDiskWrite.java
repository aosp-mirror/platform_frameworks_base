/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.net;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class DelayedDiskWrite {
    private HandlerThread mDiskWriteHandlerThread;
    private Handler mDiskWriteHandler;
    /* Tracks multiple writes on the same thread */
    private int mWriteSequence = 0;
    private final String TAG = "DelayedDiskWrite";

    public interface Writer {
        public void onWriteCalled(DataOutputStream out) throws IOException;
    }

    public void write(final String filePath, final Writer w) {
        if (TextUtils.isEmpty(filePath)) {
            throw new IllegalArgumentException("empty file path");
        }

        /* Do a delayed write to disk on a separate handler thread */
        synchronized (this) {
            if (++mWriteSequence == 1) {
                mDiskWriteHandlerThread = new HandlerThread("DelayedDiskWriteThread");
                mDiskWriteHandlerThread.start();
                mDiskWriteHandler = new Handler(mDiskWriteHandlerThread.getLooper());
            }
        }

        mDiskWriteHandler.post(new Runnable() {
            @Override
            public void run() {
                doWrite(filePath, w);
            }
        });
    }

    private void doWrite(String filePath, Writer w) {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(filePath)));
            w.onWriteCalled(out);
        } catch (IOException e) {
            loge("Error writing data file " + filePath);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {}
            }

            // Quit if no more writes sent
            synchronized (this) {
                if (--mWriteSequence == 0) {
                    mDiskWriteHandler.getLooper().quit();
                    mDiskWriteHandler = null;
                    mDiskWriteHandlerThread = null;
                }
            }
        }
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}

