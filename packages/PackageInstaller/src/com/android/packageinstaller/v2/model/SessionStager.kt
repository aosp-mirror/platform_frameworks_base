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
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionStager internal constructor(
    private val context: Context,
    private val uri: Uri,
    private val stagedSessionId: Int
) {

    companion object {
        private val LOG_TAG = SessionStager::class.java.simpleName
    }

    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int>
        get() = _progress

    suspend fun execute(): Boolean = withContext(Dispatchers.IO) {
        val pi: PackageInstaller = context.packageManager.packageInstaller
        var sessionInfo: PackageInstaller.SessionInfo?
        try {
            val session = pi.openSession(stagedSessionId)
            context.contentResolver.openInputStream(uri).use { instream ->
                session.setStagingProgress(0f)

                if (instream == null) {
                    return@withContext false
                }

                val sizeBytes = getContentSizeBytes()
                publishProgress(if (sizeBytes > 0) 0 else -1)

                var totalRead: Long = 0
                session.openWrite("PackageInstaller", 0, sizeBytes).use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val numRead = instream.read(buffer)
                        if (numRead == -1) {
                            session.fsync(out)
                            break
                        }
                        out.write(buffer, 0, numRead)

                        if (sizeBytes > 0) {
                            totalRead += numRead.toLong()
                            val fraction = totalRead.toFloat() / sizeBytes.toFloat()
                            session.setStagingProgress(fraction)
                            publishProgress((fraction * 100.0).toInt())
                        }
                    }
                }
                sessionInfo = pi.getSessionInfo(stagedSessionId)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error staging apk from content URI", e)
            sessionInfo = null
        }

        return@withContext if (sessionInfo == null
            || !sessionInfo?.isActive!!
            || sessionInfo?.resolvedBaseApkPath == null
        ) {
            Log.w(LOG_TAG, "Session info is invalid: $sessionInfo")
            false
        } else {
            true
        }
    }

    private fun getContentSizeBytes(): Long {
        return try {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                .use { afd -> afd?.length ?: AssetFileDescriptor.UNKNOWN_LENGTH }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Failed to open asset file descriptor", e)
            AssetFileDescriptor.UNKNOWN_LENGTH
        }
    }

    private suspend fun publishProgress(progressValue: Int) = withContext(Dispatchers.Main) {
        _progress.value = progressValue
    }
}
