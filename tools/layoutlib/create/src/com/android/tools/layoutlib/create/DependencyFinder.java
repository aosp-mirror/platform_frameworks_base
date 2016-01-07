/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.tools.layoutlib.annotations.VisibleForTesting;
import com.android.tools.layoutlib.annotations.VisibleForTesting.Visibility;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Analyzes the input JAR using the ASM java bytecode manipulation library
 * to list the classes and their dependencies. A "dependency" is a class
 * used by another class.
 */
public class DependencyFinder {

    // Note: a bunch of stuff has package-level access for unit tests. Consider it private.

    /** Output logger. */
    private final Log mLog;

    /**
     * Creates a new analyzer.
     *
     * @param log The log output.
     */
    public DependencyFinder(Log log) {
        mLog = log;
    }

    /**
     * Starts the analysis using parameters from the constructor.
     *
     * @param osJarPath The input source JARs to parse.
     * @return A pair: [0]: map { class FQCN => set of FQCN class dependencies }.
     *                 [1]: map { missing class FQCN => set of FQCN class that uses it. }
     */
    public List<Map<String, Set<String>>> findDeps(List<String> osJarPath) throws IOException {

        Map<String, ClassReader> zipClasses = parseZip(osJarPath);
        mLog.info("Found %d classes in input JAR%s.",
                zipClasses.size(),
                osJarPath.size() > 1 ? "s" : "");

        Map<String, Set<String>> deps = findClassesDeps(zipClasses);

        Map<String, Set<String>> missing = findMissingClasses(deps, zipClasses.keySet());

        List<Map<String, Set<String>>> result = new ArrayList<>(2);
        result.add(deps);
        result.add(missing);
        return result;
    }

    /**
     * Prints dependencies to the current logger, found stuff and missing stuff.
     */
    public void printAllDeps(List<Map<String, Set<String>>> result) {
        assert result.size() == 2;
        Map<String, Set<String>> deps = result.get(0);
        Map<String, Set<String>> missing = result.get(1);

        // Print all dependences found in the format:
        // +Found: <FQCN from zip>
        //     uses: FQCN

        mLog.info("++++++ %d Entries found in source JARs", deps.size());
        mLog.info("");

        for (Entry<String, Set<String>> entry : deps.entrySet()) {
            mLog.info(    "+Found  : %s", entry.getKey());
            for (String dep : entry.getValue()) {
                mLog.info("    uses: %s", dep);
            }

            mLog.info("");
        }


        // Now print all missing dependences in the format:
        // -Missing <FQCN>:
        //     used by: <FQCN>

        mLog.info("");
        mLog.info("------ %d Entries missing from source JARs", missing.size());
        mLog.info("");

        for (Entry<String, Set<String>> entry : missing.entrySet()) {
            mLog.info(    "-Missing  : %s", entry.getKey());
            for (String dep : entry.getValue()) {
                mLog.info("   used by: %s", dep);
            }

            mLog.info("");
        }
    }

    /**
     * Prints only a summary of the missing dependencies to the current logger.
     */
    public void printMissingDeps(List<Map<String, Set<String>>> result) {
        assert result.size() == 2;
        @SuppressWarnings("unused") Map<String, Set<String>> deps = result.get(0);
        Map<String, Set<String>> missing = result.get(1);

        for (String fqcn : missing.keySet()) {
            mLog.info("%s", fqcn);
        }
    }

    // ----------------

    /**
     * Parses a JAR file and returns a list of all classes founds using a map
     * class name => ASM ClassReader. Class names are in the form "android.view.View".
     */
    Map<String,ClassReader> parseZip(List<String> jarPathList) throws IOException {
        TreeMap<String, ClassReader> classes = new TreeMap<>();

        for (String jarPath : jarPathList) {
            ZipFile zip = new ZipFile(jarPath);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry entry;
            while (entries.hasMoreElements()) {
                entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    ClassReader cr = new ClassReader(zip.getInputStream(entry));
                    String className = classReaderToClassName(cr);
                    classes.put(className, cr);
                }
            }
        }

        return classes;
    }

    /**
     * Utility that returns the fully qualified binary class name for a ClassReader.
     * E.g. it returns something like android.view.View.
     */
    static String classReaderToClassName(ClassReader classReader) {
        if (classReader == null) {
            return null;
        } else {
            return classReader.getClassName().replace('/', '.');
        }
    }

    /**
     * Utility that returns the fully qualified binary class name from a path-like FQCN.
     * E.g. it returns android.view.View from android/view/View.
     */
    static String internalToBinaryClassName(String className) {
        if (className == null) {
            return null;
        } else {
            return className.replace('/', '.');
        }
    }

    /**
     * Finds all dependencies for all classes in keepClasses which are also
     * listed in zipClasses. Returns a map of all the dependencies found.
     */
    Map<String, Set<String>> findClassesDeps(Map<String, ClassReader> zipClasses) {

        // The dependencies that we'll collect.
        // It's a map Class name => uses class names.
        Map<String, Set<String>> dependencyMap = new TreeMap<>();

        DependencyVisitor visitor = getVisitor();

        int count = 0;
        try {
            for (Entry<String, ClassReader> entry : zipClasses.entrySet()) {
                String name = entry.getKey();

                TreeSet<String> set = new TreeSet<>();
                dependencyMap.put(name, set);
                visitor.setDependencySet(set);

                ClassReader cr = entry.getValue();
                cr.accept(visitor, 0 /* flags */);

                visitor.setDependencySet(null);

                mLog.debugNoln("Visited %d classes\r", ++count);
            }
        } finally {
            mLog.debugNoln("\n");
        }

        return dependencyMap;
    }

    /**
     * Computes which classes FQCN were found as dependencies that are NOT listed
     * in the original JAR classes.
     *
     * @param deps The map { FQCN => dependencies[] } returned by {@link #findClassesDeps(Map)}.
     * @param zipClasses The set of all classes FQCN found in the JAR files.
     * @return A map { FQCN not found in the zipClasses => classes using it }
     */
    private Map<String, Set<String>> findMissingClasses(
            Map<String, Set<String>> deps,
            Set<String> zipClasses) {
        Map<String, Set<String>> missing = new TreeMap<>();

        for (Entry<String, Set<String>> entry : deps.entrySet()) {
            String name = entry.getKey();

            for (String dep : entry.getValue()) {
                if (!zipClasses.contains(dep)) {
                    // This dependency doesn't exist in the zip classes.
                    Set<String> set = missing.get(dep);
                    if (set == null) {
                        set = new TreeSet<>();
                        missing.put(dep, set);
                    }
                    set.add(name);
                }
            }

        }

        return missing;
    }


    // ----------------------------------

    /**
     * Instantiates a new DependencyVisitor. Useful for unit tests.
     */
    @VisibleForTesting(visibility=Visibility.PRIVATE)
    DependencyVisitor getVisitor() {
        return new DependencyVisitor();
    }

    /**
     * Visitor to collect all the type dependencies from a class.
     */
    public class DependencyVisitor extends ClassVisitor {

        private Set<String> mCurrentDepSet;

        /**
         * Creates a new visitor that will find all the dependencies for the visited class.
         */
        public DependencyVisitor() {
            super(Main.ASM_VERSION);
        }

        /**
         * Sets the {@link Set} where to record direct dependencies for this class.
         * This will change before each {@link ClassReader#accept(ClassVisitor, int)} call.
         */
        public void setDependencySet(Set<String> set) {
            mCurrentDepSet = set;
        }

        /**
         * Considers the given class name as a dependency.
         */
        public void considerName(String className) {
            if (className == null) {
                return;
            }

            className = internalToBinaryClassName(className);

            try {
                // exclude classes that are part of the default JRE (the one executing this program)
                // or in java package (we won't be able to load them anyway).
                if (className.startsWith("java.") ||
                        getClass().getClassLoader().loadClass(className) != null) {
                    return;
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }

            // Add it to the dependency set for the currently visited class, as needed.
            assert mCurrentDepSet != null;
            mCurrentDepSet.add(className);
        }

        /**
         * Considers this array of names using considerName().
         */
        public void considerNames(String[] classNames) {
            if (classNames != null) {
                for (String className : classNames) {
                    considerName(className);
                }
            }
        }

        /**
         * Considers this signature or type signature by invoking the {@link SignatureVisitor}
         * on it.
         */
        public void considerSignature(String signature) {
            if (signature != null) {
                SignatureReader sr = new SignatureReader(signature);
                // SignatureReader.accept will call accessType so we don't really have
                // to differentiate where the signature comes from.
                sr.accept(new MySignatureVisitor());
            }
        }

        /**
         * Considers this {@link Type}. For arrays, the element type is considered.
         * If the type is an object, it's internal name is considered.
         */
        public void considerType(Type t) {
            if (t != null) {
                if (t.getSort() == Type.ARRAY) {
                    t = t.getElementType();
                }
                if (t.getSort() == Type.OBJECT) {
                    considerName(t.getInternalName());
                }
            }
        }

        /**
         * Considers a descriptor string. The descriptor is converted to a {@link Type}
         * and then considerType() is invoked.
         */
        public boolean considerDesc(String desc) {
            if (desc != null) {
                try {
                    if (desc.length() > 0 && desc.charAt(0) == '(') {
                        // This is a method descriptor with arguments and a return type.
                        Type t = Type.getReturnType(desc);
                        considerType(t);

                        for (Type arg : Type.getArgumentTypes(desc)) {
                            considerType(arg);
                        }

                    } else {
                        Type t = Type.getType(desc);
                        considerType(t);
                    }
                    return true;
                } catch (ArrayIndexOutOfBoundsException e) {
                    // ignore, not a valid type.
                }
            }
            return false;
        }


        // ---------------------------------------------------
        // --- ClassVisitor, FieldVisitor
        // ---------------------------------------------------

        // Visits a class header
        @Override
        public void visit(int version, int access, String name,
                String signature, String superName, String[] interfaces) {
            // signature is the signature of this class. May be null if the class is not a generic
            // one, and does not extend or implement generic classes or interfaces.

            if (signature != null) {
                considerSignature(signature);
            }

            // superName is the internal of name of the super class (see getInternalName).
            // For interfaces, the super class is Object. May be null but only for the Object class.
            considerName(superName);

            // interfaces is the internal names of the class's interfaces (see getInternalName).
            // May be null.
            considerNames(interfaces);
        }


        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            // desc is the class descriptor of the annotation class.
            considerDesc(desc);
            return new MyAnnotationVisitor();
        }

        @Override
        public void visitAttribute(Attribute attr) {
            // pass
        }

        // Visits the end of a class
        @Override
        public void visitEnd() {
            // pass
        }

        private class MyFieldVisitor extends FieldVisitor {

            public MyFieldVisitor() {
                super(Main.ASM_VERSION);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                // desc is the class descriptor of the annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitAttribute(Attribute attr) {
                // pass
            }

            // Visits the end of a class
            @Override
            public void visitEnd() {
                // pass
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                String signature, Object value) {
            // desc is the field's descriptor (see Type).
            considerDesc(desc);

            // signature is the field's signature. May be null if the field's type does not use
            // generic types.
            considerSignature(signature);

            return new MyFieldVisitor();
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // name is the internal name of an inner class (see getInternalName).
            // Note: outerName/innerName seems to be null when we're reading the
            // _Original_ClassName classes generated by layoutlib_create.
            if (outerName != null) {
                considerName(name);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
            // desc is the method's descriptor (see Type).
            considerDesc(desc);
            // signature is the method's signature. May be null if the method parameters, return
            // type and exceptions do not use generic types.
            considerSignature(signature);

            return new MyMethodVisitor();
        }

        @Override
        public void visitOuterClass(String owner, String name, String desc) {
            // pass
        }

        @Override
        public void visitSource(String source, String debug) {
            // pass
        }


        // ---------------------------------------------------
        // --- MethodVisitor
        // ---------------------------------------------------

        private class MyMethodVisitor extends MethodVisitor {

            public MyMethodVisitor() {
                super(Main.ASM_VERSION);
            }


            @Override
            public AnnotationVisitor visitAnnotationDefault() {
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitCode() {
                // pass
            }

            // field instruction
            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                // owner is the class that declares the field.
                considerName(owner);
                // desc is the field's descriptor (see Type).
                considerDesc(desc);
            }

            @Override
            public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
                // pass
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                // pass -- an IINC instruction
            }

            @Override
            public void visitInsn(int opcode) {
                // pass -- a zero operand instruction
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                // pass -- a single int operand instruction
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                // pass -- a jump instruction
            }

            @Override
            public void visitLabel(Label label) {
                // pass -- a label target
            }

            // instruction to load a constant from the stack
            @Override
            public void visitLdcInsn(Object cst) {
                if (cst instanceof Type) {
                    considerType((Type) cst);
                }
            }

            @Override
            public void visitLineNumber(int line, Label start) {
                // pass
            }

            @Override
            public void visitLocalVariable(String name, String desc,
                    String signature, Label start, Label end, int index) {
                // desc is the type descriptor of this local variable.
                considerDesc(desc);
                // signature is the type signature of this local variable. May be null if the local
                // variable type does not use generic types.
                considerSignature(signature);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                // pass -- a lookup switch instruction
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                // pass
            }

            // instruction that invokes a method
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc,
                    boolean itf) {

                // owner is the internal name of the method's owner class
                if (!considerDesc(owner) && owner.indexOf('/') != -1) {
                    considerName(owner);
                }
                // desc is the method's descriptor (see Type).
                considerDesc(desc);
            }

            // instruction multianewarray, whatever that is
            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {

                // desc an array type descriptor.
                considerDesc(desc);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
                    boolean visible) {
                // desc is the class descriptor of the annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
                // pass -- table switch instruction

            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                // type is the internal name of the type of exceptions handled by the handler,
                // or null to catch any exceptions (for "finally" blocks).
                considerName(type);
            }

            // type instruction
            @Override
            public void visitTypeInsn(int opcode, String type) {
                // type is the operand of the instruction to be visited. This operand must be the
                // internal name of an object or array class.
                considerName(type);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                // pass -- local variable instruction
            }
        }

        private class MySignatureVisitor extends SignatureVisitor {

            public MySignatureVisitor() {
                super(Main.ASM_VERSION);
            }

            // ---------------------------------------------------
            // --- SignatureVisitor
            // ---------------------------------------------------

            private String mCurrentSignatureClass = null;

            // Starts the visit of a signature corresponding to a class or interface type
            @Override
            public void visitClassType(String name) {
                mCurrentSignatureClass = name;
                considerName(name);
            }

            // Visits an inner class
            @Override
            public void visitInnerClassType(String name) {
                if (mCurrentSignatureClass != null) {
                    mCurrentSignatureClass += "$" + name;
                    considerName(mCurrentSignatureClass);
                }
            }

            @Override
            public SignatureVisitor visitArrayType() {
                return new MySignatureVisitor();
            }

            @Override
            public void visitBaseType(char descriptor) {
                // pass -- a primitive type, ignored
            }

            @Override
            public SignatureVisitor visitClassBound() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitExceptionType() {
                return new MySignatureVisitor();
            }

            @Override
            public void visitFormalTypeParameter(String name) {
                // pass
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitParameterType() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitSuperclass() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return new MySignatureVisitor();
            }

            @Override
            public void visitTypeVariable(String name) {
                // pass
            }

            @Override
            public void visitTypeArgument() {
                // pass
            }
        }


        // ---------------------------------------------------
        // --- AnnotationVisitor
        // ---------------------------------------------------

        private class MyAnnotationVisitor extends AnnotationVisitor {

            public MyAnnotationVisitor() {
                super(Main.ASM_VERSION);
            }

            // Visits a primitive value of an annotation
            @Override
            public void visit(String name, Object value) {
                // value is the actual value, whose type must be Byte, Boolean, Character, Short,
                // Integer, Long, Float, Double, String or Type
                if (value instanceof Type) {
                    considerType((Type) value);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                // desc is the class descriptor of the nested annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitEnum(String name, String desc, String value) {
                // desc is the class descriptor of the enumeration class.
                considerDesc(desc);
            }
        }
    }
}
