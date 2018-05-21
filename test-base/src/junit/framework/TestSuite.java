package junit.framework;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;

/**
 * <p>A <code>TestSuite</code> is a <code>Composite</code> of Tests.
 * It runs a collection of test cases. Here is an example using
 * the dynamic test definition.
 * <pre>
 * TestSuite suite= new TestSuite();
 * suite.addTest(new MathTest("testAdd"));
 * suite.addTest(new MathTest("testDivideByZero"));
 * </pre>
 * </p>
 * 
 * <p>Alternatively, a TestSuite can extract the tests to be run automatically.
 * To do so you pass the class of your TestCase class to the
 * TestSuite constructor.
 * <pre>
 * TestSuite suite= new TestSuite(MathTest.class);
 * </pre>
 * </p>
 * 
 * <p>This constructor creates a suite with all the methods
 * starting with "test" that take no arguments.</p>
 * 
 * <p>A final option is to do the same for a large array of test classes.
 * <pre>
 * Class[] testClasses = { MathTest.class, AnotherTest.class }
 * TestSuite suite= new TestSuite(testClasses);
 * </pre>
 * </p>
 *
 * @see Test
 */
public class TestSuite implements Test {

	/**
	 * ...as the moon sets over the early morning Merlin, Oregon
	 * mountains, our intrepid adventurers type...
	 */
	static public Test createTest(Class<?> theClass, String name) {
		Constructor<?> constructor;
		try {
			constructor= getTestConstructor(theClass);
		} catch (NoSuchMethodException e) {
			return warning("Class "+theClass.getName()+" has no public constructor TestCase(String name) or TestCase()");
		}
		Object test;
		try {
			if (constructor.getParameterTypes().length == 0) {
				test= constructor.newInstance(new Object[0]);
				if (test instanceof TestCase)
					((TestCase) test).setName(name);
			} else {
				test= constructor.newInstance(new Object[]{name});
			}
		} catch (InstantiationException e) {
			return(warning("Cannot instantiate test case: "+name+" ("+exceptionToString(e)+")"));
		} catch (InvocationTargetException e) {
			return(warning("Exception in constructor: "+name+" ("+exceptionToString(e.getTargetException())+")"));
		} catch (IllegalAccessException e) {
			return(warning("Cannot access test case: "+name+" ("+exceptionToString(e)+")"));
		}
		return (Test) test;
	}
	
	/**
	 * Gets a constructor which takes a single String as
	 * its argument or a no arg constructor.
	 */
	public static Constructor<?> getTestConstructor(Class<?> theClass) throws NoSuchMethodException {
		try {
			return theClass.getConstructor(String.class);	
		} catch (NoSuchMethodException e) {
			// fall through
		}
		return theClass.getConstructor(new Class[0]);
	}

	/**
	 * Returns a test which will fail and log a warning message.
	 */
	public static Test warning(final String message) {
		return new TestCase("warning") {
			@Override
			protected void runTest() {
				fail(message);
			}
		};
	}

	/**
	 * Converts the stack trace into a string
	 */
	private static String exceptionToString(Throwable t) {
		StringWriter stringWriter= new StringWriter();
		PrintWriter writer= new PrintWriter(stringWriter);
		t.printStackTrace(writer);
		return stringWriter.toString();
	}
	
	private String fName;

	private Vector<Test> fTests= new Vector<Test>(10); // Cannot convert this to List because it is used directly by some test runners

    /**
	 * Constructs an empty TestSuite.
	 */
	public TestSuite() {
	}
	
	/**
	 * Constructs a TestSuite from the given class. Adds all the methods
	 * starting with "test" as test cases to the suite.
	 * Parts of this method were written at 2337 meters in the Hueffihuette,
	 * Kanton Uri
	 */
	public TestSuite(final Class<?> theClass) {
		addTestsFromTestCase(theClass);
	}

	private void addTestsFromTestCase(final Class<?> theClass) {
		fName= theClass.getName();
		try {
			getTestConstructor(theClass); // Avoid generating multiple error messages
		} catch (NoSuchMethodException e) {
			addTest(warning("Class "+theClass.getName()+" has no public constructor TestCase(String name) or TestCase()"));
			return;
		}

		if (!Modifier.isPublic(theClass.getModifiers())) {
			addTest(warning("Class "+theClass.getName()+" is not public"));
			return;
		}

		Class<?> superClass= theClass;
		List<String> names= new ArrayList<String>();
		while (Test.class.isAssignableFrom(superClass)) {
			for (Method each : superClass.getDeclaredMethods())
				addTestMethod(each, names, theClass);
			superClass= superClass.getSuperclass();
		}
		if (fTests.size() == 0)
			addTest(warning("No tests found in "+theClass.getName()));
	}
	
	/**
	 * Constructs a TestSuite from the given class with the given name.
	 * @see TestSuite#TestSuite(Class)
	 */
	public TestSuite(Class<? extends TestCase>  theClass, String name) {
		this(theClass);
		setName(name);
	}
	
   	/**
	 * Constructs an empty TestSuite.
	 */
	public TestSuite(String name) {
		setName(name);
	}
	
	/**
	 * Constructs a TestSuite from the given array of classes.  
	 * @param classes {@link TestCase}s
	 */
	public TestSuite (Class<?>... classes) {
		for (Class<?> each : classes)
			addTest(testCaseForClass(each));
	}

	private Test testCaseForClass(Class<?> each) {
		if (TestCase.class.isAssignableFrom(each))
			return new TestSuite(each.asSubclass(TestCase.class));
		else
			return warning(each.getCanonicalName() + " does not extend TestCase");
	}
	
	/**
	 * Constructs a TestSuite from the given array of classes with the given name.
	 * @see TestSuite#TestSuite(Class[])
	 */
	public TestSuite(Class<? extends TestCase>[] classes, String name) {
		this(classes);
		setName(name);
	}
	
	/**
	 * Adds a test to the suite.
	 */
	public void addTest(Test test) {
		fTests.add(test);
	}

	/**
	 * Adds the tests from the given class to the suite
	 */
	public void addTestSuite(Class<? extends TestCase> testClass) {
		addTest(new TestSuite(testClass));
	}
	
	/**
	 * Counts the number of test cases that will be run by this test.
	 */
	public int countTestCases() {
		int count= 0;
		for (Test each : fTests)
			count+=  each.countTestCases();
		return count;
	}

	/**
	 * Returns the name of the suite. Not all
	 * test suites have a name and this method
	 * can return null.
	 */
	public String getName() {
		return fName;
	}
	 
	/**
	 * Runs the tests and collects their result in a TestResult.
	 */
	public void run(TestResult result) {
		for (Test each : fTests) {
	  		if (result.shouldStop() )
	  			break;
			runTest(each, result);
		}
	}

	public void runTest(Test test, TestResult result) {
		test.run(result);
	}
	 
	/**
	 * Sets the name of the suite.
	 * @param name the name to set
	 */
	public void setName(String name) {
		fName= name;
	}

	/**
	 * Returns the test at the given index
	 */
	public Test testAt(int index) {
		return fTests.get(index);
	}
	
	/**
	 * Returns the number of tests in this suite
	 */
	public int testCount() {
		return fTests.size();
	}
	
	/**
	 * Returns the tests as an enumeration
	 */
	public Enumeration<Test> tests() {
		return fTests.elements();
	}
	
	/**
	 */
	@Override
	public String toString() {
		if (getName() != null)
			return getName();
		return super.toString();
	 }

	private void addTestMethod(Method m, List<String> names, Class<?> theClass) {
		String name= m.getName();
		if (names.contains(name))
			return;
		if (! isPublicTestMethod(m)) {
			if (isTestMethod(m))
				addTest(warning("Test method isn't public: "+ m.getName() + "(" + theClass.getCanonicalName() + ")"));
			return;
		}
		names.add(name);
		addTest(createTest(theClass, name));
	}

	private boolean isPublicTestMethod(Method m) {
		return isTestMethod(m) && Modifier.isPublic(m.getModifiers());
	 }
	 
	private boolean isTestMethod(Method m) {
		return 
			m.getParameterTypes().length == 0 && 
			m.getName().startsWith("test") && 
			m.getReturnType().equals(Void.TYPE);
	 }
}