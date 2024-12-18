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

package android.tracing.perfetto;

/**
 * @hide
 */
public class Producer {

    /**
     * Initializes the global Perfetto producer.
     *
     * @param args arguments on how to initialize the Perfetto producer.
     */
    public static void init(InitArguments args) {
        nativePerfettoProducerInit(args.backends, args.shmemSizeHintKb);
    }

    private static native void nativePerfettoProducerInit(int backends, int shmemSizeHintKb);
}
