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

package com.android.layoutlib.bridge.android;

import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.RenderParams;
import com.android.ide.common.rendering.api.SessionParams.Key;

/**
 * This contains all known keys for the {@link RenderParams#getFlag(Key)}.
 * <p/>
 * The IDE has its own copy of this class which may be newer or older than this one.
 * <p/>
 * Constants should never be modified or removed from this class.
 */
public final class RenderParamsFlags {

    public static final Key<String> FLAG_KEY_ROOT_TAG =
            new Key<String>("rootTag", String.class);
    public static final Key<Boolean> FLAG_KEY_DISABLE_BITMAP_CACHING =
            new Key<Boolean>("disableBitmapCaching", Boolean.class);
    public static final Key<Boolean> FLAG_KEY_RENDER_ALL_DRAWABLE_STATES =
            new Key<Boolean>("renderAllDrawableStates", Boolean.class);
    /**
     * To tell LayoutLib that the IDE supports RecyclerView.
     * <p/>
     * Default is false.
     */
    public static final Key<Boolean> FLAG_KEY_RECYCLER_VIEW_SUPPORT =
            new Key<Boolean>("recyclerViewSupport", Boolean.class);
    /**
     * The application package name. Used via {@link LayoutlibCallback#getFlag(Key)}
     */
    public static final Key<String> FLAG_KEY_APPLICATION_PACKAGE =
            new Key<String>("applicationPackage", String.class);
    /**
     * To tell LayoutLib that IDE supports providing XML Parser for a file (useful for getting in
     * memory contents of the file). Used via {@link LayoutlibCallback#getFlag(Key)}
     */
    public static final Key<Boolean> FLAG_KEY_XML_FILE_PARSER_SUPPORT =
            new Key<Boolean>("xmlFileParser", Boolean.class);
    /**
     * To tell LayoutLib to not render when creating a new session. This allows controlling when the first
     * layout rendering will happen.
     */
    public static final Key<Boolean> FLAG_DO_NOT_RENDER_ON_CREATE =
            new Key<Boolean>("doNotRenderOnCreate", Boolean.class);

    // Disallow instances.
    private RenderParamsFlags() {}
}
