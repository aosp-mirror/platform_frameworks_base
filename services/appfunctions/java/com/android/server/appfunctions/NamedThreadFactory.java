/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link ThreadFactory} that creates threads with a given base name. */
public class NamedThreadFactory implements ThreadFactory {
    private final ThreadFactory mDefaultThreadFactory;
    private final String mBaseName;
    private final AtomicInteger mCount = new AtomicInteger(0);

    public NamedThreadFactory(final String baseName) {
        mDefaultThreadFactory = Executors.defaultThreadFactory();
        mBaseName = baseName;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        final Thread thread = mDefaultThreadFactory.newThread(runnable);
        thread.setName(mBaseName + "-" + mCount.getAndIncrement());
        return thread;
    }
}
