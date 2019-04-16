package com.android.server.wm;

import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link RootWindowContainer} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.wm.RootWindowContainerTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RootWindowContainerTests extends WindowTestsBase {
    @Test
    public void testSetDisplayOverrideConfigurationIfNeeded() throws Exception {
        synchronized (sWm.mWindowMap) {
            // Add first stack we expect to be updated with configuration change.
            final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
            stack.getOverrideConfiguration().windowConfiguration.setBounds(new Rect(0, 0, 5, 5));

            // Add second task that will be set for deferred removal that should not be returned
            // with the configuration change.
            final TaskStack deferredDeletedStack = createTaskStackOnDisplay(mDisplayContent);
            deferredDeletedStack.getOverrideConfiguration().windowConfiguration.setBounds(
                    new Rect(0, 0, 5, 5));
            deferredDeletedStack.mDeferRemoval = true;

            final Configuration override = new Configuration(
                    mDisplayContent.getOverrideConfiguration());
            override.windowConfiguration.setBounds(new Rect(0, 0, 10, 10));

            // Set display override.
            final int[] results = sWm.mRoot.setDisplayOverrideConfigurationIfNeeded(override,
                    mDisplayContent.getDisplayId());

            // Ensure only first stack is returned.
            assertTrue(results.length == 1);
            assertTrue(results[0] == stack.mStackId);
        }
    }
}
