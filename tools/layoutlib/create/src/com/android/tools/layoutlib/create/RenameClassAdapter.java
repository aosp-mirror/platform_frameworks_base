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

package com.android.tools.layoutlib.create;

import org.objectweb.asm.ClassVisitor;

/**
 * This class visitor renames a class from a given old name to a given new name.
 * The class visitor will also rename all inner classes and references in the methods.
 * <p/>
 *
 * For inner classes, this handles only the case where the outer class name changes.
 * The inner class name should remain the same.
 */
public class RenameClassAdapter extends AbstractClassAdapter {


    private final String mOldName;
    private final String mNewName;
    private String mOldBase;
    private String mNewBase;

    /**
     * Creates a class visitor that renames a class from a given old name to a given new name.
     * The class visitor will also rename all inner classes and references in the methods.
     * The names must be full qualified internal ASM names (e.g. com/blah/MyClass$InnerClass).
     */
    public RenameClassAdapter(ClassVisitor cv, String oldName, String newName) {
        super(cv);
        mOldBase = mOldName = oldName;
        mNewBase = mNewName = newName;

        int pos = mOldName.indexOf('$');
        if (pos > 0) {
            mOldBase = mOldName.substring(0, pos);
        }
        pos = mNewName.indexOf('$');
        if (pos > 0) {
            mNewBase = mNewName.substring(0, pos);
        }

        assert (mOldBase == null && mNewBase == null) || (mOldBase != null && mNewBase != null);
    }

    /**
     * Renames an internal type name, e.g. "com.package.MyClass".
     * If the type doesn't need to be renamed, returns the input string as-is.
     * <p/>
     * The internal type of some of the MethodVisitor turns out to be a type
       descriptor sometimes so descriptors are renamed too.
     */
    @Override
    protected String renameInternalType(String type) {
        if (type == null) {
            return null;
        }

        if (type.equals(mOldName)) {
            return mNewName;
        }

        if (mOldBase != mOldName && type.equals(mOldBase)) {
            return mNewBase;
        }

        int pos = type.indexOf('$');
        if (pos == mOldBase.length() && type.startsWith(mOldBase)) {
            return mNewBase + type.substring(pos);
        }
        return type;
    }

}
