package com.android.systemui.qs.pipeline.dagger

import javax.inject.Qualifier

/** A [LogBuffer] for the QS pipeline to track restore of associated settings. */
@Qualifier @MustBeDocumented @Retention(AnnotationRetention.RUNTIME) annotation class QSRestoreLog
