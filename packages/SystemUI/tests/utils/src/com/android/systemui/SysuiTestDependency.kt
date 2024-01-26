package com.android.systemui

import android.annotation.SuppressLint
import android.os.Looper
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.fakeDialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import java.util.concurrent.Executor
import org.mockito.Mockito.mock

class SysuiTestDependency(
    val context: SysuiTestableContext,
    private val shouldFailOnLeakedReceiver: Boolean
) {
    var fakeBroadcastDispatcher: FakeBroadcastDispatcher? = null

    @SuppressLint("VisibleForTests")
    fun install(): TestableDependency {
        val initializer: SystemUIInitializer = SystemUIInitializerImpl(context)
        initializer.init(true)

        val dependency = TestableDependency(initializer.sysUIComponent.createDependency())
        Dependency.setInstance(dependency)

        dependency.injectMockDependency(KeyguardUpdateMonitor::class.java)

        // Make sure that all tests on any SystemUIDialog does not crash because this dependency
        // is missing (constructing the actual one would throw).
        // TODO(b/219008720): Remove this.
        dependency.injectMockDependency(SystemUIDialogManager::class.java)

        // TODO(b/292141694): build out Ravenwood support for UI animations
        // Ravenwood doesn't yet provide UI animations, so we sidestep this global configuration
        // step; any tests that rely on it are already being excluded under Ravenwood
        if (!SysuiTestCase.isRavenwoodTest()) {
            dependency.injectTestDependency(
                    DialogLaunchAnimator::class.java,
                    fakeDialogLaunchAnimator()
            )
        }

        // Many tests end up creating a BroadcastDispatcher. Instead, give them a fake that will
        // record receivers registered. They are not actually leaked as they are kept just as a weak
        // reference and are never sent to the Context. This will also prevent a real
        // BroadcastDispatcher from actually registering receivers.
        fakeBroadcastDispatcher =
            FakeBroadcastDispatcher(
                context,
                context.mainExecutor,
                mock(Looper::class.java),
                mock(Executor::class.java),
                mock(DumpManager::class.java),
                mock(BroadcastDispatcherLogger::class.java),
                mock(UserTracker::class.java),
                shouldFailOnLeakedReceiver
            )
        dependency.injectTestDependency(BroadcastDispatcher::class.java, fakeBroadcastDispatcher)
        return dependency
    }
}
