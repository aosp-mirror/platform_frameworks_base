package com.android.systemui.media

import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.InfoMediaManager
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.media.MediaHierarchyManager.MediaLocation
import javax.inject.Inject
import javax.inject.Singleton

class MediaHost @Inject constructor(
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val mediaDataManager: MediaDataManager
) {
    var location: Int = -1
        private set
    lateinit var hostView: ViewGroup
    var isExpanded: Boolean = false
    var showsOnlyActiveMedia: Boolean = false
    var visibleChangedListener: ((Boolean) -> Unit)? = null
    var visible: Boolean = false
        private set

    private val listener = object : MediaDataManager.Listener {
        override fun onMediaDataLoaded(key: String, data: MediaData) {
            updateViewVisibility()
        }

        override fun onMediaDataRemoved(key: String) {
            updateViewVisibility()
        }
    }

    /**
     * Initialize this MediaObject and create a host view
     */
    fun init(@MediaLocation location: Int) {
        this.location = location;
        hostView = mediaHierarchyManager.register(this)
        hostView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                mediaDataManager.addListener(listener)
                updateViewVisibility()
            }

            override fun onViewDetachedFromWindow(v: View?) {
                mediaDataManager.removeListener(listener)
            }
        })
        updateViewVisibility()
    }

    fun setShouldListen(listen: Boolean) {
        // TODO: look into listening more
        mediaHierarchyManager.shouldListen = listen
    }

    private fun updateViewVisibility() {
        if (showsOnlyActiveMedia) {
            visible = mediaDataManager.hasActiveMedia()
        } else {
            visible = mediaDataManager.hasAnyMedia()
        }
        hostView.visibility = if (visible) View.VISIBLE else View.GONE
        visibleChangedListener?.invoke(visible)
    }
}