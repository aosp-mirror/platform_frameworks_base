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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;

public class GenerateGL {

    static void copy(String filename, PrintStream out) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String s;
        while ((s = br.readLine()) != null) {
            out.println(s);
        }
    }

    private static void emit(int version, boolean ext, boolean pack,
                             CodeEmitter emitter,
                             BufferedReader specReader,
                             PrintStream glStream,
                             PrintStream glImplStream,
                             PrintStream cStream) throws Exception {
        String s = null;
        while ((s = specReader.readLine()) != null) {
            if (s.trim().startsWith("//")) {
                continue;
            }

            CFunc cfunc = CFunc.parseCFunc(s);

            String fname = cfunc.getName();
            File f = new File("stubs/jsr239/" + fname +
                              ".java-1" + version + "-if");
            if (f.exists()) {
                System.out.println("Special-casing function " + fname);
                copy("stubs/jsr239/" + fname +
                     ".java-1" + version + "-if", glStream);
                copy("stubs/jsr239/" + fname + ".java-impl", glImplStream);
                copy("stubs/jsr239/" + fname + ".cpp", cStream);

                // Register native function names
                // This should be improved to require fewer discrete files
                String filename = "stubs/jsr239/" + fname + ".nativeReg";
                BufferedReader br =
                    new BufferedReader(new FileReader(filename));
                String nfunc;
                while ((nfunc = br.readLine()) != null) {
                    emitter.addNativeRegistration(nfunc);
                }
            } else {
                emitter.setVersion(version, ext, pack);
                emitter.emitCode(cfunc, s);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String classPathName = "com/google/android/gles_jni/GLImpl";
        boolean useContextPointer = true;

        int aidx = 0;
        while (args[aidx].charAt(0) == '-') {
            switch (args[aidx].charAt(1)) {
            case 'c':
                useContextPointer = false;
                break;

            default:
                System.err.println("Unknown flag: " + args[aidx]);
                System.exit(1);
            }

            aidx++;
        }

        System.out.println("useContextPointer = " + useContextPointer);

        BufferedReader spec10Reader =
            new BufferedReader(new FileReader(args[aidx++]));
        BufferedReader spec10ExtReader =
            new BufferedReader(new FileReader(args[aidx++]));
        BufferedReader spec11Reader =
            new BufferedReader(new FileReader(args[aidx++]));
        BufferedReader spec11ExtReader =
            new BufferedReader(new FileReader(args[aidx++]));
        BufferedReader spec11ExtPackReader =
            new BufferedReader(new FileReader(args[aidx++]));
        BufferedReader checksReader =
            new BufferedReader(new FileReader(args[aidx++]));

        String gl10Filename = "javax/microedition/khronos/opengles/GL10.java";
        String gl10ExtFilename =
            "javax/microedition/khronos/opengles/GL10Ext.java";
        String gl11Filename = "javax/microedition/khronos/opengles/GL11.java";
        String gl11ExtFilename =
            "javax/microedition/khronos/opengles/GL11Ext.java";
        String gl11ExtPackFilename =
            "javax/microedition/khronos/opengles/GL11ExtensionPack.java";
        String glImplFilename = "com/google/android/gles_jni/GLImpl.java";
        String cFilename = "com_google_android_gles_jni_GLImpl.cpp";

        PrintStream gl10Stream =
            new PrintStream(new FileOutputStream("out/" + gl10Filename));
        PrintStream gl10ExtStream =
            new PrintStream(new FileOutputStream("out/" + gl10ExtFilename));
        PrintStream gl11Stream =
            new PrintStream(new FileOutputStream("out/" + gl11Filename));
        PrintStream gl11ExtStream =
            new PrintStream(new FileOutputStream("out/" + gl11ExtFilename));
        PrintStream gl11ExtPackStream =
            new PrintStream(new FileOutputStream("out/" + gl11ExtPackFilename));
        PrintStream glImplStream =
            new PrintStream(new FileOutputStream("out/" + glImplFilename));
        PrintStream cStream =
            new PrintStream(new FileOutputStream("out/" + cFilename));

        ParameterChecker checker = new ParameterChecker(checksReader);

        CodeEmitter emitter =
            new Jsr239CodeEmitter(classPathName,
                               checker,
                               gl10Stream, gl10ExtStream,
                               gl11Stream, gl11ExtStream, gl11ExtPackStream,
                               glImplStream, cStream,
                               useContextPointer);

        gl10Stream.println("/* //device/java/android/" + gl10Filename);
        gl10ExtStream.println("/* //device/java/android/" + gl10ExtFilename);
        gl11Stream.println("/* //device/java/android/" + gl11Filename);
        gl11ExtStream.println("/* //device/java/android/" + gl11ExtFilename);
        gl11ExtPackStream.println("/* //device/java/android/" +
            gl11ExtPackFilename);
        glImplStream.println("/* //device/java/android/" + glImplFilename);
        cStream.println("/* //device/libs/android_runtime/" + cFilename);

        copy("stubs/jsr239/GL10Header.java-if", gl10Stream);
        copy("stubs/jsr239/GL10ExtHeader.java-if", gl10ExtStream);
        copy("stubs/jsr239/GL11Header.java-if", gl11Stream);
        copy("stubs/jsr239/GL11ExtHeader.java-if", gl11ExtStream);
        copy("stubs/jsr239/GL11ExtensionPackHeader.java-if", gl11ExtPackStream);
        copy("stubs/jsr239/GLImplHeader.java-impl", glImplStream);
        copy("stubs/jsr239/GLCHeader.cpp", cStream);

        emit(0, false, false,
             emitter, spec10Reader, gl10Stream, glImplStream, cStream);
        emit(0, true, false,
             emitter, spec10ExtReader, gl10ExtStream, glImplStream, cStream);
        emit(1, false, false,
             emitter, spec11Reader, gl11Stream, glImplStream, cStream);
        emit(1, true, false,
             emitter, spec11ExtReader, gl11ExtStream, glImplStream, cStream);
        emit(1, true, true,
             emitter, spec11ExtPackReader, gl11ExtPackStream, glImplStream,
             cStream);

        emitter.emitNativeRegistration();

        gl10Stream.println("}");
        gl10ExtStream.println("}");
        gl11Stream.println("}");
        gl11ExtStream.println("}");
        gl11ExtPackStream.println("}");
        glImplStream.println("}");
    }
}
