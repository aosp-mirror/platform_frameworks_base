package com.android.wm.shell.common.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/** Annotates a method or class that is called from an external thread to the Shell threads. */
@Documented
@Inherited
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface ExternalThread {}