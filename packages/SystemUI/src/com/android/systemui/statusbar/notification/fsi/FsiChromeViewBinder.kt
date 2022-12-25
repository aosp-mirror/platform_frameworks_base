package com.android.systemui.statusbar.notification.fsi

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.fsi.FsiDebug.Companion.log
import com.android.systemui.statusbar.phone.CentralSurfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class FsiChromeViewBinder
@Inject
constructor(
    val context: Context,
    val windowManager: WindowManager,
    val viewModelFactory: FsiChromeViewModelFactory,
    val layoutInflater: LayoutInflater,
    val centralSurfaces: CentralSurfaces,
    @Main val mainExecutor: Executor,
    @Application val scope: CoroutineScope,
) : CoreStartable {

    companion object {
        private const val classTag = "FsiChromeViewBinder"
    }

    private val fsiChromeView =
        layoutInflater.inflate(R.layout.fsi_chrome_view, null /* root */, false /* attachToRoot */)
            as FsiChromeView

    var addedToWindowManager = false
    var cornerRadius: Int = context.resources.getDimensionPixelSize(
            R.dimen.notification_corner_radius)

    override fun start() {
        val methodTag = "start"
        log("$classTag $methodTag ")

        scope.launch {
            log("$classTag $methodTag launch ")
            viewModelFactory.viewModelFlow.collect { vm -> updateForViewModel(vm) }
        }
    }

    private fun updateForViewModel(vm: FsiChromeViewModel?) {
        val methodTag = "updateForViewModel"

        if (vm == null) {
            log("$classTag $methodTag viewModel is null, removing from window manager")

            if (addedToWindowManager) {
                windowManager.removeView(fsiChromeView)
                addedToWindowManager = false
            }
            return
        }

        bindViewModel(vm, windowManager)

        if (addedToWindowManager) {
            log("$classTag $methodTag already addedToWindowManager")
        } else {
            windowManager.addView(fsiChromeView, FsiTaskViewConfig.getWmLayoutParams("PackageName"))
            addedToWindowManager = true
        }
    }

    private fun bindViewModel(
        vm: FsiChromeViewModel,
        windowManager: WindowManager,
    ) {
        log("$classTag bindViewModel")

        fsiChromeView.appIconImageView.setImageDrawable(vm.appIcon)
        fsiChromeView.appNameTextView.text = vm.appName

        fsiChromeView.dismissButton.setOnClickListener { vm.onDismiss() }
        fsiChromeView.fullscreenButton.setOnClickListener { vm.onFullscreen() }

        vm.taskView.cornerRadius = cornerRadius.toFloat()
        vm.taskView.startActivity(
            vm.fsi,
            FsiTaskViewConfig.getFillInIntent(),
            FsiTaskViewConfig.getActivityOptions(context, windowManager),
            FsiTaskViewConfig.getLaunchBounds(windowManager)
        )

        log("$classTag bindViewModel started taskview activity")
        fsiChromeView.addView(vm.taskView)
    }
}
