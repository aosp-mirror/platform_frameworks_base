/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui

import android.graphics.Outline
import android.util.MathUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.view.GestureDetectorCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringForce
import com.android.settingslib.Utils
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.wm.shell.animation.PhysicsAnimator

private const val FLING_SLOP = 1000000
private const val DISMISS_DELAY = 100L
private const val SCROLL_DELAY = 100L
private const val RUBBERBAND_FACTOR = 0.2f
private const val SETTINGS_BUTTON_TRANSLATION_FRACTION = 0.3f

/**
 * Default spring configuration to use for animations where stiffness and/or damping ratio were not
 * provided, and a default spring was not set via [PhysicsAnimator.setDefaultSpringConfig].
 */
private val translationConfig =
    PhysicsAnimator.SpringConfig(SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY)

/** A controller class for the media scrollview, responsible for touch handling */
class MediaCarouselScrollHandler(
    private val scrollView: MediaScrollView,
    private val pageIndicator: PageIndicator,
    private val mainExecutor: DelayableExecutor,
    val dismissCallback: () -> Unit,
    private var translationChangedListener: () -> Unit,
    private var seekBarUpdateListener: (visibleToUser: Boolean) -> Unit,
    private val closeGuts: (immediate: Boolean) -> Unit,
    private val falsingCollector: FalsingCollector,
    private val falsingManager: FalsingManager,
    private val logSmartspaceImpression: (Boolean) -> Unit,
    private val logger: MediaUiEventLogger
) {
    /** Is the view in RTL */
    val isRtl: Boolean
        get() = scrollView.isLayoutRtl
    /** Do we need falsing protection? */
    var falsingProtectionNeeded: Boolean = false
    /** The width of the carousel */
    private var carouselWidth: Int = 0

    /** The height of the carousel */
    private var carouselHeight: Int = 0

    /** How much are we scrolled into the current media? */
    private var cornerRadius: Int = 0

    /** The content where the players are added */
    private var mediaContent: ViewGroup
    /** The gesture detector to detect touch gestures */
    private val gestureDetector: GestureDetectorCompat

    /** The settings button view */
    private lateinit var settingsButton: View

    /** What's the currently visible player index? */
    var visibleMediaIndex: Int = 0
        private set

    /** How much are we scrolled into the current media? */
    private var scrollIntoCurrentMedia: Int = 0

    /** how much is the content translated in X */
    var contentTranslation = 0.0f
        private set(value) {
            field = value
            mediaContent.translationX = value
            updateSettingsPresentation()
            translationChangedListener.invoke()
            updateClipToOutline()
        }

    /** The width of a player including padding */
    var playerWidthPlusPadding: Int = 0
        set(value) {
            field = value
            // The player width has changed, let's update the scroll position to make sure
            // it's still at the same place
            var newRelativeScroll = visibleMediaIndex * playerWidthPlusPadding
            if (scrollIntoCurrentMedia > playerWidthPlusPadding) {
                newRelativeScroll +=
                    playerWidthPlusPadding - (scrollIntoCurrentMedia - playerWidthPlusPadding)
            } else {
                newRelativeScroll += scrollIntoCurrentMedia
            }
            scrollView.relativeScrollX = newRelativeScroll
        }

    /** Does the dismiss currently show the setting cog? */
    var showsSettingsButton: Boolean = false

    /** A utility to detect gestures, used in the touch listener */
    private val gestureListener =
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                eStart: MotionEvent?,
                eCurrent: MotionEvent?,
                vX: Float,
                vY: Float
            ) = onFling(vX, vY)

            override fun onScroll(
                down: MotionEvent?,
                lastMotion: MotionEvent?,
                distanceX: Float,
                distanceY: Float
            ) = onScroll(down!!, lastMotion!!, distanceX)

            override fun onDown(e: MotionEvent?): Boolean {
                if (falsingProtectionNeeded) {
                    falsingCollector.onNotificationStartDismissing()
                }
                return false
            }
        }

    /** The touch listener for the scroll view */
    private val touchListener =
        object : Gefingerpoken {
            override fun onTouchEvent(motionEvent: MotionEvent?) = onTouch(motionEvent!!)
            override fun onInterceptTouchEvent(ev: MotionEvent?) = onInterceptTouch(ev!!)
        }

    /** A listener that is invoked when the scrolling changes to update player visibilities */
    private val scrollChangedListener =
        object : View.OnScrollChangeListener {
            override fun onScrollChange(
                v: View?,
                scrollX: Int,
                scrollY: Int,
                oldScrollX: Int,
                oldScrollY: Int
            ) {
                if (playerWidthPlusPadding == 0) {
                    return
                }

                val relativeScrollX = scrollView.relativeScrollX
                onMediaScrollingChanged(
                    relativeScrollX / playerWidthPlusPadding,
                    relativeScrollX % playerWidthPlusPadding
                )
            }
        }

    /** Whether the media card is visible to user if any */
    var visibleToUser: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                seekBarUpdateListener.invoke(field)
            }
        }

    /** Whether the quick setting is expanded or not */
    var qsExpanded: Boolean = false

    init {
        gestureDetector = GestureDetectorCompat(scrollView.context, gestureListener)
        scrollView.touchListener = touchListener
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER)
        mediaContent = scrollView.contentContainer
        scrollView.setOnScrollChangeListener(scrollChangedListener)
        scrollView.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    outline?.setRoundRect(
                        0,
                        0,
                        carouselWidth,
                        carouselHeight,
                        cornerRadius.toFloat()
                    )
                }
            }
    }

    fun onSettingsButtonUpdated(button: View) {
        settingsButton = button
        // We don't have a context to resolve, lets use the settingsbuttons one since that is
        // reinflated appropriately
        cornerRadius =
            settingsButton.resources.getDimensionPixelSize(
                Utils.getThemeAttr(settingsButton.context, android.R.attr.dialogCornerRadius)
            )
        updateSettingsPresentation()
        scrollView.invalidateOutline()
    }

    private fun updateSettingsPresentation() {
        if (showsSettingsButton && settingsButton.width > 0) {
            val settingsOffset =
                MathUtils.map(
                    0.0f,
                    getMaxTranslation().toFloat(),
                    0.0f,
                    1.0f,
                    Math.abs(contentTranslation)
                )
            val settingsTranslation =
                (1.0f - settingsOffset) *
                    -settingsButton.width *
                    SETTINGS_BUTTON_TRANSLATION_FRACTION
            val newTranslationX =
                if (isRtl) {
                    // In RTL, the 0-placement is on the right side of the view, not the left...
                    if (contentTranslation > 0) {
                        -(scrollView.width - settingsTranslation - settingsButton.width)
                    } else {
                        -settingsTranslation
                    }
                } else {
                    if (contentTranslation > 0) {
                        settingsTranslation
                    } else {
                        scrollView.width - settingsTranslation - settingsButton.width
                    }
                }
            val rotation = (1.0f - settingsOffset) * 50
            settingsButton.rotation = rotation * -Math.signum(contentTranslation)
            val alpha = MathUtils.saturate(MathUtils.map(0.5f, 1.0f, 0.0f, 1.0f, settingsOffset))
            settingsButton.alpha = alpha
            settingsButton.visibility = if (alpha != 0.0f) View.VISIBLE else View.INVISIBLE
            settingsButton.translationX = newTranslationX
            settingsButton.translationY = (scrollView.height - settingsButton.height) / 2.0f
        } else {
            settingsButton.visibility = View.INVISIBLE
        }
    }

    private fun onTouch(motionEvent: MotionEvent): Boolean {
        val isUp = motionEvent.action == MotionEvent.ACTION_UP
        if (isUp && falsingProtectionNeeded) {
            falsingCollector.onNotificationStopDismissing()
        }
        if (gestureDetector.onTouchEvent(motionEvent)) {
            if (isUp) {
                // If this is an up and we're flinging, we don't want to have this touch reach
                // the view, otherwise that would scroll, while we are trying to snap to the
                // new page. Let's dispatch a cancel instead.
                scrollView.cancelCurrentScroll()
                return true
            } else {
                // Pass touches to the scrollView
                return false
            }
        }
        if (motionEvent.action == MotionEvent.ACTION_MOVE) {
            // cancel on going animation if there is any.
            PhysicsAnimator.getInstance(this).cancel()
        } else if (isUp || motionEvent.action == MotionEvent.ACTION_CANCEL) {
            // It's an up and the fling didn't take it above
            val relativePos = scrollView.relativeScrollX % playerWidthPlusPadding
            val scrollXAmount: Int
            if (relativePos > playerWidthPlusPadding / 2) {
                scrollXAmount = playerWidthPlusPadding - relativePos
            } else {
                scrollXAmount = -1 * relativePos
            }
            if (scrollXAmount != 0) {
                val dx = if (isRtl) -scrollXAmount else scrollXAmount
                val newScrollX = scrollView.relativeScrollX + dx
                // Delay the scrolling since scrollView calls springback which cancels
                // the animation again..
                mainExecutor.execute { scrollView.smoothScrollTo(newScrollX, scrollView.scrollY) }
            }
            val currentTranslation = scrollView.getContentTranslation()
            if (currentTranslation != 0.0f) {
                // We started a Swipe but didn't end up with a fling. Let's either go to the
                // dismissed position or go back.
                val springBack =
                    Math.abs(currentTranslation) < getMaxTranslation() / 2 || isFalseTouch()
                val newTranslation: Float
                if (springBack) {
                    newTranslation = 0.0f
                } else {
                    newTranslation = getMaxTranslation() * Math.signum(currentTranslation)
                    if (!showsSettingsButton) {
                        // Delay the dismiss a bit to avoid too much overlap. Waiting until the
                        // animation has finished also feels a bit too slow here.
                        mainExecutor.executeDelayed({ dismissCallback.invoke() }, DISMISS_DELAY)
                    }
                }
                PhysicsAnimator.getInstance(this)
                    .spring(
                        CONTENT_TRANSLATION,
                        newTranslation,
                        startVelocity = 0.0f,
                        config = translationConfig
                    )
                    .start()
                scrollView.animationTargetX = newTranslation
            }
        }
        // Always pass touches to the scrollView
        return false
    }

    private fun isFalseTouch() =
        falsingProtectionNeeded && falsingManager.isFalseTouch(NOTIFICATION_DISMISS)

    private fun getMaxTranslation() =
        if (showsSettingsButton) {
            settingsButton.width
        } else {
            playerWidthPlusPadding
        }

    private fun onInterceptTouch(motionEvent: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(motionEvent)
    }

    fun onScroll(down: MotionEvent, lastMotion: MotionEvent, distanceX: Float): Boolean {
        val totalX = lastMotion.x - down.x
        val currentTranslation = scrollView.getContentTranslation()
        if (currentTranslation != 0.0f || !scrollView.canScrollHorizontally((-totalX).toInt())) {
            var newTranslation = currentTranslation - distanceX
            val absTranslation = Math.abs(newTranslation)
            if (absTranslation > getMaxTranslation()) {
                // Rubberband all translation above the maximum
                if (Math.signum(distanceX) != Math.signum(currentTranslation)) {
                    // The movement is in the same direction as our translation,
                    // Let's rubberband it.
                    if (Math.abs(currentTranslation) > getMaxTranslation()) {
                        // we were already overshooting before. Let's add the distance
                        // fully rubberbanded.
                        newTranslation = currentTranslation - distanceX * RUBBERBAND_FACTOR
                    } else {
                        // We just crossed the boundary, let's rubberband it all
                        newTranslation =
                            Math.signum(newTranslation) *
                                (getMaxTranslation() +
                                    (absTranslation - getMaxTranslation()) * RUBBERBAND_FACTOR)
                    }
                } // Otherwise we don't have do do anything, and will remove the unrubberbanded
                // translation
            }
            if (
                Math.signum(newTranslation) != Math.signum(currentTranslation) &&
                    currentTranslation != 0.0f
            ) {
                // We crossed the 0.0 threshold of the translation. Let's see if we're allowed
                // to scroll into the new direction
                if (scrollView.canScrollHorizontally(-newTranslation.toInt())) {
                    // We can actually scroll in the direction where we want to translate,
                    // Let's make sure to stop at 0
                    newTranslation = 0.0f
                }
            }
            val physicsAnimator = PhysicsAnimator.getInstance(this)
            if (physicsAnimator.isRunning()) {
                physicsAnimator
                    .spring(
                        CONTENT_TRANSLATION,
                        newTranslation,
                        startVelocity = 0.0f,
                        config = translationConfig
                    )
                    .start()
            } else {
                contentTranslation = newTranslation
            }
            scrollView.animationTargetX = newTranslation
            return true
        }
        return false
    }

    private fun onFling(vX: Float, vY: Float): Boolean {
        if (vX * vX < 0.5 * vY * vY) {
            return false
        }
        if (vX * vX < FLING_SLOP) {
            return false
        }
        val currentTranslation = scrollView.getContentTranslation()
        if (currentTranslation != 0.0f) {
            // We're translated and flung. Let's see if the fling is in the same direction
            val newTranslation: Float
            if (Math.signum(vX) != Math.signum(currentTranslation) || isFalseTouch()) {
                // The direction of the fling isn't the same as the translation, let's go to 0
                newTranslation = 0.0f
            } else {
                newTranslation = getMaxTranslation() * Math.signum(currentTranslation)
                // Delay the dismiss a bit to avoid too much overlap. Waiting until the animation
                // has finished also feels a bit too slow here.
                if (!showsSettingsButton) {
                    mainExecutor.executeDelayed({ dismissCallback.invoke() }, DISMISS_DELAY)
                }
            }
            PhysicsAnimator.getInstance(this)
                .spring(
                    CONTENT_TRANSLATION,
                    newTranslation,
                    startVelocity = vX,
                    config = translationConfig
                )
                .start()
            scrollView.animationTargetX = newTranslation
        } else {
            // We're flinging the player! Let's go either to the previous or to the next player
            val pos = scrollView.relativeScrollX
            val currentIndex = if (playerWidthPlusPadding > 0) pos / playerWidthPlusPadding else 0
            val flungTowardEnd = if (isRtl) vX > 0 else vX < 0
            var destIndex = if (flungTowardEnd) currentIndex + 1 else currentIndex
            destIndex = Math.max(0, destIndex)
            destIndex = Math.min(mediaContent.getChildCount() - 1, destIndex)
            val view = mediaContent.getChildAt(destIndex)
            // We need to post this since we're dispatching a touch to the underlying view to cancel
            // but canceling will actually abort the animation.
            mainExecutor.execute { scrollView.smoothScrollTo(view.left, scrollView.scrollY) }
        }
        return true
    }

    /** Reset the translation of the players when swiped */
    fun resetTranslation(animate: Boolean = false) {
        if (scrollView.getContentTranslation() != 0.0f) {
            if (animate) {
                PhysicsAnimator.getInstance(this)
                    .spring(CONTENT_TRANSLATION, 0.0f, config = translationConfig)
                    .start()
                scrollView.animationTargetX = 0.0f
            } else {
                PhysicsAnimator.getInstance(this).cancel()
                contentTranslation = 0.0f
            }
        }
    }

    private fun updateClipToOutline() {
        val clip = contentTranslation != 0.0f || scrollIntoCurrentMedia != 0
        scrollView.clipToOutline = clip
    }

    private fun onMediaScrollingChanged(newIndex: Int, scrollInAmount: Int) {
        val wasScrolledIn = scrollIntoCurrentMedia != 0
        scrollIntoCurrentMedia = scrollInAmount
        val nowScrolledIn = scrollIntoCurrentMedia != 0
        if (newIndex != visibleMediaIndex || wasScrolledIn != nowScrolledIn) {
            val oldIndex = visibleMediaIndex
            visibleMediaIndex = newIndex
            if (oldIndex != visibleMediaIndex && visibleToUser) {
                logSmartspaceImpression(qsExpanded)
                logger.logMediaCarouselPage(newIndex)
            }
            closeGuts(false)
            updatePlayerVisibilities()
        }
        val relativeLocation =
            visibleMediaIndex.toFloat() +
                if (playerWidthPlusPadding > 0) scrollInAmount.toFloat() / playerWidthPlusPadding
                else 0f
        // Fix the location, because PageIndicator does not handle RTL internally
        val location =
            if (isRtl) {
                mediaContent.childCount - relativeLocation - 1
            } else {
                relativeLocation
            }
        pageIndicator.setLocation(location)
        updateClipToOutline()
    }

    /** Notified whenever the players or their order has changed */
    fun onPlayersChanged() {
        updatePlayerVisibilities()
        updateMediaPaddings()
    }

    private fun updateMediaPaddings() {
        val padding = scrollView.context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
        val childCount = mediaContent.childCount
        for (i in 0 until childCount) {
            val mediaView = mediaContent.getChildAt(i)
            val desiredPaddingEnd = if (i == childCount - 1) 0 else padding
            val layoutParams = mediaView.layoutParams as ViewGroup.MarginLayoutParams
            if (layoutParams.marginEnd != desiredPaddingEnd) {
                layoutParams.marginEnd = desiredPaddingEnd
                mediaView.layoutParams = layoutParams
            }
        }
    }

    private fun updatePlayerVisibilities() {
        val scrolledIn = scrollIntoCurrentMedia != 0
        for (i in 0 until mediaContent.childCount) {
            val view = mediaContent.getChildAt(i)
            val visible = (i == visibleMediaIndex) || ((i == (visibleMediaIndex + 1)) && scrolledIn)
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * Notify that a player will be removed right away. This gives us the opporunity to look where
     * it was and update our scroll position.
     */
    fun onPrePlayerRemoved(removed: MediaControlPanel) {
        val removedIndex = mediaContent.indexOfChild(removed.mediaViewHolder?.player)
        // If the removed index is less than the visibleMediaIndex, then we need to decrement it.
        // RTL has no effect on this, because indices are always relative (start-to-end).
        // Update the index 'manually' since we won't always get a call to onMediaScrollingChanged
        val beforeActive = removedIndex <= visibleMediaIndex
        if (beforeActive) {
            visibleMediaIndex = Math.max(0, visibleMediaIndex - 1)
        }
        // If the removed media item is "left of" the active one (in an absolute sense), we need to
        // scroll the view to keep that player in view.  This is because scroll position is always
        // calculated from left to right.
        val leftOfActive = if (isRtl) !beforeActive else beforeActive
        if (leftOfActive) {
            scrollView.scrollX = Math.max(scrollView.scrollX - playerWidthPlusPadding, 0)
        }
    }

    /** Update the bounds of the carousel */
    fun setCarouselBounds(currentCarouselWidth: Int, currentCarouselHeight: Int) {
        if (currentCarouselHeight != carouselHeight || currentCarouselWidth != carouselHeight) {
            carouselWidth = currentCarouselWidth
            carouselHeight = currentCarouselHeight
            scrollView.invalidateOutline()
        }
    }

    /** Reset the MediaScrollView to the start. */
    fun scrollToStart() {
        scrollView.relativeScrollX = 0
    }

    /**
     * Smooth scroll to the destination player.
     *
     * @param sourceIndex optional source index to indicate where the scroll should begin.
     * @param destIndex destination index to indicate where the scroll should end.
     */
    fun scrollToPlayer(sourceIndex: Int = -1, destIndex: Int) {
        if (sourceIndex >= 0 && sourceIndex < mediaContent.childCount) {
            scrollView.relativeScrollX = sourceIndex * playerWidthPlusPadding
        }
        val destIndex = Math.min(mediaContent.getChildCount() - 1, destIndex)
        val view = mediaContent.getChildAt(destIndex)
        // We need to post this to wait for the active player becomes visible.
        mainExecutor.executeDelayed(
            { scrollView.smoothScrollTo(view.left, scrollView.scrollY) },
            SCROLL_DELAY
        )
    }

    companion object {
        private val CONTENT_TRANSLATION =
            object : FloatPropertyCompat<MediaCarouselScrollHandler>("contentTranslation") {
                override fun getValue(handler: MediaCarouselScrollHandler): Float {
                    return handler.contentTranslation
                }

                override fun setValue(handler: MediaCarouselScrollHandler, value: Float) {
                    handler.contentTranslation = value
                }
            }
    }
}
