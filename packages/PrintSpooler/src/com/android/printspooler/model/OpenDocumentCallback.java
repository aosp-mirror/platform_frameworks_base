/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.model;

/**
 * Callbacks interface for opening a file.
 */
public interface OpenDocumentCallback {
    public static final int ERROR_MALFORMED_PDF_FILE = -1;
    public static final int ERROR_SECURE_PDF_FILE = -2;

    /**
     * Called after the file is opened.
     */
    public void onSuccess();

    /**
     * Called after opening the file failed.
     *
     * @param error The error.
     */
    public void onFailure(int error);
}
