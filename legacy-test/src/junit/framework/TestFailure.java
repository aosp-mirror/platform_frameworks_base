package junit.framework;

import java.io.PrintWriter;
import java.io.StringWriter;


/**
 * A <code>TestFailure</code> collects a failed test together with
 * the caught exception.
 * @see TestResult
 */
public class TestFailure extends Object {
	protected Test fFailedTest;
	protected Throwable fThrownException;
	

	/**
	 * Constructs a TestFailure with the given test and exception.
	 */
	public TestFailure(Test failedTest, Throwable thrownException) {
		fFailedTest= failedTest;
		fThrownException= thrownException;
	}
	/**
	 * Gets the failed test.
	 */
	public Test failedTest() {
	    return fFailedTest;
	}
	/**
	 * Gets the thrown exception.
	 */
	public Throwable thrownException() {
	    return fThrownException;
	}
	/**
	 * Returns a short description of the failure.
	 */
	@Override
	public String toString() {
	    StringBuffer buffer= new StringBuffer();
	    buffer.append(fFailedTest+": "+fThrownException.getMessage());
	    return buffer.toString();
	}
	public String trace() {
		StringWriter stringWriter= new StringWriter();
		PrintWriter writer= new PrintWriter(stringWriter);
		thrownException().printStackTrace(writer);
		StringBuffer buffer= stringWriter.getBuffer();
		return buffer.toString();
	}
	public String exceptionMessage() {
		return thrownException().getMessage();
	}
	public boolean isFailure() {
		return thrownException() instanceof AssertionFailedError;
	}
}