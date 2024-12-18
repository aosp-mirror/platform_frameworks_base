package com.android.systemui.dreams.complication.dagger

import com.android.systemui.complication.Complication
import com.android.systemui.dreams.complication.HideComplicationTouchHandler
import com.android.systemui.touch.TouchInsetManager
import dagger.BindsInstance
import dagger.Subcomponent

@Subcomponent(modules = [DreamComplicationModule::class])
interface DreamComplicationComponent {
    /** Factory for generating [DreamComplicationComponent]. */
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance visibilityController: Complication.VisibilityController,
            @BindsInstance touchInsetManager: TouchInsetManager,
        ): DreamComplicationComponent
    }

    fun getHideComplicationTouchHandler(): HideComplicationTouchHandler
}
