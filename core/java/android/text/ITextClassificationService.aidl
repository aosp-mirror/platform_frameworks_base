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

package android.text;

import android.os.ParcelFileDescriptor;

/**
 * Interface to the text classification service, which grants access to the text classification
 * LSTM model file.
 * {@hide}
 */
interface ITextClassificationService {

    /**
     * Request a file descriptor with read-only access to the LSTM model file.
     * This file descriptor should be closed after the client is done with it.
     */
    ParcelFileDescriptor getModelFileFd();
}
