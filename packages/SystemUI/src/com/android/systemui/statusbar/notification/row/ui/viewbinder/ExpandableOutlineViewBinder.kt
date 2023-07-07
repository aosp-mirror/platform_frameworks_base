package com.android.systemui.statusbar.notification.row.ui.viewbinder

import com.android.systemui.statusbar.notification.row.ExpandableOutlineView
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ExpandableOutlineViewModel as ViewModel

object ExpandableOutlineViewBinder {
    fun bind(
        viewModel: ViewModel,
        view: ExpandableOutlineView,
    ) {
        ExpandableViewBinder.bind(viewModel, view)
    }
}
