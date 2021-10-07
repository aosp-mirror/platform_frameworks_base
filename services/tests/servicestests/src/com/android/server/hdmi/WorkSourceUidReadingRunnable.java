/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.hdmi;

import android.os.Binder;

import java.util.Optional;

/**
 * Reads and records Binder's work source UID when executed.
 */
public class WorkSourceUidReadingRunnable implements Runnable {
    private Optional<Integer> mWorkSourceUid = Optional.empty();

    @Override
    public void run() {
        mWorkSourceUid = Optional.of(Binder.getCallingWorkSourceUid());
    }

    /**
     * @return The work source UID read during execution, or Optional.empty() if never executed.
     */
    public Optional<Integer> getWorkSourceUid() {
        return mWorkSourceUid;
    }
}
