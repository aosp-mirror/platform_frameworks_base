package com.android.systemui.statusbar.notification.collection.notifcollection

import android.os.Handler
import android.util.ArrayMap
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import java.io.PrintWriter

/**
 * A helpful class that implements the core contract of the lifetime extender internally,
 * making it easier for coordinators to interact with them
 */
abstract class SelfTrackingLifetimeExtender(
    private val tag: String,
    private val name: String,
    private val debug: Boolean,
    private val mainHandler: Handler
) : NotifLifetimeExtender, Dumpable {
    private lateinit var mCallback: NotifLifetimeExtender.OnEndLifetimeExtensionCallback
    protected val mEntriesExtended = ArrayMap<String, NotificationEntry>()
    private var mEnding = false

    /**
     * When debugging, warn if the call is happening during and "end lifetime extension" call.
     *
     * Note: this will warn a lot! The pipeline explicitly re-invokes all lifetime extenders
     * whenever one ends, giving all of them a chance to re-up their lifetime extension.
     */
    private fun warnIfEnding() {
        if (debug && mEnding) Log.w(tag, "reentrant code while ending a lifetime extension")
    }

    fun endAllLifetimeExtensions() {
        // clear the map before iterating over a copy of the items, because the pipeline will
        // always give us another chance to extend the lifetime again, and we don't want
        // concurrent modification
        val entries = mEntriesExtended.values.toList()
        if (debug) Log.d(tag, "$name.endAllLifetimeExtensions() entries=$entries")
        mEntriesExtended.clear()
        warnIfEnding()
        mEnding = true
        entries.forEach { mCallback.onEndLifetimeExtension(this, it) }
        mEnding = false
    }

    fun endLifetimeExtensionAfterDelay(key: String, delayMillis: Long) {
        if (debug) {
            Log.d(tag, "$name.endLifetimeExtensionAfterDelay" +
                    "(key=$key, delayMillis=$delayMillis)" +
                    " isExtending=${isExtending(key)}")
        }
        if (isExtending(key)) {
            mainHandler.postDelayed({ endLifetimeExtension(key) }, delayMillis)
        }
    }

    fun endLifetimeExtension(key: String) {
        if (debug) {
            Log.d(tag, "$name.endLifetimeExtension(key=$key)" +
                    " isExtending=${isExtending(key)}")
        }
        warnIfEnding()
        mEnding = true
        mEntriesExtended.remove(key)?.let { removedEntry ->
            mCallback.onEndLifetimeExtension(this, removedEntry)
        }
        mEnding = false
    }

    fun isExtending(key: String) = mEntriesExtended.contains(key)

    final override fun getName(): String = name

    final override fun maybeExtendLifetime(entry: NotificationEntry, reason: Int): Boolean {
        val shouldExtend = queryShouldExtendLifetime(entry)
        if (debug) {
            Log.d(tag, "$name.shouldExtendLifetime(key=${entry.key}, reason=$reason)" +
                    " isExtending=${isExtending(entry.key)}" +
                    " shouldExtend=$shouldExtend")
        }
        warnIfEnding()
        if (shouldExtend && mEntriesExtended.put(entry.key, entry) == null) {
            onStartedLifetimeExtension(entry)
        }
        return shouldExtend
    }

    final override fun cancelLifetimeExtension(entry: NotificationEntry) {
        if (debug) {
            Log.d(tag, "$name.cancelLifetimeExtension(key=${entry.key})" +
                    " isExtending=${isExtending(entry.key)}")
        }
        warnIfEnding()
        mEntriesExtended.remove(entry.key)
        onCanceledLifetimeExtension(entry)
    }

    abstract fun queryShouldExtendLifetime(entry: NotificationEntry): Boolean
    open fun onStartedLifetimeExtension(entry: NotificationEntry) {}
    open fun onCanceledLifetimeExtension(entry: NotificationEntry) {}

    final override fun setCallback(callback: NotifLifetimeExtender.OnEndLifetimeExtensionCallback) {
        mCallback = callback
    }

    final override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("LifetimeExtender: $name:")
        pw.println("  mEntriesExtended: ${mEntriesExtended.size}")
        mEntriesExtended.forEach { pw.println("  * ${it.key}") }
    }
}