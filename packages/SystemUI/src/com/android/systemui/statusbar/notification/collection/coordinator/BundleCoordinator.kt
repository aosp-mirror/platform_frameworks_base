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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.NotificationChannel.NEWS_ID
import android.app.NotificationChannel.PROMOTIONS_ID
import android.app.NotificationChannel.RECS_ID
import android.app.NotificationChannel.SOCIAL_MEDIA_ID
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.NewsHeader
import com.android.systemui.statusbar.notification.dagger.PromoHeader
import com.android.systemui.statusbar.notification.dagger.RecsHeader
import com.android.systemui.statusbar.notification.dagger.SocialHeader
import com.android.systemui.statusbar.notification.stack.BUCKET_NEWS
import com.android.systemui.statusbar.notification.stack.BUCKET_PROMO
import com.android.systemui.statusbar.notification.stack.BUCKET_RECS
import com.android.systemui.statusbar.notification.stack.BUCKET_SOCIAL
import javax.inject.Inject

/**
 * Coordinator for sections derived from NotificationAssistantService classification.
 */
@CoordinatorScope
class BundleCoordinator @Inject constructor(
    @NewsHeader private val newsHeaderController: NodeController,
    @SocialHeader private val socialHeaderController: NodeController,
    @RecsHeader private val recsHeaderController: NodeController,
    @PromoHeader private val promoHeaderController: NodeController,
) : Coordinator {

    val newsSectioner =
            object : NotifSectioner("News", BUCKET_NEWS) {
                override fun isInSection(entry: ListEntry): Boolean {
                    return entry.representativeEntry?.channel?.id == NEWS_ID
                }

                override fun getHeaderNodeController(): NodeController? {
                    return newsHeaderController
                }
            }

    val socialSectioner =
        object : NotifSectioner("Social", BUCKET_SOCIAL) {
            override fun isInSection(entry: ListEntry): Boolean {
                return entry.representativeEntry?.channel?.id == SOCIAL_MEDIA_ID
            }

            override fun getHeaderNodeController(): NodeController? {
                return socialHeaderController
            }
        }

    val recsSectioner =
        object : NotifSectioner("Recommendations", BUCKET_RECS) {
            override fun isInSection(entry: ListEntry): Boolean {
                return entry.representativeEntry?.channel?.id == RECS_ID
            }

            override fun getHeaderNodeController(): NodeController? {
                return recsHeaderController
            }
        }

    val promoSectioner =
        object : NotifSectioner("Promotions", BUCKET_PROMO) {
            override fun isInSection(entry: ListEntry): Boolean {
                return entry.representativeEntry?.channel?.id == PROMOTIONS_ID
            }

            override fun getHeaderNodeController(): NodeController? {
                return promoHeaderController
            }
        }

    override fun attach(pipeline: NotifPipeline) {
    }
}
