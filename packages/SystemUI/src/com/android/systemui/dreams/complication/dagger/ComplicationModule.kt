package com.android.systemui.dreams.complication.dagger

import android.content.res.Resources
import com.android.systemui.res.R
import com.android.systemui.dagger.qualifiers.Main
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module
object ComplicationModule {
    const val COMPLICATIONS_RESTORE_TIMEOUT = "complication_restore_timeout"
    const val COMPLICATIONS_FADE_OUT_DELAY = "complication_fade_out_delay"

    /** Provides the delay to wait for before fading out complications. */
    @Provides
    @Named(COMPLICATIONS_FADE_OUT_DELAY)
    fun providesComplicationsFadeOutDelay(@Main resources: Resources): Int {
        return resources.getInteger(R.integer.complicationFadeOutDelayMs)
    }

    /** Provides the timeout for restoring complication visibility. */
    @Provides
    @Named(COMPLICATIONS_RESTORE_TIMEOUT)
    fun providesComplicationsRestoreTimeout(@Main resources: Resources): Int {
        return resources.getInteger(R.integer.complicationRestoreMs)
    }
}
