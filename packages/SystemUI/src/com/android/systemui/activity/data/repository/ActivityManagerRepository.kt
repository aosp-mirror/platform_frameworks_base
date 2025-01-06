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

package com.android.systemui.activity.data.repository

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.core.Logger
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/** Repository for interfacing with [ActivityManager]. */
interface ActivityManagerRepository {
    /**
     * Given a UID, creates a flow that emits true when the process with the given UID is visible to
     * the user and false otherwise.
     *
     * @param identifyingLogTag a tag identifying who created this flow, used for logging.
     */
    fun createIsAppVisibleFlow(
        creationUid: Int,
        logger: Logger,
        identifyingLogTag: String,
    ): Flow<Boolean>
}

@SysUISingleton
class ActivityManagerRepositoryImpl
@Inject
constructor(
    @Background private val backgroundContext: CoroutineContext,
    private val activityManager: ActivityManager,
) : ActivityManagerRepository {
    override fun createIsAppVisibleFlow(
        creationUid: Int,
        logger: Logger,
        identifyingLogTag: String,
    ): Flow<Boolean> {
        return conflatedCallbackFlow {
                val listener =
                    object : ActivityManager.OnUidImportanceListener {
                        override fun onUidImportance(uid: Int, importance: Int) {
                            if (uid != creationUid) {
                                return
                            }
                            val isAppVisible = isAppVisibleToUser(importance)
                            logger.d({
                                "$str1: #onUidImportance. importance=$int1, isAppVisible=$bool1"
                            }) {
                                str1 = identifyingLogTag
                                int1 = importance
                                bool1 = isAppVisible
                            }
                            trySend(isAppVisible)
                        }
                    }
                try {
                    // TODO(b/286258140): Replace this with the #addOnUidImportanceListener
                    //  overload that filters to certain UIDs.
                    activityManager.addOnUidImportanceListener(listener, IMPORTANCE_CUTPOINT)
                } catch (e: SecurityException) {
                    logger.e({ "$str1: Security exception on #addOnUidImportanceListener" }, e) {
                        str1 = identifyingLogTag
                    }
                }

                awaitClose { activityManager.removeOnUidImportanceListener(listener) }
            }
            .distinctUntilChanged()
            .onStart {
                try {
                    val isVisibleOnStart =
                        isAppVisibleToUser(activityManager.getUidImportance(creationUid))
                    logger.d({ "$str1: Starting UID observation. isAppVisible=$bool1" }) {
                        str1 = identifyingLogTag
                        bool1 = isVisibleOnStart
                    }
                    emit(isVisibleOnStart)
                } catch (e: SecurityException) {
                    logger.e({ "$str1: Security exception on #getUidImportance" }, e) {
                        str1 = identifyingLogTag
                    }
                    emit(false)
                }
            }
            .flowOn(backgroundContext)
    }

    /** Returns true if the given [importance] represents an app that's visible to the user. */
    private fun isAppVisibleToUser(importance: Int): Boolean {
        return importance <= IMPORTANCE_CUTPOINT
    }

    companion object {
        private const val IMPORTANCE_CUTPOINT = IMPORTANCE_FOREGROUND
    }
}
