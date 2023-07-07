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
 * limitations under the License
 */

package com.android.systemui.util

import android.os.Binder
import android.os.Binder.ProxyTransactListener
import android.os.Build
import android.os.IBinder
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.Trace.TRACE_TAG_APP
import android.os.Trace.asyncTraceForTrackBegin
import android.os.Trace.asyncTraceForTrackEnd
import android.util.Log
import com.android.settingslib.utils.ThreadUtils
import com.android.systemui.CoreStartable
import com.android.systemui.DejankUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlin.random.Random.Default.nextInt

@SysUISingleton
class BinderLogger
@Inject
constructor(
    private val featureFlags: FeatureFlags,
) : CoreStartable, ProxyTransactListener {

    override fun start() {
        // This feature is only allowed on "userdebug" and "eng" builds
        if (Build.IS_USER) return
        if (!featureFlags.isEnabled(Flags.WARN_ON_BLOCKING_BINDER_TRANSACTIONS)) return
        if (DejankUtils.STRICT_MODE_ENABLED) {
            Log.e(
                TAG,
                "Feature disabled; persist.sysui.strictmode (DejankUtils) and " +
                    "WARN_ON_BLOCKING_BINDER_TRANSACTIONS (BinderLogger) are incompatible"
            )
            return
        }
        Binder.setProxyTransactListener(this)
        val policyBuilder = ThreadPolicy.Builder().detectCustomSlowCalls().penaltyLog()
        StrictMode.setThreadPolicy(policyBuilder.build())
    }

    override fun onTransactStarted(binder: IBinder, transactionCode: Int, flags: Int): Any? {
        // Ignore one-way binder transactions
        if (flags and IBinder.FLAG_ONEWAY != 0) return null
        // Ignore anything not on the main thread
        if (!ThreadUtils.isMainThread()) return null

        // To make it easier to debug, log the most likely cause of the blocking binder transaction
        // by parsing the stack trace.
        val tr = Throwable()
        val analysis = BinderTransactionAnalysis.fromStackTrace(tr.stackTrace)
        val traceCookie = nextInt()
        asyncTraceForTrackBegin(TRACE_TAG_APP, TRACK_NAME, analysis.traceMessage, traceCookie)
        if (analysis.isSystemUi) {
            StrictMode.noteSlowCall(analysis.logMessage)
        } else {
            Log.v(TAG, analysis.logMessage, tr)
        }
        return traceCookie
    }

    override fun onTransactStarted(binder: IBinder, transactionCode: Int): Any? {
        return null
    }

    override fun onTransactEnded(o: Any?) {
        if (o is Int) {
            asyncTraceForTrackEnd(TRACE_TAG_APP, TRACK_NAME, o)
        }
    }

    /**
     * Class for finding the origin of a binder transaction from a stack trace and creating an error
     * message that indicates 1) whether or not the call originated from System UI, and 2) the name
     * of the binder transaction and where it was called.
     *
     * There are two types of stack traces:
     * 1. Stack traces that originate from System UI, which look like the following: ```
     *    android.os.BinderProxy.transact(BinderProxy.java:541)
     *    android.content.pm.BaseParceledListSlice.<init>(BaseParceledListSlice.java:94)
     *    android.content.pm.ParceledListSlice.<init>(ParceledListSlice.java:42)
     *    android.content.pm.ParceledListSlice.<init>(Unknown Source:0)
     *    android.content.pm.ParceledListSlice$1.createFromParcel(ParceledListSlice.java:80)
     *    android.content.pm.ParceledListSlice$1.createFromParcel(ParceledListSlice.java:78)
     *    android.os.Parcel.readTypedObject(Parcel.java:3982)
     *    android.content.pm.IPackageManager$Stub$Proxy.getInstalledPackages(IPackageManager.java:5029)
     *    com.android.systemui.ExampleClass.runTwoWayBinderIPC(ExampleClass.kt:343) ... ``` Most of
     *    these binder transactions contain a reference to a `$Stub$Proxy`, but some don't. For
     *    example: ``` android.os.BinderProxy.transact(BinderProxy.java:541)
     *    android.content.ContentProviderProxy.query(ContentProviderNative.java:479)
     *    android.content.ContentResolver.query(ContentResolver.java:1219)
     *    com.android.systemui.power.PowerUI.refreshEstimateIfNeeded(PowerUI.java:383) ``` In both
     *    cases, we identify the call closest to a sysui package to make the error more clear.
     * 2. Stack traces that originate outside of System UI, which look like the following: ```
     *    android.os.BinderProxy.transact(BinderProxy.java:541)
     *    android.service.notification.IStatusBarNotificationHolder$Stub$Proxy.get(IStatusBarNotificationHolder.java:121)
     *    android.service.notification.NotificationListenerService$NotificationListenerWrapper.onNotificationPosted(NotificationListenerService.java:1396)
     *    android.service.notification.INotificationListener$Stub.onTransact(INotificationListener.java:248)
     *    android.os.Binder.execTransactInternal(Binder.java:1285)
     *    android.os.Binder.execTransact(Binder.java:1244) ```
     *
     * @param isSystemUi Whether or not the call originated from System UI. If it didn't, it's due
     *   to internal implementation details of a core framework API.
     * @param cause The cause of the binder transaction
     * @param binderCall The binder transaction itself
     */
    private class BinderTransactionAnalysis
    constructor(
        val isSystemUi: Boolean,
        cause: StackTraceElement?,
        binderCall: StackTraceElement?
    ) {
        val logMessage: String
        val traceMessage: String

        init {
            val callName =
                (if (isSystemUi) getSimpleCallRefWithFileAndLineNumber(cause)
                else "${getSimpleCallRef(cause)}()") + " -> ${getBinderCallRef(binderCall)}"
            logMessage =
                "Blocking binder transaction detected" +
                    (if (!isSystemUi) ", but the call did not originate from System UI" else "") +
                    ": $callName"
            traceMessage = "${if (isSystemUi) "sysui" else "core"}: $callName"
        }

        companion object {
            fun fromStackTrace(stackTrace: Array<StackTraceElement>): BinderTransactionAnalysis {
                if (stackTrace.size < 2) {
                    return BinderTransactionAnalysis(false, null, null)
                }
                var previousStackElement: StackTraceElement = stackTrace.first()

                // The stack element corresponding to the binder transaction. For example:
                // `android.content.pm.IPackageManager$Stub$Proxy.getInstalledPackages(IPackageManager.java:50)`
                var binderTransaction: StackTraceElement? = null
                // The stack element that caused the binder transaction in the first place.
                // For example:
                // `com.android.systemui.ExampleClass.runTwoWayBinderIPC(ExampleClass.kt:343)`
                var causeOfBinderTransaction: StackTraceElement? = null

                // Iterate starting from the top of the stack (BinderProxy.transact).
                for (i in 1 until stackTrace.size) {
                    val stackElement = stackTrace[i]
                    if (previousStackElement.className?.endsWith("\$Stub\$Proxy") == true) {
                        binderTransaction = previousStackElement
                        causeOfBinderTransaction = stackElement
                    }
                    // As a heuristic, find the top-most call from a sysui package (besides this
                    // class) and blame it
                    val className = stackElement.className
                    if (
                        className != BinderLogger::class.java.name &&
                            (className.startsWith(SYSUI_PKG) || className.startsWith(KEYGUARD_PKG))
                    ) {
                        causeOfBinderTransaction = stackElement

                        return BinderTransactionAnalysis(
                            true,
                            causeOfBinderTransaction,
                            // If an IInterface.Stub.Proxy-like call has not been identified yet,
                            // blame the previous call in the stack.
                            binderTransaction ?: previousStackElement
                        )
                    }
                    previousStackElement = stackElement
                }
                // If we never found a call originating from sysui, report it with an explanation
                // that we could not find a culprit in sysui.
                return BinderTransactionAnalysis(false, causeOfBinderTransaction, binderTransaction)
            }
        }
    }

    companion object {
        private const val TAG: String = "SystemUIBinder"
        private const val TRACK_NAME = "Blocking Binder Transactions"
        private const val SYSUI_PKG = "com.android.systemui"
        private const val KEYGUARD_PKG = "com.android.keyguard"
        private const val UNKNOWN = "<unknown>"

        /**
         * Start of the source file for any R8 build within AOSP.
         *
         * TODO(b/213833843): Allow configuration of the prefix via a build variable.
         */
        private const val AOSP_SOURCE_FILE_MARKER = "go/retraceme "

        /** Start of the source file for any R8 compiler build. */
        private const val R8_SOURCE_FILE_MARKER = "R8_"

        /**
         * Returns a short string for a [StackTraceElement] that references a binder transaction.
         * For example, a stack frame of
         * `android.content.pm.IPackageManager$Stub$Proxy.getInstalledPackages(IPackageManager.java:50)`
         * would return `android.content.pm.IPackageManager#getInstalledPackages()`
         */
        private fun getBinderCallRef(stackFrame: StackTraceElement?): String =
            if (stackFrame != null) "${getBinderClassName(stackFrame)}#${stackFrame.methodName}()"
            else UNKNOWN

        /**
         * Returns the class name of a [StackTraceElement], removing any `$Stub$Proxy` suffix, but
         * still including the package name. This makes binder class names more readable. For
         * example, for a stack element of
         * `android.content.pm.IPackageManager$Stub$Proxy.getInstalledPackages(IPackageManager.java:50)`
         * this would return `android.content.pm.IPackageManager`
         */
        private fun getBinderClassName(stackFrame: StackTraceElement): String {
            val className = stackFrame.className
            val stubDefIndex = className.indexOf("\$Stub\$Proxy")
            return if (stubDefIndex > 0) className.substring(0, stubDefIndex) else className
        }

        /**
         * Returns a short string for a [StackTraceElement], including the file name and line
         * number.
         *
         * If the source file needs retracing, this falls back to the default `toString()` method so
         * that it can be read by the `retrace` command-line tool (`m retrace`).
         */
        private fun getSimpleCallRefWithFileAndLineNumber(stackFrame: StackTraceElement?): String =
            if (stackFrame != null) {
                with(stackFrame) {
                    if (
                        fileName == null ||
                            fileName.startsWith(AOSP_SOURCE_FILE_MARKER) ||
                            fileName.startsWith(R8_SOURCE_FILE_MARKER)
                    ) {
                        // If the source file needs retracing, use the default toString() method for
                        // compatibility with the retrace command-line tool
                        "at $stackFrame"
                    } else {
                        "at ${getSimpleCallRef(stackFrame)}($fileName:$lineNumber)"
                    }
                }
            } else UNKNOWN

        /**
         * Returns a short string for a [StackTraceElement] including it's class name and method
         * name. For example, if the stack frame is
         * `com.android.systemui.ExampleController.makeBlockingBinderIPC(ExampleController.kt:343)`
         * this would return `ExampleController#makeBlockingBinderIPC()`
         */
        private fun getSimpleCallRef(stackFrame: StackTraceElement?): String =
            if (stackFrame != null) "${getSimpleClassName(stackFrame)}#${stackFrame.methodName}"
            else UNKNOWN

        /**
         * Returns a short string for a [StackTraceElement] including just its class name without
         * any package name. For example, if the stack frame is
         * `com.android.systemui.ExampleController.makeBlockingBinderIPC(ExampleController.kt:343)`
         * this would return `ExampleController`
         */
        private fun getSimpleClassName(stackFrame: StackTraceElement): String =
            try {
                with(Class.forName(stackFrame.className)) {
                    canonicalName?.substring(packageName.length + 1)
                }
            } finally {} ?: stackFrame.className
    }
}
