/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import android.util.SparseBooleanArray;

/**
 * Version of AppForegroundHelper to be used for testing. All apps are treated as foreground until
 * notified otherwise.
 */
public class FakeAppForegroundHelper extends AppForegroundHelper {

    private final SparseBooleanArray mForegroundUids;

    public FakeAppForegroundHelper() {
        mForegroundUids = new SparseBooleanArray();
    }

    public void setAppForeground(int uid, boolean foreground) {
        mForegroundUids.put(uid, foreground);
        notifyAppForeground(uid, foreground);
    }

    @Override
    public boolean isAppForeground(int uid) {
        return mForegroundUids.get(uid, true);
    }
}
