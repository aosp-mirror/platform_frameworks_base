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
public class GLESCodeEmitter extends JniCodeEmitter {

    PrintStream mJavaImplStream;
    PrintStream mCStream;

    PrintStream mJavaInterfaceStream;

    /**
      */
    public GLESCodeEmitter(String classPathName,
                          ParameterChecker checker,
                          PrintStream javaImplStream,
                          PrintStream cStream) {
        mClassPathName = classPathName;
        mChecker = checker;

        mJavaImplStream = javaImplStream;
        mCStream = cStream;
        mUseContextPointer = false;
        mUseStaticMethods = true;
    }

    public void emitCode(CFunc cfunc, String original) {
        emitCode(cfunc, original, null, mJavaImplStream,
                mCStream);
    }

    public void emitNativeRegistration(String nativeRegistrationName) {
        emitNativeRegistration(nativeRegistrationName, mCStream);
    }
}
