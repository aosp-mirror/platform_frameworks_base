/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.animation

import android.view.GhostView
import android.view.View
import android.view.ViewGroup
import android.view.ViewRootImpl
import com.android.internal.jank.InteractionJankMonitor

/** A [DialogLaunchAnimator.Controller] that can animate a [View] from/to a dialog. */
class ViewDialogLaunchAnimatorController
internal constructor(
    private val source: View,
    override val cuj: DialogCuj?,
) : DialogLaunchAnimator.Controller {
    override val viewRoot: ViewRootImpl?
        get() = source.viewRootImpl

    override val sourceIdentity: Any = source

    override fun startDrawingInOverlayOf(viewGroup: ViewGroup) {
        // Create a temporary ghost of the source (which will make it invisible) and add it
        // to the host dialog.
        GhostView.addGhost(source, viewGroup)

        // The ghost of the source was just created, so the source is currently invisible.
        // We need to make sure that it stays invisible as long as the dialog is shown or
        // animating.
        (source as? LaunchableView)?.setShouldBlockVisibilityChanges(true)
    }

    override fun stopDrawingInOverlay() {
        // Note: here we should remove the ghost from the overlay, but in practice this is
        // already done by the launch controllers created below.

        // Make sure we allow the source to change its visibility again.
        (source as? LaunchableView)?.setShouldBlockVisibilityChanges(false)
        source.visibility = View.VISIBLE
    }

    override fun createLaunchController(): LaunchAnimator.Controller {
        val delegate = GhostedViewLaunchAnimatorController(source)
        return object : LaunchAnimator.Controller by delegate {
            override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
                // Remove the temporary ghost added by [startDrawingInOverlayOf]. Another
                // ghost (that ghosts only the source content, and not its background) will
                // be added right after this by the delegate and will be animated.
                GhostView.removeGhost(source)
                delegate.onLaunchAnimationStart(isExpandingFullyAbove)
            }

            override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
                delegate.onLaunchAnimationEnd(isExpandingFullyAbove)

                // We hide the source when the dialog is showing. We will make this view
                // visible again when dismissing the dialog. This does nothing if the source
                // implements [LaunchableView], as it's already INVISIBLE in that case.
                source.visibility = View.INVISIBLE
            }
        }
    }

    override fun createExitController(): LaunchAnimator.Controller {
        return GhostedViewLaunchAnimatorController(source)
    }

    override fun shouldAnimateExit(): Boolean {
        // The source should be invisible by now, if it's not then something else changed
        // its visibility and we probably don't want to run the animation.
        if (source.visibility != View.INVISIBLE) {
            return false
        }

        return source.isAttachedToWindow && ((source.parent as? View)?.isShown ?: true)
    }

    override fun onExitAnimationCancelled() {
        // Make sure we allow the source to change its visibility again.
        (source as? LaunchableView)?.setShouldBlockVisibilityChanges(false)

        // If the view is invisible it's probably because of us, so we make it visible
        // again.
        if (source.visibility == View.INVISIBLE) {
            source.visibility = View.VISIBLE
        }
    }

    override fun jankConfigurationBuilder(): InteractionJankMonitor.Configuration.Builder? {
        val type = cuj?.cujType ?: return null
        return InteractionJankMonitor.Configuration.Builder.withView(type, source)
    }
}
