/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.text.IClipboard;
import android.content.Context;

/**
 * Implementation of the clipboard for copy and paste.
 */
public class ClipboardService extends IClipboard.Stub {
    private CharSequence mClipboard = "";

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) { }

    // javadoc from interface
    public void setClipboardText(CharSequence text) {
        synchronized (this) {
            if (text == null) {
                text = "";
            }
    
            mClipboard = text;
        }
    }

    // javadoc from interface
    public CharSequence getClipboardText() {
        synchronized (this) {
            return mClipboard;
        }
    }

    // javadoc from interface
    public boolean hasClipboardText() {
        synchronized (this) {
            return mClipboard.length() > 0;
        }
    }
}
