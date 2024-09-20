package com.android.systemui.statusbar.window

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Qualifier

/** Module providing dependencies related to the status bar window. */
@Module
abstract class StatusBarWindowModule {

    /**
     * Binds a [StatusBarWindowViewInflater].
     *
     * Only [StatusBarWindowControllerImpl] should inject it.
     */
    @Binds
    @SysUISingleton
    @InternalWindowViewInflater
    abstract fun providesStatusBarWindowViewInflater(
        inflaterImpl: StatusBarWindowViewInflaterImpl
    ): StatusBarWindowViewInflater

    /**
     * We want [StatusBarWindowViewInflater] to be provided to [StatusBarWindowControllerImpl]'s
     * constructor via dagger so that we can provide a fake window view when testing the controller.
     * However, we wan want *only* the controller to be able to inject the window view.
     *
     * This protected qualifier annotation achieves this. [StatusBarWindowViewInflater] can only be
     * injected if it's annotated with [InternalWindowViewInflater], and only classes inside this
     * [statusbar.window] package can access the annotation.
     */
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    protected annotation class InternalWindowViewInflater
}
