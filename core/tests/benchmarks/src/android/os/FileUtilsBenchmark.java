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

package android.os;

import static android.os.FileUtils.copyInternalSendfile;
import static android.os.FileUtils.copyInternalSplice;
import static android.os.FileUtils.copyInternalUserspace;

import android.os.FileUtils.MemoryPipe;

import com.google.caliper.BeforeExperiment;
import com.google.caliper.Param;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class FileUtilsBenchmark {
    @Param({"32", "32000", "32000000"})
    private int mSize;

    private File mSrc;
    private File mDest;

    private byte[] mData;

    @BeforeExperiment
    protected void setUp() throws Exception {
        mSrc = new File("/data/local/tmp/src");
        mDest = new File("/data/local/tmp/dest");

        mData = new byte[mSize];

        try (FileOutputStream os = new FileOutputStream(mSrc)) {
            os.write(mData);
        }
    }

    public void timeRegularUserspace(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (FileInputStream in = new FileInputStream(mSrc);
                    FileOutputStream out = new FileOutputStream(mDest)) {
                copyInternalUserspace(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }

    public void timeRegularSendfile(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (FileInputStream in = new FileInputStream(mSrc);
                    FileOutputStream out = new FileOutputStream(mDest)) {
                copyInternalSendfile(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }

    public void timePipeSourceUserspace(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (MemoryPipe in = MemoryPipe.createSource(mData);
                    FileOutputStream out = new FileOutputStream(mDest)) {
                copyInternalUserspace(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }

    public void timePipeSourceSplice(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (MemoryPipe in = MemoryPipe.createSource(mData);
                    FileOutputStream out = new FileOutputStream(mDest)) {
                copyInternalSplice(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }

    public void timePipeSinkUserspace(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (FileInputStream in = new FileInputStream(mSrc);
                    MemoryPipe out = MemoryPipe.createSink(mData)) {
                copyInternalUserspace(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }

    public void timePipeSinkSplice(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            try (FileInputStream in = new FileInputStream(mSrc);
                    MemoryPipe out = MemoryPipe.createSink(mData)) {
                copyInternalSplice(in.getFD(), out.getFD(), Long.MAX_VALUE, null, null, null);
            }
        }
    }
}
