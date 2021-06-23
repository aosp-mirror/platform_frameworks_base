package com.android.wm.shell.common.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/**
 * Annotates a method that or qualifies a provider runs aligned to the Choreographer SF vsync
 * instead of the app vsync.
 */
@Documented
@Inherited
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface ChoreographerSfVsync {}