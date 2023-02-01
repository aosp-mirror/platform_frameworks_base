package com.android.systemui.statusbar.notification.fsi

import android.annotation.UiContext
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.fsi.FsiDebug.Companion.log
import com.android.wm.shell.TaskView
import com.android.wm.shell.TaskViewFactory
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Handle view-related data for fullscreen intent container on lockscreen. Wraps FsiChromeRepo,
 * transforms events/state into view-relevant representation for FsiChromeView. Alive for lifetime
 * of SystemUI.
 */
@SysUISingleton
class FsiChromeViewModelFactory
@Inject
constructor(
    val repo: FsiChromeRepo,
    val taskViewFactory: Optional<TaskViewFactory>,
    @UiContext val context: Context,
    @Main val mainExecutor: Executor,
) : CoreStartable {

    companion object {
        private const val classTag = "FsiChromeViewModelFactory"
    }

    val viewModelFlow: Flow<FsiChromeViewModel?> =
        repo.infoFlow.mapLatest { fsiInfo ->
            fsiInfo?.let {
                log("$classTag viewModelFlow got new fsiInfo")

                // mapLatest emits null when FSIInfo is null
                FsiChromeViewModel(
                    fsiInfo.appName,
                    fsiInfo.appIcon,
                    createTaskView(),
                    fsiInfo.fullscreenIntent,
                    repo
                )
            }
        }

    override fun start() {
        log("$classTag start")
    }

    private suspend fun createTaskView(): TaskView = suspendCancellableCoroutine { k ->
        log("$classTag createTaskView")

        taskViewFactory.get().create(context, mainExecutor) { taskView -> k.resume(taskView) }
    }
}

// Alive for lifetime of FSI.
data class FsiChromeViewModel(
    val appName: String,
    val appIcon: Drawable,
    val taskView: TaskView,
    val fsi: PendingIntent,
    val repo: FsiChromeRepo
) {
    companion object {
        private const val classTag = "FsiChromeViewModel"
    }

    fun onDismiss() {
        log("$classTag onDismiss")
        repo.dismiss()
    }
    fun onFullscreen() {
        log("$classTag onFullscreen")
        repo.onFullscreen()
    }
}
