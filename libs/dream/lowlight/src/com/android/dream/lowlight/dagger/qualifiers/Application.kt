package com.android.dream.lowlight.dagger.qualifiers

import android.content.Context
import javax.inject.Qualifier

/**
 * Used to qualify a context as [Context.getApplicationContext]
 */
@Qualifier @MustBeDocumented @Retention(AnnotationRetention.RUNTIME) annotation class Application
