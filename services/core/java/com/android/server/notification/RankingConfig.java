/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import android.app.Notification;

import java.util.List;

public interface RankingConfig {

    List<Notification.Topic> getTopics(String packageName, int uid);

    int getTopicPriority(String packageName, int uid, Notification.Topic topic);

    void setTopicPriority(String packageName, int uid, Notification.Topic topic, int priority);

    int getTopicVisibilityOverride(String packageName, int uid, Notification.Topic topic);

    void setTopicVisibilityOverride(String packageName, int uid, Notification.Topic topic,
            int visibility);

    void setTopicImportance(String packageName, int uid, Notification.Topic topic, int importance);

    int getTopicImportance(String packageName, int uid, Notification.Topic topic);

    void setAppImportance(String packageName, int uid, int importance);

    boolean doesAppUseTopics(String packageName, int uid);
}
