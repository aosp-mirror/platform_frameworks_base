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

#include "metric_util.h"

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
        "MoveToBackground", ActivityForegroundStateChanged::BACKGROUND);
}

AtomMatcher CreateMoveToForegroundAtomMatcher() {
    return CreateActivityForegroundStateChangedAtomMatcher(
        "MoveToForeground", ActivityForegroundStateChanged::FOREGROUND);
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
    predicate.mutable_simple_predicate()->set_start(StringToId("MoveToBackground"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("MoveToForeground"));
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
    event->write(state);
    event->init();
    return event;
}

std::unique_ptr<LogEvent> CreateScreenBrightnessChangedEvent(
    int level, uint64_t timestampNs) {
    auto event = std::make_unique<LogEvent>(android::util::SCREEN_BRIGHTNESS_CHANGED, timestampNs);
    (event->write(level));
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

sp<StatsLogProcessor> CreateStatsLogProcessor(const long timeBaseSec, const StatsdConfig& config,
                                              const ConfigKey& key) {
    sp<UidMap> uidMap = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    sp<StatsLogProcessor> processor = new StatsLogProcessor(
        uidMap, anomalyAlarmMonitor, periodicAlarmMonitor, timeBaseSec * NS_PER_SEC,
        [](const ConfigKey&){return true;});
    processor->OnConfigUpdated(timeBaseSec * NS_PER_SEC, key, config);
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


}  // namespace statsd
}  // namespace os
}  // namespace android