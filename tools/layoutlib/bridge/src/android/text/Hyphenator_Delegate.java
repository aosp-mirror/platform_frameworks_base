/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Delegate that overrides implementation for certain methods in {@link android.text.Hyphenator}
 * <p/>
 * Through the layoutlib_create tool, selected methods of Hyphenator have been replaced
 * by calls to methods of the same name in this delegate class.
 */
public class Hyphenator_Delegate {

    private static final DelegateManager<Hyphenator_Delegate> sDelegateManager = new
            DelegateManager<Hyphenator_Delegate>(Hyphenator_Delegate.class);

    @LayoutlibDelegate
    /*package*/ static File getSystemHyphenatorLocation() {
        // FIXME
        return null;
    }

    /*package*/ @SuppressWarnings("UnusedParameters")  // TODO implement this.
    static long loadHyphenator(ByteBuffer buffer, int offset, int minPrefix, int minSuffix) {
        return sDelegateManager.addNewDelegate(new Hyphenator_Delegate());
    }
}
