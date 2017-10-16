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

#include <vector>

#include <stdio.h>

namespace android {
namespace os {
namespace statsd {

static StatsdConfig build_fake_config();

ConfigManager::ConfigManager() {
}

ConfigManager::~ConfigManager() {
}

void ConfigManager::Startup() {
    // TODO: Implement me -- read from storage and call onto all of the listeners.
    // Instead, we'll just make a fake one.

    // this should be called from StatsService when it receives a statsd_config
    UpdateConfig(ConfigKey(0, "fake"), build_fake_config());
}

void ConfigManager::AddListener(const sp<ConfigListener>& listener) {
    mListeners.push_back(listener);
}

void ConfigManager::UpdateConfig(const ConfigKey& key, const StatsdConfig& config) {
    // Add to map
    mConfigs[key] = config;
    // Why doesn't this work? mConfigs.insert({key, config});

    // Save to disk
    update_saved_configs();

    // Tell everyone
    for (auto& listener : mListeners) {
        listener->OnConfigUpdated(key, config);
    }
}

void ConfigManager::RemoveConfig(const ConfigKey& key) {
    unordered_map<ConfigKey, StatsdConfig>::iterator it = mConfigs.find(key);
    if (it != mConfigs.end()) {
        // Remove from map
        mConfigs.erase(it);

        // Save to disk
        update_saved_configs();

        // Tell everyone
        for (auto& listener : mListeners) {
            listener->OnConfigRemoved(key);
        }
    }
    // If we didn't find it, just quietly ignore it.
}

void ConfigManager::RemoveConfigs(int uid) {
    vector<ConfigKey> removed;

    for (auto it = mConfigs.begin(); it != mConfigs.end();) {
        // Remove from map
        if (it->first.GetUid() == uid) {
            removed.push_back(it->first);
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

void ConfigManager::Dump(FILE* out) {
    fprintf(out, "CONFIGURATIONS (%d)\n", (int)mConfigs.size());
    fprintf(out, "     uid name\n");
    for (unordered_map<ConfigKey, StatsdConfig>::const_iterator it = mConfigs.begin();
         it != mConfigs.end(); it++) {
        fprintf(out, "  %6d %s\n", it->first.GetUid(), it->first.GetName().c_str());
        // TODO: Print the contents of the config too.
    }
}

void ConfigManager::update_saved_configs() {
    // TODO: Implement me -- write to disk.
}

static StatsdConfig build_fake_config() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_config_id(12345L);

    // One count metric to count screen on
    CountMetric* metric = config.add_count_metric();
    metric->set_metric_id(20150717L);
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);

    // One count metric to count PHOTO_CHANGE_OR_CHROME_CRASH
    metric = config.add_count_metric();
    metric->set_metric_id(20150718L);
    metric->set_what("PHOTO_PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);
    metric->set_condition("SCREEN_IS_ON");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->add_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->add_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    LogEntryMatcher* procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_CRASH");

    SimpleLogEntryMatcher* simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    KeyValueMatcher* keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.google.android.apps.photos" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    keyValueMatcher->set_eq_int(2);

    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_START");

    simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.google.android.apps.photos" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1 /*STATE*/);
    keyValueMatcher->set_eq_int(1);

    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_PROCESS_STATE_CHANGE");
    LogEntryMatcher_Combination* combinationMatcher = procEventMatcher->mutable_combination();
    combinationMatcher->set_operation(LogicalOperation::OR);
    combinationMatcher->add_matcher("PHOTO_START");
    combinationMatcher->add_matcher("PHOTO_CRASH");

    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("CHROME_CRASH");

    simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.android.chrome" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(1 /*STATE*/);
    keyValueMatcher->set_eq_int(2);

    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_CHANGE_OR_CHROME_CRASH");
    combinationMatcher = procEventMatcher->mutable_combination();
    combinationMatcher->set_operation(LogicalOperation::OR);
    combinationMatcher->add_matcher("PHOTO_PROCESS_STATE_CHANGE");
    combinationMatcher->add_matcher("CHROME_CRASH");

    Condition* condition = config.add_condition();
    condition->set_name("SCREEN_IS_ON");
    SimpleCondition* simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_ON");
    simpleCondition->set_stop("SCREEN_IS_OFF");

    condition = config.add_condition();
    condition->set_name("PHOTO_STARTED");

    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("PHOTO_START");
    simpleCondition->set_stop("PHOTO_CRASH");

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_OFF");

    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_OFF");
    simpleCondition->set_stop("SCREEN_IS_ON");

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_EITHER_ON_OFF");

    Condition_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_OFF");

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_NEITHER_ON_OFF");

    combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::NOR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_OFF");

    return config;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
