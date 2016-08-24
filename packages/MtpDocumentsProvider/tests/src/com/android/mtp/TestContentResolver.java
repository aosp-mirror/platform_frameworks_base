/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.database.ContentObserver;
import android.net.Uri;
import android.test.mock.MockContentResolver;

import junit.framework.Assert;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class TestContentResolver extends MockContentResolver {
    private static final int TIMEOUT_PERIOD_MS = 3000;
    private final Map<Uri, Phaser> mPhasers = new HashMap<>();

    @Override
    public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
        getPhaser(uri).arrive();
    }


    void waitForNotification(Uri uri, int count) throws InterruptedException, TimeoutException {
        Assert.assertEquals(count, getPhaser(uri).awaitAdvanceInterruptibly(
                count - 1, TIMEOUT_PERIOD_MS, TimeUnit.MILLISECONDS));
    }

    int getChangeCount(Uri uri) {
        if (mPhasers.containsKey(uri)) {
            return mPhasers.get(uri).getPhase();
        } else {
            return 0;
        }
    }

    private synchronized Phaser getPhaser(Uri uri) {
        Phaser phaser = mPhasers.get(uri);
        if (phaser == null) {
            phaser = new Phaser(1);
            mPhasers.put(uri, phaser);
        }
        return phaser;
    }
}
