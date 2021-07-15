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

package com.android.egg.neko

import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleTemplate
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.internal.logging.MetricsLogger
import java.util.Random
import java.util.concurrent.Flow
import java.util.function.Consumer

import com.android.egg.R

const val CONTROL_ID_WATER = "water"
const val CONTROL_ID_FOOD = "food"
const val CONTROL_ID_TOY = "toy"

const val FOOD_SPAWN_CAT_DELAY_MINS = 5L

const val COLOR_FOOD_FG = 0xFFFF8000.toInt()
const val COLOR_FOOD_BG = COLOR_FOOD_FG and 0x40FFFFFF.toInt()
const val COLOR_WATER_FG = 0xFF0080FF.toInt()
const val COLOR_WATER_BG = COLOR_WATER_FG and 0x40FFFFFF.toInt()
const val COLOR_TOY_FG = 0xFFFF4080.toInt()
const val COLOR_TOY_BG = COLOR_TOY_FG and 0x40FFFFFF.toInt()

val P_TOY_ICONS = intArrayOf(
        1, R.drawable.ic_toy_mouse,
        1, R.drawable.ic_toy_fish,
        1, R.drawable.ic_toy_ball,
        1, R.drawable.ic_toy_laser
)

@RequiresApi(30)
fun Control_toString(control: Control): String {
    val hc = String.format("0x%08x", control.hashCode())
    return ("Control($hc id=${control.controlId}, type=${control.deviceType}, " +
        "title=${control.title}, template=${control.controlTemplate})")
}

@RequiresApi(30)
public class NekoControlsService : ControlsProviderService(), PrefState.PrefsListener {
    private val TAG = "NekoControls"

    private val controls = HashMap<String, Control>()
    private val publishers = ArrayList<UglyPublisher>()
    private val rng = Random()
    private val metricsLogger = MetricsLogger()

    private var lastToyIcon: Icon? = null

    private lateinit var prefs: PrefState

    override fun onCreate() {
        super.onCreate()

        prefs = PrefState(this)
        prefs.setListener(this)

        createDefaultControls()
    }

    override fun onPrefsChanged() {
        createDefaultControls()
    }

    private fun createDefaultControls() {
        val foodState: Int = prefs.foodState
        if (foodState != 0) {
            NekoService.registerJobIfNeeded(this, FOOD_SPAWN_CAT_DELAY_MINS)
        }

        val water = prefs.waterState

        controls[CONTROL_ID_WATER] = makeWaterBowlControl(water)
        controls[CONTROL_ID_FOOD] = makeFoodBowlControl(foodState != 0)
        controls[CONTROL_ID_TOY] = makeToyControl(currentToyIcon(), false)
    }

    private fun currentToyIcon(): Icon {
        val icon = lastToyIcon ?: randomToyIcon()
        lastToyIcon = icon
        return icon
    }

    private fun randomToyIcon(): Icon {
        return Icon.createWithResource(resources, Cat.chooseP(rng, P_TOY_ICONS, 4))
    }

    private fun colorize(s: CharSequence, color: Int): CharSequence {
        val ssb = SpannableStringBuilder(s)
        ssb.setSpan(ForegroundColorSpan(color), 0, s.length, 0)
        return ssb
    }

    private fun makeToyControl(icon: Icon?, thrown: Boolean): Control {
        return Control.StatefulBuilder(CONTROL_ID_TOY, getPendingIntent())
                .setDeviceType(DeviceTypes.TYPE_UNKNOWN)
                .setCustomIcon(icon)
                        //  ?.setTint(COLOR_TOY_FG)) // TODO(b/159559045): uncomment when fixed
                .setCustomColor(ColorStateList.valueOf(COLOR_TOY_BG))
                .setTitle(colorize(getString(R.string.control_toy_title), COLOR_TOY_FG))
                .setStatusText(colorize(
                        if (thrown) getString(R.string.control_toy_status) else "",
                        COLOR_TOY_FG))
                .setControlTemplate(StatelessTemplate("toy"))
                .setStatus(Control.STATUS_OK)
                .setSubtitle(if (thrown) "" else getString(R.string.control_toy_subtitle))
                .setAppIntent(getAppIntent())
                .build()
    }

    private fun makeWaterBowlControl(fillLevel: Float): Control {
        return Control.StatefulBuilder(CONTROL_ID_WATER, getPendingIntent())
                .setDeviceType(DeviceTypes.TYPE_KETTLE)
                .setTitle(colorize(getString(R.string.control_water_title), COLOR_WATER_FG))
                .setCustomColor(ColorStateList.valueOf(COLOR_WATER_BG))
                .setCustomIcon(Icon.createWithResource(resources,
                        if (fillLevel >= 100f) R.drawable.ic_water_filled else R.drawable.ic_water))
                        //.setTint(COLOR_WATER_FG)) // TODO(b/159559045): uncomment when fixed
                .setControlTemplate(RangeTemplate("waterlevel", 0f, 200f, fillLevel, 10f,
                        "%.0f mL"))
                .setStatus(Control.STATUS_OK)
                .setSubtitle(if (fillLevel == 0f) getString(R.string.control_water_subtitle) else "")
                .build()
    }

    private fun makeFoodBowlControl(filled: Boolean): Control {
        return Control.StatefulBuilder(CONTROL_ID_FOOD, getPendingIntent())
                .setDeviceType(DeviceTypes.TYPE_UNKNOWN)
                .setCustomColor(ColorStateList.valueOf(COLOR_FOOD_BG))
                .setTitle(colorize(getString(R.string.control_food_title), COLOR_FOOD_FG))
                .setCustomIcon(Icon.createWithResource(resources,
                        if (filled) R.drawable.ic_foodbowl_filled else R.drawable.ic_bowl))
                        // .setTint(COLOR_FOOD_FG)) // TODO(b/159559045): uncomment when fixed
                .setStatusText(
                        if (filled) colorize(
                                getString(R.string.control_food_status_full), 0xCCFFFFFF.toInt())
                        else colorize(
                                getString(R.string.control_food_status_empty), 0x80FFFFFF.toInt()))
                .setControlTemplate(ToggleTemplate("foodbowl", ControlButton(filled, "Refill")))
                .setStatus(Control.STATUS_OK)
                .setSubtitle(if (filled) "" else getString(R.string.control_food_subtitle))
                .build()
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
                .setClass(this, NekoLand::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getAppIntent(): PendingIntent {
        return getPendingIntent()
    }

    override fun performControlAction(
        controlId: String,
        action: ControlAction,
        consumer: Consumer<Int>
    ) {
        when (controlId) {
            CONTROL_ID_FOOD -> {
                // refill bowl
                controls[CONTROL_ID_FOOD] = makeFoodBowlControl(true)
                Log.v(TAG, "Bowl refilled. (Registering job.)")
                NekoService.registerJob(this, FOOD_SPAWN_CAT_DELAY_MINS)
                metricsLogger.histogram("egg_neko_offered_food", 11)
                prefs.foodState = 11
            }
            CONTROL_ID_TOY -> {
                Log.v(TAG, "Toy tossed.")
                controls[CONTROL_ID_TOY] =
                    makeToyControl(currentToyIcon(), true)
                // TODO: re-enable toy
                Thread() {
                    Thread.sleep((1 + Random().nextInt(4)) * 1000L)
                    NekoService.getExistingCat(prefs)?.let {
                        NekoService.notifyCat(this, it)
                    }
                    controls[CONTROL_ID_TOY] = makeToyControl(randomToyIcon(), false)
                    pushControlChanges()
                }.start()
            }
            CONTROL_ID_WATER -> {
                if (action is FloatAction) {
                    controls[CONTROL_ID_WATER] = makeWaterBowlControl(action.newValue)
                    Log.v(TAG, "Water level set to " + action.newValue)
                    prefs.waterState = action.newValue
                }
            }
            else -> {
                return
            }
        }
        consumer.accept(ControlAction.RESPONSE_OK)
        pushControlChanges()
    }

    private fun pushControlChanges() {
        Thread() {
            publishers.forEach { it.refresh() }
        }.start()
    }

    private fun makeStateless(c: Control?): Control? {
        if (c == null) return null
        return Control.StatelessBuilder(c.controlId, c.appIntent)
                .setTitle(c.title)
                .setSubtitle(c.subtitle)
                .setStructure(c.structure)
                .setDeviceType(c.deviceType)
                .setCustomIcon(c.customIcon)
                .setCustomColor(c.customColor)
                .build()
    }

    override fun createPublisherFor(list: MutableList<String>): Flow.Publisher<Control> {
        createDefaultControls()

        val publisher = UglyPublisher(list, true)
        publishers.add(publisher)
        return publisher
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        createDefaultControls()

        val publisher = UglyPublisher(controls.keys, false)
        publishers.add(publisher)
        return publisher
    }

    private inner class UglyPublisher(
        val controlKeys: Iterable<String>,
        val indefinite: Boolean
    ) : Flow.Publisher<Control> {
        val subscriptions = ArrayList<UglySubscription>()

        private inner class UglySubscription(
            val initialControls: Iterator<Control>,
            var subscriber: Flow.Subscriber<in Control>?
        ) : Flow.Subscription {
            override fun cancel() {
                Log.v(TAG, "cancel subscription: $this for subscriber: $subscriber " +
                        "to publisher: $this@UglyPublisher")
                subscriber = null
                unsubscribe(this)
            }

            override fun request(p0: Long) {
                (0 until p0).forEach { _ ->
                    if (initialControls.hasNext()) {
                        send(initialControls.next())
                    } else {
                        if (!indefinite) subscriber?.onComplete()
                    }
                }
            }

            fun send(c: Control) {
                Log.v(TAG, "sending update: " + Control_toString(c) + " => " + subscriber)
                subscriber?.onNext(c)
            }
        }

        override fun subscribe(subscriber: Flow.Subscriber<in Control>) {
            Log.v(TAG, "subscribe to publisher: $this by subscriber: $subscriber")
            val sub = UglySubscription(controlKeys.mapNotNull { controls[it] }.iterator(),
            subscriber)
            subscriptions.add(sub)
            subscriber.onSubscribe(sub)
        }

        fun unsubscribe(sub: UglySubscription) {
            Log.v(TAG, "no more subscriptions, removing subscriber: $sub")
            subscriptions.remove(sub)
            if (subscriptions.size == 0) {
                Log.v(TAG, "no more subscribers, removing publisher: $this")
                publishers.remove(this)
            }
        }

        fun refresh() {
            controlKeys.mapNotNull { controls[it] }.forEach { control ->
                subscriptions.forEach { sub ->
                    sub.send(control)
                }
            }
        }
    }
}
