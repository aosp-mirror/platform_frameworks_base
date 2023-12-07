package com.android.wm.shell.windowdecor.viewholder

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.view.View

/**
 * Encapsulates the root [View] of a window decoration and its children to facilitate looking up
 * children (via findViewById) and updating to the latest data from [RunningTaskInfo].
 */
internal abstract class DesktopModeWindowDecorationViewHolder(rootView: View) {
  val context: Context = rootView.context

  /**
   * A signal to the view holder that new data is available and that the views should be updated to
   * reflect it.
   */
  abstract fun bindData(taskInfo: RunningTaskInfo)

    /** Callback when the handle menu is opened. */
    abstract fun onHandleMenuOpened()

    /** Callback when the handle menu is closed. */
    abstract fun onHandleMenuClosed()
}
