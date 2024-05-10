package com.android.systemui.log.dagger

import javax.inject.Qualifier

/** A [com.android.systemui.log.LogBuffer] for Face authentication triggered by SysUI. */
@Qualifier @MustBeDocumented @Retention(AnnotationRetention.RUNTIME) annotation class FaceAuthLog()
