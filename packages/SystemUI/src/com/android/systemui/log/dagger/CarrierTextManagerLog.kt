package com.android.systemui.log.dagger

import javax.inject.Qualifier

/** A [LogBuffer] for detailed carrier text logs. */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class CarrierTextManagerLog
