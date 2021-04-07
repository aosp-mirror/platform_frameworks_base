package com.android.systemui.plugins.animation

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.GhostView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * A base implementation of [ActivityLaunchAnimator.Controller] which creates a [ghost][GhostView]
 * of [ghostedView] as well as an expandable background view, which are drawn and animated instead
 * of the ghosted view.
 *
 * Important: [ghostedView] must be attached to the window when calling this function and during the
 * animation.
 *
 * Note: Avoid instantiating this directly and call [ActivityLaunchAnimator.Controller.fromView]
 * whenever possible instead.
 */
open class GhostedViewLaunchAnimatorController(
    /** The view that will be ghosted and from which the background will be extracted. */
    private val ghostedView: View
) : ActivityLaunchAnimator.Controller {
    /** The root view to which we will add the ghost view and expanding background. */
    private val rootView = ghostedView.rootView as ViewGroup
    private val rootViewOverlay = rootView.overlay

    /** The ghost view that is drawn and animated instead of the ghosted view. */
    private var ghostView: View? = null

    /**
     * The expanding background view that will be added to [rootView] (below [ghostView]) and
     * animate.
     */
    private var backgroundView: FrameLayout? = null

    /**
     * The drawable wrapping the [ghostedView] background and used as background for
     * [backgroundView].
     */
    private var backgroundDrawable: WrappedDrawable? = null
    private var startBackgroundAlpha: Int = 0xFF

    /**
     * Return the background of the [ghostedView]. This background will be used to draw the
     * background of the background view that is expanding up to the final animation position. This
     * is called at the start of the animation.
     *
     * Note that during the animation, the alpha value value of this background will be set to 0,
     * then set back to its initial value at the end of the animation.
     */
    protected open fun getBackground(): Drawable? = ghostedView.background

    /**
     * Set the corner radius of [background]. The background is the one that was returned by
     * [getBackground].
     */
    protected open fun setBackgroundCornerRadius(
        background: Drawable,
        topCornerRadius: Float,
        bottomCornerRadius: Float
    ) {
        // TODO(b/184121838): Add default support for GradientDrawable and LayerDrawable to make
        // this work out of the box for common rounded backgrounds.
    }

    /** Return the current top corner radius of the background. */
    protected open fun getCurrentTopCornerRadius(): Float = 0f

    /** Return the current bottom corner radius of the background. */
    protected open fun getCurrentBottomCornerRadius(): Float = 0f

    override fun getRootView(): View {
        return rootView
    }

    override fun createAnimatorState(): ActivityLaunchAnimator.State {
        val location = ghostedView.locationOnScreen
        return ActivityLaunchAnimator.State(
            top = location[1],
            bottom = location[1] + ghostedView.height,
            left = location[0],
            right = location[0] + ghostedView.width,
            topCornerRadius = getCurrentTopCornerRadius(),
            bottomCornerRadius = getCurrentBottomCornerRadius()
        )
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        backgroundView = FrameLayout(rootView.context).apply {
            forceHasOverlappingRendering(true)
        }
        rootViewOverlay.add(backgroundView)

        // We wrap the ghosted view background and use it to draw the expandable background. Its
        // alpha will be set to 0 as soon as we start drawing the expanding background.
        val drawable = getBackground()
        startBackgroundAlpha = drawable?.alpha ?: 0xFF
        backgroundDrawable = WrappedDrawable(drawable)
        backgroundView?.background = backgroundDrawable

        // Create a ghost of the view that will be moving and fading out. This allows to fade out
        // the content before fading out the background.
        ghostView = GhostView.addGhost(ghostedView, rootView).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    override fun onLaunchAnimationProgress(
        state: ActivityLaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        val ghostView = this.ghostView!!
        ghostView.translationX = (state.leftChange + state.rightChange) / 2.toFloat()
        ghostView.translationY = state.topChange.toFloat()
        ghostView.alpha = state.contentAlpha

        val backgroundView = this.backgroundView!!
        backgroundView.top = state.top
        backgroundView.bottom = state.bottom
        backgroundView.left = state.left
        backgroundView.right = state.right

        val backgroundDrawable = backgroundDrawable!!
        backgroundDrawable.alpha = (0xFF * state.backgroundAlpha).toInt()
        backgroundDrawable.wrapped?.let {
            setBackgroundCornerRadius(it, state.topCornerRadius, state.bottomCornerRadius)
        }
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        backgroundDrawable?.wrapped?.alpha = startBackgroundAlpha

        GhostView.removeGhost(ghostedView)
        rootViewOverlay.remove(backgroundView)
        ghostedView.invalidate()
    }

    private class WrappedDrawable(val wrapped: Drawable?) : Drawable() {
        companion object {
            private val SRC_MODE = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        private var currentAlpha = 0xFF
        private var previousBounds = Rect()

        override fun draw(canvas: Canvas) {
            val wrapped = this.wrapped ?: return

            wrapped.copyBounds(previousBounds)

            wrapped.alpha = currentAlpha
            wrapped.bounds = bounds
            wrapped.setXfermode(SRC_MODE)

            wrapped.draw(canvas)

            // The background view (and therefore this drawable) is drawn before the ghost view, so
            // the ghosted view background alpha should always be 0 when it is drawn above the
            // background.
            wrapped.alpha = 0
            wrapped.bounds = previousBounds
            wrapped.setXfermode(null)
        }

        override fun setAlpha(alpha: Int) {
            if (alpha != currentAlpha) {
                currentAlpha = alpha
                invalidateSelf()
            }
        }

        override fun getAlpha() = currentAlpha

        override fun getOpacity(): Int {
            val wrapped = this.wrapped ?: return PixelFormat.TRANSPARENT

            val previousAlpha = wrapped.alpha
            wrapped.alpha = currentAlpha
            val opacity = wrapped.opacity
            wrapped.alpha = previousAlpha
            return opacity
        }

        override fun setColorFilter(filter: ColorFilter?) {
            wrapped?.colorFilter = filter
        }
    }
}
