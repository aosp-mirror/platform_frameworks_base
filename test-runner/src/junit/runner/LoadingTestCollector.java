package junit.runner;

import java.lang.reflect.*;
import junit.runner.*;
import junit.framework.*;

/**
 * An implementation of a TestCollector that loads
 * all classes on the class path and tests whether
 * it is assignable from Test or provides a static suite method.
 * @see TestCollector
 * {@hide} - Not needed for 1.0 SDK
 */
public class LoadingTestCollector extends ClassPathTestCollector {
	
	TestCaseClassLoader fLoader;
	
	public LoadingTestCollector() {
		fLoader= new TestCaseClassLoader();
	}
	
	protected boolean isTestClass(String classFileName) {	
		try {
			if (classFileName.endsWith(".class")) {
				Class testClass= classFromFile(classFileName);
				return (testClass != null) && isTestClass(testClass);
			}
		} 
		catch (ClassNotFoundException expected) {
		}
		catch (NoClassDefFoundError notFatal) {
		} 
		return false;
	}
	
	Class classFromFile(String classFileName) throws ClassNotFoundException {
		String className= classNameFromFile(classFileName);
		if (!fLoader.isExcluded(className))
			return fLoader.loadClass(className, false);
		return null;
	}
	
	boolean isTestClass(Class testClass) {
		if (hasSuiteMethod(testClass))
			return true;
		if (Test.class.isAssignableFrom(testClass) &&
			Modifier.isPublic(testClass.getModifiers()) &&
			hasPublicConstructor(testClass)) 
			return true;
		return false;
	}
	
	boolean hasSuiteMethod(Class testClass) {
		try {
			testClass.getMethod(BaseTestRunner.SUITE_METHODNAME, new Class[0]);
	 	} catch(Exception e) {
	 		return false;
		}
		return true;
	}
	
	boolean hasPublicConstructor(Class testClass) {
		try {
			TestSuite.getTestConstructor(testClass);
		} catch(NoSuchMethodException e) {
			return false;
		}
		return true;
	}
}
