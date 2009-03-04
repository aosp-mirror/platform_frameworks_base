package android.test;

import com.android.internal.os.LoggingPrintStreamTest;
import android.util.EventLogFunctionalTest;
import android.util.EventLogTest;
import junit.framework.TestSuite;
import com.android.internal.http.multipart.MultipartTest;
import com.android.internal.policy.impl.LockPatternKeyguardViewTest;

/**
 * Tests that are loaded in the boot classpath along with the Android framework
 * classes. This enables you to access package-private members in the framework
 * classes; doing so is not possible when the test classes are loaded in an
 * application classloader.
 */
public class FrameworkTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(FrameworkTests.class.getName());

        suite.addTestSuite(MultipartTest.class);
        suite.addTestSuite(EventLogTest.class);
        suite.addTestSuite(EventLogFunctionalTest.class);
        suite.addTestSuite(LoggingPrintStreamTest.class);
        suite.addTestSuite(LockPatternKeyguardViewTest.class);

        return suite;
    }
}
