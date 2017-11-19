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

#include "stats_util.h"

#include <android-base/file.h>
#include <dirent.h>
#include <stdio.h>
#include <vector>
#include "android-base/stringprintf.h"

namespace android {
namespace os {
namespace statsd {

#define STATS_SERVICE_DIR "/data/system/stats-service"

static StatsdConfig build_fake_config();

using android::base::StringPrintf;
using std::unique_ptr;

ConfigManager::ConfigManager() {
}

ConfigManager::~ConfigManager() {
}

void ConfigManager::Startup() {
    readConfigFromDisk();

    // this should be called from StatsService when it receives a statsd_config
    UpdateConfig(ConfigKey(1000, "fake"), build_fake_config());
}

void ConfigManager::AddListener(const sp<ConfigListener>& listener) {
    mListeners.push_back(listener);
}

void ConfigManager::UpdateConfig(const ConfigKey& key, const StatsdConfig& config) {
    // Add to map
    mConfigs[key] = config;
    // Why doesn't this work? mConfigs.insert({key, config});

    // Save to disk
    update_saved_configs(key, config);

    // Tell everyone
    for (auto& listener : mListeners) {
        listener->OnConfigUpdated(key, config);
    }
}

void ConfigManager::SetConfigReceiver(const ConfigKey& key, const string& pkg, const string& cls) {
    mConfigReceivers[key] = pair<string, string>(pkg, cls);
}

void ConfigManager::RemoveConfigReceiver(const ConfigKey& key) {
    mConfigReceivers.erase(key);
}

void ConfigManager::RemoveConfig(const ConfigKey& key) {
    unordered_map<ConfigKey, StatsdConfig>::iterator it = mConfigs.find(key);
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
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_SERVICE_DIR), closedir);
    if (dir == NULL) {
        ALOGD("no default config on disk");
        return;
    }
    string prefix = StringPrintf("%d-%s", key.GetUid(), key.GetName().c_str());
    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] != '.' && strncmp(name, prefix.c_str(), prefix.size()) == 0) {
            if (remove(StringPrintf("%s/%s", STATS_SERVICE_DIR, name).c_str()) != 0) {
                ALOGD("no file found");
            }
        }
    }
}

void ConfigManager::RemoveConfigs(int uid) {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        if (it->first.GetUid() == uid) {
            removed.push_back(it->first);
            it = mConfigs.erase(it);
            mConfigReceivers.erase(it->first);
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

vector<ConfigKey> ConfigManager::GetAllConfigKeys() {
    vector<ConfigKey> ret;
    for (auto it = mConfigs.cbegin(); it != mConfigs.cend(); ++it) {
        ret.push_back(it->first);
    }
    return ret;
}

const pair<string, string> ConfigManager::GetConfigReceiver(const ConfigKey& key) {
    auto it = mConfigReceivers.find(key);
    if (it == mConfigReceivers.end()) {
        return pair<string,string>();
    } else {
        return it->second;
    }
}

void ConfigManager::Dump(FILE* out) {
    fprintf(out, "CONFIGURATIONS (%d)\n", (int)mConfigs.size());
    fprintf(out, "     uid name\n");
    for (unordered_map<ConfigKey, StatsdConfig>::const_iterator it = mConfigs.begin();
         it != mConfigs.end(); it++) {
        fprintf(out, "  %6d %s\n", it->first.GetUid(), it->first.GetName().c_str());
        auto receiverIt = mConfigReceivers.find(it->first);
        if (receiverIt != mConfigReceivers.end()) {
            fprintf(out, "    -> received by %s, %s\n", receiverIt->second.first.c_str(),
                    receiverIt->second.second.c_str());
        }
        // TODO: Print the contents of the config too.
    }
}

void ConfigManager::readConfigFromDisk() {
    unique_ptr<DIR, decltype(&closedir)> dir(opendir(STATS_SERVICE_DIR), closedir);
    if (dir == NULL) {
        ALOGD("no default config on disk");
        return;
    }

    dirent* de;
    while ((de = readdir(dir.get()))) {
        char* name = de->d_name;
        if (name[0] == '.') continue;
        ALOGD("file %s", name);

        int index = 0;
        int uid = 0;
        string configName;
        char* substr = strtok(name, "-");
        // Timestamp lives at index 2 but we skip parsing it as it's not needed.
        while (substr != nullptr && index < 2) {
            if (index) {
                uid = atoi(substr);
            } else {
                configName = substr;
            }
            index++;
        }
        if (index < 2) continue;
        string file_name = StringPrintf("%s/%s", STATS_SERVICE_DIR, name);
        ALOGD("full file %s", file_name.c_str());
        int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd != -1) {
            string content;
            if (android::base::ReadFdToString(fd, &content)) {
                StatsdConfig config;
                if (config.ParseFromString(content)) {
                    mConfigs[ConfigKey(uid, configName)] = config;
                    ALOGD("map key uid=%d|name=%s", uid, name);
                }
            }
            close(fd);
        }
    }
}

void ConfigManager::update_saved_configs(const ConfigKey& key, const StatsdConfig& config) {
    mkdir(STATS_SERVICE_DIR, S_IRWXU);

    // If there is a pre-existing config with same key we should first delete it.
    remove_saved_configs(key);

    // Then we save the latest config.
    string file_name = StringPrintf("%s/%d-%s-%ld", STATS_SERVICE_DIR, key.GetUid(),
                                    key.GetName().c_str(), time(nullptr));
    int fd = open(file_name.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR);
    if (fd != -1) {
        const int numBytes = config.ByteSize();
        vector<uint8_t> buffer(numBytes);
        config.SerializeToArray(&buffer[0], numBytes);
        int result = write(fd, &buffer[0], numBytes);
        close(fd);
        bool wroteKey = (result == numBytes);
        ALOGD("wrote to file %d", wroteKey);
    }
}

static StatsdConfig build_fake_config() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_name("12345");

    int WAKE_LOCK_TAG_ID = 1111;  // put a fake id here to make testing easier.
    int WAKE_LOCK_UID_KEY_ID = 1;
    int WAKE_LOCK_NAME_KEY = 3;
    int WAKE_LOCK_STATE_KEY = 4;
    int WAKE_LOCK_ACQUIRE_VALUE = 1;
    int WAKE_LOCK_RELEASE_VALUE = 0;

    int APP_USAGE_ID = 12345;
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
    int KERNEL_WAKELOCK_NAME_KEY = 4;

    int DEVICE_TEMPERATURE_TAG_ID = 33;
    int DEVICE_TEMPERATURE_KEY = 1;

    // Count Screen ON events.
    CountMetric* metric = config.add_count_metric();
    metric->set_name("1");
    metric->set_what("SCREEN_TURNED_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);

    // Anomaly threshold for screen-on count.
    Alert* alert = config.add_alerts();
    alert->set_name("1");
    alert->set_number_of_buckets(6);
    alert->set_trigger_if_sum_gt(10);
    alert->set_refractory_period_secs(30);

    // Count process state changes, slice by uid.
    metric = config.add_count_metric();
    metric->set_name("2");
    metric->set_what("PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    KeyMatcher* keyMatcher = metric->add_dimension();
    keyMatcher->set_key(UID_PROCESS_STATE_UID_KEY);

    // Anomaly threshold for background count.
    alert = config.add_alerts();
    alert->set_name("2");
    alert->set_number_of_buckets(4);
    alert->set_trigger_if_sum_gt(30);
    alert->set_refractory_period_secs(20);

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    keyMatcher = metric->add_dimension();
    keyMatcher->set_key(UID_PROCESS_STATE_UID_KEY);
    metric->set_condition("SCREEN_IS_OFF");

    // Count wake lock, slice by uid, while SCREEN_IS_ON and app in background
    metric = config.add_count_metric();
    metric->set_name("4");
    metric->set_what("APP_GET_WL");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    keyMatcher = metric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    metric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    EventConditionLink* link = metric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_main()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background, slice by uid
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_name("5");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_type(DurationMetric_AggregationType_DURATION_SUM);
    keyMatcher = durationMetric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_main()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // max Duration of an app holding any wl, while screen on and app in background, slice by uid
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("6");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_type(DurationMetric_AggregationType_DURATION_MAX_SPARSE);
    keyMatcher = durationMetric->add_dimension();
    keyMatcher->set_key(WAKE_LOCK_UID_KEY_ID);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_main()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of an app holding any wl, while screen on and app in background
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("7");
    durationMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    durationMetric->set_type(DurationMetric_AggregationType_DURATION_MAX_SPARSE);
    durationMetric->set_what("WL_HELD_PER_APP_PER_NAME");
    durationMetric->set_condition("APP_IS_BACKGROUND_AND_SCREEN_ON");
    link = durationMetric->add_links();
    link->set_condition("APP_IS_BACKGROUND");
    link->add_key_in_main()->set_key(WAKE_LOCK_UID_KEY_ID);
    link->add_key_in_condition()->set_key(APP_USAGE_UID_KEY_ID);

    // Duration of screen on time.
    durationMetric = config.add_duration_metric();
    durationMetric->set_name("8");
    durationMetric->mutable_bucket()->set_bucket_size_millis(10 * 1000L);
    durationMetric->set_type(DurationMetric_AggregationType_DURATION_SUM);
    durationMetric->set_what("SCREEN_IS_ON");

    // Value metric to count KERNEL_WAKELOCK when screen turned on
    ValueMetric* valueMetric = config.add_value_metric();
    valueMetric->set_name("6");
    valueMetric->set_what("KERNEL_WAKELOCK");
    valueMetric->set_value_field(1);
    valueMetric->set_condition("SCREEN_IS_ON");
    keyMatcher = valueMetric->add_dimension();
    keyMatcher->set_key(KERNEL_WAKELOCK_NAME_KEY);
    // This is for testing easier. We should never set bucket size this small.
    valueMetric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);

    // Add an EventMetric to log process state change events.
    EventMetric* eventMetric = config.add_event_metric();
    eventMetric->set_name("9");
    eventMetric->set_what("SCREEN_TURNED_ON");

    // Add an GaugeMetric.
    GaugeMetric* gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_name("10");
    gaugeMetric->set_what("DEVICE_TEMPERATURE");
    gaugeMetric->set_gauge_field(DEVICE_TEMPERATURE_KEY);
    gaugeMetric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);

    // Event matchers............
    LogEntryMatcher* temperatureEntryMatcher = config.add_log_entry_matcher();
    temperatureEntryMatcher->set_name("DEVICE_TEMPERATURE");
    temperatureEntryMatcher->mutable_simple_log_entry_matcher()->set_tag(
        DEVICE_TEMPERATURE_TAG_ID);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_TURNED_ON");
    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(SCREEN_EVENT_TAG_ID);
    KeyValueMatcher* keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(SCREEN_EVENT_STATE_KEY);
    keyValueMatcher->set_eq_int(SCREEN_EVENT_ON_VALUE);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_TURNED_OFF");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(SCREEN_EVENT_TAG_ID);
    keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(SCREEN_EVENT_STATE_KEY);
    keyValueMatcher->set_eq_int(SCREEN_EVENT_OFF_VALUE);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("PROCESS_STATE_CHANGE");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(UID_PROCESS_STATE_TAG_ID);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("APP_GOES_BACKGROUND");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(APP_USAGE_ID);
    keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(APP_USAGE_STATE_KEY);
    keyValueMatcher->set_eq_int(APP_USAGE_BACKGROUND);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("APP_GOES_FOREGROUND");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(APP_USAGE_ID);
    keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(APP_USAGE_STATE_KEY);
    keyValueMatcher->set_eq_int(APP_USAGE_FOREGROUND);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("APP_GET_WL");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(WAKE_LOCK_TAG_ID);
    keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(WAKE_LOCK_STATE_KEY);
    keyValueMatcher->set_eq_int(WAKE_LOCK_ACQUIRE_VALUE);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("APP_RELEASE_WL");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(WAKE_LOCK_TAG_ID);
    keyValueMatcher = simpleLogEntryMatcher->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(WAKE_LOCK_STATE_KEY);
    keyValueMatcher->set_eq_int(WAKE_LOCK_RELEASE_VALUE);

    // pulled events
    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("KERNEL_WAKELOCK");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(KERNEL_WAKELOCK_TAG_ID);

    // Conditions.............
    Condition* condition = config.add_condition();
    condition->set_name("SCREEN_IS_ON");
    SimpleCondition* simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_TURNED_ON");
    simpleCondition->set_stop("SCREEN_TURNED_OFF");
    simpleCondition->set_count_nesting(false);

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_OFF");
    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_TURNED_OFF");
    simpleCondition->set_stop("SCREEN_TURNED_ON");
    simpleCondition->set_count_nesting(false);

    condition = config.add_condition();
    condition->set_name("APP_IS_BACKGROUND");
    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("APP_GOES_BACKGROUND");
    simpleCondition->set_stop("APP_GOES_FOREGROUND");
    KeyMatcher* condition_dimension1 = simpleCondition->add_dimension();
    condition_dimension1->set_key(APP_USAGE_UID_KEY_ID);
    simpleCondition->set_count_nesting(false);

    condition = config.add_condition();
    condition->set_name("APP_IS_BACKGROUND_AND_SCREEN_ON");
    Condition_Combination* combination_condition = condition->mutable_combination();
    combination_condition->set_operation(LogicalOperation::AND);
    combination_condition->add_condition("APP_IS_BACKGROUND");
    combination_condition->add_condition("SCREEN_IS_ON");

    condition = config.add_condition();
    condition->set_name("WL_HELD_PER_APP_PER_NAME");
    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("APP_GET_WL");
    simpleCondition->set_stop("APP_RELEASE_WL");
    KeyMatcher* condition_dimension = simpleCondition->add_dimension();
    condition_dimension->set_key(WAKE_LOCK_UID_KEY_ID);
    condition_dimension = simpleCondition->add_dimension();
    condition_dimension->set_key(WAKE_LOCK_NAME_KEY);
    simpleCondition->set_count_nesting(true);

    condition = config.add_condition();
    condition->set_name("WL_HELD_PER_APP");
    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("APP_GET_WL");
    simpleCondition->set_stop("APP_RELEASE_WL");
    simpleCondition->set_initial_value(SimpleCondition_InitialValue_FALSE);
    condition_dimension = simpleCondition->add_dimension();
    condition_dimension->set_key(WAKE_LOCK_UID_KEY_ID);
    simpleCondition->set_count_nesting(true);

    return config;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
