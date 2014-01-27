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

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class RenameClassAdapterTest {

    private RenameClassAdapter mOuter;
    private RenameClassAdapter mInner;

    @Before
    public void setUp() throws Exception {
        mOuter = new RenameClassAdapter(null, // cv
                                         "com.pack.Old",
                                         "org.blah.New");

        mInner = new RenameClassAdapter(null, // cv
                                         "com.pack.Old$Inner",
                                         "org.blah.New$Inner");
    }

    @After
    public void tearDown() throws Exception {
    }

    /**
     * Renames a type, e.g. "Lcom.package.My;"
     * If the type doesn't need to be renamed, returns the input string as-is.
     */
    @Test
    public void testRenameTypeDesc() {

        // primitive types are left untouched
        assertEquals("I", mOuter.renameTypeDesc("I"));
        assertEquals("D", mOuter.renameTypeDesc("D"));
        assertEquals("V", mOuter.renameTypeDesc("V"));

        // object types that need no renaming are left untouched
        assertEquals("Lcom.package.MyClass;", mOuter.renameTypeDesc("Lcom.package.MyClass;"));
        assertEquals("Lcom.package.MyClass;", mInner.renameTypeDesc("Lcom.package.MyClass;"));

        // object types that match the requirements
        assertEquals("Lorg.blah.New;", mOuter.renameTypeDesc("Lcom.pack.Old;"));
        assertEquals("Lorg.blah.New$Inner;", mInner.renameTypeDesc("Lcom.pack.Old$Inner;"));
        // inner classes match the base type which is being renamed
        assertEquals("Lorg.blah.New$Other;", mOuter.renameTypeDesc("Lcom.pack.Old$Other;"));
        assertEquals("Lorg.blah.New$Other;", mInner.renameTypeDesc("Lcom.pack.Old$Other;"));

        // arrays
        assertEquals("[Lorg.blah.New;",  mOuter.renameTypeDesc("[Lcom.pack.Old;"));
        assertEquals("[[Lorg.blah.New;", mOuter.renameTypeDesc("[[Lcom.pack.Old;"));

        assertEquals("[Lorg.blah.New;",  mInner.renameTypeDesc("[Lcom.pack.Old;"));
        assertEquals("[[Lorg.blah.New;", mInner.renameTypeDesc("[[Lcom.pack.Old;"));
    }

    /**
     * Renames an object type, e.g. "Lcom.package.MyClass;" or an array type that has an
     * object element, e.g. "[Lcom.package.MyClass;"
     * If the type doesn't need to be renamed, returns the internal name of the input type.
     */
    @Test
    public void testRenameType() {
        // Skip. This is actually tested by testRenameTypeDesc above.
    }

    /**
     * Renames an internal type name, e.g. "com.package.MyClass".
     * If the type doesn't need to be renamed, returns the input string as-is.
     */
    @Test
    public void testRenameInternalType() {
        // an actual FQCN
        assertEquals("org.blah.New", mOuter.renameInternalType("com.pack.Old"));
        assertEquals("org.blah.New$Inner", mOuter.renameInternalType("com.pack.Old$Inner"));

        assertEquals("org.blah.New$Other", mInner.renameInternalType("com.pack.Old$Other"));
        assertEquals("org.blah.New$Other", mInner.renameInternalType("com.pack.Old$Other"));
    }

    /**
     * Renames a method descriptor, i.e. applies renameType to all arguments and to the
     * return value.
     */
    @Test
    public void testRenameMethodDesc() {
        assertEquals("(IDLorg.blah.New;[Lorg.blah.New$Inner;)Lorg.blah.New$Other;",
               mOuter.renameMethodDesc("(IDLcom.pack.Old;[Lcom.pack.Old$Inner;)Lcom.pack.Old$Other;"));
    }



}
