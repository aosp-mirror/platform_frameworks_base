/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class JniCodeEmitter {

    static final boolean mUseCPlusPlus = true;
    protected boolean mUseContextPointer = true;
    protected boolean mUseStaticMethods = false;
    protected String mClassPathName;
    protected ParameterChecker mChecker;
    protected List<String> nativeRegistrations = new ArrayList<String>();
    boolean needsExit;
    protected static String indent = "    ";
    HashSet<String> mFunctionsEmitted = new HashSet<String>();

    public static String getJniName(JType jType) {
        String jniName = "";
        if (jType.isClass()) {
            return "L" + jType.getBaseType() + ";";
        } else if (jType.isArray()) {
            jniName = "[";
        }

        String baseType = jType.getBaseType();
        if (baseType.equals("int")) {
            jniName += "I";
        } else if (baseType.equals("float")) {
            jniName += "F";
        } else if (baseType.equals("boolean")) {
            jniName += "Z";
        } else if (baseType.equals("short")) {
            jniName += "S";
        } else if (baseType.equals("long")) {
            jniName += "L";
        } else if (baseType.equals("byte")) {
            jniName += "B";
        } else if (baseType.equals("String")) {
            jniName += "Ljava/lang/String;";
        } else if (baseType.equals("void")) {
            // nothing.
        } else {
            throw new RuntimeException("Unknown primitive basetype " + baseType);
        }
        return jniName;
    }


    public void emitCode(CFunc cfunc, String original,
            PrintStream javaInterfaceStream,
            PrintStream javaImplStream,
            PrintStream cStream) {
        JFunc jfunc;
        String signature;
        boolean duplicate;

        if (cfunc.hasTypedPointerArg()) {
            jfunc = JFunc.convert(cfunc, true);

            // Don't emit duplicate functions
            // These may appear because they are defined in multiple
            // Java interfaces (e.g., GL11/GL11ExtensionPack)
            signature = jfunc.toString();
            duplicate = false;
            if (mFunctionsEmitted.contains(signature)) {
                duplicate = true;
            } else {
                mFunctionsEmitted.add(signature);
            }

            if (!duplicate) {
                emitNativeDeclaration(jfunc, javaImplStream);
                emitJavaCode(jfunc, javaImplStream);
            }
            if (javaInterfaceStream != null) {
                emitJavaInterfaceCode(jfunc, javaInterfaceStream);
            }
            if (!duplicate) {
                emitJniCode(jfunc, cStream);
            }
        }

        jfunc = JFunc.convert(cfunc, false);

        signature = jfunc.toString();
        duplicate = false;
        if (mFunctionsEmitted.contains(signature)) {
            duplicate = true;
        } else {
            mFunctionsEmitted.add(signature);
        }

        if (!duplicate) {
            emitNativeDeclaration(jfunc, javaImplStream);
        }
        if (javaInterfaceStream != null) {
            emitJavaInterfaceCode(jfunc, javaInterfaceStream);
        }
        if (!duplicate) {
            emitJavaCode(jfunc, javaImplStream);
            emitJniCode(jfunc, cStream);
        }
    }

    public void emitNativeDeclaration(JFunc jfunc, PrintStream out) {
        out.println("    // C function " + jfunc.getCFunc().getOriginal());
        out.println();

        emitFunction(jfunc, out, true, false);
    }

    public void emitJavaInterfaceCode(JFunc jfunc, PrintStream out) {
        emitFunction(jfunc, out, false, true);
    }

    public void emitJavaCode(JFunc jfunc, PrintStream out) {
        emitFunction(jfunc, out, false, false);
    }

    boolean isPointerFunc(JFunc jfunc) {
        String name = jfunc.getName();
        return (name.endsWith("Pointer") || name.endsWith("PointerOES"))
            && jfunc.getCFunc().hasPointerArg();
    }

    void emitFunctionCall(JFunc jfunc, PrintStream out, String iii, boolean grabArray) {
        boolean isVoid = jfunc.getType().isVoid();
        boolean isPointerFunc = isPointerFunc(jfunc);

        if (!isVoid) {
            out.println(iii +
                        jfunc.getType() + " _returnValue;");
        }
        out.println(iii +
                    (isVoid ? "" : "_returnValue = ") +
                    jfunc.getName() +
                    (isPointerFunc ? "Bounds" : "" ) +
                    "(");

        int numArgs = jfunc.getNumArgs();
        for (int i = 0; i < numArgs; i++) {
            String argName = jfunc.getArgName(i);
            JType argType = jfunc.getArgType(i);

            if (grabArray && argType.isTypedBuffer()) {
                String typeName = argType.getBaseType();
                typeName = typeName.substring(9, typeName.length() - 6);
                out.println(iii + indent + "get" + typeName + "Array(" + argName + "),");
                out.print(iii + indent + "getOffset(" + argName + ")");
            } else {
                out.print(iii + indent + argName);
            }
            if (i == numArgs - 1) {
                if (isPointerFunc) {
                    out.println(",");
                    out.println(iii + indent + argName + ".remaining()");
                } else {
                    out.println();
                }
            } else {
                out.println(",");
            }
        }

        out.println(iii + ");");
    }

    void printIfcheckPostamble(PrintStream out, boolean isBuffer, boolean emitExceptionCheck,
            String iii) {
                printIfcheckPostamble(out, isBuffer, emitExceptionCheck,
                                      "offset", "_remaining", iii);
            }

    void printIfcheckPostamble(PrintStream out, boolean isBuffer, boolean emitExceptionCheck,
            String offset, String remaining, String iii) {
                out.println(iii + "    default:");
                out.println(iii + "        _needed = 0;");
                out.println(iii + "        break;");
                out.println(iii + "}");

                out.println(iii + "if (" + remaining + " < _needed) {");
                if (emitExceptionCheck) {
                    out.println(iii + indent + "_exception = 1;");
                }
                out.println(iii + indent + "jniThrowException(_env, " +
                        "\"java/lang/IllegalArgumentException\", " +
                        "\"" + (isBuffer ? "remaining()" : "length - " + offset) + " < needed\");");
                out.println(iii + indent + "goto exit;");
                needsExit = true;
                out.println(iii + "}");
            }

    boolean isNullAllowed(CFunc cfunc) {
        String[] checks = mChecker.getChecks(cfunc.getName());
        int index = 1;
        if (checks != null) {
            while (index < checks.length) {
                if (checks[index].equals("return")) {
                    index += 2;
                } else if (checks[index].startsWith("check")) {
                    index += 3;
                } else if (checks[index].equals("ifcheck")) {
                    index += 5;
                } else if (checks[index].equals("unsupported")) {
                    index += 1;
                } else if (checks[index].equals("requires")) {
                    index += 2;
                } else if (checks[index].equals("nullAllowed")) {
                    return true;
                } else {
                    System.out.println("Error: unknown keyword \"" +
                                       checks[index] + "\"");
                    System.exit(0);
                }
            }
        }
        return false;
    }

    String getErrorReturnValue(CFunc cfunc) {
        CType returnType = cfunc.getType();
        boolean isVoid = returnType.isVoid();
        if (isVoid) {
            return null;
        }

        String[] checks = mChecker.getChecks(cfunc.getName());

        int index = 1;
        if (checks != null) {
            while (index < checks.length) {
                if (checks[index].equals("return")) {
                    return checks[index + 1];
                } else if (checks[index].startsWith("check")) {
                    index += 3;
                } else if (checks[index].equals("ifcheck")) {
                    index += 5;
                } else if (checks[index].equals("unsupported")) {
                    index += 1;
                } else if (checks[index].equals("requires")) {
                    index += 2;
                } else if (checks[index].equals("nullAllowed")) {
                    index += 1;
                } else {
                    System.out.println("Error: unknown keyword \"" +
                                       checks[index] + "\"");
                    System.exit(0);
                }
            }
        }

        return null;
    }

    boolean isUnsupportedFunc(CFunc cfunc) {
        String[] checks = mChecker.getChecks(cfunc.getName());
        int index = 1;
        if (checks != null) {
            while (index < checks.length) {
                if (checks[index].equals("unsupported")) {
                    return true;
                } else if (checks[index].equals("requires")) {
                    index += 2;
                } else if (checks[index].equals("return")) {
                    index += 2;
                } else if (checks[index].startsWith("check")) {
                    index += 3;
                } else if (checks[index].equals("ifcheck")) {
                    index += 5;
                } else if (checks[index].equals("nullAllowed")) {
                    index += 1;
                } else {
                    System.out.println("Error: unknown keyword \"" +
                                       checks[index] + "\"");
                    System.exit(0);
                }
            }
        }
        return false;
    }

    String isRequiresFunc(CFunc cfunc) {
        String[] checks = mChecker.getChecks(cfunc.getName());
        int index = 1;
        if (checks != null) {
            while (index < checks.length) {
                if (checks[index].equals("unsupported")) {
                    index += 1;
                } else if (checks[index].equals("requires")) {
                    return checks[index+1];
                } else if (checks[index].equals("return")) {
                    index += 2;
                } else if (checks[index].startsWith("check")) {
                    index += 3;
                } else if (checks[index].equals("ifcheck")) {
                    index += 5;
                } else if (checks[index].equals("nullAllowed")) {
                    index += 1;
                } else {
                    System.out.println("Error: unknown keyword \"" +
                                       checks[index] + "\"");
                    System.exit(0);
                }
            }
        }
        return null;
    }

    void emitNativeBoundsChecks(CFunc cfunc, String cname, PrintStream out,
            boolean isBuffer, boolean emitExceptionCheck, String offset, String remaining, String iii) {

        String[] checks = mChecker.getChecks(cfunc.getName());

        boolean lastWasIfcheck = false;

        int index = 1;
        if (checks != null) {
            while (index < checks.length) {
                if (checks[index].startsWith("check")) {
                    if (lastWasIfcheck) {
                        printIfcheckPostamble(out, isBuffer, emitExceptionCheck,
                                offset, remaining, iii);
                    }
                    lastWasIfcheck = false;
                    if (cname != null && !cname.equals(checks[index + 1])) {
                        index += 3;
                        continue;
                    }
                    out.println(iii + "if (" + remaining + " < " + checks[index + 2] + ") {");
                    if (emitExceptionCheck) {
                        out.println(iii + indent + "_exception = 1;");
                    }
                    String exceptionClassName = "java/lang/IllegalArgumentException";
                    // If the "check" keyword was of the form
                    // "check_<class name>", use the class name in the
                    // exception to be thrown
                    int underscore = checks[index].indexOf('_');
                    if (underscore >= 0) {
                        String abbr = checks[index].substring(underscore + 1);
                        if (abbr.equals("AIOOBE")) {
                            exceptionClassName = "java/lang/ArrayIndexOutOfBoundsException";
                        } else {
                            throw new RuntimeException("unknown exception abbreviation: " + abbr);
                        }
                    }
                    out.println(iii + indent + "jniThrowException(_env, " +
                            "\"" + exceptionClassName + "\", " +
                            "\"" + (isBuffer ? "remaining()" : "length - " + offset) + " < " + checks[index + 2] + "\");");

                    out.println(iii + indent + "goto exit;");
                    needsExit = true;
                    out.println(iii + "}");

                    index += 3;
                } else if (checks[index].equals("ifcheck")) {
                    String[] matches = checks[index + 4].split(",");

                    if (!lastWasIfcheck) {
                        out.println(iii + "int _needed;");
                        out.println(iii + "switch (" + checks[index + 3] + ") {");
                    }

                    for (int i = 0; i < matches.length; i++) {
                        out.println("#if defined(" + matches[i] + ")");
                        out.println(iii + "    case " + matches[i] + ":");
                        out.println("#endif // defined(" + matches[i] + ")");
                    }
                    out.println(iii + "        _needed = " + checks[index + 2] + ";");
                    out.println(iii + "        break;");

                    lastWasIfcheck = true;
                    index += 5;
                } else if (checks[index].equals("return")) {
                    // ignore
                    index += 2;
                } else if (checks[index].equals("unsupported")) {
                    // ignore
                    index += 1;
                } else if (checks[index].equals("requires")) {
                    // ignore
                    index += 2;
                } else if (checks[index].equals("nullAllowed")) {
                    // ignore
                    index += 1;
                } else {
                    System.out.println("Error: unknown keyword \"" + checks[index] + "\"");
                    System.exit(0);
                }
            }
        }

        if (lastWasIfcheck) {
            printIfcheckPostamble(out, isBuffer, emitExceptionCheck, iii);
        }
    }

    boolean hasNonConstArg(JFunc jfunc, CFunc cfunc, List<Integer> nonPrimitiveArgs) {
        if (nonPrimitiveArgs.size() > 0) {
            for (int i = nonPrimitiveArgs.size() - 1; i >= 0; i--) {
                int idx = nonPrimitiveArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);
                if (jfunc.getArgType(idx).isArray()) {
                    if (!cfunc.getArgType(cIndex).isConst()) {
                        return true;
                    }
                } else if (jfunc.getArgType(idx).isBuffer()) {
                    if (!cfunc.getArgType(cIndex).isConst()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Emit a function in several variants:
     *
     * if nativeDecl: public native <returntype> func(args);
     *
     * if !nativeDecl:
     *   if interfaceDecl:  public <returntype> func(args);
     *   if !interfaceDecl: public <returntype> func(args) { body }
     */
    void emitFunction(JFunc jfunc, PrintStream out, boolean nativeDecl, boolean interfaceDecl) {
        boolean isPointerFunc = isPointerFunc(jfunc);

        if (!nativeDecl && !interfaceDecl && !isPointerFunc) {
            // If it's not a pointer function, we've already emitted it
            // with nativeDecl == true
            return;
        }

        String maybeStatic = mUseStaticMethods ? "static " : "";

        if (isPointerFunc) {
            out.println(indent +
                        (nativeDecl ? "private " + maybeStatic +"native " :
                         (interfaceDecl ? "" : "public ") + maybeStatic) +
                        jfunc.getType() + " " +
                        jfunc.getName() +
                        (nativeDecl ? "Bounds" : "") +
                        "(");
        } else {
            out.println(indent +
                        (nativeDecl ? "public " + maybeStatic +"native " :
                         (interfaceDecl ? "" : "public ") + maybeStatic) +
                        jfunc.getType() + " " +
                        jfunc.getName() +
                        "(");
        }

        int numArgs = jfunc.getNumArgs();
        for (int i = 0; i < numArgs; i++) {
            String argName = jfunc.getArgName(i);
            JType argType = jfunc.getArgType(i);

            out.print(indent + indent + argType + " " + argName);
            if (i == numArgs - 1) {
                if (isPointerFunc && nativeDecl) {
                    out.println(",");
                    out.println(indent + indent + "int remaining");
                } else {
                    out.println();
                }
            } else {
                out.println(",");
            }
        }

        if (nativeDecl || interfaceDecl) {
            out.println(indent + ");");
        } else {
            out.println(indent + ") {");

            String iii = indent + indent;

            // emitBoundsChecks(jfunc, out, iii);
            emitFunctionCall(jfunc, out, iii, false);

            // Set the pointer after we call the native code, so that if
            // the native code throws an exception we don't modify the
            // pointer. We assume that the native code is written so that
            // if an exception is thrown, then the underlying glXXXPointer
            // function will not have been called.

            String fname = jfunc.getName();
            if (isPointerFunc) {
                // TODO - deal with VBO variants
                if (fname.equals("glColorPointer")) {
                    out.println(iii + "if ((size == 4) &&");
                    out.println(iii + "    ((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_UNSIGNED_BYTE) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_colorPointer = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glNormalPointer")) {
                    out.println(iii + "if (((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_BYTE) ||");
                    out.println(iii + "     (type == GL_SHORT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_normalPointer = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glTexCoordPointer")) {
                    out.println(iii + "if (((size == 2) ||");
                    out.println(iii + "     (size == 3) ||");
                    out.println(iii + "     (size == 4)) &&");
                    out.println(iii + "    ((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_BYTE) ||");
                    out.println(iii + "     (type == GL_SHORT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_texCoordPointer = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glVertexPointer")) {
                    out.println(iii + "if (((size == 2) ||");
                    out.println(iii + "     (size == 3) ||");
                    out.println(iii + "     (size == 4)) &&");
                    out.println(iii + "    ((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_BYTE) ||");
                    out.println(iii + "     (type == GL_SHORT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_vertexPointer = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glPointSizePointerOES")) {
                    out.println(iii + "if (((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_pointSizePointerOES = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glMatrixIndexPointerOES")) {
                    out.println(iii + "if (((size == 2) ||");
                    out.println(iii + "     (size == 3) ||");
                    out.println(iii + "     (size == 4)) &&");
                    out.println(iii + "    ((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_BYTE) ||");
                    out.println(iii + "     (type == GL_SHORT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_matrixIndexPointerOES = pointer;");
                    out.println(iii + "}");
                } else if (fname.equals("glWeightPointer")) {
                    out.println(iii + "if (((size == 2) ||");
                    out.println(iii + "     (size == 3) ||");
                    out.println(iii + "     (size == 4)) &&");
                    out.println(iii + "    ((type == GL_FLOAT) ||");
                    out.println(iii + "     (type == GL_BYTE) ||");
                    out.println(iii + "     (type == GL_SHORT) ||");
                    out.println(iii + "     (type == GL_FIXED)) &&");
                    out.println(iii + "    (stride >= 0)) {");
                    out.println(iii + indent + "_weightPointerOES = pointer;");
                    out.println(iii + "}");
                }
            }

            boolean isVoid = jfunc.getType().isVoid();

            if (!isVoid) {
                out.println(indent + indent + "return _returnValue;");
            }
            out.println(indent + "}");
        }
        out.println();
    }

    public void addNativeRegistration(String s) {
        nativeRegistrations.add(s);
    }

    public void emitNativeRegistration(String registrationFunctionName,
            PrintStream cStream) {
        cStream.println("static const char *classPathName = \"" +
                        mClassPathName +
                        "\";");
        cStream.println();

        cStream.println("static JNINativeMethod methods[] = {");

        cStream.println("{\"_nativeClassInit\", \"()V\", (void*)nativeClassInit },");

        Iterator<String> i = nativeRegistrations.iterator();
        while (i.hasNext()) {
            cStream.println(i.next());
        }

        cStream.println("};");
        cStream.println();


        cStream.println("int " + registrationFunctionName + "(JNIEnv *_env)");
        cStream.println("{");
        cStream.println(indent +
                        "int err;");

        cStream.println(indent +
                        "err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));");

        cStream.println(indent + "return err;");
        cStream.println("}");
    }

    public JniCodeEmitter() {
        super();
    }

    String getJniType(JType jType) {
        if (jType.isVoid()) {
            return "void";
        }

        String baseType = jType.getBaseType();
        if (jType.isPrimitive()) {
            if (baseType.equals("String")) {
                return "jstring";
            } else {
                return "j" + baseType;
            }
        } else if (jType.isArray()) {
            return "j" + baseType + "Array";
        } else {
            return "jobject";
        }
    }

    String getJniMangledName(String name) {
        name = name.replaceAll("_", "_1");
        name = name.replaceAll(";", "_2");
        name = name.replaceAll("\\[", "_3");
        return name;
    }

    public void emitJniCode(JFunc jfunc, PrintStream out) {
        CFunc cfunc = jfunc.getCFunc();

        // Emit comment identifying original C function
        //
        // Example:
        //
        // /* void glClipPlanef ( GLenum plane, const GLfloat *equation ) */
        //
        out.println("/* " + cfunc.getOriginal() + " */");

        // Emit JNI signature (name)
        //
        // Example:
        //
        // void
        // android_glClipPlanef__I_3FI
        //

        String outName = "android_" + jfunc.getName();
        boolean isPointerFunc = isPointerFunc(jfunc);
        boolean isVBOPointerFunc = (outName.endsWith("Pointer") ||
                outName.endsWith("PointerOES") ||
            outName.endsWith("DrawElements") || outName.endsWith("VertexAttribPointer")) &&
            !jfunc.getCFunc().hasPointerArg();
        if (isPointerFunc) {
            outName += "Bounds";
        }

        out.print("static ");
        out.println(getJniType(jfunc.getType()));
        out.print(outName);

        String rsignature = getJniName(jfunc.getType());

        String signature = "";
        int numArgs = jfunc.getNumArgs();
        for (int i = 0; i < numArgs; i++) {
            JType argType = jfunc.getArgType(i);
            signature += getJniName(argType);
        }
        if (isPointerFunc) {
            signature += "I";
        }

        // Append signature to function name
        String sig = getJniMangledName(signature).replace('.', '_').replace('/', '_');
        out.print("__" + sig);
        outName += "__" + sig;

        signature = signature.replace('.', '/');
        rsignature = rsignature.replace('.', '/');

        out.println();
        if (rsignature.length() == 0) {
            rsignature = "V";
        }

        String s = "{\"" +
            jfunc.getName() +
            (isPointerFunc ? "Bounds" : "") +
            "\", \"(" + signature +")" +
            rsignature +
            "\", (void *) " +
            outName +
            " },";
        nativeRegistrations.add(s);

        List<Integer> nonPrimitiveArgs = new ArrayList<Integer>();
        List<Integer> stringArgs = new ArrayList<Integer>();
        int numBufferArgs = 0;
        List<String> bufferArgNames = new ArrayList<String>();

        // Emit JNI signature (arguments)
        //
        // Example:
        //
        // (JNIEnv *_env, jobject this, jint plane, jfloatArray equation_ref, jint offset) {
        //
        out.print("  (JNIEnv *_env, jobject _this");
        for (int i = 0; i < numArgs; i++) {
            out.print(", ");
            JType argType = jfunc.getArgType(i);
            String suffix;
            if (!argType.isPrimitive()) {
                if (argType.isArray()) {
                    suffix = "_ref";
                } else {
                    suffix = "_buf";
                }
                nonPrimitiveArgs.add(new Integer(i));
                if (jfunc.getArgType(i).isBuffer()) {
                    int cIndex = jfunc.getArgCIndex(i);
                    String cname = cfunc.getArgName(cIndex);
                    bufferArgNames.add(cname);
                    numBufferArgs++;
                }
            } else {
                suffix = "";
            }
            if (argType.isString()) {
                stringArgs.add(new Integer(i));
            }

            out.print(getJniType(argType) + " " + jfunc.getArgName(i) + suffix);
        }
        if (isPointerFunc) {
            out.print(", jint remaining");
        }
        out.println(") {");

        int numArrays = 0;
        int numBuffers = 0;
        int numStrings = 0;
        for (int i = 0; i < nonPrimitiveArgs.size(); i++) {
            int idx = nonPrimitiveArgs.get(i).intValue();
            JType argType = jfunc.getArgType(idx);
            if (argType.isArray()) {
                ++numArrays;
            }
            if (argType.isBuffer()) {
                ++numBuffers;
            }
            if (argType.isString()) {
                ++numStrings;
            }
        }

        // Emit method body

        // Emit local variable declarations for _exception and _returnValue
        //
        // Example:
        //
        // android::gl::ogles_context_t *ctx;
        //
        // jint _exception;
        // GLenum _returnValue;
        //
        CType returnType = cfunc.getType();
        boolean isVoid = returnType.isVoid();

        boolean isUnsupported = isUnsupportedFunc(cfunc);
        if (isUnsupported) {
            out.println(indent +
                        "jniThrowException(_env, \"java/lang/UnsupportedOperationException\",");
            out.println(indent +
                        "    \"" + cfunc.getName() + "\");");
            if (!isVoid) {
                String retval = getErrorReturnValue(cfunc);
                out.println(indent + "return " + retval + ";");
            }
            out.println("}");
            out.println();
            return;
        }

        String requiresExtension = isRequiresFunc(cfunc);
        if (requiresExtension != null) {
            out.println(indent +
                        "if (! supportsExtension(_env, _this, have_" + requiresExtension + "ID)) {");
            out.println(indent + indent +
                        "jniThrowException(_env, \"java/lang/UnsupportedOperationException\",");
            out.println(indent + indent +
                        "    \"" + cfunc.getName() + "\");");
            if (isVoid) {
                out.println(indent + indent + "    return;");
            } else {
                String retval = getErrorReturnValue(cfunc);
                out.println(indent + indent + "    return " + retval + ";");
            }
            out.println(indent + "}");
        }
        if (mUseContextPointer) {
            out.println(indent +
                "android::gl::ogles_context_t *ctx = getContext(_env, _this);");
        }

        boolean initializeReturnValue = stringArgs.size() > 0;

        boolean emitExceptionCheck = (numArrays > 0 || numBuffers > 0 || numStrings > 0) &&
            hasNonConstArg(jfunc, cfunc, nonPrimitiveArgs);
        // mChecker.getChecks(cfunc.getName()) != null

        // Emit an _exeption variable if there will be error checks
        if (emitExceptionCheck) {
            out.println(indent + "jint _exception = 0;");
        }

        // Emit a single _array or multiple _XXXArray variables
        if (numBufferArgs == 1) {
                out.println(indent + "jarray _array = (jarray) 0;");
        } else {
            for (int i = 0; i < numBufferArgs; i++) {
                out.println(indent + "jarray _" + bufferArgNames.get(i) +
                            "Array = (jarray) 0;");
            }
        }
        if (!isVoid) {
            String retval = getErrorReturnValue(cfunc);
            if (retval != null) {
                out.println(indent + returnType.getDeclaration() +
                            " _returnValue = " + retval + ";");
            } else if (initializeReturnValue) {
                out.println(indent + returnType.getDeclaration() +
                " _returnValue = 0;");
            } else {
                out.println(indent + returnType.getDeclaration() +
                            " _returnValue;");
            }
        }

        // Emit local variable declarations for pointer arguments
        //
        // Example:
        //
        // GLfixed *eqn_base;
        // GLfixed *eqn;
        //
        String offset = "offset";
        String remaining = "_remaining";
        if (nonPrimitiveArgs.size() > 0) {
            for (int i = 0; i < nonPrimitiveArgs.size(); i++) {
                int idx = nonPrimitiveArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);
                String cname = cfunc.getArgName(cIndex);

                CType type = cfunc.getArgType(jfunc.getArgCIndex(idx));
                String decl = type.getDeclaration();
                if (jfunc.getArgType(idx).isArray()) {
                    out.println(indent +
                                decl +
                                (decl.endsWith("*") ? "" : " ") +
                                jfunc.getArgName(idx) +
                                "_base = (" + decl + ") 0;");
                }
                remaining = ((numArrays + numBuffers) <= 1) ? "_remaining" :
                    "_" + cname + "Remaining";
                out.println(indent +
                            "jint " + remaining + ";");
                out.println(indent +
                            decl +
                            (decl.endsWith("*") ? "" : " ") +
                            jfunc.getArgName(idx) +
                            " = (" + decl + ") 0;");
            }

            out.println();
        }

        // Emit local variable declaration for strings
        if (stringArgs.size() > 0) {
            for (int i = 0; i < stringArgs.size(); i++) {
                int idx = stringArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);
                String cname = cfunc.getArgName(cIndex);

                out.println(indent + "const char* _native" + cname + " = 0;");
            }

            out.println();
        }

        // Null pointer checks and GetStringUTFChars
        if (stringArgs.size() > 0) {
            for (int i = 0; i < stringArgs.size(); i++) {
                int idx = stringArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);
                String cname = cfunc.getArgName(cIndex);

                CType type = cfunc.getArgType(jfunc.getArgCIndex(idx));
                String decl = type.getDeclaration();
                out.println(indent + "if (!" + cname + ") {");
                out.println(indent + "    jniThrowException(_env, " +
                        "\"java/lang/IllegalArgumentException\", \"" + cname + " == null\");");
                out.println(indent + "    goto exit;");
                needsExit = true;
                out.println(indent + "}");

                out.println(indent + "_native" + cname + " = _env->GetStringUTFChars(" + cname + ", 0);");
            }

            out.println();
        }

        // Emit 'GetPrimitiveArrayCritical' for arrays
        // Emit 'GetPointer' calls for Buffer pointers
        int bufArgIdx = 0;
        if (nonPrimitiveArgs.size() > 0) {
            for (int i = 0; i < nonPrimitiveArgs.size(); i++) {
                int idx = nonPrimitiveArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);

                String cname = cfunc.getArgName(cIndex);
                offset = numArrays <= 1 ? "offset" :
                    cname + "Offset";
                remaining = ((numArrays + numBuffers) <= 1) ? "_remaining" :
                    "_" + cname + "Remaining";

                if (jfunc.getArgType(idx).isArray()) {
                    out.println(indent +
                                "if (!" +
                                cname +
                                "_ref) {");
                    if (emitExceptionCheck) {
                        out.println(indent + indent + "_exception = 1;");
                    }
                    out.println(indent + "    jniThrowException(_env, " +
                            "\"java/lang/IllegalArgumentException\", " +
                            "\"" + cname + " == null\");");
                    out.println(indent + "    goto exit;");
                    needsExit = true;
                    out.println(indent + "}");

                    out.println(indent + "if (" + offset + " < 0) {");
                    if (emitExceptionCheck) {
                        out.println(indent + indent + "_exception = 1;");
                    }
                    out.println(indent + "    jniThrowException(_env, " +
                            "\"java/lang/IllegalArgumentException\", \"" + offset + " < 0\");");
                    out.println(indent + "    goto exit;");
                    needsExit = true;
                    out.println(indent + "}");

                    out.println(indent + remaining + " = " +
                                    (mUseCPlusPlus ? "_env" : "(*_env)") +
                                    "->GetArrayLength(" +
                                    (mUseCPlusPlus ? "" : "_env, ") +
                                    cname + "_ref) - " + offset + ";");

                    emitNativeBoundsChecks(cfunc, cname, out, false,
                                           emitExceptionCheck,
                                           offset, remaining, "    ");

                    out.println(indent +
                                cname +
                                "_base = (" +
                                cfunc.getArgType(cIndex).getDeclaration() +
                                ")");
                    out.println(indent + "    " +
                                (mUseCPlusPlus ? "_env" : "(*_env)") +
                                "->GetPrimitiveArrayCritical(" +
                                (mUseCPlusPlus ? "" : "_env, ") +
                                jfunc.getArgName(idx) +
                                "_ref, (jboolean *)0);");
                    out.println(indent +
                                cname + " = " + cname + "_base + " + offset +
                                ";");
                    out.println();
                } else {
                    String array = numBufferArgs <= 1 ? "_array" :
                        "_" + bufferArgNames.get(bufArgIdx++) + "Array";

                    boolean nullAllowed = isNullAllowed(cfunc) || isPointerFunc;
                    if (nullAllowed) {
                        out.println(indent + "if (" + cname + "_buf) {");
                        out.print(indent);
                    }

                    if (isPointerFunc) {
                        out.println(indent +
                                cname +
                                " = (" +
                                cfunc.getArgType(cIndex).getDeclaration() +
                                ") getDirectBufferPointer(_env, " +
                                cname + "_buf);");
                        String iii = "    ";
                        out.println(iii + indent + "if ( ! " + cname + " ) {");
                        out.println(iii + iii + indent + "return;");
                        out.println(iii + indent + "}");
                    } else {
                        out.println(indent +
                                    cname +
                                    " = (" +
                                    cfunc.getArgType(cIndex).getDeclaration() +
                                    ")getPointer(_env, " +
                                    cname +
                                    "_buf, &" + array + ", &" + remaining +
                                    ");");
                    }

                    emitNativeBoundsChecks(cfunc, cname, out, true,
                                           emitExceptionCheck,
                                           offset, remaining, nullAllowed ? "        " : "    ");

                    if (nullAllowed) {
                        out.println(indent + "}");
                    }
                }
            }
        }

        if (!isVoid) {
            out.print(indent + "_returnValue = ");
        } else {
            out.print(indent);
        }
        String name = cfunc.getName();

        if (mUseContextPointer) {
            name = name.substring(2, name.length()); // Strip off 'gl' prefix
            name = name.substring(0, 1).toLowerCase() +
                name.substring(1, name.length());
            out.print("ctx->procs.");
        }

        out.print(name + (isPointerFunc ? "Bounds" : "") + "(");

        numArgs = cfunc.getNumArgs();
        if (numArgs == 0) {
            if (mUseContextPointer) {
                out.println("ctx);");
            } else {
                out.println(");");
            }
        } else {
            if (mUseContextPointer) {
                out.println("ctx,");
            } else {
                out.println();
            }
            for (int i = 0; i < numArgs; i++) {
                String typecast;
                if (i == numArgs - 1 && isVBOPointerFunc) {
                    typecast = "const GLvoid *";
                } else {
                    typecast = cfunc.getArgType(i).getDeclaration();
                }
                out.print(indent + indent +
                          "(" +
                          typecast +
                          ")");
                if (cfunc.getArgType(i).isConstCharPointer()) {
                    out.print("_native");
                }
                out.print(cfunc.getArgName(i));

                if (i == numArgs - 1) {
                    if (isPointerFunc) {
                        out.println(",");
                        out.println(indent + indent + "(GLsizei)remaining");
                    } else {
                        out.println();
                    }
                } else {
                    out.println(",");
                }
            }
            out.println(indent + ");");
        }

        if (needsExit) {
            out.println();
            out.println("exit:");
            needsExit = false;
        }

        bufArgIdx = 0;
        if (nonPrimitiveArgs.size() > 0) {
            for (int i = nonPrimitiveArgs.size() - 1; i >= 0; i--) {
                int idx = nonPrimitiveArgs.get(i).intValue();

                int cIndex = jfunc.getArgCIndex(idx);
                if (jfunc.getArgType(idx).isArray()) {

                    // If the argument is 'const', GL will not write to it.
                    // In this case, we can use the 'JNI_ABORT' flag to avoid
                    // the need to write back to the Java array
                    out.println(indent +
                                "if (" + jfunc.getArgName(idx) + "_base) {");
                    out.println(indent + indent +
                                (mUseCPlusPlus ? "_env" : "(*_env)") +
                                "->ReleasePrimitiveArrayCritical(" +
                                (mUseCPlusPlus ? "" : "_env, ") +
                                jfunc.getArgName(idx) + "_ref, " +
                                cfunc.getArgName(cIndex) +
                                "_base,");
                    out.println(indent + indent + indent +
                                (cfunc.getArgType(cIndex).isConst() ?
                                 "JNI_ABORT" :
                                 "_exception ? JNI_ABORT: 0") +
                                ");");
                    out.println(indent + "}");
                } else if (jfunc.getArgType(idx).isBuffer()) {
                    if (! isPointerFunc) {
                        String array = numBufferArgs <= 1 ? "_array" :
                            "_" + bufferArgNames.get(bufArgIdx++) + "Array";
                        out.println(indent + "if (" + array + ") {");
                        out.println(indent + indent +
                                    "releasePointer(_env, " + array + ", " +
                                    cfunc.getArgName(cIndex) +
                                    ", " +
                                    (cfunc.getArgType(cIndex).isConst() ?
                                     "JNI_FALSE" : "_exception ? JNI_FALSE :" +
                                             " JNI_TRUE") +
                                    ");");
                        out.println(indent + "}");
                    }
                }
            }
        }

        // Emit local variable declaration for strings
        if (stringArgs.size() > 0) {
            for (int i = 0; i < stringArgs.size(); i++) {
                int idx = stringArgs.get(i).intValue();
                int cIndex = jfunc.getArgCIndex(idx);
                String cname = cfunc.getArgName(cIndex);

                out.println(indent + "if (_native" + cname + ") {");
                out.println(indent + "    _env->ReleaseStringUTFChars(" + cname + ", _native" + cname + ");");
                out.println(indent + "}");
            }

            out.println();
        }


        if (!isVoid) {
            out.println(indent + "return _returnValue;");
        }

        out.println("}");
        out.println();
    }

}
