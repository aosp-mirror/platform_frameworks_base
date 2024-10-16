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

package com.android.systemui.statusbar.notification.row.icon

import android.app.ActivityManager
import android.app.Flags
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.internal.R
import com.android.launcher3.icons.BaseIconFactory
import com.android.systemui.dagger.SysUISingleton
import dagger.Module
import dagger.Provides
import javax.inject.Inject
import javax.inject.Provider

/** A provider used to cache and fetch app icons used by notifications. */
interface AppIconProvider {
    @Throws(NameNotFoundException::class)
    fun getOrFetchAppIcon(packageName: String, context: Context): Drawable
}

@SysUISingleton
class AppIconProviderImpl @Inject constructor(private val sysuiContext: Context) : AppIconProvider {
    private val iconFactory: BaseIconFactory
        get() {
            val isLowRam = ActivityManager.isLowRamDeviceStatic()
            val res = sysuiContext.resources
            val iconSize: Int =
                res.getDimensionPixelSize(
                    if (isLowRam) R.dimen.notification_small_icon_size_low_ram
                    else R.dimen.notification_small_icon_size
                )
            return BaseIconFactory(sysuiContext, res.configuration.densityDpi, iconSize)
        }

    override fun getOrFetchAppIcon(packageName: String, context: Context): Drawable {
        val icon = context.packageManager.getApplicationIcon(packageName)
        return BitmapDrawable(
            context.resources,
            iconFactory.createScaledBitmap(icon, BaseIconFactory.MODE_HARDWARE),
        )
    }
}

class NoOpIconProvider : AppIconProvider {
    companion object {
        const val TAG = "NoOpIconProvider"
    }

    override fun getOrFetchAppIcon(packageName: String, context: Context): Drawable {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
        return ColorDrawable(Color.WHITE)
    }
}

@Module
class AppIconProviderModule {
    @Provides
    @SysUISingleton
    fun provideImpl(realImpl: Provider<AppIconProviderImpl>): AppIconProvider =
        if (Flags.notificationsRedesignAppIcons()) {
            realImpl.get()
        } else {
            NoOpIconProvider()
        }
}
