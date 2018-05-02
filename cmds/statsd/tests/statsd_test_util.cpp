// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {


AtomMatcher CreateSimpleAtomMatcher(const string& name, int atomId) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(atomId);
    return atom_matcher;
}

AtomMatcher CreateTemperatureAtomMatcher() {
    return CreateSimpleAtomMatcher("TemperatureMatcher", android::util::TEMPERATURE);
}

AtomMatcher CreateScheduledJobStateChangedAtomMatcher(const string& name,
                                                      ScheduledJobStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SCHEDULED_JOB_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(3);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateStartScheduledJobAtomMatcher() {
    return CreateScheduledJobStateChangedAtomMatcher("ScheduledJobStart",
                                                     ScheduledJobStateChanged::STARTED);
}

AtomMatcher CreateFinishScheduledJobAtomMatcher() {
    return CreateScheduledJobStateChangedAtomMatcher("ScheduledJobFinish",
                                                     ScheduledJobStateChanged::FINISHED);
}

AtomMatcher CreateScreenBrightnessChangedAtomMatcher() {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId("ScreenBrightnessChanged"));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SCREEN_BRIGHTNESS_CHANGED);
    return atom_matcher;
}

AtomMatcher CreateUidProcessStateChangedAtomMatcher() {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId("UidProcessStateChanged"));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::UID_PROCESS_STATE_CHANGED);
    return atom_matcher;
}

AtomMatcher CreateWakelockStateChangedAtomMatcher(const string& name,
                                                  WakelockStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::WAKELOCK_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(4);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateAcquireWakelockAtomMatcher() {
    return CreateWakelockStateChangedAtomMatcher("AcquireWakelock", WakelockStateChanged::ACQUIRE);
}

AtomMatcher CreateReleaseWakelockAtomMatcher() {
    return CreateWakelockStateChangedAtomMatcher("ReleaseWakelock", WakelockStateChanged::RELEASE);
}

AtomMatcher CreateBatterySaverModeStateChangedAtomMatcher(
    const string& name, BatterySaverModeStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::BATTERY_SAVER_MODE_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(1);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateBatterySaverModeStartAtomMatcher() {
    return CreateBatterySaverModeStateChangedAtomMatcher(
        "BatterySaverModeStart", BatterySaverModeStateChanged::ON);
}


AtomMatcher CreateBatterySaverModeStopAtomMatcher() {
    return CreateBatterySaverModeStateChangedAtomMatcher(
        "BatterySaverModeStop", BatterySaverModeStateChanged::OFF);
}


AtomMatcher CreateScreenStateChangedAtomMatcher(
    const string& name, android::view::DisplayStateEnum state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SCREEN_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(1);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}


AtomMatcher CreateScreenTurnedOnAtomMatcher() {
    return CreateScreenStateChangedAtomMatcher("ScreenTurnedOn",
            android::view::DisplayStateEnum::DISPLAY_STATE_ON);
}

AtomMatcher CreateScreenTurnedOffAtomMatcher() {
    return CreateScreenStateChangedAtomMatcher("ScreenTurnedOff",
            ::android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
}

AtomMatcher CreateSyncStateChangedAtomMatcher(
    const string& name, SyncStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::SYNC_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(3);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateSyncStartAtomMatcher() {
    return CreateSyncStateChangedAtomMatcher("SyncStart", SyncStateChanged::ON);
}

AtomMatcher CreateSyncEndAtomMatcher() {
    return CreateSyncStateChangedAtomMatcher("SyncEnd", SyncStateChanged::OFF);
}

AtomMatcher CreateActivityForegroundStateChangedAtomMatcher(
    const string& name, ActivityForegroundStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(4);  // Activity field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateMoveToBackgroundAtomMatcher() {
    return CreateActivityForegroundStateChangedAtomMatcher(
        "Background", ActivityForegroundStateChanged::BACKGROUND);
}

AtomMatcher CreateMoveToForegroundAtomMatcher() {
    return CreateActivityForegroundStateChangedAtomMatcher(
        "Foreground", ActivityForegroundStateChanged::FOREGROUND);
}

AtomMatcher CreateProcessLifeCycleStateChangedAtomMatcher(
    const string& name, ProcessLifeCycleStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(3);  // Process state field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateProcessCrashAtomMatcher() {
    return CreateProcessLifeCycleStateChangedAtomMatcher(
        "Crashed", ProcessLifeCycleStateChanged::CRASHED);
}

Predicate CreateScheduledJobPredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("ScheduledJobRunningPredicate"));
    predicate.mutable_simple_predicate()->set_start(StringToId("ScheduledJobStart"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("ScheduledJobFinish"));
    return predicate;
}

Predicate CreateBatterySaverModePredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("BatterySaverIsOn"));
    predicate.mutable_simple_predicate()->set_start(StringToId("BatterySaverModeStart"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("BatterySaverModeStop"));
    return predicate;
}

Predicate CreateScreenIsOnPredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("ScreenIsOn"));
    predicate.mutable_simple_predicate()->set_start(StringToId("ScreenTurnedOn"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("ScreenTurnedOff"));
    return predicate;
}

Predicate CreateScreenIsOffPredicate() {
    Predicate predicate;
    predicate.set_id(1111123);
    predicate.mutable_simple_predicate()->set_start(StringToId("ScreenTurnedOff"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("ScreenTurnedOn"));
    return predicate;
}

Predicate CreateHoldingWakelockPredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("HoldingWakelock"));
    predicate.mutable_simple_predicate()->set_start(StringToId("AcquireWakelock"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("ReleaseWakelock"));
    return predicate;
}

Predicate CreateIsSyncingPredicate() {
    Predicate predicate;
    predicate.set_id(33333333333333);
    predicate.mutable_simple_predicate()->set_start(StringToId("SyncStart"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("SyncEnd"));
    return predicate;
}

Predicate CreateIsInBackgroundPredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("IsInBackground"));
    predicate.mutable_simple_predicate()->set_start(StringToId("Background"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("Foreground"));
    return predicate;
}

void addPredicateToPredicateCombination(const Predicate& predicate,
                                        Predicate* combinationPredicate) {
    combinationPredicate->mutable_combination()->add_predicate(predicate.id());
}

FieldMatcher CreateAttributionUidDimensions(const int atomId,
                                            const std::vector<Position>& positions) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const auto position : positions) {
        auto child = dimensions.add_child();
        child->set_field(1);
        child->set_position(position);
        child->add_child()->set_field(1);
    }
    return dimensions;
}

FieldMatcher CreateAttributionUidAndTagDimensions(const int atomId,
                                                 const std::vector<Position>& positions) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const auto position : positions) {
        auto child = dimensions.add_child();
        child->set_field(1);
        child->set_position(position);
        child->add_child()->set_field(1);
        child->add_child()->set_field(2);
    }
    return dimensions;
}

FieldMatcher CreateDimensions(const int atomId, const std::vector<int>& fields) {
    FieldMatcher dimensions;
    dimensions.set_field(atomId);
    for (const int field : fields) {
        dimensions.add_child()->set_field(field);
    }
    return dimensions;
}

std::unique_ptr<LogEvent> CreateScreenStateChangedEvent(
    const android::view::DisplayStateEnum state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SCREEN_STATE_CHANGED, timestampNs);
    EXPECT_TRUE(event->write(state));
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateBatterySaverOnEvent(uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(
        android::util::BATTERY_SAVER_MODE_STATE_CHANGED, timestampNs);
    EXPECT_TRUE(event->write(BatterySaverModeStateChanged::ON));
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateBatterySaverOffEvent(uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(
        android::util::BATTERY_SAVER_MODE_STATE_CHANGED, timestampNs);
    EXPECT_TRUE(event->write(BatterySaverModeStateChanged::OFF));
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateScreenBrightnessChangedEvent(
    int level, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SCREEN_BRIGHTNESS_CHANGED, timestampNs);
    EXPECT_TRUE(event->write(level));
    event->init();
    return event;

}

std::unique_ptr<LogEvent> CreateScheduledJobStateChangedEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& jobName,
        const ScheduledJobStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SCHEDULED_JOB_STATE_CHANGED, timestampNs);
    event->write(attributions);
    event->write(jobName);
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateStartScheduledJobEvent(
    const std::vector<AttributionNodeInternal>& attributions,
    const string& name, uint64_t timestampNs) {
    return CreateScheduledJobStateChangedEvent(
            attributions, name, ScheduledJobStateChanged::STARTED, timestampNs);
}

// Create log event when scheduled job finishes.
std::unique_ptr<LogEvent> CreateFinishScheduledJobEvent(
    const std::vector<AttributionNodeInternal>& attributions,
    const string& name, uint64_t timestampNs) {
    return CreateScheduledJobStateChangedEvent(
            attributions, name, ScheduledJobStateChanged::FINISHED, timestampNs);
}

std::unique_ptr<LogEvent> CreateWakelockStateChangedEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& wakelockName,
        const WakelockStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::WAKELOCK_STATE_CHANGED, timestampNs);
    event->write(attributions);
    event->write(android::os::WakeLockLevelEnum::PARTIAL_WAKE_LOCK);
    event->write(wakelockName);
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateAcquireWakelockEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& wakelockName,
        uint64_t timestampNs) {
    return CreateWakelockStateChangedEvent(
        attributions, wakelockName, WakelockStateChanged::ACQUIRE, timestampNs);
}

std::unique_ptr<LogEvent> CreateReleaseWakelockEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& wakelockName,
        uint64_t timestampNs) {
    return CreateWakelockStateChangedEvent(
        attributions, wakelockName, WakelockStateChanged::RELEASE, timestampNs);
}

std::unique_ptr<LogEvent> CreateActivityForegroundStateChangedEvent(
    const int uid, const ActivityForegroundStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(
        android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, timestampNs);
    event->write(uid);
    event->write("pkg_name");
    event->write("class_name");
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateMoveToBackgroundEvent(const int uid, uint64_t timestampNs) {
    return CreateActivityForegroundStateChangedEvent(
        uid, ActivityForegroundStateChanged::BACKGROUND, timestampNs);
}

std::unique_ptr<LogEvent> CreateMoveToForegroundEvent(const int uid, uint64_t timestampNs) {
    return CreateActivityForegroundStateChangedEvent(
        uid, ActivityForegroundStateChanged::FOREGROUND, timestampNs);
}

std::unique_ptr<LogEvent> CreateSyncStateChangedEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& name,
        const SyncStateChanged::State state, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SYNC_STATE_CHANGED, timestampNs);
    event->write(attributions);
    event->write(name);
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateSyncStartEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& name,
        uint64_t timestampNs) {
    return CreateSyncStateChangedEvent(attributions, name, SyncStateChanged::ON, timestampNs);
}

std::unique_ptr<LogEvent> CreateSyncEndEvent(
        const std::vector<AttributionNodeInternal>& attributions, const string& name,
        uint64_t timestampNs) {
    return CreateSyncStateChangedEvent(attributions, name, SyncStateChanged::OFF, timestampNs);
}

std::unique_ptr<LogEvent> CreateProcessLifeCycleStateChangedEvent(
    const int uid, const ProcessLifeCycleStateChanged::State state, uint64_t timestampNs) {
    auto logEvent = std::make_unique<LogEvent>(
        android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, timestampNs);
    logEvent->write(uid);
    logEvent->write("");
    logEvent->write(state);
    logEvent->init();
    return logEvent;
}

std::unique_ptr<LogEvent> CreateAppCrashEvent(const int uid, uint64_t timestampNs) {
    return CreateProcessLifeCycleStateChangedEvent(
        uid, ProcessLifeCycleStateChanged::CRASHED, timestampNs);
}

std::unique_ptr<LogEvent> CreateIsolatedUidChangedEvent(
    int isolatedUid, int hostUid, bool is_create, uint64_t timestampNs) {
    auto logEvent = std::make_unique<LogEvent>(
        android::util::ISOLATED_UID_CHANGED, timestampNs);
    logEvent->write(hostUid);
    logEvent->write(isolatedUid);
    logEvent->write(is_create);
    logEvent->init();
    return logEvent;
}

sp<StatsLogProcessor> CreateStatsLogProcessor(const int64_t timeBaseNs, const int64_t currentTimeNs,
                                              const StatsdConfig& config, const ConfigKey& key) {
    sp<UidMap> uidMap = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor =
        new AlarmMonitor(1,  [](const sp<IStatsCompanionService>&, int64_t){},
                [](const sp<IStatsCompanionService>&){});
    sp<AlarmMonitor> periodicAlarmMonitor =
        new AlarmMonitor(1,  [](const sp<IStatsCompanionService>&, int64_t){},
                [](const sp<IStatsCompanionService>&){});
    sp<StatsLogProcessor> processor = new StatsLogProcessor(
        uidMap, anomalyAlarmMonitor, periodicAlarmMonitor, timeBaseNs, [](const ConfigKey&){});
    processor->OnConfigUpdated(currentTimeNs, key, config);
    return processor;
}

AttributionNodeInternal CreateAttribution(const int& uid, const string& tag) {
    AttributionNodeInternal attribution;
    attribution.set_uid(uid);
    attribution.set_tag(tag);
    return attribution;
}

void sortLogEventsByTimestamp(std::vector<std::unique_ptr<LogEvent>> *events) {
  std::sort(events->begin(), events->end(),
            [](const std::unique_ptr<LogEvent>& a, const std::unique_ptr<LogEvent>& b) {
              return a->GetElapsedTimestampNs() < b->GetElapsedTimestampNs();
            });
}

int64_t StringToId(const string& str) {
    return static_cast<int64_t>(std::hash<std::string>()(str));
}

void ValidateAttributionUidDimension(const DimensionsValue& value, int atomId, int uid) {
    EXPECT_EQ(value.field(), atomId);
    // Attribution field.
    EXPECT_EQ(value.value_tuple().dimensions_value(0).field(), 1);
    // Uid only.
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).value_int(), uid);
}

void ValidateUidDimension(const DimensionsValue& value, int atomId, int uid) {
    EXPECT_EQ(value.field(), atomId);
    EXPECT_EQ(value.value_tuple().dimensions_value_size(), 1);
    // Attribution field.
    EXPECT_EQ(value.value_tuple().dimensions_value(0).field(), 1);
    // Uid only.
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).value_int(), uid);
}

void ValidateUidDimension(const DimensionsValue& value, int node_idx, int atomId, int uid) {
    EXPECT_EQ(value.field(), atomId);
    EXPECT_GT(value.value_tuple().dimensions_value_size(), node_idx);
    // Attribution field.
    EXPECT_EQ(value.value_tuple().dimensions_value(node_idx).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(0).value_int(), uid);
}

void ValidateAttributionUidAndTagDimension(
    const DimensionsValue& value, int node_idx, int atomId, int uid, const std::string& tag) {
    EXPECT_EQ(value.field(), atomId);
    EXPECT_GT(value.value_tuple().dimensions_value_size(), node_idx);
    // Attribution field.
    EXPECT_EQ(1, value.value_tuple().dimensions_value(node_idx).field());
    // Uid only.
    EXPECT_EQ(2, value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value_size());
    EXPECT_EQ(1, value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(0).field());
    EXPECT_EQ(uid, value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(0).value_int());
    EXPECT_EQ(2, value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(1).field());
    EXPECT_EQ(tag, value.value_tuple().dimensions_value(node_idx)
        .value_tuple().dimensions_value(1).value_str());
}

void ValidateAttributionUidAndTagDimension(
    const DimensionsValue& value, int atomId, int uid, const std::string& tag) {
    EXPECT_EQ(value.field(), atomId);
    EXPECT_EQ(1, value.value_tuple().dimensions_value_size());
    // Attribution field.
    EXPECT_EQ(1, value.value_tuple().dimensions_value(0).field());
    // Uid only.
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value_size(), 2);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(0).value_int(), uid);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(1).field(), 2);
    EXPECT_EQ(value.value_tuple().dimensions_value(0)
        .value_tuple().dimensions_value(1).value_str(), tag);
}

bool EqualsTo(const DimensionsValue& s1, const DimensionsValue& s2) {
    if (s1.field() != s2.field()) {
        return false;
    }
    if (s1.value_case() != s2.value_case()) {
        return false;
    }
    switch (s1.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return (s1.value_str() == s2.value_str());
        case DimensionsValue::ValueCase::kValueInt:
            return s1.value_int() == s2.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return s1.value_long() == s2.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return s1.value_bool() == s2.value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return s1.value_float() == s2.value_float();
        case DimensionsValue::ValueCase::kValueTuple: {
            if (s1.value_tuple().dimensions_value_size() !=
                s2.value_tuple().dimensions_value_size()) {
                return false;
            }
            bool allMatched = true;
            for (int i = 0; allMatched && i < s1.value_tuple().dimensions_value_size(); ++i) {
                allMatched &= EqualsTo(s1.value_tuple().dimensions_value(i),
                                       s2.value_tuple().dimensions_value(i));
            }
            return allMatched;
        }
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
        default:
            return true;
    }
}

bool LessThan(const DimensionsValue& s1, const DimensionsValue& s2) {
    if (s1.field() != s2.field()) {
        return s1.field() < s2.field();
    }
    if (s1.value_case() != s2.value_case()) {
        return s1.value_case() < s2.value_case();
    }
    switch (s1.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return s1.value_str() < s2.value_str();
        case DimensionsValue::ValueCase::kValueInt:
            return s1.value_int() < s2.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return s1.value_long() < s2.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return (int)s1.value_bool() < (int)s2.value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return s1.value_float() < s2.value_float();
        case DimensionsValue::ValueCase::kValueTuple: {
            if (s1.value_tuple().dimensions_value_size() !=
                s2.value_tuple().dimensions_value_size()) {
                return s1.value_tuple().dimensions_value_size() <
                       s2.value_tuple().dimensions_value_size();
            }
            for (int i = 0; i < s1.value_tuple().dimensions_value_size(); ++i) {
                if (EqualsTo(s1.value_tuple().dimensions_value(i),
                             s2.value_tuple().dimensions_value(i))) {
                    continue;
                } else {
                    return LessThan(s1.value_tuple().dimensions_value(i),
                                    s2.value_tuple().dimensions_value(i));
                }
            }
            return false;
        }
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
        default:
            return false;
    }
}

bool LessThan(const DimensionsPair& s1, const DimensionsPair& s2) {
    if (LessThan(s1.dimInWhat, s2.dimInWhat)) {
        return true;
    } else if (LessThan(s2.dimInWhat, s1.dimInWhat)) {
        return false;
    }

    return LessThan(s1.dimInCondition, s2.dimInCondition);
}

void backfillStringInDimension(const std::map<uint64_t, string>& str_map,
                               DimensionsValue* dimension) {
    if (dimension->has_value_str_hash()) {
        auto it = str_map.find((uint64_t)(dimension->value_str_hash()));
        if (it != str_map.end()) {
            dimension->clear_value_str_hash();
            dimension->set_value_str(it->second);
        } else {
            ALOGE("Can not find the string hash: %llu",
                (unsigned long long)dimension->value_str_hash());
        }
    } else if (dimension->has_value_tuple()) {
        auto value_tuple = dimension->mutable_value_tuple();
        for (int i = 0; i < value_tuple->dimensions_value_size(); ++i) {
            backfillStringInDimension(str_map, value_tuple->mutable_dimensions_value(i));
        }
    }
}

void backfillStringInReport(ConfigMetricsReport *config_report) {
    std::map<uint64_t, string> str_map;
    for (const auto& str : config_report->strings()) {
        uint64_t hash = Hash64(str);
        if (str_map.find(hash) != str_map.end()) {
            ALOGE("String hash conflicts: %s %s", str.c_str(), str_map[hash].c_str());
        }
        str_map[hash] = str;
    }
    for (int i = 0; i < config_report->metrics_size(); ++i) {
        auto metric_report = config_report->mutable_metrics(i);
        if (metric_report->has_count_metrics()) {
            backfillStringInDimension(str_map, metric_report->mutable_count_metrics());
        } else if (metric_report->has_duration_metrics()) {
            backfillStringInDimension(str_map, metric_report->mutable_duration_metrics());
        } else if (metric_report->has_gauge_metrics()) {
            backfillStringInDimension(str_map, metric_report->mutable_gauge_metrics());
        } else if (metric_report->has_value_metrics()) {
            backfillStringInDimension(str_map, metric_report->mutable_value_metrics());
        }
    }
    // Backfill the package names.
    for (int i = 0 ; i < config_report->uid_map().snapshots_size(); ++i) {
        auto snapshot = config_report->mutable_uid_map()->mutable_snapshots(i);
        for (int j = 0 ; j < snapshot->package_info_size(); ++j) {
            auto package_info = snapshot->mutable_package_info(j);
            if (package_info->has_name_hash()) {
                auto it = str_map.find((uint64_t)(package_info->name_hash()));
                if (it != str_map.end()) {
                    package_info->clear_name_hash();
                    package_info->set_name(it->second);
                } else {
                    ALOGE("Can not find the string package name hash: %llu",
                        (unsigned long long)package_info->name_hash());
                }

            }
        }
    }
    // Backfill the app name in app changes.
    for (int i = 0 ; i < config_report->uid_map().changes_size(); ++i) {
        auto change = config_report->mutable_uid_map()->mutable_changes(i);
        if (change->has_app_hash()) {
            auto it = str_map.find((uint64_t)(change->app_hash()));
            if (it != str_map.end()) {
                change->clear_app_hash();
                change->set_app(it->second);
            } else {
                ALOGE("Can not find the string change app name hash: %llu",
                    (unsigned long long)change->app_hash());
            }
        }
    }
}

void backfillStringInReport(ConfigMetricsReportList *config_report_list) {
    for (int i = 0; i < config_report_list->reports_size(); ++i) {
        backfillStringInReport(config_report_list->mutable_reports(i));
    }
}

bool backfillDimensionPath(const DimensionsValue& path,
                           const google::protobuf::RepeatedPtrField<DimensionsValue>& leafValues,
                           int* leafIndex,
                           DimensionsValue* dimension) {
    dimension->set_field(path.field());
    if (path.has_value_tuple()) {
        for (int i = 0; i < path.value_tuple().dimensions_value_size(); ++i) {
            if (!backfillDimensionPath(
                path.value_tuple().dimensions_value(i), leafValues, leafIndex,
                dimension->mutable_value_tuple()->add_dimensions_value())) {
                return false;
            }
        }
    } else {
        if (*leafIndex < 0 || *leafIndex >= leafValues.size()) {
            return false;
        }
        dimension->MergeFrom(leafValues.Get(*leafIndex));
        (*leafIndex)++;
    }
    return true;
}

bool backfillDimensionPath(const DimensionsValue& path,
                           const google::protobuf::RepeatedPtrField<DimensionsValue>& leafValues,
                           DimensionsValue* dimension) {
    int leafIndex = 0;
    return backfillDimensionPath(path, leafValues, &leafIndex, dimension);
}

void backfillDimensionPath(ConfigMetricsReportList *config_report_list) {
    for (int i = 0; i < config_report_list->reports_size(); ++i) {
        auto report = config_report_list->mutable_reports(i);
        for (int j = 0; j < report->metrics_size(); ++j) {
            auto metric_report = report->mutable_metrics(j);
            if (metric_report->has_dimensions_path_in_what() ||
                metric_report->has_dimensions_path_in_condition()) {
                auto whatPath = metric_report->dimensions_path_in_what();
                auto conditionPath = metric_report->dimensions_path_in_condition();
                if (metric_report->has_count_metrics()) {
                    backfillDimensionPath(whatPath, conditionPath,
                                          metric_report->mutable_count_metrics());
                } else if (metric_report->has_duration_metrics()) {
                    backfillDimensionPath(whatPath, conditionPath,
                                          metric_report->mutable_duration_metrics());
                } else if (metric_report->has_gauge_metrics()) {
                    backfillDimensionPath(whatPath, conditionPath,
                                          metric_report->mutable_gauge_metrics());
                } else if (metric_report->has_value_metrics()) {
                    backfillDimensionPath(whatPath, conditionPath,
                                          metric_report->mutable_value_metrics());
                }
                metric_report->clear_dimensions_path_in_what();
                metric_report->clear_dimensions_path_in_condition();
            }
        }
    }
}

void backfillStartEndTimestamp(StatsLogReport *report) {
    const int64_t timeBaseNs = report->time_base_elapsed_nano_seconds();
    const int64_t bucketSizeNs = report->bucket_size_nano_seconds();
    if (report->has_count_metrics()) {
        backfillStartEndTimestampForMetrics(
            timeBaseNs, bucketSizeNs, report->mutable_count_metrics());
    } else if (report->has_duration_metrics()) {
        backfillStartEndTimestampForMetrics(
            timeBaseNs, bucketSizeNs, report->mutable_duration_metrics());
    } else if (report->has_gauge_metrics()) {
        backfillStartEndTimestampForMetrics(
            timeBaseNs, bucketSizeNs, report->mutable_gauge_metrics());
        if (report->gauge_metrics().skipped_size() > 0) {
            backfillStartEndTimestampForSkippedBuckets(
                timeBaseNs, report->mutable_gauge_metrics());
        }
    } else if (report->has_value_metrics()) {
        backfillStartEndTimestampForMetrics(
            timeBaseNs, bucketSizeNs, report->mutable_value_metrics());
        if (report->value_metrics().skipped_size() > 0) {
            backfillStartEndTimestampForSkippedBuckets(
                timeBaseNs, report->mutable_value_metrics());
        }
    }
}

void backfillStartEndTimestamp(ConfigMetricsReport *config_report) {
    for (int j = 0; j < config_report->metrics_size(); ++j) {
        backfillStartEndTimestamp(config_report->mutable_metrics(j));
    }
}

void backfillStartEndTimestamp(ConfigMetricsReportList *config_report_list) {
    for (int i = 0; i < config_report_list->reports_size(); ++i) {
        backfillStartEndTimestamp(config_report_list->mutable_reports(i));
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android