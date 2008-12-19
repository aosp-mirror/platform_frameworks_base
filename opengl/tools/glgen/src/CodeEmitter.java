
public interface CodeEmitter {

    void setVersion(int version, boolean ext, boolean pack);
    void emitCode(CFunc cfunc, String original);
    void addNativeRegistration(String fname);
    void emitNativeRegistration();
}
