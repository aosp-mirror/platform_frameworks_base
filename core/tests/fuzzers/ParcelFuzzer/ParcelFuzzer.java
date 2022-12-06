/*
 * Copyright (C) 2022 The Android Open Source Project
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
package parcelfuzzer;

import android.util.Log;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import randomparcel.FuzzBinder;

public class ParcelFuzzer {

    static {
        // Initialize JNI dependencies
        FuzzBinder.init();
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider provider) {
        // Default behavior for Java APIs is to throw RuntimeException.
        // We need to fuzz to detect other problems which are not handled explicitly.
        // TODO(b/150808347): Change known API exceptions to subclass of
        // RuntimeExceptions and catch those only.
        try {
            provider.pickValue(FuzzUtils.FUZZ_OPERATIONS).doFuzz(provider);
        } catch (RuntimeException e) {
            Log.e("ParcelFuzzer", "Exception occurred while fuzzing ", e);
        }
    }
}
