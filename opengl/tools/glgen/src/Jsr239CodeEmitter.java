/*
 * Copyright (C) 2009 The Android Open Source Project
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

/**
 * Emits a Java interface and Java & C implementation for a C function.
 *
 * <p> The Java interface will have Buffer and array variants for functions that
 * have a typed pointer argument.  The array variant will convert a single "<type> *data"
 * argument to a pair of arguments "<type>[] data, int offset".
 */
public class Jsr239CodeEmitter extends JniCodeEmitter implements CodeEmitter {

    PrintStream mJava10InterfaceStream;
    PrintStream mJava10ExtInterfaceStream;
    PrintStream mJava11InterfaceStream;
    PrintStream mJava11ExtInterfaceStream;
    PrintStream mJava11ExtPackInterfaceStream;
    PrintStream mJavaImplStream;
    PrintStream mCStream;

    PrintStream mJavaInterfaceStream;

    /**
     * @param java10InterfaceStream the PrintStream to which to emit the Java interface for GL 1.0 functions
     * @param java10ExtInterfaceStream the PrintStream to which to emit the Java interface for GL 1.0 extension functions
     * @param java11InterfaceStream the PrintStream to which to emit the Java interface for GL 1.1 functions
     * @param java11ExtInterfaceStream the PrintStream to which to emit the Java interface for GL 1.1 Extension functions
     * @param java11ExtPackInterfaceStream the PrintStream to which to emit the Java interface for GL 1.1 Extension Pack functions
     * @param javaImplStream the PrintStream to which to emit the Java implementation
     * @param cStream the PrintStream to which to emit the C implementation
     */
    public Jsr239CodeEmitter(String classPathName,
                          ParameterChecker checker,
                          PrintStream java10InterfaceStream,
                          PrintStream java10ExtInterfaceStream,
                          PrintStream java11InterfaceStream,
                          PrintStream java11ExtInterfaceStream,
                          PrintStream java11ExtPackInterfaceStream,
                          PrintStream javaImplStream,
                          PrintStream cStream,
                          boolean useContextPointer) {
        mClassPathName = classPathName;
        mChecker = checker;
        mJava10InterfaceStream = java10InterfaceStream;
        mJava10ExtInterfaceStream = java10ExtInterfaceStream;
        mJava11InterfaceStream = java11InterfaceStream;
        mJava11ExtInterfaceStream = java11ExtInterfaceStream;
        mJava11ExtPackInterfaceStream = java11ExtPackInterfaceStream;
        mJavaImplStream = javaImplStream;
        mCStream = cStream;
        mUseContextPointer = useContextPointer;
    }

    public void setVersion(int version, boolean ext, boolean pack) {
        if (version == 0) {
            mJavaInterfaceStream = ext ? mJava10ExtInterfaceStream :
                mJava10InterfaceStream;
        } else if (version == 1) {
            mJavaInterfaceStream = ext ?
                (pack ? mJava11ExtPackInterfaceStream :
                 mJava11ExtInterfaceStream) :
                mJava11InterfaceStream;
        } else {
            throw new RuntimeException("Bad version: " + version);
        }
    }

    public void emitCode(CFunc cfunc, String original) {
        emitCode(cfunc, original, mJavaInterfaceStream, mJavaImplStream, mCStream);
    }

    public void emitNativeRegistration() {
        emitNativeRegistration("register_com_google_android_gles_jni_GLImpl", mCStream);
    }
}
