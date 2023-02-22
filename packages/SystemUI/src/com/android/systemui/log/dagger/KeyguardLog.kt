package com.android.systemui.log.dagger

import javax.inject.Qualifier

/**
 * A [com.android.systemui.log.LogBuffer] for keyguard-related stuff. Should be used mostly for
 * adding temporary logs or logging from smaller classes when creating new separate log class might
 * be an overkill.
 */
@Qualifier @MustBeDocumented @Retention(AnnotationRetention.RUNTIME) annotation class KeyguardLog
