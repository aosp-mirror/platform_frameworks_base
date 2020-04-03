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

#include <aidl/android/util/StatsEventParcel.h>
#include "stats_event.h"

using aidl::android::util::StatsEventParcel;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

StatsLogReport outputStreamToProto(ProtoOutputStream* proto) {
    vector<uint8_t> bytes;
    bytes.resize(proto->size());
    size_t pos = 0;
    sp<ProtoReader> reader = proto->data();

    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&((bytes)[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }

    StatsLogReport report;
    report.ParseFromArray(bytes.data(), bytes.size());
    return report;
}

AtomMatcher CreateSimpleAtomMatcher(const string& name, int atomId) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(atomId);
    return atom_matcher;
}

AtomMatcher CreateTemperatureAtomMatcher() {
    return CreateSimpleAtomMatcher("TemperatureMatcher", util::TEMPERATURE);
}

AtomMatcher CreateScheduledJobStateChangedAtomMatcher(const string& name,
                                                      ScheduledJobStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(util::SCHEDULED_JOB_STATE_CHANGED);
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
    simple_atom_matcher->set_atom_id(util::SCREEN_BRIGHTNESS_CHANGED);
    return atom_matcher;
}

AtomMatcher CreateUidProcessStateChangedAtomMatcher() {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId("UidProcessStateChanged"));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(util::UID_PROCESS_STATE_CHANGED);
    return atom_matcher;
}

AtomMatcher CreateWakelockStateChangedAtomMatcher(const string& name,
                                                  WakelockStateChanged::State state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(util::WAKELOCK_STATE_CHANGED);
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
    simple_atom_matcher->set_atom_id(util::BATTERY_SAVER_MODE_STATE_CHANGED);
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

AtomMatcher CreateBatteryStateChangedAtomMatcher(const string& name,
                                                 BatteryPluggedStateEnum state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(util::PLUGGED_STATE_CHANGED);
    auto field_value_matcher = simple_atom_matcher->add_field_value_matcher();
    field_value_matcher->set_field(1);  // State field.
    field_value_matcher->set_eq_int(state);
    return atom_matcher;
}

AtomMatcher CreateBatteryStateNoneMatcher() {
    return CreateBatteryStateChangedAtomMatcher("BatteryPluggedNone",
                                                BatteryPluggedStateEnum::BATTERY_PLUGGED_NONE);
}

AtomMatcher CreateBatteryStateUsbMatcher() {
    return CreateBatteryStateChangedAtomMatcher("BatteryPluggedUsb",
                                                BatteryPluggedStateEnum::BATTERY_PLUGGED_USB);
}

AtomMatcher CreateScreenStateChangedAtomMatcher(
    const string& name, android::view::DisplayStateEnum state) {
    AtomMatcher atom_matcher;
    atom_matcher.set_id(StringToId(name));
    auto simple_atom_matcher = atom_matcher.mutable_simple_atom_matcher();
    simple_atom_matcher->set_atom_id(util::SCREEN_STATE_CHANGED);
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
    simple_atom_matcher->set_atom_id(util::SYNC_STATE_CHANGED);
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
    simple_atom_matcher->set_atom_id(util::ACTIVITY_FOREGROUND_STATE_CHANGED);
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
    simple_atom_matcher->set_atom_id(util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
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

Predicate CreateDeviceUnpluggedPredicate() {
    Predicate predicate;
    predicate.set_id(StringToId("DeviceUnplugged"));
    predicate.mutable_simple_predicate()->set_start(StringToId("BatteryPluggedNone"));
    predicate.mutable_simple_predicate()->set_stop(StringToId("BatteryPluggedUsb"));
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

State CreateScreenState() {
    State state;
    state.set_id(StringToId("ScreenState"));
    state.set_atom_id(util::SCREEN_STATE_CHANGED);
    return state;
}

State CreateUidProcessState() {
    State state;
    state.set_id(StringToId("UidProcessState"));
    state.set_atom_id(util::UID_PROCESS_STATE_CHANGED);
    return state;
}

State CreateOverlayState() {
    State state;
    state.set_id(StringToId("OverlayState"));
    state.set_atom_id(util::OVERLAY_STATE_CHANGED);
    return state;
}

State CreateScreenStateWithOnOffMap() {
    State state;
    state.set_id(StringToId("ScreenStateOnOff"));
    state.set_atom_id(util::SCREEN_STATE_CHANGED);

    auto map = CreateScreenStateOnOffMap();
    *state.mutable_map() = map;

    return state;
}

State CreateScreenStateWithInDozeMap() {
    State state;
    state.set_id(StringToId("ScreenStateInDoze"));
    state.set_atom_id(util::SCREEN_STATE_CHANGED);

    auto map = CreateScreenStateInDozeMap();
    *state.mutable_map() = map;

    return state;
}

StateMap_StateGroup CreateScreenStateOnGroup() {
    StateMap_StateGroup group;
    group.set_group_id(StringToId("SCREEN_ON"));
    group.add_value(2);
    group.add_value(5);
    group.add_value(6);
    return group;
}

StateMap_StateGroup CreateScreenStateOffGroup() {
    StateMap_StateGroup group;
    group.set_group_id(StringToId("SCREEN_OFF"));
    group.add_value(0);
    group.add_value(1);
    group.add_value(3);
    group.add_value(4);
    return group;
}

StateMap CreateScreenStateOnOffMap() {
    StateMap map;
    *map.add_group() = CreateScreenStateOnGroup();
    *map.add_group() = CreateScreenStateOffGroup();
    return map;
}

StateMap_StateGroup CreateScreenStateInDozeGroup() {
    StateMap_StateGroup group;
    group.set_group_id(StringToId("SCREEN_DOZE"));
    group.add_value(3);
    group.add_value(4);
    return group;
}

StateMap_StateGroup CreateScreenStateNotDozeGroup() {
    StateMap_StateGroup group;
    group.set_group_id(StringToId("SCREEN_NOT_DOZE"));
    group.add_value(0);
    group.add_value(1);
    group.add_value(2);
    group.add_value(5);
    group.add_value(6);
    return group;
}

StateMap CreateScreenStateInDozeMap() {
    StateMap map;
    *map.add_group() = CreateScreenStateInDozeGroup();
    *map.add_group() = CreateScreenStateNotDozeGroup();
    return map;
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

FieldMatcher CreateAttributionUidAndOtherDimensions(const int atomId,
                                                    const std::vector<Position>& positions,
                                                    const std::vector<int>& fields) {
    FieldMatcher dimensions = CreateAttributionUidDimensions(atomId, positions);

    for (const int field : fields) {
        dimensions.add_child()->set_field(field);
    }
    return dimensions;
}

// START: get primary key functions
void getUidProcessKey(int uid, HashableDimensionKey* key) {
    int pos1[] = {1, 0, 0};
    Field field1(27 /* atom id */, pos1, 0 /* depth */);
    Value value1((int32_t)uid);

    key->addValue(FieldValue(field1, value1));
}

void getOverlayKey(int uid, string packageName, HashableDimensionKey* key) {
    int pos1[] = {1, 0, 0};
    int pos2[] = {2, 0, 0};

    Field field1(59 /* atom id */, pos1, 0 /* depth */);
    Field field2(59 /* atom id */, pos2, 0 /* depth */);

    Value value1((int32_t)uid);
    Value value2(packageName);

    key->addValue(FieldValue(field1, value1));
    key->addValue(FieldValue(field2, value2));
}

void getPartialWakelockKey(int uid, const std::string& tag, HashableDimensionKey* key) {
    int pos1[] = {1, 1, 1};
    int pos3[] = {2, 0, 0};
    int pos4[] = {3, 0, 0};

    Field field1(10 /* atom id */, pos1, 2 /* depth */);

    Field field3(10 /* atom id */, pos3, 0 /* depth */);
    Field field4(10 /* atom id */, pos4, 0 /* depth */);

    Value value1((int32_t)uid);
    Value value3((int32_t)1 /*partial*/);
    Value value4(tag);

    key->addValue(FieldValue(field1, value1));
    key->addValue(FieldValue(field3, value3));
    key->addValue(FieldValue(field4, value4));
}

void getPartialWakelockKey(int uid, HashableDimensionKey* key) {
    int pos1[] = {1, 1, 1};
    int pos3[] = {2, 0, 0};

    Field field1(10 /* atom id */, pos1, 2 /* depth */);
    Field field3(10 /* atom id */, pos3, 0 /* depth */);

    Value value1((int32_t)uid);
    Value value3((int32_t)1 /*partial*/);

    key->addValue(FieldValue(field1, value1));
    key->addValue(FieldValue(field3, value3));
}
// END: get primary key functions

shared_ptr<LogEvent> CreateTwoValueLogEvent(int atomId, int64_t eventTimeNs, int32_t value1,
                                            int32_t value2) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value1);
    AStatsEvent_writeInt32(statsEvent, value2);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);

    return logEvent;
}

void CreateTwoValueLogEvent(LogEvent* logEvent, int atomId, int64_t eventTimeNs, int32_t value1,
                            int32_t value2) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value1);
    AStatsEvent_writeInt32(statsEvent, value2);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}

shared_ptr<LogEvent> CreateThreeValueLogEvent(int atomId, int64_t eventTimeNs, int32_t value1,
                                              int32_t value2, int32_t value3) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value1);
    AStatsEvent_writeInt32(statsEvent, value2);
    AStatsEvent_writeInt32(statsEvent, value3);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);

    return logEvent;
}

void CreateThreeValueLogEvent(LogEvent* logEvent, int atomId, int64_t eventTimeNs, int32_t value1,
                              int32_t value2, int32_t value3) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value1);
    AStatsEvent_writeInt32(statsEvent, value2);
    AStatsEvent_writeInt32(statsEvent, value3);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}

shared_ptr<LogEvent> CreateRepeatedValueLogEvent(int atomId, int64_t eventTimeNs, int32_t value) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value);
    AStatsEvent_writeInt32(statsEvent, value);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);

    return logEvent;
}

void CreateRepeatedValueLogEvent(LogEvent* logEvent, int atomId, int64_t eventTimeNs,
                                 int32_t value) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);

    AStatsEvent_writeInt32(statsEvent, value);
    AStatsEvent_writeInt32(statsEvent, value);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}

shared_ptr<LogEvent> CreateNoValuesLogEvent(int atomId, int64_t eventTimeNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);

    return logEvent;
}

void CreateNoValuesLogEvent(LogEvent* logEvent, int atomId, int64_t eventTimeNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, eventTimeNs);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
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

std::unique_ptr<LogEvent> CreateBatterySaverOnEvent(uint64_t timestampNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::BATTERY_SAVER_MODE_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, BatterySaverModeStateChanged::ON);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateBatterySaverOffEvent(uint64_t timestampNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::BATTERY_SAVER_MODE_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, BatterySaverModeStateChanged::OFF);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateBatteryStateChangedEvent(const uint64_t timestampNs, const BatteryPluggedStateEnum state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::PLUGGED_STATE_CHANGED);
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

std::unique_ptr<LogEvent> CreateScreenBrightnessChangedEvent(uint64_t timestampNs, int level) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::SCREEN_BRIGHTNESS_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, level);
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

std::unique_ptr<LogEvent> CreateWakelockStateChangedEvent(uint64_t timestampNs,
                                                          const vector<int>& attributionUids,
                                                          const vector<string>& attributionTags,
                                                          const string& wakelockName,
                                                          const WakelockStateChanged::State state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::WAKELOCK_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    vector<const char*> cTags(attributionTags.size());
    for (int i = 0; i < cTags.size(); i++) {
        cTags[i] = attributionTags[i].c_str();
    }

    AStatsEvent_writeAttributionChain(statsEvent,
                                      reinterpret_cast<const uint32_t*>(attributionUids.data()),
                                      cTags.data(), attributionUids.size());
    AStatsEvent_writeInt32(statsEvent, android::os::WakeLockLevelEnum::PARTIAL_WAKE_LOCK);
    AStatsEvent_writeString(statsEvent, wakelockName.c_str());
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateAcquireWakelockEvent(uint64_t timestampNs,
                                                     const vector<int>& attributionUids,
                                                     const vector<string>& attributionTags,
                                                     const string& wakelockName) {
    return CreateWakelockStateChangedEvent(timestampNs, attributionUids, attributionTags,
                                           wakelockName, WakelockStateChanged::ACQUIRE);
}

std::unique_ptr<LogEvent> CreateReleaseWakelockEvent(uint64_t timestampNs,
                                                     const vector<int>& attributionUids,
                                                     const vector<string>& attributionTags,
                                                     const string& wakelockName) {
    return CreateWakelockStateChangedEvent(timestampNs, attributionUids, attributionTags,
                                           wakelockName, WakelockStateChanged::RELEASE);
}

std::unique_ptr<LogEvent> CreateActivityForegroundStateChangedEvent(
        uint64_t timestampNs, const int uid, const ActivityForegroundStateChanged::State state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, "pkg_name");
    AStatsEvent_writeString(statsEvent, "class_name");
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateMoveToBackgroundEvent(uint64_t timestampNs, const int uid) {
    return CreateActivityForegroundStateChangedEvent(timestampNs, uid,
                                                     ActivityForegroundStateChanged::BACKGROUND);
}

std::unique_ptr<LogEvent> CreateMoveToForegroundEvent(uint64_t timestampNs, const int uid) {
    return CreateActivityForegroundStateChangedEvent(timestampNs, uid,
                                                     ActivityForegroundStateChanged::FOREGROUND);
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

std::unique_ptr<LogEvent> CreateProcessLifeCycleStateChangedEvent(
        uint64_t timestampNs, const int uid, const ProcessLifeCycleStateChanged::State state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, "");
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateAppCrashEvent(uint64_t timestampNs, const int uid) {
    return CreateProcessLifeCycleStateChangedEvent(timestampNs, uid,
                                                   ProcessLifeCycleStateChanged::CRASHED);
}

std::unique_ptr<LogEvent> CreateAppCrashOccurredEvent(uint64_t timestampNs, const int uid) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::APP_CRASH_OCCURRED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, "eventType");
    AStatsEvent_writeString(statsEvent, "processName");
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateIsolatedUidChangedEvent(uint64_t timestampNs, int hostUid,
                                                        int isolatedUid, bool is_create) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::ISOLATED_UID_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, hostUid);
    AStatsEvent_writeInt32(statsEvent, isolatedUid);
    AStatsEvent_writeInt32(statsEvent, is_create);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateUidProcessStateChangedEvent(
        uint64_t timestampNs, int uid, const android::app::ProcessStateEnum state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::UID_PROCESS_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateBleScanStateChangedEvent(uint64_t timestampNs,
                                                         const vector<int>& attributionUids,
                                                         const vector<string>& attributionTags,
                                                         const BleScanStateChanged::State state,
                                                         const bool filtered, const bool firstMatch,
                                                         const bool opportunistic) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::BLE_SCAN_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    vector<const char*> cTags(attributionTags.size());
    for (int i = 0; i < cTags.size(); i++) {
        cTags[i] = attributionTags[i].c_str();
    }

    AStatsEvent_writeAttributionChain(statsEvent,
                                      reinterpret_cast<const uint32_t*>(attributionUids.data()),
                                      cTags.data(), attributionUids.size());
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_writeInt32(statsEvent, filtered);       // filtered
    AStatsEvent_writeInt32(statsEvent, firstMatch);     // first match
    AStatsEvent_writeInt32(statsEvent, opportunistic);  // opportunistic
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

std::unique_ptr<LogEvent> CreateOverlayStateChangedEvent(int64_t timestampNs, const int32_t uid,
                                                         const string& packageName,
                                                         const bool usingAlertWindow,
                                                         const OverlayStateChanged::State state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::OVERLAY_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, packageName.c_str());
    AStatsEvent_writeInt32(statsEvent, usingAlertWindow);
    AStatsEvent_writeInt32(statsEvent, state);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
    return logEvent;
}

sp<StatsLogProcessor> CreateStatsLogProcessor(const int64_t timeBaseNs, const int64_t currentTimeNs,
                                              const StatsdConfig& config, const ConfigKey& key,
                                              const shared_ptr<IPullAtomCallback>& puller,
                                              const int32_t atomTag) {
    sp<UidMap> uidMap = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    if (puller != nullptr) {
        pullerManager->RegisterPullAtomCallback(/*uid=*/0, atomTag, NS_PER_SEC, NS_PER_SEC * 10, {},
                                                puller);
    }
    sp<AlarmMonitor> anomalyAlarmMonitor =
        new AlarmMonitor(1,
                         [](const shared_ptr<IStatsCompanionService>&, int64_t){},
                         [](const shared_ptr<IStatsCompanionService>&){});
    sp<AlarmMonitor> periodicAlarmMonitor =
        new AlarmMonitor(1,
                         [](const shared_ptr<IStatsCompanionService>&, int64_t){},
                         [](const shared_ptr<IStatsCompanionService>&){});
    sp<StatsLogProcessor> processor =
            new StatsLogProcessor(uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
                                  timeBaseNs, [](const ConfigKey&) { return true; },
                                  [](const int&, const vector<int64_t>&) {return true;});
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

void ValidateWakelockAttributionUidAndTagDimension(const DimensionsValue& value, const int atomId,
                                                   const int uid, const string& tag) {
    EXPECT_EQ(value.field(), atomId);
    EXPECT_EQ(value.value_tuple().dimensions_value_size(), 2);
    // Attribution field.
    EXPECT_EQ(value.value_tuple().dimensions_value(0).field(), 1);
    // Uid field.
    EXPECT_EQ(value.value_tuple().dimensions_value(0).value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).value_int(),
              uid);
    // Tag field.
    EXPECT_EQ(value.value_tuple().dimensions_value(1).field(), 3);
    EXPECT_EQ(value.value_tuple().dimensions_value(1).value_str(), tag);
}

void ValidateAttributionUidDimension(const DimensionsValue& value, int atomId, int uid) {
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

Status FakeSubsystemSleepCallback::onPullAtom(int atomTag,
        const shared_ptr<IPullAtomResultReceiver>& resultReceiver) {
    // Convert stats_events into StatsEventParcels.
    std::vector<StatsEventParcel> parcels;
    for (int i = 1; i < 3; i++) {
        AStatsEvent* event = AStatsEvent_obtain();
        AStatsEvent_setAtomId(event, atomTag);
        std::string subsystemName = "subsystem_name_";
        subsystemName = subsystemName + std::to_string(i);
        AStatsEvent_writeString(event, subsystemName.c_str());
        AStatsEvent_writeString(event, "subsystem_subname foo");
        AStatsEvent_writeInt64(event, /*count= */ i);
        AStatsEvent_writeInt64(event, /*time_millis= */ i * 100);
        AStatsEvent_build(event);
        size_t size;
        uint8_t* buffer = AStatsEvent_getBuffer(event, &size);

        StatsEventParcel p;
        // vector.assign() creates a copy, but this is inevitable unless
        // stats_event.h/c uses a vector as opposed to a buffer.
        p.buffer.assign(buffer, buffer + size);
        parcels.push_back(std::move(p));
        AStatsEvent_write(event);
    }
    resultReceiver->pullFinished(atomTag, /*success=*/true, parcels);
    return Status::ok();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
