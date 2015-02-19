/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.ILayoutResultCallback;
import android.print.IPrintDocumentAdapterObserver;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;

/**
 * Interface for communication with the print adapter object.
 *
 * @hide
 */
oneway interface IPrintDocumentAdapter {
    void setObserver(in IPrintDocumentAdapterObserver observer);
    void start();
    void layout(in PrintAttributes oldAttributes, in PrintAttributes newAttributes,
            ILayoutResultCallback callback, in Bundle metadata, int sequence);
    void write(in PageRange[] pages, in ParcelFileDescriptor fd,
            IWriteResultCallback callback, int sequence);
    void finish();
    void kill(String reason);
}
