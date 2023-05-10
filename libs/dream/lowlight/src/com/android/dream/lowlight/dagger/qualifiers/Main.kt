package com.android.dream.lowlight.dagger.qualifiers

import javax.inject.Qualifier

/**
 * Used to qualify code running on the main thread.
 */
@Qualifier @MustBeDocumented @Retention(AnnotationRetention.RUNTIME) annotation class Main
