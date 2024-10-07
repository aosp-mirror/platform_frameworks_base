/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.frameworks.coretests.methodcallerhelperapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import com.android.frameworks.coretests.aidl.ITestInterface

/**
 * Receiver used to call methods when a binder is received
 * {@link android.os.BinderUncaughtExceptionHandlerTest}.
 */
class CallMethodsReceiver : BroadcastReceiver() {
    private val TAG = "CallMethodsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.getAction()) {
                ACTION_CALL_METHOD -> intent.getExtras()!!.let {
                    Log.i(TAG, "Received ACTION_CALL_METHOD with extras: $it")
                    val iface = it.getBinder(EXTRA_BINDER)!!.let(ITestInterface.Stub::asInterface)!!
                    val name = it.getString(EXTRA_METHOD_NAME)!!
                    try {
                        when (name) {
                            "foo" -> iface.foo(5)
                            "onewayFoo" -> iface.onewayFoo(5)
                            "bar" -> iface.bar(5)
                            else -> Log.e(TAG, "Unknown method name")
                        }
                    } catch (e: Exception) {
                        // Exceptions expected
                    }
                }
                else -> Log.e(TAG, "Unknown action " + intent.getAction())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ", e)
        }
    }
}
