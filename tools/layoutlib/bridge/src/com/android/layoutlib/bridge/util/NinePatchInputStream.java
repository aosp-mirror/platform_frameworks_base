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

package com.android.layoutlib.bridge.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * Simpler wrapper around FileInputStream. This is used when the input stream represent
 * not a normal bitmap but a nine patch.
 * This is useful when the InputStream is created in a method but used in another that needs
 * to know whether this is 9-patch or not, such as BitmapFactory.
 */
public class NinePatchInputStream extends FileInputStream {
    private boolean mFakeMarkSupport = true;
    public NinePatchInputStream(File file) throws FileNotFoundException {
        super(file);
    }

    @Override
    public boolean markSupported() {
        if (mFakeMarkSupport) {
            // this is needed so that BitmapFactory doesn't wrap this in a BufferedInputStream.
            return true;
        }

        return super.markSupported();
    }

    public void disableFakeMarkSupport() {
        // disable fake mark support so that in case codec actually try to use them
        // we don't lie to them.
        mFakeMarkSupport = false;
    }
}
