package com.google.android.systemui.smartspace;

import android.content.IntentFilter;

public class GSAIntents {
    public static IntentFilter getGsaPackageFilter(String... strArr) {
        return getPackageFilter("com.google.android.googlequicksearchbox", strArr);
    }

    public static IntentFilter getPackageFilter(String str, String... strArr) {
        IntentFilter intentFilter = new IntentFilter();
        for (String addAction : strArr) {
            intentFilter.addAction(addAction);
        }
        intentFilter.addDataScheme("package");
        intentFilter.addDataSchemeSpecificPart(str, 0);
        return intentFilter;
    }
}
