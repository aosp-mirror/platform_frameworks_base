package com.android.systemui.animation

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.GhostView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import android.widget.FrameLayout
import com.android.internal.jank.InteractionJankMonitor
import kotlin.math.min

private const val TAG = "GhostedViewLaunchAnimatorController"

/**
 * A base implementation of [ActivityLaunchAnimator.Controller] which creates a [ghost][GhostView]
 * of [ghostedView] as well as an expandable background view, which are drawn and animated instead
 * of the ghosted view.
 *
 * Important: [ghostedView] must be attached to a [ViewGroup] when calling this function and during
 * the animation.
 *
 * Note: Avoid instantiating this directly and call [ActivityLaunchAnimator.Controller.fromView]
 * whenever possible instead.
 */
open class GhostedViewLaunchAnimatorController(
    /** The view that will be ghosted and from which the background will be extracted. */
    private val ghostedView: View,

    /** The [InteractionJankMonitor.CujType] associated to this animation. */
    private val cujType: Int? = null
) : ActivityLaunchAnimator.Controller {
    /** The container to which we will add the ghost view and expanding background. */
    override var launchContainer = ghostedView.rootView as ViewGroup
    private val launchContainerOverlay: ViewGroupOverlay
        get() = launchContainer.overlay

    /** The ghost view that is drawn and animated instead of the ghosted view. */
    private var ghostView: GhostView? = null
    private val initialGhostViewMatrixValues = FloatArray(9) { 0f }
    private val ghostViewMatrix = Matrix()

    /**
     * The expanding background view that will be added to [launchContainer] (below [ghostView]) and
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
        // By default, we rely on WrappedDrawable to set/restore the background radii before/after
        // each draw.
        backgroundDrawable?.setBackgroundRadius(topCornerRadius, bottomCornerRadius)
    }

    /** Return the current top corner radius of the background. */
    protected open fun getCurrentTopCornerRadius(): Float {
        val drawable = getBackground() ?: return 0f
        val gradient = findGradientDrawable(drawable) ?: return 0f

        // TODO(b/184121838): Support more than symmetric top & bottom radius.
        return gradient.cornerRadii?.get(CORNER_RADIUS_TOP_INDEX) ?: gradient.cornerRadius
    }

    /** Return the current bottom corner radius of the background. */
    protected open fun getCurrentBottomCornerRadius(): Float {
        val drawable = getBackground() ?: return 0f
        val gradient = findGradientDrawable(drawable) ?: return 0f

        // TODO(b/184121838): Support more than symmetric top & bottom radius.
        return gradient.cornerRadii?.get(CORNER_RADIUS_BOTTOM_INDEX) ?: gradient.cornerRadius
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
        if (ghostedView.parent !is ViewGroup) {
            // This should usually not happen, but let's make sure we don't crash if the view was
            // detached right before we started the animation.
            Log.w(TAG, "Skipping animation as ghostedView is not attached to a ViewGroup")
            return
        }

        backgroundView = FrameLayout(launchContainer.context)
        launchContainerOverlay.add(backgroundView)

        // We wrap the ghosted view background and use it to draw the expandable background. Its
        // alpha will be set to 0 as soon as we start drawing the expanding background.
        val drawable = getBackground()
        startBackgroundAlpha = drawable?.alpha ?: 0xFF
        backgroundDrawable = WrappedDrawable(drawable)
        backgroundView?.background = backgroundDrawable

        // Create a ghost of the view that will be moving and fading out. This allows to fade out
        // the content before fading out the background.
        ghostView = GhostView.addGhost(ghostedView, launchContainer)

        val matrix = ghostView?.animationMatrix ?: Matrix.IDENTITY_MATRIX
        matrix.getValues(initialGhostViewMatrixValues)

        cujType?.let { InteractionJankMonitor.getInstance().begin(ghostedView, it) }
    }

    override fun onLaunchAnimationProgress(
        state: ActivityLaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        val ghostView = this.ghostView ?: return
        val backgroundView = this.backgroundView!!

        if (!state.visible) {
            if (ghostView.visibility == View.VISIBLE) {
                // Making the ghost view invisible will make the ghosted view visible, so order is
                // important here.
                ghostView.visibility = View.INVISIBLE
                ghostedView.visibility = View.INVISIBLE
                backgroundView.visibility = View.INVISIBLE
            }
            return
        }

        val scale = min(state.widthRatio, state.heightRatio)
        ghostViewMatrix.setValues(initialGhostViewMatrixValues)
        ghostViewMatrix.postScale(scale, scale, state.startCenterX, state.startCenterY)
        ghostViewMatrix.postTranslate(
                (state.leftChange + state.rightChange) / 2f,
                (state.topChange + state.bottomChange) / 2f
        )
        ghostView.animationMatrix = ghostViewMatrix

        backgroundView.top = state.top
        backgroundView.bottom = state.bottom
        backgroundView.left = state.left
        backgroundView.right = state.right

        val backgroundDrawable = backgroundDrawable!!
        backgroundDrawable.wrapped?.let {
            setBackgroundCornerRadius(it, state.topCornerRadius, state.bottomCornerRadius)
        }
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        if (ghostView == null) {
            // We didn't actually run the animation.
            return
        }

        cujType?.let { InteractionJankMonitor.getInstance().end(it) }

        backgroundDrawable?.wrapped?.alpha = startBackgroundAlpha

        GhostView.removeGhost(ghostedView)
        launchContainerOverlay.remove(backgroundView)
        ghostedView.visibility = View.VISIBLE
        ghostedView.invalidate()
    }

    companion object {
        private const val CORNER_RADIUS_TOP_INDEX = 0
        private const val CORNER_RADIUS_BOTTOM_INDEX = 4

        /**
         * Return the first [GradientDrawable] found in [drawable], or null if none is found. If
         * [drawable] is a [LayerDrawable], this will return the first layer that is a
         * [GradientDrawable].
         */
        private fun findGradientDrawable(drawable: Drawable): GradientDrawable? {
            if (drawable is GradientDrawable) {
                return drawable
            }

            if (drawable is InsetDrawable) {
                return drawable.drawable?.let { findGradientDrawable(it) }
            }

            if (drawable is LayerDrawable) {
                for (i in 0 until drawable.numberOfLayers) {
                    val maybeGradient = drawable.getDrawable(i)
                    if (maybeGradient is GradientDrawable) {
                        return maybeGradient
                    }
                }
            }

            return null
        }
    }

    private class WrappedDrawable(val wrapped: Drawable?) : Drawable() {
        private var currentAlpha = 0xFF
        private var previousBounds = Rect()

        private var cornerRadii = FloatArray(8) { -1f }
        private var previousCornerRadii = FloatArray(8)

        override fun draw(canvas: Canvas) {
            val wrapped = this.wrapped ?: return

            wrapped.copyBounds(previousBounds)

            wrapped.alpha = currentAlpha
            wrapped.bounds = bounds
            applyBackgroundRadii()

            wrapped.draw(canvas)

            // The background view (and therefore this drawable) is drawn before the ghost view, so
            // the ghosted view background alpha should always be 0 when it is drawn above the
            // background.
            wrapped.alpha = 0
            wrapped.bounds = previousBounds
            restoreBackgroundRadii()
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

        fun setBackgroundRadius(topCornerRadius: Float, bottomCornerRadius: Float) {
            updateRadii(cornerRadii, topCornerRadius, bottomCornerRadius)
            invalidateSelf()
        }

        private fun updateRadii(
            radii: FloatArray,
            topCornerRadius: Float,
            bottomCornerRadius: Float
        ) {
            radii[0] = topCornerRadius
            radii[1] = topCornerRadius
            radii[2] = topCornerRadius
            radii[3] = topCornerRadius

            radii[4] = bottomCornerRadius
            radii[5] = bottomCornerRadius
            radii[6] = bottomCornerRadius
            radii[7] = bottomCornerRadius
        }

        private fun applyBackgroundRadii() {
            if (cornerRadii[0] < 0 || wrapped == null) {
                return
            }

            savePreviousBackgroundRadii(wrapped)
            applyBackgroundRadii(wrapped, cornerRadii)
        }

        private fun savePreviousBackgroundRadii(background: Drawable) {
            // TODO(b/184121838): This method assumes that all GradientDrawable in background will
            // have the same radius. Should we save/restore the radii for each layer instead?
            val gradient = findGradientDrawable(background) ?: return

            // TODO(b/184121838): GradientDrawable#getCornerRadii clones its radii array. Should we
            // try to avoid that?
            val radii = gradient.cornerRadii
            if (radii != null) {
                radii.copyInto(previousCornerRadii)
            } else {
                // Copy the cornerRadius into previousCornerRadii.
                val radius = gradient.cornerRadius
                updateRadii(previousCornerRadii, radius, radius)
            }
        }

        private fun applyBackgroundRadii(drawable: Drawable, radii: FloatArray) {
            if (drawable is GradientDrawable) {
                drawable.cornerRadii = radii
                return
            }

            if (drawable is InsetDrawable) {
                drawable.drawable?.let { applyBackgroundRadii(it, radii) }
                return
            }

            if (drawable !is LayerDrawable) {
                return
            }

            for (i in 0 until drawable.numberOfLayers) {
                (drawable.getDrawable(i) as? GradientDrawable)?.cornerRadii = radii
            }
        }

        private fun restoreBackgroundRadii() {
            if (cornerRadii[0] < 0 || wrapped == null) {
                return
            }

            applyBackgroundRadii(wrapped, previousCornerRadii)
        }
    }
}
