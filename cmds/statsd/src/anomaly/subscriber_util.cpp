/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "external/Perfetto.h"
#include "subscriber/IncidentdReporter.h"
#include "subscriber/SubscriberReporter.h"

namespace android {
namespace os {
namespace statsd {

void triggerSubscribers(int64_t ruleId, int64_t metricId, const MetricDimensionKey& dimensionKey,
                        int64_t metricValue, const ConfigKey& configKey,
                        const std::vector<Subscription>& subscriptions) {
    VLOG("informSubscribers called.");
    if (subscriptions.empty()) {
        VLOG("No Subscriptions were associated.");
        return;
    }

    for (const Subscription& subscription : subscriptions) {
        if (subscription.probability_of_informing() < 1
                && ((float)rand() / (float)RAND_MAX) >= subscription.probability_of_informing()) {
            // Note that due to float imprecision, 0.0 and 1.0 might not truly mean never/always.
            // The config writer was advised to use -0.1 and 1.1 for never/always.
            ALOGI("Fate decided that a subscriber would not be informed.");
            continue;
        }
        switch (subscription.subscriber_information_case()) {
            case Subscription::SubscriberInformationCase::kIncidentdDetails:
                if (!GenerateIncidentReport(subscription.incidentd_details(), ruleId, metricId,
                                            dimensionKey, metricValue, configKey)) {
                    ALOGW("Failed to generate incident report.");
                }
                break;
            case Subscription::SubscriberInformationCase::kPerfettoDetails:
                if (!CollectPerfettoTraceAndUploadToDropbox(subscription.perfetto_details(),
                                                            subscription.id(), ruleId, configKey)) {
                    ALOGW("Failed to generate perfetto traces.");
                }
                break;
            case Subscription::SubscriberInformationCase::kBroadcastSubscriberDetails:
                SubscriberReporter::getInstance().alertBroadcastSubscriber(configKey, subscription,
                                                                           dimensionKey);
                break;
            default:
                break;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
