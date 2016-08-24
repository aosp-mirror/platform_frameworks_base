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
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.documentsui.Shared.EXTRA_BENCHMARK;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.CountDownLatch;

public class LauncherActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.android.documentsui";
    private static final int BENCHMARK_REQUEST_CODE = 1986;

    public static CountDownLatch testCaseLatch = null;
    public static long measurement = -1;

    private long mStartTime = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        new Handler().post(new Runnable() {
            @Override public void run() {
                final Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(EXTRA_BENCHMARK, true);
                intent.setType("*/*");

                mStartTime = System.currentTimeMillis();
                startActivityForResult(intent, BENCHMARK_REQUEST_CODE);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == BENCHMARK_REQUEST_CODE) {
            measurement = System.currentTimeMillis() - mStartTime;
            testCaseLatch.countDown();
            finish();
        }
    }
}
