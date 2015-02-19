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

package com.android.printspooler.renderer;

import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;

/**
 * Interface for communication with a remote pdf editor.
 */
interface IPdfEditor {
    int openDocument(in ParcelFileDescriptor source);
    void removePages(in PageRange[] pages);
    void applyPrintAttributes(in PrintAttributes attributes);
    void write(in ParcelFileDescriptor destination);
    void closeDocument();
}
