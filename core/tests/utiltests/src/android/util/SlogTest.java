/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.util;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SlogTest {
    private static final String TAG = "tag";
    private static final String MSG = "msg";
    private static final Throwable THROWABLE = new Throwable();

    @Test
    public void testSimple() {
        Slog.v(TAG, MSG);
        Slog.d(TAG, MSG);
        Slog.i(TAG, MSG);
        Slog.w(TAG, MSG);
        Slog.e(TAG, MSG);
    }

    @Test
    public void testThrowable() {
        Slog.v(TAG, MSG, THROWABLE);
        Slog.d(TAG, MSG, THROWABLE);
        Slog.i(TAG, MSG, THROWABLE);
        Slog.w(TAG, MSG, THROWABLE);
        Slog.e(TAG, MSG, THROWABLE);
    }
}
