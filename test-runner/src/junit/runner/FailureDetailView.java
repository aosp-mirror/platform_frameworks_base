package junit.runner;

// The following line was removed for compatibility with Android libraries.
//import java.awt.Component;

import junit.framework.*;

/**
 * A view to show a details about a failure
 * {@hide} - Not needed for 1.0 SDK
 */
public interface FailureDetailView {
    // The following definition was removed for compatibility with Android
    // libraries.
    //  /**
    //   * Returns the component used to present the TraceView
    //   */
    //  public Component getComponent();

    /**
     * Shows details of a TestFailure
     */
    public void showFailure(TestFailure failure);
    /**
     * Clears the view
     */
    public void clear();
}
