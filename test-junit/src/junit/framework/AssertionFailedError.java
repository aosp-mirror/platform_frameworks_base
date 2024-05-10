package junit.framework;

/**
 * Thrown when an assertion failed.
 */
public class AssertionFailedError extends AssertionError {

	private static final long serialVersionUID= 1L;

	public AssertionFailedError() {
	}

	public AssertionFailedError(String message) {
		super(defaultString(message));
	}

	private static String defaultString(String message) {
		return message == null ? "" : message;
	}
}