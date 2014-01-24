/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.res;

import com.android.layoutlib.bridge.impl.RenderSessionImpl;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.res.Resources.NotFoundException;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;
import android.util.TypedValue;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Resources$Theme}
 *
 * Through the layoutlib_create tool, the original  methods of Theme have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class Resources_Theme_Delegate {

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int[] attrs) {
        return RenderSessionImpl.getCurrentContext().obtainStyledAttributes(attrs);
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int resid, int[] attrs)
            throws NotFoundException {
        return RenderSessionImpl.getCurrentContext().obtainStyledAttributes(resid, attrs);
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        return RenderSessionImpl.getCurrentContext().obtainStyledAttributes(
                set, attrs, defStyleAttr, defStyleRes);
    }

    @LayoutlibDelegate
    /*package*/ static boolean resolveAttribute(
            Resources thisResources, Theme thisTheme,
            int resid, TypedValue outValue,
            boolean resolveRefs) {
        return RenderSessionImpl.getCurrentContext().resolveThemeAttribute(
                resid, outValue, resolveRefs);
    }
}
