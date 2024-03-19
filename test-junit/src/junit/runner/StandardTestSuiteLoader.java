package junit.runner;

// android-changed - class not present in upstream JUnit 4.10
// added here to retain BaseTestRunner.getLoader API

/**
 * The standard test suite loader. It can only load the same class once.
 * {@hide}
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
