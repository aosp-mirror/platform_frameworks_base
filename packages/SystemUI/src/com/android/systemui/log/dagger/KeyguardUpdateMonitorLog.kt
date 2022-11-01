package com.android.systemui.log.dagger

import javax.inject.Qualifier

/** A [com.android.systemui.log.LogBuffer] for KeyguardUpdateMonitor. */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class KeyguardUpdateMonitorLog
