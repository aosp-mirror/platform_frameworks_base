
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;

public class GenerateGLES {

    static void copy(String filename, PrintStream out) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String s;
        while ((s = br.readLine()) != null) {
            out.println(s);
        }
    }

    private static void emit(GLESCodeEmitter emitter,
                             BufferedReader specReader,
                             PrintStream glStream,
                             PrintStream cStream) throws Exception {
        String s = null;
        while ((s = specReader.readLine()) != null) {
            if (s.trim().startsWith("//")) {
                continue;
            }

            CFunc cfunc = CFunc.parseCFunc(s);

            String fname = cfunc.getName();
            String stubRoot = "stubs/gles11/" + fname;
            String javaPath = stubRoot + ".java";
            File f = new File(javaPath);
            if (f.exists()) {
                System.out.println("Special-casing function " + fname);
                copy(javaPath, glStream);
                copy(stubRoot + ".cpp", cStream);

                // Register native function names
                // This should be improved to require fewer discrete files
                String filename = stubRoot + ".nativeReg";
                BufferedReader br =
                    new BufferedReader(new FileReader(filename));
                String nfunc;
                while ((nfunc = br.readLine()) != null) {
                    emitter.addNativeRegistration(nfunc);
                }
            } else {
                emitter.emitCode(cfunc, s);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int aidx = 0;
        while ((aidx < args.length) && (args[aidx].charAt(0) == '-')) {
            switch (args[aidx].charAt(1)) {
            default:
                System.err.println("Unknown flag: " + args[aidx]);
                System.exit(1);
            }

            aidx++;
        }

        BufferedReader checksReader =
            new BufferedReader(new FileReader("specs/gles11/checks.spec"));
        ParameterChecker checker = new ParameterChecker(checksReader);

        // Generate files
        for(String suffix: new String[] {"GLES10", "GLES10Ext",
                "GLES11", "GLES11Ext"})
        {
            BufferedReader spec11Reader =
                new BufferedReader(new FileReader("specs/gles11/"
                        + suffix + ".spec"));
            String gl11Filename = "android/opengl/" + suffix + ".java";
            String gl11cFilename = "android_opengl_" + suffix + ".cpp";
            PrintStream gl11Stream =
                new PrintStream(new FileOutputStream("out/" + gl11Filename));
            PrintStream gl11cStream =
                new PrintStream(new FileOutputStream("out/" + gl11cFilename));
            gl11Stream.println("/*");
            gl11cStream.println("/*");
            copy("stubs/gles11/" + suffix + "Header.java-if", gl11Stream);
            copy("stubs/gles11/" + suffix + "cHeader.cpp", gl11cStream);
            GLESCodeEmitter emitter = new GLESCodeEmitter(
                    "android/opengl/" + suffix,
                    checker, gl11Stream, gl11cStream);
            emit(emitter, spec11Reader, gl11Stream, gl11cStream);
            emitter.emitNativeRegistration("register_android_opengl_jni_"
                    + suffix);
            gl11Stream.println("}");
            gl11Stream.close();
            gl11cStream.close();
        }
    }
}
