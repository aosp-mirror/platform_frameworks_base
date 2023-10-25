/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.graphics.drawable.AnimatedStateListDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.util.StateSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.res.R

class DeviceEntryIconView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttrs: Int = 0,
) : FrameLayout(context, attrs, defStyleAttrs) {
    val longPressHandlingView: LongPressHandlingView =
        LongPressHandlingView(context, attrs) {
            context.resources.getInteger(R.integer.config_lockIconLongPress).toLong()
        }
    val iconView: ImageView = ImageView(context, attrs).apply { id = R.id.device_entry_icon_fg }
    val bgView: ImageView = ImageView(context, attrs).apply { id = R.id.device_entry_icon_bg }
    val aodFpDrawable: LottieDrawable = LottieDrawable()
    var accessibilityHintType: AccessibilityHintType = AccessibilityHintType.NONE

    private var animatedIconDrawable: AnimatedStateListDrawable = AnimatedStateListDrawable()

    init {
        setupIconStates()
        setupIconTransitions()
        setupAccessibilityDelegate()

        // Ordering matters. From background to foreground we want:
        //     bgView, iconView, longpressHandlingView overlay
        addBgImageView()
        addIconImageView()
        addLongpressHandlingView()
    }

    private fun setupAccessibilityDelegate() {
        accessibilityDelegate =
            object : AccessibilityDelegate() {
                private val accessibilityAuthenticateHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_authenticate_hint)
                    )
                private val accessibilityEnterHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_enter_hint)
                    )
                override fun onInitializeAccessibilityNodeInfo(
                    v: View,
                    info: AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    when (accessibilityHintType) {
                        AccessibilityHintType.AUTHENTICATE ->
                            info.addAction(accessibilityAuthenticateHint)
                        AccessibilityHintType.ENTER -> info.addAction(accessibilityEnterHint)
                        AccessibilityHintType.NONE -> return
                    }
                }
            }
    }

    private fun setupIconStates() {
        // Lockscreen States
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, false),
            context.getDrawable(R.drawable.ic_lock)!!,
            R.id.locked,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, false),
            context.getDrawable(R.drawable.ic_unlocked)!!,
            R.id.unlocked,
        )
        // FINGERPRINT
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, false),
            context.getDrawable(R.drawable.ic_fingerprint)!!,
            R.id.locked_fp,
        )

        // AOD states
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, true),
            context.getDrawable(R.drawable.ic_lock_aod)!!,
            R.id.locked_aod,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, true),
            context.getDrawable(R.drawable.ic_unlocked_aod)!!,
            R.id.unlocked_aod,
        )
        // FINGERPRINT
        LottieCompositionFactory.fromRawRes(mContext, R.raw.udfps_aod_fp).addListener { result ->
            aodFpDrawable.setComposition(result)
        }
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, true),
            aodFpDrawable,
            R.id.udfps_aod_fp,
        )

        // WILDCARD: should always be the last state added since any states will match with this
        // and therefore won't get matched with subsequent states.
        animatedIconDrawable.addState(
            StateSet.WILD_CARD,
            context.getDrawable(R.color.transparent)!!,
            R.id.no_icon,
        )
    }

    private fun setupIconTransitions() {
        // LockscreenFp <=> LockscreenUnlocked
        animatedIconDrawable.addTransition(
            R.id.locked_fp,
            R.id.unlocked,
            context.getDrawable(R.drawable.fp_to_unlock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked_fp,
            context.getDrawable(R.drawable.unlock_to_fp) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenLocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.locked_aod,
            R.id.locked,
            context.getDrawable(R.drawable.lock_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.lock_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenUnlocked <=> AodUnlocked
        animatedIconDrawable.addTransition(
            R.id.unlocked_aod,
            R.id.unlocked,
            context.getDrawable(R.drawable.unlocked_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.unlocked_aod,
            context.getDrawable(R.drawable.unlocked_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenLocked <=> LockscreenUnlocked
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.unlocked,
            context.getDrawable(R.drawable.lock_to_unlock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked,
            context.getDrawable(R.drawable.unlocked_to_locked) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenFingerprint <=> LockscreenLocked
        animatedIconDrawable.addTransition(
            R.id.locked_fp,
            R.id.locked,
            context.getDrawable(R.drawable.fp_to_locked) as AnimatedVectorDrawable,
            /* reversible */ true,
        )

        // LockscreenUnlocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.unlocked_to_aod_lock) as AnimatedVectorDrawable,
            /* reversible */ true,
        )
    }

    private fun addLongpressHandlingView() {
        addView(longPressHandlingView)
        val lp = longPressHandlingView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        longPressHandlingView.layoutParams = lp
    }

    private fun addIconImageView() {
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.setImageDrawable(animatedIconDrawable)
        addView(iconView)
        val lp = iconView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER
        iconView.layoutParams = lp
    }

    private fun addBgImageView() {
        bgView.setImageDrawable(context.getDrawable(R.drawable.fingerprint_bg))
        addView(bgView)
        val lp = bgView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        bgView.layoutParams = lp
    }

    fun getIconState(icon: IconType, aod: Boolean): IntArray {
        val lockIconState = IntArray(2)
        when (icon) {
            IconType.LOCK -> lockIconState[0] = android.R.attr.state_first
            IconType.UNLOCK -> lockIconState[0] = android.R.attr.state_last
            IconType.FINGERPRINT -> lockIconState[0] = android.R.attr.state_middle
        }
        if (aod) {
            lockIconState[1] = android.R.attr.state_single
        } else {
            lockIconState[1] = -android.R.attr.state_single
        }
        return lockIconState
    }

    enum class IconType {
        LOCK,
        UNLOCK,
        FINGERPRINT,
    }

    enum class AccessibilityHintType {
        NONE,
        AUTHENTICATE,
        ENTER,
    }
}
