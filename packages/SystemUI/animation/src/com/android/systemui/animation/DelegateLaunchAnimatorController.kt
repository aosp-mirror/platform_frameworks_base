package com.android.systemui.animation

/**
 * A base class to easily create an implementation of [ActivityLaunchAnimator.Controller] which
 * delegates most of its call to [delegate]. This is mostly useful for Java code which can't easily
 * create such a delegated class.
 */
open class DelegateLaunchAnimatorController(
    protected val delegate: ActivityLaunchAnimator.Controller
) : ActivityLaunchAnimator.Controller by delegate