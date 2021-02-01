/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.overlaytest;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.annotation.NonNull;
import android.content.Context;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

class LocalOverlayManager {
    private static final long TIMEOUT = 30;

    public static void toggleOverlaysAndWait(@NonNull final String[] overlaysToEnable,
            @NonNull final String[] overlaysToDisable) throws Exception {
        final int userId = UserHandle.myUserId();
        OverlayManagerTransaction.Builder builder = new OverlayManagerTransaction.Builder();
        for (String pkg : overlaysToEnable) {
            builder.setEnabled(pkg, true, userId);
        }
        for (String pkg : overlaysToDisable) {
            builder.setEnabled(pkg, false, userId);
        }
        OverlayManagerTransaction transaction = builder.build();

        final Context ctx = InstrumentationRegistry.getTargetContext();
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            while (true) {
                final String[] paths = ctx.getResources().getAssets().getApkPaths();
                if (arrayTailContains(paths, overlaysToEnable)
                        && arrayDoesNotContain(paths, overlaysToDisable)) {
                    return true;
                }
                Thread.sleep(10);
            }
        });

        OverlayManager om = ctx.getSystemService(OverlayManager.class);
        om.commit(transaction);

        Executor executor = (cmd) -> new Thread(cmd).start();
        executor.execute(task);
        task.get(TIMEOUT, SECONDS);
    }

    private static boolean arrayTailContains(@NonNull final String[] array,
            @NonNull final String[] substrings) {
        if (array.length < substrings.length) {
            return false;
        }
        for (int i = 0; i < substrings.length; i++) {
            String a = array[array.length - substrings.length + i];
            String s = substrings[i];
            if (!a.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private static boolean arrayDoesNotContain(@NonNull final String[] array,
            @NonNull final String[] substrings) {
        for (String s : substrings) {
            for (String a : array) {
                if (a.contains(s)) {
                    return false;
                }
            }
        }
        return true;
    }
}
