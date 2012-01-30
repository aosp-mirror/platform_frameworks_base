package junit.runner;

/**
 * The standard test suite loader. It can only load the same class once.
 * {@hide} - Not needed for 1.0 SDK
 */
public class StandardTestSuiteLoader implements TestSuiteLoader {
    /**
     * Uses the system class loader to load the test class
     */
    public Class load(String suiteClassName) throws ClassNotFoundException {
        return Class.forName(suiteClassName);
    }
    /**
     * Uses the system class loader to load the test class
     */
    public Class reload(Class aClass) throws ClassNotFoundException {
        return aClass;
    }
}
