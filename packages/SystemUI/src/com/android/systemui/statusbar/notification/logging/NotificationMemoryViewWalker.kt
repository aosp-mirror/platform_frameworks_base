package com.android.systemui.statusbar.notification.logging

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.internal.R
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.util.children

/** Walks view hiearchy of a given notification to estimate its memory use. */
internal object NotificationMemoryViewWalker {

    private const val TAG = "NotificationMemory"

    /** Builder for [NotificationViewUsage] objects. */
    private class UsageBuilder {
        private var smallIcon: Int = 0
        private var largeIcon: Int = 0
        private var systemIcons: Int = 0
        private var style: Int = 0
        private var customViews: Int = 0
        private var softwareBitmaps = 0

        fun addSmallIcon(smallIconUse: Int) = apply { smallIcon += smallIconUse }
        fun addLargeIcon(largeIconUse: Int) = apply { largeIcon += largeIconUse }
        fun addSystem(systemIconUse: Int) = apply { systemIcons += systemIconUse }
        fun addStyle(styleUse: Int) = apply { style += styleUse }
        fun addSoftwareBitmapPenalty(softwareBitmapUse: Int) = apply {
            softwareBitmaps += softwareBitmapUse
        }

        fun addCustomViews(customViewsUse: Int) = apply { customViews += customViewsUse }

        fun build(viewType: ViewType): NotificationViewUsage {
            return NotificationViewUsage(
                viewType = viewType,
                smallIcon = smallIcon,
                largeIcon = largeIcon,
                systemIcons = systemIcons,
                style = style,
                customViews = customViews,
                softwareBitmapsPenalty = softwareBitmaps,
            )
        }
    }

    /**
     * Returns memory usage of public and private views contained in passed
     * [ExpandableNotificationRow]
     */
    fun getViewUsage(row: ExpandableNotificationRow?): List<NotificationViewUsage> {
        if (row == null) {
            return listOf()
        }

        // The ordering here is significant since it determines deduplication of seen drawables.
        return listOf(
            getViewUsage(ViewType.PRIVATE_EXPANDED_VIEW, row.privateLayout?.expandedChild),
            getViewUsage(ViewType.PRIVATE_CONTRACTED_VIEW, row.privateLayout?.contractedChild),
            getViewUsage(ViewType.PRIVATE_HEADS_UP_VIEW, row.privateLayout?.headsUpChild),
            getViewUsage(ViewType.PUBLIC_VIEW, row.publicLayout),
            getTotalUsage(row)
        )
    }

    /**
     * Calculate total usage of all views - we need to do a separate traversal to make sure we don't
     * double count fields.
     */
    private fun getTotalUsage(row: ExpandableNotificationRow): NotificationViewUsage {
        val totalUsage = UsageBuilder()
        val seenObjects = hashSetOf<Int>()

        row.publicLayout?.let { computeViewHierarchyUse(it, totalUsage, seenObjects) }
        row.privateLayout?.let { child ->
            for (view in listOf(child.expandedChild, child.contractedChild, child.headsUpChild)) {
                (view as? ViewGroup)?.let { v ->
                    computeViewHierarchyUse(v, totalUsage, seenObjects)
                }
            }
        }
        return totalUsage.build(ViewType.TOTAL)
    }

    private fun getViewUsage(
        type: ViewType,
        rootView: View?,
        seenObjects: HashSet<Int> = hashSetOf()
    ): NotificationViewUsage {
        val usageBuilder = UsageBuilder()
        (rootView as? ViewGroup)?.let { computeViewHierarchyUse(it, usageBuilder, seenObjects) }
        return usageBuilder.build(type)
    }

    private fun computeViewHierarchyUse(
        rootView: ViewGroup,
        builder: UsageBuilder,
        seenObjects: HashSet<Int> = hashSetOf(),
    ) {
        for (child in rootView.children) {
            if (child is ViewGroup) {
                computeViewHierarchyUse(child, builder, seenObjects)
            } else {
                computeViewUse(child, builder, seenObjects)
            }
        }
    }

    private fun computeViewUse(view: View, builder: UsageBuilder, seenObjects: HashSet<Int>) {
        if (view !is ImageView) return
        val drawable = view.drawable ?: return
        val drawableRef = System.identityHashCode(drawable)
        if (seenObjects.contains(drawableRef)) return
        val drawableUse = computeDrawableUse(drawable, seenObjects)
        // TODO(b/235451049): We need to make sure we traverse large icon before small icon -
        // sometimes the large icons are assigned to small icon views and we want to
        // attribute them to large view in those cases.
        when (view.id) {
            R.id.left_icon,
            R.id.icon,
            R.id.conversation_icon -> builder.addSmallIcon(drawableUse)
            R.id.right_icon -> builder.addLargeIcon(drawableUse)
            R.id.big_picture -> builder.addStyle(drawableUse)
            // Elements that are part of platform with resources
            R.id.phishing_alert,
            R.id.feedback,
            R.id.alerted_icon,
            R.id.expand_button_icon,
            R.id.remote_input_send -> builder.addSystem(drawableUse)
            // Custom view ImageViews
            else -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Custom view: ${identifierForView(view)}")
                }
                builder.addCustomViews(drawableUse)
            }
        }

        if (isDrawableSoftwareBitmap(drawable)) {
            builder.addSoftwareBitmapPenalty(drawableUse)
        }

        seenObjects.add(drawableRef)
    }

    private fun computeDrawableUse(drawable: Drawable, seenObjects: HashSet<Int>): Int =
        when (drawable) {
            is BitmapDrawable -> {
                val ref = System.identityHashCode(drawable.bitmap)
                if (seenObjects.contains(ref)) {
                    0
                } else {
                    seenObjects.add(ref)
                    drawable.bitmap.allocationByteCount
                }
            }
            else -> 0
        }

    private fun isDrawableSoftwareBitmap(drawable: Drawable) =
        drawable is BitmapDrawable && drawable.bitmap.config != Bitmap.Config.HARDWARE

    private fun identifierForView(view: View) =
        if (view.id == View.NO_ID) {
            "no-id"
        } else {
            view.resources.getResourceName(view.id)
        }
}
