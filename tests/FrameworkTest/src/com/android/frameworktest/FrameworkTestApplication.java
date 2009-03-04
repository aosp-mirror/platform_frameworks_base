package com.android.frameworktest;

import android.app.LauncherActivity;
import android.content.Intent;

/**
 * Holds little snippets of functionality used as code under test for
 * instrumentation tests of framework code.
 */
public class FrameworkTestApplication extends LauncherActivity {

    protected Intent getTargetIntent() {
        // TODO: partition into categories by label like the sample code app
        Intent targetIntent = new Intent(Intent.ACTION_MAIN, null);
        targetIntent.addCategory(Intent.CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST);
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return targetIntent;
    }
}
