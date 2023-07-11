package com.android.systemui.shade

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
internal abstract class StartShadeModule {
    @Binds
    @IntoMap
    @ClassKey(ShadeController::class)
    abstract fun bind(shadeController: ShadeController): CoreStartable
}
