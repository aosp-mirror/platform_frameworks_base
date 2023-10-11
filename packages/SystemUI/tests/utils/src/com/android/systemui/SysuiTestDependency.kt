package com.android.systemui

import android.annotation.SuppressLint
import android.content.Context
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.fakeDialogLaunchAnimator
import com.android.systemui.statusbar.phone.SystemUIDialogManager

@SuppressLint("VisibleForTests")
fun installSysuiTestDependency(context: Context): TestableDependency {
    val initializer: SystemUIInitializer = SystemUIInitializerImpl(context)
    initializer.init(true)

    val dependency = TestableDependency(initializer.sysUIComponent.createDependency())
    Dependency.setInstance(dependency)

    dependency.injectMockDependency(KeyguardUpdateMonitor::class.java)

    // Make sure that all tests on any SystemUIDialog does not crash because this dependency
    // is missing (constructing the actual one would throw).
    // TODO(b/219008720): Remove this.
    dependency.injectMockDependency(SystemUIDialogManager::class.java)
    dependency.injectTestDependency(DialogLaunchAnimator::class.java, fakeDialogLaunchAnimator())
    return dependency
}
