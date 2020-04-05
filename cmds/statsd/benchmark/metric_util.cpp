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

#include "stats_event.h"

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
        uint64_t timestampNs, const android::view::DisplayStateEnum state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::SCREEN_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateScheduledJobStateChangedEvent(
        const vector<int>& attributionUids, const vector<string>& attributionTags,
        const string& jobName, const ScheduledJobStateChanged::State state, uint64_t timestampNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::SCHEDULED_JOB_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    vector<const char*> cTags(attributionTags.size());
    for (int i = 0; i < cTags.size(); i++) {
        cTags[i] = attributionTags[i].c_str();
    }

    AStatsEvent_writeAttributionChain(statsEvent,
                                      reinterpret_cast<const uint32_t*>(attributionUids.data()),
                                      cTags.data(), attributionUids.size());
    AStatsEvent_writeString(statsEvent, jobName.c_str());
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateStartScheduledJobEvent(uint64_t timestampNs,
                                                       const vector<int>& attributionUids,
                                                       const vector<string>& attributionTags,
                                                       const string& jobName) {
    return CreateScheduledJobStateChangedEvent(attributionUids, attributionTags, jobName,
                                               ScheduledJobStateChanged::STARTED, timestampNs);
}

// Create log event when scheduled job finishes.
std::unique_ptr<LogEvent> CreateFinishScheduledJobEvent(uint64_t timestampNs,
                                                        const vector<int>& attributionUids,
                                                        const vector<string>& attributionTags,
                                                        const string& jobName) {
    return CreateScheduledJobStateChangedEvent(attributionUids, attributionTags, jobName,
                                               ScheduledJobStateChanged::FINISHED, timestampNs);
}

std::unique_ptr<LogEvent> CreateSyncStateChangedEvent(uint64_t timestampNs,
                                                      const vector<int>& attributionUids,
                                                      const vector<string>& attributionTags,
                                                      const string& name,
                                                      const SyncStateChanged::State state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::SYNC_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    vector<const char*> cTags(attributionTags.size());
    for (int i = 0; i < cTags.size(); i++) {
        cTags[i] = attributionTags[i].c_str();
    }

    AStatsEvent_writeAttributionChain(statsEvent,
                                      reinterpret_cast<const uint32_t*>(attributionUids.data()),
                                      cTags.data(), attributionUids.size());
    AStatsEvent_writeString(statsEvent, name.c_str());
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateSyncStartEvent(uint64_t timestampNs,
                                               const vector<int>& attributionUids,
                                               const vector<string>& attributionTags,
                                               const string& name) {
    return CreateSyncStateChangedEvent(timestampNs, attributionUids, attributionTags, name,
                                       SyncStateChanged::ON);
}

std::unique_ptr<LogEvent> CreateSyncEndEvent(uint64_t timestampNs,
                                             const vector<int>& attributionUids,
                                             const vector<string>& attributionTags,
                                             const string& name) {
    return CreateSyncStateChangedEvent(timestampNs, attributionUids, attributionTags, name,
                                       SyncStateChanged::OFF);
}

sp<StatsLogProcessor> CreateStatsLogProcessor(const long timeBaseSec, const StatsdConfig& config,
                                              const ConfigKey& key) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    sp<StatsLogProcessor> processor =
            new StatsLogProcessor(uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
                                  timeBaseSec * NS_PER_SEC, [](const ConfigKey&) { return true; },
                                  [](const int&, const vector<int64_t>&) { return true; });
    processor->OnConfigUpdated(timeBaseSec * NS_PER_SEC, key, config);
    return processor;
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
