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

package android.renderscript;

import java.io.IOException;
import java.io.InputStream;

import android.content.res.Resources;
import android.content.res.AssetManager;
import android.util.Log;
import android.util.TypedValue;

/**
 * @hide
 *
 **/
public class Font extends BaseObj {

    Font(int id, RenderScript rs) {
        super(id, rs);
    }

    static public Font create(RenderScript rs, Resources res, String fileName, int size)
        throws IllegalArgumentException {

        rs.validate();
        try {
            int dpi = res.getDisplayMetrics().densityDpi;
            int fontId = rs.nFontCreateFromFile(fileName, size, dpi);

            if(fontId == 0) {
                throw new IllegalStateException("Load loading a font");
            }
            Font rsFont = new Font(fontId, rs);

            return rsFont;

        } catch (Exception e) {
            // Ignore
        }

        return null;
    }
}
