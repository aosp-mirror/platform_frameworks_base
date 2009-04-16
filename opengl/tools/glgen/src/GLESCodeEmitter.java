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
