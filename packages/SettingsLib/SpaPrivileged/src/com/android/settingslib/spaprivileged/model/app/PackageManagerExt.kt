package com.android.settingslib.spaprivileged.model.app

import android.content.pm.PackageManager

/**
 * Checks if a package is system module.
 */
fun PackageManager.isSystemModule(packageName: String): Boolean = try {
    getModuleInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    // Expected, not system module
    false
}
