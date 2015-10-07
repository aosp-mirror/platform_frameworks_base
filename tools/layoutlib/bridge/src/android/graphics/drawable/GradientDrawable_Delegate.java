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

package android.graphics.drawable;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Path;
import android.graphics.drawable.GradientDrawable.GradientState;

import java.lang.reflect.Field;

/**
 * Delegate implementing the native methods of {@link GradientDrawable}
 *
 * Through the layoutlib_create tool, the original native methods of GradientDrawable have been
 * replaced by calls to methods of the same name in this delegate class.
 */
public class GradientDrawable_Delegate {

    /**
     * The ring can be built either by drawing full circles, or by drawing arcs in case the
     * circle isn't complete. LayoutLib cannot handle drawing full circles (requires path
     * subtraction). So, if we need to draw full circles, we switch to drawing 99% circle.
     */
    @LayoutlibDelegate
    /*package*/ static Path buildRing(GradientDrawable thisDrawable, GradientState st) {
        boolean useLevel = st.mUseLevelForShape;
        int level = thisDrawable.getLevel();
        // 10000 is the max level. See android.graphics.drawable.Drawable#getLevel()
        float sweep = useLevel ? (360.0f * level / 10000.0f) : 360f;
        Field mLevel = null;
        if (sweep >= 360 || sweep <= -360) {
            st.mUseLevelForShape = true;
            // Use reflection to set the value of the field to prevent setting the drawable to
            // dirty again.
            try {
                mLevel = Drawable.class.getDeclaredField("mLevel");
                mLevel.setAccessible(true);
                mLevel.setInt(thisDrawable, 9999);  // set to one less than max.
            } catch (NoSuchFieldException e) {
                // The field has been removed in a recent framework change. Fall back to old
                // buggy behaviour.
            } catch (IllegalAccessException e) {
                // We've already set the field to be accessible.
                assert false;
            }
        }
        Path path = thisDrawable.buildRing_Original(st);
        st.mUseLevelForShape = useLevel;
        if (mLevel != null) {
            try {
                mLevel.setInt(thisDrawable, level);
            } catch (IllegalAccessException e) {
                assert false;
            }
        }
        return path;
    }
}
