/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.PackageParser;
import android.os.Process;
import android.os.Trace;
import android.util.DisplayMetrics;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ConcurrentUtils;

import java.io.File;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

/**
 * Helper class for parallel parsing of packages using {@link PackageParser}.
 * <p>Parsing requests are processed by a thread-pool of {@link #MAX_THREADS}.
 * At any time, at most {@link #QUEUE_CAPACITY} results are kept in RAM</p>
 */
class ParallelPackageParser implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 10;
    private static final int MAX_THREADS = 4;

    private final String[] mSeparateProcesses;
    private final boolean mOnlyCore;
    private final DisplayMetrics mMetrics;
    private final File mCacheDir;
    private final PackageParser.Callback mPackageParserCallback;
    private volatile String mInterruptedInThread;

    private final BlockingQueue<ParseResult> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final ExecutorService mService = ConcurrentUtils.newFixedThreadPool(MAX_THREADS,
            "package-parsing-thread", Process.THREAD_PRIORITY_FOREGROUND);

    ParallelPackageParser(String[] separateProcesses, boolean onlyCoreApps,
            DisplayMetrics metrics, File cacheDir, PackageParser.Callback callback) {
        mSeparateProcesses = separateProcesses;
        mOnlyCore = onlyCoreApps;
        mMetrics = metrics;
        mCacheDir = cacheDir;
        mPackageParserCallback = callback;
    }

    static class ParseResult {

        PackageParser.Package pkg; // Parsed package
        File scanFile; // File that was parsed
        Throwable throwable; // Set if an error occurs during parsing

        @Override
        public String toString() {
            return "ParseResult{" +
                    "pkg=" + pkg +
                    ", scanFile=" + scanFile +
                    ", throwable=" + throwable +
                    '}';
        }
    }

    /**
     * Take the parsed package from the parsing queue, waiting if necessary until the element
     * appears in the queue.
     * @return parsed package
     */
    public ParseResult take() {
        try {
            if (mInterruptedInThread != null) {
                throw new InterruptedException("Interrupted in " + mInterruptedInThread);
            }
            return mQueue.take();
        } catch (InterruptedException e) {
            // We cannot recover from interrupt here
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Submits the file for parsing
     * @param scanFile file to scan
     * @param parseFlags parse falgs
     */
    public void submit(File scanFile, int parseFlags) {
        mService.submit(() -> {
            ParseResult pr = new ParseResult();
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parallel parsePackage [" + scanFile + "]");
            try {
                PackageParser pp = new PackageParser();
                pp.setSeparateProcesses(mSeparateProcesses);
                pp.setOnlyCoreApps(mOnlyCore);
                pp.setDisplayMetrics(mMetrics);
                pp.setCacheDir(mCacheDir);
                pp.setCallback(mPackageParserCallback);
                pr.scanFile = scanFile;
                pr.pkg = parsePackage(pp, scanFile, parseFlags);
            } catch (Throwable e) {
                pr.throwable = e;
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            try {
                mQueue.put(pr);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Propagate result to callers of take().
                // This is helpful to prevent main thread from getting stuck waiting on
                // ParallelPackageParser to finish in case of interruption
                mInterruptedInThread = Thread.currentThread().getName();
            }
        });
    }

    @VisibleForTesting
    protected PackageParser.Package parsePackage(PackageParser packageParser, File scanFile,
            int parseFlags) throws PackageParser.PackageParserException {
        return packageParser.parsePackage(scanFile, parseFlags, true /* useCaches */);
    }

    @Override
    public void close() {
        List<Runnable> unfinishedTasks = mService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: "
                    + unfinishedTasks);
        }
    }
}
