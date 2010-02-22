package junit.runner;

/**
 * An implementation of a TestCollector that considers
 * a class to be a test class when it contains the
 * pattern "Test" in its name
 * @see TestCollector
 * {@hide} - Not needed for 1.0 SDK
 */
public class SimpleTestCollector extends ClassPathTestCollector {
	
	public SimpleTestCollector() {
	}
	
	protected boolean isTestClass(String classFileName) {
		return 
			classFileName.endsWith(".class") && 
			classFileName.indexOf('$') < 0 &&
			classFileName.indexOf("Test") > 0;
	}
}
