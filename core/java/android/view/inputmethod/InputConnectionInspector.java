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

package android.view.inputmethod;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * @hide
 */
public final class InputConnectionInspector {

    @Retention(SOURCE)
    @IntDef({MissingMethodFlags.GET_SELECTED_TEXT,
            MissingMethodFlags.SET_COMPOSING_REGION,
            MissingMethodFlags.COMMIT_CORRECTION,
            MissingMethodFlags.REQUEST_CURSOR_UPDATES,
            MissingMethodFlags.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
            MissingMethodFlags.GET_HANDLER,
            MissingMethodFlags.CLOSE_CONNECTION,
            MissingMethodFlags.COMMIT_CONTENT,
    })
    public @interface MissingMethodFlags {
        /**
         * {@link InputConnection#getSelectedText(int)} is available in
         * {@link android.os.Build.VERSION_CODES#GINGERBREAD} and later.
         */
        int GET_SELECTED_TEXT = 1 << 0;
        /**
         * {@link InputConnection#setComposingRegion(int, int)} is available in
         * {@link android.os.Build.VERSION_CODES#GINGERBREAD} and later.
         */
        int SET_COMPOSING_REGION = 1 << 1;
        /**
         * {@link InputConnection#commitCorrection(CorrectionInfo)} is available in
         * {@link android.os.Build.VERSION_CODES#HONEYCOMB} and later.
         */
        int COMMIT_CORRECTION = 1 << 2;
        /**
         * {@link InputConnection#requestCursorUpdates(int)} is available in
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP} and later.
         */
        int REQUEST_CURSOR_UPDATES = 1 << 3;
        /**
         * {@link InputConnection#deleteSurroundingTextInCodePoints(int, int)}} is available in
         * {@link android.os.Build.VERSION_CODES#N} and later.
         */
        int DELETE_SURROUNDING_TEXT_IN_CODE_POINTS = 1 << 4;
        /**
         * {@link InputConnection#deleteSurroundingTextInCodePoints(int, int)}} is available in
         * {@link android.os.Build.VERSION_CODES#N} and later.
         */
        int GET_HANDLER = 1 << 5;
        /**
         * {@link InputConnection#closeConnection()}} is available in
         * {@link android.os.Build.VERSION_CODES#N} and later.
         */
        int CLOSE_CONNECTION = 1 << 6;
        /**
         * {@link InputConnection#commitContent(InputContentInfo, int, Bundle)} is available in
         * {@link android.os.Build.VERSION_CODES#N} MR-1 and later.
         */
        int COMMIT_CONTENT = 1 << 7;
    }

    private static final Map<Class, Integer> sMissingMethodsMap = Collections.synchronizedMap(
            new WeakHashMap<>());

    @MissingMethodFlags
    public static int getMissingMethodFlags(@Nullable final InputConnection ic) {
        if (ic == null) {
            return 0;
        }
        // Optimization for a known class.
        if (ic instanceof BaseInputConnection) {
            return 0;
        }
        // Optimization for a known class.
        if (ic instanceof InputConnectionWrapper) {
            return ((InputConnectionWrapper) ic).getMissingMethodFlags();
        }
        return getMissingMethodFlagsInternal(ic.getClass());
    }

    @MissingMethodFlags
    public static int getMissingMethodFlagsInternal(@NonNull final Class clazz) {
        final Integer cachedFlags = sMissingMethodsMap.get(clazz);
        if (cachedFlags != null) {
            return cachedFlags;
        }
        int flags = 0;
        if (!hasGetSelectedText(clazz)) {
            flags |= MissingMethodFlags.GET_SELECTED_TEXT;
        }
        if (!hasSetComposingRegion(clazz)) {
            flags |= MissingMethodFlags.SET_COMPOSING_REGION;
        }
        if (!hasCommitCorrection(clazz)) {
            flags |= MissingMethodFlags.COMMIT_CORRECTION;
        }
        if (!hasRequestCursorUpdate(clazz)) {
            flags |= MissingMethodFlags.REQUEST_CURSOR_UPDATES;
        }
        if (!hasDeleteSurroundingTextInCodePoints(clazz)) {
            flags |= MissingMethodFlags.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS;
        }
        if (!hasGetHandler(clazz)) {
            flags |= MissingMethodFlags.GET_HANDLER;
        }
        if (!hasCloseConnection(clazz)) {
            flags |= MissingMethodFlags.CLOSE_CONNECTION;
        }
        if (!hasCommitContent(clazz)) {
            flags |= MissingMethodFlags.COMMIT_CONTENT;
        }
        sMissingMethodsMap.put(clazz, flags);
        return flags;
    }

    private static boolean hasGetSelectedText(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("getSelectedText", int.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasSetComposingRegion(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("setComposingRegion", int.class, int.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasCommitCorrection(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("commitCorrection", CorrectionInfo.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasRequestCursorUpdate(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("requestCursorUpdates", int.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasDeleteSurroundingTextInCodePoints(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("deleteSurroundingTextInCodePoints", int.class,
                    int.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasGetHandler(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("getHandler");
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasCloseConnection(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("closeConnection");
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasCommitContent(@NonNull final Class clazz) {
        try {
            final Method method = clazz.getMethod("commitContent", InputContentInfo.class,
                    int.class, Bundle.class);
            return !Modifier.isAbstract(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static String getMissingMethodFlagsAsString(@MissingMethodFlags final int flags) {
        final StringBuilder sb = new StringBuilder();
        boolean isEmpty = true;
        if ((flags & MissingMethodFlags.GET_SELECTED_TEXT) != 0) {
            sb.append("getSelectedText(int)");
            isEmpty = false;
        }
        if ((flags & MissingMethodFlags.SET_COMPOSING_REGION) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("setComposingRegion(int, int)");
            isEmpty = false;
        }
        if ((flags & MissingMethodFlags.COMMIT_CORRECTION) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("commitCorrection(CorrectionInfo)");
            isEmpty = false;
        }
        if ((flags & MissingMethodFlags.REQUEST_CURSOR_UPDATES) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("requestCursorUpdate(int)");
            isEmpty = false;
        }
        if ((flags & MissingMethodFlags.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("deleteSurroundingTextInCodePoints(int, int)");
            isEmpty = false;
        }
        if ((flags & MissingMethodFlags.GET_HANDLER) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("getHandler()");
        }
        if ((flags & MissingMethodFlags.CLOSE_CONNECTION) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("closeConnection()");
        }
        if ((flags & MissingMethodFlags.COMMIT_CONTENT) != 0) {
            if (!isEmpty) {
                sb.append(",");
            }
            sb.append("commitContent(InputContentInfo, Bundle)");
        }
        return sb.toString();
    }
}
