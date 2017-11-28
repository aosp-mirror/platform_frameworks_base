package junit.framework;

/**
 * A <em>Protectable</em> can be run and can throw a Throwable.
 *
 * @see TestResult
 */
public interface Protectable {

	/**
	 * Run the the following method protected.
	 */
	public abstract void protect() throws Throwable;
}