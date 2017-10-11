/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.util.Map;
import java.util.Set;

/**
 * Interface describing the work to be done by {@link AsmGenerator}.
 */
public interface ICreateInfo {

    /**
     * Returns the list of class from layoutlib_create to inject in layoutlib.
     * The list can be empty but must not be null.
     */
    Class<?>[] getInjectedClasses();

    /**
     * Returns the list of methods to rewrite as delegates.
     * The list can be empty but must not be null.
     */
    String[] getDelegateMethods();

    /**
     * Returns the list of classes on which to delegate all native methods.
     * The list can be empty but must not be null.
     */
    String[] getDelegateClassNatives();

    /**
     * Returns the list of classes to rename, must be an even list: the binary FQCN
     * of class to replace followed by the new FQCN.
     * The list can be empty but must not be null.
     */
    String[] getRenamedClasses();

    /**
     * List of classes to refactor. This is similar to combining {@link #getRenamedClasses()} and
     * {@link #getJavaPkgClasses()}.
     * Classes included here will be renamed and then all their references in any other classes
     * will be also modified.
     * FQCN of class to refactor followed by its new FQCN.
     */
    String[] getRefactoredClasses();

    /**
     * Returns the list of classes for which the methods returning them should be deleted.
     * The array contains a list of null terminated section starting with the name of the class
     * to rename in which the methods are deleted, followed by a list of return types identifying
     * the methods to delete.
     * The list can be empty but must not be null.
     */
    String[] getDeleteReturns();

    /**
     * Returns the list of classes to refactor, must be an even list: the
     * binary FQCN of class to replace followed by the new FQCN. All references
     * to the old class should be updated to the new class.
     * The list can be empty but must not be null.
     */
    String[] getJavaPkgClasses();

    Set<String> getExcludedClasses();

    /**
     * Returns a list of fields which should be promoted to public visibility. The array values
     * are in the form of the binary FQCN of the class containing the field and the field name
     * separated by a '#'.
     */
    String[] getPromotedFields();

    /**
     * Returns a list of classes to be promoted to public visibility.
     */
    String[] getPromotedClasses();

    /**
     * Returns a map from binary FQCN className to {@link InjectMethodRunnable} which will be
     * called to inject methods into a class.
     * Can be empty but must not be null.
     */
    Map<String, InjectMethodRunnable> getInjectedMethodsMap();

    abstract class InjectMethodRunnable {
        /**
         * @param cv Must be {@link ClassVisitor}. However, the param type is object so that when
         * loading the class, ClassVisitor is not loaded. This is because when injecting
         * CreateInfo in LayoutLib (see {@link #getInjectedClasses()}, we don't want to inject
         * asm classes also, but still keep CreateInfo loadable.
         */
        public abstract void generateMethods(Object cv);
    }
}
