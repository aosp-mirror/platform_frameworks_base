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

package com.android.server.text;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.ITextClassificationService;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Text classification service.
 * This is used to provide access to the text classification LSTM model file.
 */
public class TextClassificationService extends ITextClassificationService.Stub {

    private static final String LOG_TAG = "TextClassificationService";

    public static final class Lifecycle extends SystemService {

        private TextClassificationService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new TextClassificationService();
        }

        @Override
        public void onStart() {
            try {
                publishBinderService(Context.TEXT_CLASSIFICATION_SERVICE, mService);
            } catch (Throwable t) {
                // Starting this service is not critical to the running of this device and should
                // therefore not crash the device. If it fails, log the error and continue.
                Slog.e(LOG_TAG, "Could not start the TextClassificationService.", t);
            }
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getModelFileFd() throws RemoteException {
        try {
            return ParcelFileDescriptor.open(
                    new File("/etc/assistant/smart-selection.model"),
                    ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Throwable t) {
            Slog.e(LOG_TAG, "Error retrieving an fd to the text classification model file.", t);
            throw new RemoteException(t.getMessage());
        }
    }
}
