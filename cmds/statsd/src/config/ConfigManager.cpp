/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "config/ConfigManager.h"
#include "storage/StorageManager.h"

#include "stats_util.h"

#include <android-base/file.h>
#include <dirent.h>
#include <stdio.h>
#include <vector>
#include "android-base/stringprintf.h"

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::pair;
using std::set;
using std::string;
using std::vector;

#define STATS_SERVICE_DIR "/data/misc/stats-service"

using android::base::StringPrintf;
using std::unique_ptr;

ConfigManager::ConfigManager() {
}

ConfigManager::~ConfigManager() {
}

void ConfigManager::Startup() {
    map<ConfigKey, StatsdConfig> configsFromDisk;
    StorageManager::readConfigFromDisk(configsFromDisk);
    for (const auto& pair : configsFromDisk) {
        UpdateConfig(pair.first, pair.second);
    }
}

void ConfigManager::StartupForTest() {
    // Dummy function to avoid reading configs from disks for tests.
}

void ConfigManager::AddListener(const sp<ConfigListener>& listener) {
    mListeners.push_back(listener);
}

void ConfigManager::UpdateConfig(const ConfigKey& key, const StatsdConfig& config) {
    // Add to set
    mConfigs.insert(key);

    // Save to disk
    update_saved_configs(key, config);

    // Tell everyone
    for (auto& listener : mListeners) {
        listener->OnConfigUpdated(key, config);
    }
}

void ConfigManager::SetConfigReceiver(const ConfigKey& key, const sp<IBinder>& intentSender) {
    mConfigReceivers[key] = intentSender;
}

void ConfigManager::RemoveConfigReceiver(const ConfigKey& key) {
    mConfigReceivers.erase(key);
}

void ConfigManager::RemoveConfig(const ConfigKey& key) {
    auto it = mConfigs.find(key);
    if (it != mConfigs.end()) {
        // Remove from map
        mConfigs.erase(it);

        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }

    // Remove from disk. There can still be a lingering file on disk so we check
    // whether or not the config was on memory.
    remove_saved_configs(key);
}

void ConfigManager::remove_saved_configs(const ConfigKey& key) {
    string suffix = StringPrintf("%d-%lld", key.GetUid(), (long long)key.GetId());
    StorageManager::deleteSuffixedFiles(STATS_SERVICE_DIR, suffix.c_str());
}

void ConfigManager::RemoveConfigs(int uid) {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        if (it->GetUid() == uid) {
            remove_saved_configs(*it);
            removed.push_back(*it);
            mConfigReceivers.erase(*it);
            it = mConfigs.erase(it);
        } else {
            it++;
        }
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }
}

void ConfigManager::RemoveAllConfigs() {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        removed.push_back(*it);
        auto receiverIt = mConfigReceivers.find(*it);
        if (receiverIt != mConfigReceivers.end()) {
            mConfigReceivers.erase(*it);
        }
        it = mConfigs.erase(it);
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }
}

vector<ConfigKey> ConfigManager::GetAllConfigKeys() const {
    vector<ConfigKey> ret;
    for (auto it = mConfigs.cbegin(); it != mConfigs.cend(); ++it) {
        ret.push_back(*it);
    }
    return ret;
}

const sp<android::IBinder> ConfigManager::GetConfigReceiver(const ConfigKey& key) const {
    auto it = mConfigReceivers.find(key);
    if (it == mConfigReceivers.end()) {
        return nullptr;
    } else {
        return it->second;
    }
}

void ConfigManager::Dump(FILE* out) {
    fprintf(out, "CONFIGURATIONS (%d)\n", (int)mConfigs.size());
    fprintf(out, "     uid name\n");
    for (const auto& key : mConfigs) {
        fprintf(out, "  %6d %lld\n", key.GetUid(), (long long)key.GetId());
        auto receiverIt = mConfigReceivers.find(key);
        if (receiverIt != mConfigReceivers.end()) {
            fprintf(out, "    -> received by PendingIntent as binder\n");
        }
    }
}

void ConfigManager::update_saved_configs(const ConfigKey& key, const StatsdConfig& config) {
    // If there is a pre-existing config with same key we should first delete it.
    remove_saved_configs(key);

    // Then we save the latest config.
    string file_name = StringPrintf("%s/%ld_%d_%lld", STATS_SERVICE_DIR, time(nullptr),
                                    key.GetUid(), (long long)key.GetId());
    const int numBytes = config.ByteSize();
    vector<uint8_t> buffer(numBytes);
    config.SerializeToArray(&buffer[0], numBytes);
    StorageManager::writeFile(file_name.c_str(), &buffer[0], numBytes);
}

StatsdConfig build_fake_config() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_id(12345);

    int WAKE_LOCK_TAG_ID = 1111;  // put a fake id here to make testing easier.
    int WAKE_LOCK_UID_KEY_ID = 1;
    int WAKE_LOCK_NAME_KEY = 3;
    int WAKE_LOCK_STATE_KEY = 4;
    int WAKE_LOCK_ACQUIRE_VALUE = 1;
    int WAKE_LOCK_RELEASE_VALUE = 0;

    int APP_USAGE_TAG_ID = 12345;
    int APP_USAGE_UID_KEY_ID = 1;
    int APP_USAGE_STATE_KEY = 2;
    int APP_USAGE_FOREGROUND = 1;
    int APP_USAGE_BACKGROUND = 0;

    int SCREEN_EVENT_TAG_ID = 29;
    int SCREEN_EVENT_STATE_KEY = 1;
    int SCREEN_EVENT_ON_VALUE = 2;
    int SCREEN_EVENT_OFF_VALUE = 1;

    int UID_PROCESS_STATE_TAG_ID = 27;
    int UID_PROCESS_STATE_UID_KEY = 1;

    int KERNEL_WAKELOCK_TAG_ID = 1004;
    int KERNEL_WAKELOCK_COUNT_KEY = 2;
    int KERNEL_WAKELOCK_NAME_KEY = 1;

    int DEVICE_TEMPERATURE_TAG_ID = 33;
    int DEVICE_TEMPERATURE_KEY = 1;

    // Count Screen ON events.
    CountMetric* metric = config.add_count_metric();
    metric->set_id(1);  // METRIC_1
    metric->set_what(102);  //  "SCREEN_TURNED_ON"
    metric->set_bucket(ONE_MINUTE);

    // Anomaly threshold for screen-on count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*Alert* alert = config.add_alert();
    alert->set_id("ALERT_1");
    alert->set_metric_name("METRIC_1");
    alert->set_number_of_buckets(6);
    alert->set_trigger_if_sum_gt(10);
    alert->set_refractory_period_secs(30);
    Alert::IncidentdDetails* details = alert->mutable_incidentd_details();
    details->add_section(12);
    details->add_section(13);*/

    config.add_allowed_log_source("AID_ROOT");
    config.add_allowed_log_source("AID_SYSTEM");
    config.add_allowed_log_source("AID_BLUETOOTH");
    config.add_allowed_log_source("com.android.statsd.dogfood");
    config.add_allowed_log_source("com.android.systemui");

    // Count process state changes, slice by uid.
    metric = config.add_count_metric();
    metric->set_id(2);  // "METRIC_2"
    metric->set_what(104);
    metric->set_bucket(ONE_MINUTE);
    FieldMatcher* dimensions = metric->mutable_dimensions_in_what();
    dimensions->set_field(UID_PROCESS_STATE_TAG_ID);
    dimensions->add_child()->set_field(UID_PROCESS_STATE_UID_KEY);

    // Anomaly threshold for background count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*
    alert = config.add_alert();
    alert->set_id("ALERT_2");
    alert->set_metric_name("METRIC_2");
    alert->set_number_of_buckets(4);
    alert->set_trigger_if_sum_gt(30);
    alert->set_refractory_period_secs(20);
    details = alert->mutable_incidentd_details();
    details->add_section(14);
    details->add_section(15);*/

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(104);
    metric->set_bucket(ONE_MINUTE);

    dimensions = metric->mutable_dimensions_in_what();
    dimensions->set_field(UID_PROCESS_STATE_TAG_ID);
    dimensions->add_child()->set_field(UID_PROCESS_STATE_UID_KEY);
    metric->set_condition(202);

    // Count wake lock, slice by uid, while SCREEN_IS_ON and app in background
    metric = config.add_count_metric();
    metric->set_id(4);
    metric->set_what(107);
    metric->set_bucket(ONE_MINUTE);
    dimensions = metric->mutable_dimensions_in_what();
    dimensions->set_field(WAKE_LOCK_TAG_ID);
    dimensions->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);


    metric->set_condition(204);
    MetricConditionLink* link = metric->add_links();
    link->set_condition(203);
    link->mutable_fields_in_what()->set_field(WAKE_LOCK_TAG_ID);
    link->mutable_fields_in_what()->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    link->mutable_fields_in_condition()->set_field(APP_USAGE_TAG_ID);
    link->mutable_fields_in_condition()->add_child()->set_field(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background, slice by uid
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(5);
    durationMetric->set_bucket(ONE_MINUTE);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);
    dimensions = durationMetric->mutable_dimensions_in_what();
    dimensions->set_field(WAKE_LOCK_TAG_ID);
    dimensions->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what(205);
    durationMetric->set_condition(204);
    link = durationMetric->add_links();
    link->set_condition(203);
    link->mutable_fields_in_what()->set_field(WAKE_LOCK_TAG_ID);
    link->mutable_fields_in_what()->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    link->mutable_fields_in_condition()->set_field(APP_USAGE_TAG_ID);
    link->mutable_fields_in_condition()->add_child()->set_field(APP_USAGE_UID_KEY_ID);

    // max Duration of an app holding any wl, while screen on and app in background, slice by uid
    durationMetric = config.add_duration_metric();
    durationMetric->set_id(6);
    durationMetric->set_bucket(ONE_MINUTE);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);
    dimensions = durationMetric->mutable_dimensions_in_what();
    dimensions->set_field(WAKE_LOCK_TAG_ID);
    dimensions->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what(205);
    durationMetric->set_condition(204);
    link = durationMetric->add_links();
    link->set_condition(203);
    link->mutable_fields_in_what()->set_field(WAKE_LOCK_TAG_ID);
    link->mutable_fields_in_what()->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    link->mutable_fields_in_condition()->set_field(APP_USAGE_TAG_ID);
    link->mutable_fields_in_condition()->add_child()->set_field(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background
    durationMetric = config.add_duration_metric();
    durationMetric->set_id(7);
    durationMetric->set_bucket(ONE_MINUTE);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);
    durationMetric->set_what(205);
    durationMetric->set_condition(204);
    link = durationMetric->add_links();
    link->set_condition(203);
    link->mutable_fields_in_what()->set_field(WAKE_LOCK_TAG_ID);
    link->mutable_fields_in_what()->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    link->mutable_fields_in_condition()->set_field(APP_USAGE_TAG_ID);
    link->mutable_fields_in_condition()->add_child()->set_field(APP_USAGE_UID_KEY_ID);


    // Duration of screen on time.
    durationMetric = config.add_duration_metric();
    durationMetric->set_id(8);
    durationMetric->set_bucket(ONE_MINUTE);
    durationMetric->set_aggregation_type(DurationMetric_AggregationType_SUM);
    durationMetric->set_what(201);

    // Anomaly threshold for background count.
    // TODO(b/70627390): Uncomment once the bug is fixed.
    /*
    alert = config.add_alert();
    alert->set_id(308);
    alert->set_metric_id(8);
    alert->set_number_of_buckets(4);
    alert->set_trigger_if_sum_gt(2000000000); // 2 seconds
    alert->set_refractory_period_secs(120);
    details = alert->mutable_incidentd_details();
    details->add_section(-1);*/

    // Value metric to count KERNEL_WAKELOCK when screen turned on
    ValueMetric* valueMetric = config.add_value_metric();
    valueMetric->set_id(11);
    valueMetric->set_what(109);
    valueMetric->mutable_value_field()->set_field(KERNEL_WAKELOCK_TAG_ID);
    valueMetric->mutable_value_field()->add_child()->set_field(KERNEL_WAKELOCK_COUNT_KEY);
    valueMetric->set_condition(201);
    dimensions = valueMetric->mutable_dimensions_in_what();
    dimensions->set_field(KERNEL_WAKELOCK_TAG_ID);
    dimensions->add_child()->set_field(KERNEL_WAKELOCK_NAME_KEY);
    // This is for testing easier. We should never set bucket size this small.
    durationMetric->set_bucket(ONE_MINUTE);

    // Add an EventMetric to log process state change events.
    EventMetric* eventMetric = config.add_event_metric();
    eventMetric->set_id(9);
    eventMetric->set_what(102); // "SCREEN_TURNED_ON"

    // Add an GaugeMetric.
    GaugeMetric* gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_id(10);
    gaugeMetric->set_what(101);
    auto gaugeFieldMatcher = gaugeMetric->mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(DEVICE_TEMPERATURE_TAG_ID);
    gaugeFieldMatcher->add_child()->set_field(DEVICE_TEMPERATURE_KEY);
    durationMetric->set_bucket(ONE_MINUTE);

    // Event matchers.
    AtomMatcher* temperatureAtomMatcher = config.add_atom_matcher();
    temperatureAtomMatcher->set_id(101);  // "DEVICE_TEMPERATURE"
    temperatureAtomMatcher->mutable_simple_atom_matcher()->set_atom_id(
        DEVICE_TEMPERATURE_TAG_ID);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(102);  // "SCREEN_TURNED_ON"
    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(SCREEN_EVENT_TAG_ID);
    FieldValueMatcher* fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(SCREEN_EVENT_STATE_KEY);
    fieldValueMatcher->set_eq_int(SCREEN_EVENT_ON_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(103);  // "SCREEN_TURNED_OFF"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(SCREEN_EVENT_TAG_ID);
    fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(SCREEN_EVENT_STATE_KEY);
    fieldValueMatcher->set_eq_int(SCREEN_EVENT_OFF_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(104);  // "PROCESS_STATE_CHANGE"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(UID_PROCESS_STATE_TAG_ID);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(105);  // "APP_GOES_BACKGROUND"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(APP_USAGE_TAG_ID);
    fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(APP_USAGE_STATE_KEY);
    fieldValueMatcher->set_eq_int(APP_USAGE_BACKGROUND);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(106);  // "APP_GOES_FOREGROUND"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(APP_USAGE_TAG_ID);
    fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(APP_USAGE_STATE_KEY);
    fieldValueMatcher->set_eq_int(APP_USAGE_FOREGROUND);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(107);  // "APP_GET_WL"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(WAKE_LOCK_TAG_ID);
    fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(WAKE_LOCK_STATE_KEY);
    fieldValueMatcher->set_eq_int(WAKE_LOCK_ACQUIRE_VALUE);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(108);  //"APP_RELEASE_WL"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(WAKE_LOCK_TAG_ID);
    fieldValueMatcher = simpleAtomMatcher->add_field_value_matcher();
    fieldValueMatcher->set_field(WAKE_LOCK_STATE_KEY);
    fieldValueMatcher->set_eq_int(WAKE_LOCK_RELEASE_VALUE);

    // pulled events
    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(109);  // "KERNEL_WAKELOCK"
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(KERNEL_WAKELOCK_TAG_ID);

    // Predicates.............
    Predicate* predicate = config.add_predicate();
    predicate->set_id(201);  // "SCREEN_IS_ON"
    SimplePredicate* simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start(102);  // "SCREEN_TURNED_ON"
    simplePredicate->set_stop(103);
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_id(202);  // "SCREEN_IS_OFF"
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start(103);
    simplePredicate->set_stop(102);  // "SCREEN_TURNED_ON"
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_id(203);  // "APP_IS_BACKGROUND"
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start(105);
    simplePredicate->set_stop(106);
    FieldMatcher* predicate_dimension1 = simplePredicate->mutable_dimensions();
    predicate_dimension1->set_field(APP_USAGE_TAG_ID);
    predicate_dimension1->add_child()->set_field(APP_USAGE_UID_KEY_ID);
    simplePredicate->set_count_nesting(false);

    predicate = config.add_predicate();
    predicate->set_id(204);  // "APP_IS_BACKGROUND_AND_SCREEN_ON"
    Predicate_Combination* combination_predicate = predicate->mutable_combination();
    combination_predicate->set_operation(LogicalOperation::AND);
    combination_predicate->add_predicate(203);
    combination_predicate->add_predicate(201);

    predicate = config.add_predicate();
    predicate->set_id(205);  // "WL_HELD_PER_APP_PER_NAME"
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start(107);
    simplePredicate->set_stop(108);
    FieldMatcher* predicate_dimension = simplePredicate->mutable_dimensions();
    predicate_dimension1->set_field(WAKE_LOCK_TAG_ID);
    predicate_dimension->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    predicate_dimension->add_child()->set_field(WAKE_LOCK_NAME_KEY);
    simplePredicate->set_count_nesting(true);

    predicate = config.add_predicate();
    predicate->set_id(206);  // "WL_HELD_PER_APP"
    simplePredicate = predicate->mutable_simple_predicate();
    simplePredicate->set_start(107);
    simplePredicate->set_stop(108);
    simplePredicate->set_initial_value(SimplePredicate_InitialValue_FALSE);
    predicate_dimension = simplePredicate->mutable_dimensions();
    predicate_dimension->set_field(WAKE_LOCK_TAG_ID);
    predicate_dimension->add_child()->set_field(WAKE_LOCK_UID_KEY_ID);
    simplePredicate->set_count_nesting(true);

    return config;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
