/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.temporarydisplay.chipbar

import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.testing.TestableLooper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.animation.doOnCancel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.TintedIcon
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewUiEvent
import com.android.systemui.temporarydisplay.TemporaryViewUiEventLogger
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ChipbarCoordinatorTest : SysuiTestCase() {
    private lateinit var underTest: ChipbarCoordinator

    @Mock private lateinit var logger: ChipbarLogger
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var viewUtil: ViewUtil
    @Mock private lateinit var vibratorHelper: VibratorHelper
    @Mock private lateinit var swipeGestureHandler: SwipeChipbarAwayGestureHandler
    private lateinit var chipbarAnimator: TestChipbarAnimator
    private lateinit var fakeWakeLockBuilder: WakeLockFake.Builder
    private lateinit var fakeWakeLock: WakeLockFake
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var uiEventLogger: TemporaryViewUiEventLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenReturn(TIMEOUT)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        fakeWakeLock = WakeLockFake()
        fakeWakeLockBuilder = WakeLockFake.Builder(context)
        fakeWakeLockBuilder.setWakeLock(fakeWakeLock)

        uiEventLoggerFake = UiEventLoggerFake()
        uiEventLogger = TemporaryViewUiEventLogger(uiEventLoggerFake)
        chipbarAnimator = TestChipbarAnimator()

        underTest =
            ChipbarCoordinator(
                context,
                logger,
                windowManager,
                fakeExecutor,
                accessibilityManager,
                configurationController,
                dumpManager,
                powerManager,
                chipbarAnimator,
                falsingManager,
                falsingCollector,
                swipeGestureHandler,
                viewUtil,
                vibratorHelper,
                fakeWakeLockBuilder,
                fakeClock,
                uiEventLogger,
            )
        underTest.start()
    }

    @Test
    fun displayView_contentDescription_iconHasDescription() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("loadedCD")),
                Text.Loaded("text"),
                endItem = null,
            )
        )

        val contentDescView = getChipbarView().getInnerView()
        assertThat(contentDescView.contentDescription.toString()).contains("loadedCD")
        assertThat(contentDescView.contentDescription.toString()).contains("text")
    }

    @Test
    fun displayView_contentDescription_iconHasNoDescription() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("text"),
                endItem = null,
            )
        )

        val contentDescView = getChipbarView().getInnerView()
        assertThat(contentDescView.contentDescription.toString()).isEqualTo("text")
    }

    @Test
    fun displayView_contentDescription_endIsLoading() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("loadedCD")),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val contentDescView = getChipbarView().getInnerView()
        val loadingDesc = context.resources.getString(R.string.media_transfer_loading)
        assertThat(contentDescView.contentDescription.toString()).contains("text")
        assertThat(contentDescView.contentDescription.toString()).contains(loadingDesc)
    }

    @Test
    fun displayView_contentDescription_endNotLoading() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("loadedCD")),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Error,
            )
        )

        val contentDescView = getChipbarView().getInnerView()
        val loadingDesc = context.resources.getString(R.string.media_transfer_loading)
        assertThat(contentDescView.contentDescription.toString()).contains("text")
        assertThat(contentDescView.contentDescription.toString()).doesNotContain(loadingDesc)
    }

    @Test
    fun displayView_loadedIcon_correctlyRendered() {
        val drawable = context.getDrawable(R.drawable.ic_celebration)!!

        underTest.displayView(
            createChipbarInfo(
                Icon.Loaded(drawable, contentDescription = ContentDescription.Loaded("loadedCD")),
                Text.Loaded("text"),
                endItem = null,
            )
        )

        val iconView = getChipbarView().getStartIconView()
        assertThat(iconView.drawable).isEqualTo(drawable)
        assertThat(iconView.contentDescription).isEqualTo("loadedCD")
    }

    @Test
    fun displayView_resourceIcon_correctlyRendered() {
        val contentDescription = ContentDescription.Resource(R.string.controls_error_timeout)
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription),
                Text.Loaded("text"),
                endItem = null,
            )
        )

        val iconView = getChipbarView().getStartIconView()
        assertThat(iconView.contentDescription)
            .isEqualTo(contentDescription.loadContentDescription(context))
    }

    @Test
    fun displayView_loadedText_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("display view text here"),
                endItem = null,
            )
        )

        assertThat(getChipbarView().getChipText()).isEqualTo("display view text here")
    }

    @Test
    fun displayView_resourceText_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Resource(R.string.screenrecord_start_error),
                endItem = null,
            )
        )

        assertThat(getChipbarView().getChipText())
            .isEqualTo(context.getString(R.string.screenrecord_start_error))
    }

    @Test
    fun displayView_endItemNull_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = null,
            )
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun displayView_endItemLoading_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun displayView_endItemError_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Error,
            )
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun displayView_endItemButton_correctlyRendered() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem =
                    ChipbarEndItem.Button(
                        Text.Loaded("button text"),
                        onClickListener = {},
                    ),
            )
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getEndButton().text).isEqualTo("button text")
        assertThat(chipbarView.getEndButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun displayView_endItemButtonClicked_falseTap_listenerNotRun() {
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(true)
        var isClicked = false
        val buttonClickListener = View.OnClickListener { isClicked = true }

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem =
                    ChipbarEndItem.Button(
                        Text.Loaded("button text"),
                        buttonClickListener,
                    ),
            )
        )

        getChipbarView().getEndButton().performClick()

        assertThat(isClicked).isFalse()
    }

    @Test
    fun displayView_endItemButtonClicked_notFalseTap_listenerRun() {
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(false)
        var isClicked = false
        val buttonClickListener = View.OnClickListener { isClicked = true }

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem =
                    ChipbarEndItem.Button(
                        Text.Loaded("button text"),
                        buttonClickListener,
                    ),
            )
        )

        getChipbarView().getEndButton().performClick()

        assertThat(isClicked).isTrue()
    }

    @Test
    fun displayView_loading_animationStarted() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        assertThat(underTest.loadingDetails!!.animator.isStarted).isTrue()
    }

    @Test
    fun displayView_notLoading_noAnimation() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Error,
            )
        )

        assertThat(underTest.loadingDetails).isNull()
    }

    @Test
    fun displayView_loadingThenNotLoading_animationStopped() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val animator = underTest.loadingDetails!!.animator
        var cancelled = false
        animator.doOnCancel { cancelled = true }

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Button(Text.Loaded("button")) {},
            )
        )

        assertThat(cancelled).isTrue()
        assertThat(underTest.loadingDetails).isNull()
    }

    @Test
    fun displayView_loadingThenHideView_animationStopped() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val animator = underTest.loadingDetails!!.animator
        var cancelled = false
        animator.doOnCancel { cancelled = true }

        underTest.removeView(DEVICE_ID, "TestReason")

        assertThat(cancelled).isTrue()
        assertThat(underTest.loadingDetails).isNull()
    }

    @Test
    fun displayView_loadingThenNewLoading_animationStaysTheSame() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val animator = underTest.loadingDetails!!.animator
        var cancelled = false
        animator.doOnCancel { cancelled = true }

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("new text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        assertThat(underTest.loadingDetails!!.animator).isEqualTo(animator)
        assertThat(underTest.loadingDetails!!.animator.isStarted).isTrue()
        assertThat(cancelled).isFalse()
    }

    @Test
    fun displayView_vibrationEffect_doubleClickEffectWithHardwareFeedback() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = null,
                vibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK),
            )
        )

        verify(vibratorHelper)
            .vibrate(
                any(),
                any(),
                eq(VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)),
                any(),
                eq(VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)),
            )
    }

    /** Regression test for b/266119467. */
    @Test
    fun displayView_animationFailure_viewsStillBecomeVisible() {
        chipbarAnimator.allowAnimation = false

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.id.check_box, null),
                Text.Loaded("text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val view = getChipbarView()
        assertThat(view.getInnerView().alpha).isEqualTo(1f)
        assertThat(view.getStartIconView().alpha).isEqualTo(1f)
        assertThat(view.getLoadingIcon().alpha).isEqualTo(1f)
        assertThat(view.getChipTextView().alpha).isEqualTo(1f)
    }

    @Test
    fun updateView_viewUpdated() {
        // First, display a view
        val drawable = context.getDrawable(R.drawable.ic_celebration)!!

        underTest.displayView(
            createChipbarInfo(
                Icon.Loaded(drawable, contentDescription = ContentDescription.Loaded("loadedCD")),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getStartIconView().drawable).isEqualTo(drawable)
        assertThat(chipbarView.getStartIconView().contentDescription).isEqualTo("loadedCD")
        assertThat(chipbarView.getChipText()).isEqualTo("title text")
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.GONE)

        // WHEN the view is updated
        val newDrawable = context.getDrawable(R.drawable.ic_cake)!!
        underTest.updateView(
            createChipbarInfo(
                Icon.Loaded(newDrawable, ContentDescription.Loaded("new CD")),
                Text.Loaded("new title text"),
                endItem = ChipbarEndItem.Error,
            ),
            chipbarView
        )

        // THEN we display the new view
        assertThat(chipbarView.getStartIconView().drawable).isEqualTo(newDrawable)
        assertThat(chipbarView.getStartIconView().contentDescription).isEqualTo("new CD")
        assertThat(chipbarView.getChipText()).isEqualTo("new title text")
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getEndButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun viewUpdates_logged() {
        val drawable = context.getDrawable(R.drawable.ic_celebration)!!
        underTest.displayView(
            createChipbarInfo(
                Icon.Loaded(drawable, contentDescription = ContentDescription.Loaded("loadedCD")),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
            )
        )

        verify(logger).logViewUpdate(eq(WINDOW_TITLE), eq("title text"), any())

        underTest.displayView(
            createChipbarInfo(
                Icon.Loaded(drawable, ContentDescription.Loaded("new CD")),
                Text.Loaded("new title text"),
                endItem = ChipbarEndItem.Error,
            )
        )

        verify(logger).logViewUpdate(eq(WINDOW_TITLE), eq("new title text"), any())
    }

    /** Regression test for b/266209420. */
    @Test
    fun displayViewThenImmediateRemoval_viewStillRemoved() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Error,
            ),
        )
        val chipbarView = getChipbarView()

        underTest.removeView(DEVICE_ID, "test reason")

        verify(windowManager).removeView(chipbarView)
    }

    /** Regression test for b/266209420. */
    @Test
    fun removeView_animationFailure_viewStillRemoved() {
        chipbarAnimator.allowAnimation = false

        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Error,
            ),
        )
        val chipbarView = getChipbarView()

        underTest.removeView(DEVICE_ID, "test reason")

        verify(windowManager).removeView(chipbarView)
    }

    @Test
    fun swipeToDismiss_false_neverListensForGesture() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
                allowSwipeToDismiss = false,
            )
        )

        verify(swipeGestureHandler, never()).addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun swipeToDismiss_true_listensForGesture() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
                allowSwipeToDismiss = true,
            )
        )

        verify(swipeGestureHandler).addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun swipeToDismiss_swipeOccurs_viewDismissed_manuallyDismissedLogged() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
                allowSwipeToDismiss = true,
            )
        )
        val view = getChipbarView()

        val callbackCaptor = argumentCaptor<(MotionEvent) -> Unit>()
        verify(swipeGestureHandler).addOnGestureDetectedCallback(any(), capture(callbackCaptor))

        callbackCaptor.value.invoke(MotionEvent.obtain(0L, 0L, 0, 0f, 0f, 0))

        verify(windowManager).removeView(view)
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_MANUALLY_DISMISSED.id)
    }

    @Test
    fun swipeToDismiss_viewUpdatedToFalse_swipeOccurs_viewNotDismissed() {
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
                allowSwipeToDismiss = true,
            )
        )
        val view = getChipbarView()
        val callbackCaptor = argumentCaptor<(MotionEvent) -> Unit>()
        verify(swipeGestureHandler).addOnGestureDetectedCallback(any(), capture(callbackCaptor))

        // only one log for view addition
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_ADDED.id)

        // WHEN the view is updated to not allow swipe-to-dismiss
        underTest.displayView(
            createChipbarInfo(
                Icon.Resource(R.drawable.ic_cake, contentDescription = null),
                Text.Loaded("title text"),
                endItem = ChipbarEndItem.Loading,
                allowSwipeToDismiss = false,
            )
        )

        // THEN the callback is removed
        verify(swipeGestureHandler).removeOnGestureDetectedCallback(any())

        // And WHEN the old callback is invoked
        callbackCaptor.value.invoke(MotionEvent.obtain(0L, 0L, 0, 0f, 0f, 0))

        // THEN it is ignored and view isn't removed
        verify(windowManager, never()).removeView(view)
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
    }

    private fun createChipbarInfo(
        startIcon: Icon,
        text: Text,
        endItem: ChipbarEndItem?,
        vibrationEffect: VibrationEffect? = null,
        allowSwipeToDismiss: Boolean = false,
    ): ChipbarInfo {
        return ChipbarInfo(
            TintedIcon(startIcon, tint = null),
            text,
            endItem,
            vibrationEffect,
            allowSwipeToDismiss,
            windowTitle = WINDOW_TITLE,
            wakeReason = WAKE_REASON,
            timeoutMs = TIMEOUT,
            id = DEVICE_ID,
            priority = ViewPriority.NORMAL,
            instanceId = InstanceId.fakeInstanceId(0),
        )
    }

    private fun ViewGroup.getInnerView() = this.requireViewById<ViewGroup>(R.id.chipbar_inner)

    private fun ViewGroup.getStartIconView() = this.requireViewById<ImageView>(R.id.start_icon)

    private fun ViewGroup.getChipTextView() = this.requireViewById<TextView>(R.id.text)

    private fun ViewGroup.getChipText(): String = this.getChipTextView().text as String

    private fun ViewGroup.getLoadingIcon(): View = this.requireViewById(R.id.loading)

    private fun ViewGroup.getEndButton(): TextView = this.requireViewById(R.id.end_button)

    private fun ViewGroup.getErrorIcon(): View = this.requireViewById(R.id.error)

    private fun getChipbarView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    /** Test class that lets us disallow animations. */
    inner class TestChipbarAnimator : ChipbarAnimator() {
        var allowAnimation: Boolean = true

        override fun animateViewIn(innerView: ViewGroup, onAnimationEnd: Runnable): Boolean {
            if (!allowAnimation) {
                return false
            }
            return super.animateViewIn(innerView, onAnimationEnd)
        }

        override fun animateViewOut(innerView: ViewGroup, onAnimationEnd: Runnable): Boolean {
            if (!allowAnimation) {
                return false
            }
            return super.animateViewOut(innerView, onAnimationEnd)
        }
    }
}

private const val TIMEOUT = 10000
private const val WINDOW_TITLE = "Test Chipbar Window Title"
private const val WAKE_REASON = "TEST_CHIPBAR_WAKE_REASON"
private const val DEVICE_ID = "id"
