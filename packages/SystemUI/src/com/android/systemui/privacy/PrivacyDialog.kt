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

package com.android.systemui.privacy

import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dialog to show ongoing and recent app ops usage.
 *
 * @see PrivacyDialogController
 * @param context A context to create the dialog
 * @param list list of elements to show in the dialog. The elements will show in the same order they
 *             appear in the list
 * @param activityStarter a callback to start an activity for a given package name, user id, attributionTag and intent
 */
class PrivacyDialog(
    context: Context,
    private val list: List<PrivacyElement>,
    activityStarter: (String, Int, CharSequence?, Intent?) -> Unit
) : SystemUIDialog(context, R.style.PrivacyDialog) {

    private val dismissListeners = mutableListOf<WeakReference<OnDialogDismissed>>()
    private val dismissed = AtomicBoolean(false)

    private val iconColorSolid = Utils.getColorAttrDefaultColor(
            this.context, com.android.internal.R.attr.colorPrimary
    )
    private val enterpriseText = " ${context.getString(R.string.ongoing_privacy_dialog_enterprise)}"
    private val phonecall = context.getString(R.string.ongoing_privacy_dialog_phonecall)

    private lateinit var rootView: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            attributes.fitInsetsTypes = attributes.fitInsetsTypes or WindowInsets.Type.statusBars()
            attributes.receiveInsetsIgnoringZOrder = true
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        setTitle(R.string.ongoing_privacy_dialog_a11y_title)
        setContentView(R.layout.privacy_dialog)
        rootView = requireViewById<ViewGroup>(R.id.root)

        list.forEach {
            rootView.addView(createView(it))
        }
    }

    /**
     * Add a listener that will be called when the dialog is dismissed.
     *
     * If the dialog has already been dismissed, the listener will be called immediately, in the
     * same thread.
     */
    fun addOnDismissListener(listener: OnDialogDismissed) {
        if (dismissed.get()) {
            listener.onDialogDismissed()
        } else {
            dismissListeners.add(WeakReference(listener))
        }
    }

    override fun stop() {
        dismissed.set(true)
        val iterator = dismissListeners.iterator()
        while (iterator.hasNext()) {
            val el = iterator.next()
            iterator.remove()
            el.get()?.onDialogDismissed()
        }
    }

    private fun createView(element: PrivacyElement): View {
        val newView = LayoutInflater.from(context).inflate(
                R.layout.privacy_dialog_item, rootView, false
        ) as ViewGroup
        val d = getDrawableForType(element.type)
        d.findDrawableByLayerId(R.id.icon).setTint(iconColorSolid)
        newView.requireViewById<ImageView>(R.id.icon).apply {
            setImageDrawable(d)
            contentDescription = element.type.getName(context)
        }
        val stringId = getStringIdForState(element.active)
        val app = if (element.phoneCall) phonecall else element.applicationName
        val appName = if (element.enterprise) {
            TextUtils.concat(app, enterpriseText)
        } else {
            app
        }
        val firstLine = context.getString(stringId, appName)
        val finalText = getFinalText(firstLine, element.attributionLabel, element.proxyLabel)
        newView.requireViewById<TextView>(R.id.text).text = finalText
        if (element.phoneCall) {
            newView.requireViewById<View>(R.id.chevron).visibility = View.GONE
        }
        newView.apply {
            setTag(element)
            if (!element.phoneCall) {
                setOnClickListener(clickListener)
            }
        }
        return newView
    }

    private fun getFinalText(
        firstLine: CharSequence,
        attributionLabel: CharSequence?,
        proxyLabel: CharSequence?
    ): CharSequence {
        var dialogText: CharSequence? = null
        if (attributionLabel != null && proxyLabel != null) {
            dialogText = context.getString(R.string.ongoing_privacy_dialog_attribution_proxy_label,
                attributionLabel, proxyLabel)
        } else if (attributionLabel != null) {
            dialogText = context.getString(R.string.ongoing_privacy_dialog_attribution_label,
                attributionLabel)
        } else if (proxyLabel != null) {
            dialogText = context.getString(R.string.ongoing_privacy_dialog_attribution_text,
                proxyLabel)
        }
        return if (dialogText != null) TextUtils.concat(firstLine, " ", dialogText) else firstLine
    }

    private fun getStringIdForState(active: Boolean): Int {
        return if (active) {
            R.string.ongoing_privacy_dialog_using_op
        } else {
            R.string.ongoing_privacy_dialog_recent_op
        }
    }

    private fun getDrawableForType(type: PrivacyType): LayerDrawable {
        return context.getDrawable(when (type) {
            PrivacyType.TYPE_LOCATION -> R.drawable.privacy_item_circle_location
            PrivacyType.TYPE_CAMERA -> R.drawable.privacy_item_circle_camera
            PrivacyType.TYPE_MICROPHONE -> R.drawable.privacy_item_circle_microphone
            PrivacyType.TYPE_MEDIA_PROJECTION -> R.drawable.privacy_item_circle_media_projection
        }) as LayerDrawable
    }

    private val clickListener = View.OnClickListener { v ->
        v.tag?.let {
            val element = it as PrivacyElement
            activityStarter(element.packageName, element.userId,
                element.attributionTag, element.navigationIntent)
        }
    }

    /** */
    data class PrivacyElement(
        val type: PrivacyType,
        val packageName: String,
        val userId: Int,
        val applicationName: CharSequence,
        val attributionTag: CharSequence?,
        val attributionLabel: CharSequence?,
        val proxyLabel: CharSequence?,
        val lastActiveTimestamp: Long,
        val active: Boolean,
        val enterprise: Boolean,
        val phoneCall: Boolean,
        val permGroupName: CharSequence,
        val navigationIntent: Intent?
    ) {
        private val builder = StringBuilder("PrivacyElement(")

        init {
            builder.append("type=${type.logName}")
            builder.append(", packageName=$packageName")
            builder.append(", userId=$userId")
            builder.append(", appName=$applicationName")
            if (attributionTag != null) {
                builder.append(", attributionTag=$attributionTag")
            }
            if (attributionLabel != null) {
                builder.append(", attributionLabel=$attributionLabel")
            }
            if (proxyLabel != null) {
                builder.append(", proxyLabel=$proxyLabel")
            }
            builder.append(", lastActive=$lastActiveTimestamp")
            if (active) {
                builder.append(", active")
            }
            if (enterprise) {
                builder.append(", enterprise")
            }
            if (phoneCall) {
                builder.append(", phoneCall")
            }
            builder.append(", permGroupName=$permGroupName)")
            if (navigationIntent != null) {
                builder.append(", navigationIntent=$navigationIntent")
            }
        }

        override fun toString(): String = builder.toString()
    }

    /** */
    interface OnDialogDismissed {
        fun onDialogDismissed()
    }
}