/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui;

import com.android.systemui.dagger.SysUISingleton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;

/**
 * Thread that offloads work from the UI thread but that is still perceptible to the user, so the
 * priority is the same as the main thread.
 */
@SysUISingleton
public class UiOffloadThread {

    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    @Inject
    public UiOffloadThread() {
    }

    public Future<?> execute(Runnable runnable) {
        return mExecutorService.submit(runnable);
    }
}
