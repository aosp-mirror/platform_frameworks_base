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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Class that generates a new JAR from a list of classes, some of which are to be kept as-is
 * and some of which are to be stubbed partially or totally.
 */
public class AsmGenerator {

    /** Output logger. */
    private final Log mLog;
    /** The path of the destination JAR to create. */
    private final String mOsDestJar;
    /** List of classes to inject in the final JAR from _this_ archive. */
    private final Class<?>[] mInjectClasses;
    /** The set of methods to stub out. */
    private final Set<String> mStubMethods;
    /** All classes to output as-is, except if they have native methods. */
    private Map<String, ClassReader> mKeep;
    /** All dependencies that must be completely stubbed. */
    private Map<String, ClassReader> mDeps;
    /** All files that are to be copied as-is. */
    private Map<String, InputStream> mCopyFiles;
    /** All classes where certain method calls need to be rewritten. */
    private Set<String> mReplaceMethodCallsClasses;
    /** Counter of number of classes renamed during transform. */
    private int mRenameCount;
    /** FQCN Names of the classes to rename: map old-FQCN => new-FQCN */
    private final HashMap<String, String> mRenameClasses;
    /** FQCN Names of "old" classes that were NOT renamed. This starts with the full list of
     *  old-FQCN to rename and they get erased as they get renamed. At the end, classes still
     *  left here are not in the code base anymore and thus were not renamed. */
    private HashSet<String> mClassesNotRenamed;
    /** A map { FQCN => set { list of return types to delete from the FQCN } }. */
    private HashMap<String, Set<String>> mDeleteReturns;
    /** A map { FQCN => set { method names } } of methods to rewrite as delegates.
     *  The special name {@link DelegateClassAdapter#ALL_NATIVES} can be used as in internal set. */
    private final HashMap<String, Set<String>> mDelegateMethods;
    /** FQCN Names of classes to refactor. All reference to old-FQCN will be updated to new-FQCN.
     * map old-FQCN => new-FQCN */
    private final HashMap<String, String> mRefactorClasses;

    /**
     * Creates a new generator that can generate the output JAR with the stubbed classes.
     *
     * @param log Output logger.
     * @param osDestJar The path of the destination JAR to create.
     * @param createInfo Creation parameters. Must not be null.
     */
    public AsmGenerator(Log log, String osDestJar, ICreateInfo createInfo) {
        mLog = log;
        mOsDestJar = osDestJar;
        mInjectClasses = createInfo.getInjectedClasses();
        mStubMethods = new HashSet<String>(Arrays.asList(createInfo.getOverriddenMethods()));

        // Create the map/set of methods to change to delegates
        mDelegateMethods = new HashMap<String, Set<String>>();
        for (String signature : createInfo.getDelegateMethods()) {
            int pos = signature.indexOf('#');
            if (pos <= 0 || pos >= signature.length() - 1) {
                continue;
            }
            String className = binaryToInternalClassName(signature.substring(0, pos));
            String methodName = signature.substring(pos + 1);
            Set<String> methods = mDelegateMethods.get(className);
            if (methods == null) {
                methods = new HashSet<String>();
                mDelegateMethods.put(className, methods);
            }
            methods.add(methodName);
        }
        for (String className : createInfo.getDelegateClassNatives()) {
            className = binaryToInternalClassName(className);
            Set<String> methods = mDelegateMethods.get(className);
            if (methods == null) {
                methods = new HashSet<String>();
                mDelegateMethods.put(className, methods);
            }
            methods.add(DelegateClassAdapter.ALL_NATIVES);
        }

        // Create the map of classes to rename.
        mRenameClasses = new HashMap<String, String>();
        mClassesNotRenamed = new HashSet<String>();
        String[] renameClasses = createInfo.getRenamedClasses();
        int n = renameClasses.length;
        for (int i = 0; i < n; i += 2) {
            assert i + 1 < n;
            // The ASM class names uses "/" separators, whereas regular FQCN use "."
            String oldFqcn = binaryToInternalClassName(renameClasses[i]);
            String newFqcn = binaryToInternalClassName(renameClasses[i + 1]);
            mRenameClasses.put(oldFqcn, newFqcn);
            mClassesNotRenamed.add(oldFqcn);
        }

        // Create a map of classes to be refactored.
        mRefactorClasses = new HashMap<String, String>();
        String[] refactorClasses = createInfo.getJavaPkgClasses();
        n = refactorClasses.length;
        for (int i = 0; i < n; i += 2) {
            assert i + 1 < n;
            String oldFqcn = binaryToInternalClassName(refactorClasses[i]);
            String newFqcn = binaryToInternalClassName(refactorClasses[i + 1]);
            mRefactorClasses.put(oldFqcn, newFqcn);
        }

        // create the map of renamed class -> return type of method to delete.
        mDeleteReturns = new HashMap<String, Set<String>>();
        String[] deleteReturns = createInfo.getDeleteReturns();
        Set<String> returnTypes = null;
        String renamedClass = null;
        for (String className : deleteReturns) {
            // if we reach the end of a section, add it to the main map
            if (className == null) {
                if (returnTypes != null) {
                    mDeleteReturns.put(renamedClass, returnTypes);
                }

                renamedClass = null;
                continue;
            }

            // if the renamed class is null, this is the beginning of a section
            if (renamedClass == null) {
                renamedClass = binaryToInternalClassName(className);
                continue;
            }

            // just a standard return type, we add it to the list.
            if (returnTypes == null) {
                returnTypes = new HashSet<String>();
            }
            returnTypes.add(binaryToInternalClassName(className));
        }
    }

    /**
     * Returns the list of classes that have not been renamed yet.
     * <p/>
     * The names are "internal class names" rather than FQCN, i.e. they use "/" instead "."
     * as package separators.
     */
    public Set<String> getClassesNotRenamed() {
        return mClassesNotRenamed;
    }

    /**
     * Utility that returns the internal ASM class name from a fully qualified binary class
     * name. E.g. it returns android/view/View from android.view.View.
     */
    String binaryToInternalClassName(String className) {
        if (className == null) {
            return null;
        } else {
            return className.replace('.', '/');
        }
    }

    /** Sets the map of classes to output as-is, except if they have native methods */
    public void setKeep(Map<String, ClassReader> keep) {
        mKeep = keep;
    }

    /** Sets the map of dependencies that must be completely stubbed */
    public void setDeps(Map<String, ClassReader> deps) {
        mDeps = deps;
    }

    /** Sets the map of files to output as-is. */
    public void setCopyFiles(Map<String, InputStream> copyFiles) {
        mCopyFiles = copyFiles;
    }

    public void setRewriteMethodCallClasses(Set<String> rewriteMethodCallClasses) {
        mReplaceMethodCallsClasses = rewriteMethodCallClasses;
    }

    /** Generates the final JAR */
    public void generate() throws IOException {
        TreeMap<String, byte[]> all = new TreeMap<String, byte[]>();

        for (Class<?> clazz : mInjectClasses) {
            String name = classToEntryPath(clazz);
            InputStream is = ClassLoader.getSystemResourceAsStream(name);
            ClassReader cr = new ClassReader(is);
            byte[] b = transform(cr, true);
            name = classNameToEntryPath(transformName(cr.getClassName()));
            all.put(name, b);
        }

        for (Entry<String, ClassReader> entry : mDeps.entrySet()) {
            ClassReader cr = entry.getValue();
            byte[] b = transform(cr, true);
            String name = classNameToEntryPath(transformName(cr.getClassName()));
            all.put(name, b);
        }

        for (Entry<String, ClassReader> entry : mKeep.entrySet()) {
            ClassReader cr = entry.getValue();
            byte[] b = transform(cr, true);
            String name = classNameToEntryPath(transformName(cr.getClassName()));
            all.put(name, b);
        }

        for (Entry<String, InputStream> entry : mCopyFiles.entrySet()) {
            try {
                byte[] b = inputStreamToByteArray(entry.getValue());
                all.put(entry.getKey(), b);
            } catch (IOException e) {
                // Ignore.
            }

        }
        mLog.info("# deps classes: %d", mDeps.size());
        mLog.info("# keep classes: %d", mKeep.size());
        mLog.info("# renamed     : %d", mRenameCount);

        createJar(new FileOutputStream(mOsDestJar), all);
        mLog.info("Created JAR file %s", mOsDestJar);
    }

    /**
     * Writes the JAR file.
     *
     * @param outStream The file output stream were to write the JAR.
     * @param all The map of all classes to output.
     * @throws IOException if an I/O error has occurred
     */
    void createJar(FileOutputStream outStream, Map<String,byte[]> all) throws IOException {
        JarOutputStream jar = new JarOutputStream(outStream);
        for (Entry<String, byte[]> entry : all.entrySet()) {
            String name = entry.getKey();
            JarEntry jar_entry = new JarEntry(name);
            jar.putNextEntry(jar_entry);
            jar.write(entry.getValue());
            jar.closeEntry();
        }
        jar.flush();
        jar.close();
    }

    /**
     * Utility method that converts a fully qualified java name into a JAR entry path
     * e.g. for the input "android.view.View" it returns "android/view/View.class"
     */
    String classNameToEntryPath(String className) {
        return className.replaceAll("\\.", "/").concat(".class");
    }

    /**
     * Utility method to get the JAR entry path from a Class name.
     * e.g. it returns something like "com/foo/OuterClass$InnerClass1$InnerClass2.class"
     */
    private String classToEntryPath(Class<?> clazz) {
        String name = "";
        Class<?> parent;
        while ((parent = clazz.getEnclosingClass()) != null) {
            name = "$" + clazz.getSimpleName() + name;
            clazz = parent;
        }
        return classNameToEntryPath(clazz.getCanonicalName() + name);
    }

    /**
     * Transforms a class.
     * <p/>
     * There are 3 kind of transformations:
     *
     * 1- For "mock" dependencies classes, we want to remove all code from methods and replace
     * by a stub. Native methods must be implemented with this stub too. Abstract methods are
     * left intact. Modified classes must be overridable (non-private, non-final).
     * Native methods must be made non-final, non-private.
     *
     * 2- For "keep" classes, we want to rewrite all native methods as indicated above.
     * If a class has native methods, it must also be made non-private, non-final.
     *
     * Note that unfortunately static methods cannot be changed to non-static (since static and
     * non-static are invoked differently.)
     */
    byte[] transform(ClassReader cr, boolean stubNativesOnly) {

        boolean hasNativeMethods = hasNativeMethods(cr);

        // Get the class name, as an internal name (e.g. com/android/SomeClass$InnerClass)
        String className = cr.getClassName();

        String newName = transformName(className);
        // transformName returns its input argument if there's no need to rename the class
        if (!newName.equals(className)) {
            mRenameCount++;
            // This class is being renamed, so remove it from the list of classes not renamed.
            mClassesNotRenamed.remove(className);
        }

        mLog.debug("Transform %s%s%s%s", className,
                newName.equals(className) ? "" : " (renamed to " + newName + ")",
                hasNativeMethods ? " -- has natives" : "",
                stubNativesOnly ? " -- stub natives only" : "");

        // Rewrite the new class from scratch, without reusing the constant pool from the
        // original class reader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = cw;

        if (mReplaceMethodCallsClasses.contains(className)) {
            cv = new ReplaceMethodCallsAdapter(cv);
        }

        cv = new RefactorClassAdapter(cv, mRefactorClasses);
        if (!newName.equals(className)) {
            cv = new RenameClassAdapter(cv, className, newName);
        }

        cv = new TransformClassAdapter(mLog, mStubMethods, mDeleteReturns.get(className),
                newName, cv, stubNativesOnly);

        Set<String> delegateMethods = mDelegateMethods.get(className);
        if (delegateMethods != null && !delegateMethods.isEmpty()) {
            // If delegateMethods only contains one entry ALL_NATIVES and the class is
            // known to have no native methods, just skip this step.
            if (hasNativeMethods ||
                    !(delegateMethods.size() == 1 &&
                            delegateMethods.contains(DelegateClassAdapter.ALL_NATIVES))) {
                cv = new DelegateClassAdapter(mLog, cv, className, delegateMethods);
            }
        }

        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * Should this class be renamed, this returns the new name. Otherwise it returns the
     * original name.
     *
     * @param className The internal ASM name of the class that may have to be renamed
     * @return A new transformed name or the original input argument.
     */
    String transformName(String className) {
        String newName = mRenameClasses.get(className);
        if (newName != null) {
            return newName;
        }
        int pos = className.indexOf('$');
        if (pos > 0) {
            // Is this an inner class of a renamed class?
            String base = className.substring(0, pos);
            newName = mRenameClasses.get(base);
            if (newName != null) {
                return newName + className.substring(pos);
            }
        }

        return className;
    }

    /**
     * Returns true if a class has any native methods.
     */
    boolean hasNativeMethods(ClassReader cr) {
        ClassHasNativeVisitor cv = new ClassHasNativeVisitor();
        cr.accept(cv, 0);
        return cv.hasNativeMethods();
    }

    private byte[] inputStreamToByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];  // 8KB
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toByteArray();
    }
}
