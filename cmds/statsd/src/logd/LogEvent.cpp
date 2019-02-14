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

#define DEBUG false  // STOPSHIP if true
#include "logd/LogEvent.h"

#include "stats_log_util.h"
#include "statslog.h"

#include <binder/IPCThreadState.h>

namespace android {
namespace os {
namespace statsd {

using namespace android::util;
using android::util::ProtoOutputStream;
using std::string;
using std::vector;

LogEvent::LogEvent(log_msg& msg) {
    mContext =
            create_android_log_parser(msg.msg() + sizeof(uint32_t), msg.len() - sizeof(uint32_t));
    mLogdTimestampNs = msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec;
    mLogUid = msg.entry_v4.uid;
    init(mContext);
    if (mContext) {
        // android_log_destroy will set mContext to NULL
        android_log_destroy(&mContext);
    }
}

LogEvent::LogEvent(const LogEvent& event) {
    mTagId = event.mTagId;
    mLogUid = event.mLogUid;
    mElapsedTimestampNs = event.mElapsedTimestampNs;
    mLogdTimestampNs = event.mLogdTimestampNs;
    mValues = event.mValues;
}

LogEvent::LogEvent(const StatsLogEventWrapper& statsLogEventWrapper, int workChainIndex) {
    mTagId = statsLogEventWrapper.getTagId();
    mLogdTimestampNs = statsLogEventWrapper.getWallClockTimeNs();
    mElapsedTimestampNs = statsLogEventWrapper.getElapsedRealTimeNs();
    mLogUid = 0;
    int workChainPosOffset = 0;
    if (workChainIndex != -1) {
        const WorkChain& wc = statsLogEventWrapper.getWorkChains()[workChainIndex];
        // chains are at field 1, level 2
        int depth = 2;
        for (int i = 0; i < (int)wc.uids.size(); i++) {
            int pos[] = {1, i + 1, 1};
            mValues.push_back(FieldValue(Field(mTagId, pos, depth), Value(wc.uids[i])));
            pos[2]++;
            mValues.push_back(FieldValue(Field(mTagId, pos, depth), Value(wc.tags[i])));
            mValues.back().mField.decorateLastPos(2);
        }
        mValues.back().mField.decorateLastPos(1);
        workChainPosOffset = 1;
    }
    for (int i = 0; i < (int)statsLogEventWrapper.getElements().size(); i++) {
        Field field(statsLogEventWrapper.getTagId(), getSimpleField(i + 1 + workChainPosOffset));
        switch (statsLogEventWrapper.getElements()[i].type) {
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::INT:
                mValues.push_back(
                        FieldValue(field, Value(statsLogEventWrapper.getElements()[i].int_value)));
                break;
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::LONG:
                mValues.push_back(
                        FieldValue(field, Value(statsLogEventWrapper.getElements()[i].long_value)));
                break;
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::FLOAT:
                mValues.push_back(FieldValue(
                        field, Value(statsLogEventWrapper.getElements()[i].float_value)));
                break;
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::DOUBLE:
                mValues.push_back(FieldValue(
                        field, Value(statsLogEventWrapper.getElements()[i].double_value)));
                break;
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::STRING:
                mValues.push_back(
                        FieldValue(field, Value(statsLogEventWrapper.getElements()[i].str_value)));
                break;
            case android::os::StatsLogValue::STATS_LOG_VALUE_TYPE::STORAGE:
                mValues.push_back(FieldValue(
                        field, Value(statsLogEventWrapper.getElements()[i].storage_value)));
                break;
            default:
                break;
        }
    }
}

void LogEvent::createLogEvents(const StatsLogEventWrapper& statsLogEventWrapper,
                               std::vector<std::shared_ptr<LogEvent>>& logEvents) {
    if (statsLogEventWrapper.getWorkChains().size() == 0) {
        logEvents.push_back(std::make_shared<LogEvent>(statsLogEventWrapper, -1));
    } else {
        for (size_t i = 0; i < statsLogEventWrapper.getWorkChains().size(); i++) {
            logEvents.push_back(std::make_shared<LogEvent>(statsLogEventWrapper, i));
        }
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs) {
    mLogdTimestampNs = wallClockTimestampNs;
    mTagId = tagId;
    mLogUid = 0;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, elapsedTimestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   int32_t uid,
                   const std::map<int32_t, int32_t>& int_map,
                   const std::map<int32_t, int64_t>& long_map,
                   const std::map<int32_t, std::string>& string_map,
                   const std::map<int32_t, float>& float_map) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::KEY_VALUE_PAIRS_ATOM;
    mLogUid = uid;

    int pos[] = {1, 1, 1};

    mValues.push_back(FieldValue(Field(mTagId, pos, 0 /* depth */), Value(uid)));
    pos[0]++;
    for (const auto&itr : int_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 2;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : long_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 3;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : string_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 4;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }

    for (const auto&itr : float_map) {
        pos[2] = 1;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.first)));
        pos[2] = 5;
        mValues.push_back(FieldValue(Field(mTagId, pos, 2 /* depth */), Value(itr.second)));
        mValues.back().mField.decorateLastPos(2);
        pos[1]++;
    }
    if (!mValues.empty()) {
        mValues.back().mField.decorateLastPos(1);
        mValues.at(mValues.size() - 2).mField.decorateLastPos(1);
    }
}

LogEvent::LogEvent(const string& trainName, int64_t trainVersionCode, bool requiresStaging,
                   bool rollbackEnabled, bool requiresLowLatencyMonitor, int32_t state,
                   const std::vector<uint8_t>& experimentIds, int32_t userId) {
    mLogdTimestampNs = getWallClockNs();
    mElapsedTimestampNs = getElapsedRealtimeNs();
    mTagId = android::util::BINARY_PUSH_STATE_CHANGED;
    mLogUid = android::IPCThreadState::self()->getCallingUid();

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)), Value(trainName)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(trainVersionCode)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)), Value((int)requiresStaging)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)), Value((int)rollbackEnabled)));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(5)), Value((int)requiresLowLatencyMonitor)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(6)), Value(state)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(7)), Value(experimentIds)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(8)), Value(userId)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const SpeakerImpedance& speakerImpedance) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::SPEAKER_IMPEDANCE_REPORTED;

    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(1)), Value(speakerImpedance.speakerLocation)));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(2)), Value(speakerImpedance.milliOhms)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const HardwareFailed& hardwareFailed) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::HARDWARE_FAILED;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(int32_t(hardwareFailed.hardwareType))));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(2)), Value(hardwareFailed.hardwareLocation)));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(3)), Value(int32_t(hardwareFailed.errorCode))));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const PhysicalDropDetected& physicalDropDetected) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::PHYSICAL_DROP_DETECTED;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(int32_t(physicalDropDetected.confidencePctg))));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(2)), Value(physicalDropDetected.accelPeak)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)),
                                 Value(physicalDropDetected.freefallDuration)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const ChargeCycles& chargeCycles) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::CHARGE_CYCLES_REPORTED;

    for (size_t i = 0; i < chargeCycles.cycleBucket.size(); i++) {
        mValues.push_back(FieldValue(Field(mTagId, getSimpleField(i + 1)),
                                     Value(chargeCycles.cycleBucket[i])));
    }
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const BatteryHealthSnapshotArgs& batteryHealthSnapshotArgs) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::BATTERY_HEALTH_SNAPSHOT;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(int32_t(batteryHealthSnapshotArgs.type))));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)),
                                 Value(batteryHealthSnapshotArgs.temperatureDeciC)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)),
                                 Value(batteryHealthSnapshotArgs.voltageMicroV)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)),
                                 Value(batteryHealthSnapshotArgs.currentMicroA)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(5)),
                                 Value(batteryHealthSnapshotArgs.openCircuitVoltageMicroV)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(6)),
                                 Value(batteryHealthSnapshotArgs.resistanceMicroOhm)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(7)),
                                 Value(batteryHealthSnapshotArgs.levelPercent)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs, const SlowIo& slowIo) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::SLOW_IO;

    int pos[] = {1};
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(1)), Value(int32_t(slowIo.operation))));
    pos[0]++;
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(slowIo.count)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const SpeechDspStat& speechDspStat) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::SPEECH_DSP_STAT_REPORTED;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(speechDspStat.totalUptimeMillis)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)),
                                 Value(speechDspStat.totalDowntimeMillis)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)),
                                 Value(speechDspStat.totalCrashCount)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)),
                                 Value(speechDspStat.totalRecoverCount)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const BatteryCausedShutdown& batteryCausedShutdown) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::BATTERY_CAUSED_SHUTDOWN;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(batteryCausedShutdown.voltageMicroV)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const UsbPortOverheatEvent& usbPortOverheatEvent) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = android::util::USB_PORT_OVERHEAT_EVENT_REPORTED;

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)),
                                 Value(usbPortOverheatEvent.plugTemperatureDeciC)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)),
                                 Value(usbPortOverheatEvent.maxTemperatureDeciC)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)),
                                 Value(usbPortOverheatEvent.timeToOverheat)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)),
                                 Value(usbPortOverheatEvent.timeToHysteresis)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(5)),
                                 Value(usbPortOverheatEvent.timeToInactive)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const VendorAtom& vendorAtom) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = vendorAtom.atomId;

    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(1)), Value(vendorAtom.reverseDomainName)));
    for (int i = 0; i < (int)vendorAtom.values.size(); i++) {
        switch (vendorAtom.values[i].getDiscriminator()) {
            case VendorAtom::Value::hidl_discriminator::intValue:
                mValues.push_back(FieldValue(Field(mTagId, getSimpleField(i + 2)),
                                             Value(vendorAtom.values[i].intValue())));
                break;
            case VendorAtom::Value::hidl_discriminator::longValue:
                mValues.push_back(FieldValue(Field(mTagId, getSimpleField(i + 2)),
                                             Value(vendorAtom.values[i].longValue())));
                break;
            case VendorAtom::Value::hidl_discriminator::floatValue:
                mValues.push_back(FieldValue(Field(mTagId, getSimpleField(i + 2)),
                                             Value(vendorAtom.values[i].floatValue())));
                break;
            case VendorAtom::Value::hidl_discriminator::stringValue:
                mValues.push_back(FieldValue(Field(mTagId, getSimpleField(i + 2)),
                                             Value(vendorAtom.values[i].stringValue())));
                break;
        }
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t timestampNs) : LogEvent(tagId, timestampNs, 0) {}

LogEvent::LogEvent(int32_t tagId, int64_t timestampNs, int32_t uid) {
    mLogdTimestampNs = timestampNs;
    mTagId = tagId;
    mLogUid = uid;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, timestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

void LogEvent::init() {
    if (mContext) {
        const char* buffer;
        size_t len = android_log_write_list_buffer(mContext, &buffer);
        // turns to reader mode
        android_log_context contextForRead = create_android_log_parser(buffer, len);
        if (contextForRead) {
            init(contextForRead);
            // destroy the context to save memory.
            // android_log_destroy will set mContext to NULL
            android_log_destroy(&contextForRead);
        }
        android_log_destroy(&mContext);
    }
}

LogEvent::~LogEvent() {
    if (mContext) {
        // This is for the case when LogEvent is created using the test interface
        // but init() isn't called.
        android_log_destroy(&mContext);
    }
}

bool LogEvent::write(int32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(int64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(const string& value) {
    if (mContext) {
        return android_log_write_string8_len(mContext, value.c_str(), value.length()) >= 0;
    }
    return false;
}

bool LogEvent::write(float value) {
    if (mContext) {
        return android_log_write_float32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::writeKeyValuePairs(int32_t uid,
                                  const std::map<int32_t, int32_t>& int_map,
                                  const std::map<int32_t, int64_t>& long_map,
                                  const std::map<int32_t, std::string>& string_map,
                                  const std::map<int32_t, float>& float_map) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         write(uid);
         for (const auto& itr : int_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : long_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : string_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second.c_str());
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         for (const auto& itr : float_map) {
             if (android_log_write_list_begin(mContext) < 0) {
                return false;
             }
             write(itr.first);
             write(itr.second);
             if (android_log_write_list_end(mContext) < 0) {
                return false;
             }
         }

         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

bool LogEvent::write(const std::vector<AttributionNodeInternal>& nodes) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         for (size_t i = 0; i < nodes.size(); ++i) {
             if (!write(nodes[i])) {
                return false;
             }
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

bool LogEvent::write(const AttributionNodeInternal& node) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         if (android_log_write_int32(mContext, node.uid()) < 0) {
            return false;
         }
         if (android_log_write_string8(mContext, node.tag().c_str()) < 0) {
            return false;
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 *
 * The idea here is to read through the log items once, we get as much information we need for
 * matching as possible. Because this log will be matched against lots of matchers.
 */
void LogEvent::init(android_log_context context) {
    android_log_list_element elem;
    int i = 0;
    int depth = -1;
    int pos[] = {1, 1, 1};
    bool isKeyValuePairAtom = false;
    do {
        elem = android_log_read_next(context);
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
                // elem at [0] is EVENT_TYPE_LIST, [1] is the timestamp, [2] is tag id.
                if (i == 2) {
                    mTagId = elem.data.int32;
                    isKeyValuePairAtom = (mTagId == android::util::KEY_VALUE_PAIRS_ATOM);
                } else {
                    if (depth < 0 || depth > 2) {
                        return;
                    }

                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int32_t)elem.data.int32)));

                    pos[depth]++;
                }
                break;
            case EVENT_TYPE_FLOAT: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                // Handles the oneof field in KeyValuePair atom.
                if (isKeyValuePairAtom && depth == 2) {
                    pos[depth] = 5;
                }

                mValues.push_back(FieldValue(Field(mTagId, pos, depth), Value(elem.data.float32)));

                pos[depth]++;

            } break;
            case EVENT_TYPE_STRING: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                // Handles the oneof field in KeyValuePair atom.
                if (isKeyValuePairAtom && depth == 2) {
                    pos[depth] = 4;
                }
                mValues.push_back(FieldValue(Field(mTagId, pos, depth),
                                             Value(string(elem.data.string, elem.len))));

                pos[depth]++;

            } break;
            case EVENT_TYPE_LONG: {
                if (i == 1) {
                    mElapsedTimestampNs = elem.data.int64;
                } else {
                    if (depth < 0 || depth > 2) {
                        ALOGE("Depth > 2. Not supported!");
                        return;
                    }
                    // Handles the oneof field in KeyValuePair atom.
                    if (isKeyValuePairAtom && depth == 2) {
                        pos[depth] = 3;
                    }
                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int64_t)elem.data.int64)));

                    pos[depth]++;
                }
            } break;
            case EVENT_TYPE_LIST:
                depth++;
                if (depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }
                pos[depth] = 1;

                break;
            case EVENT_TYPE_LIST_STOP: {
                int prevDepth = depth;
                depth--;
                if (depth >= 0 && depth < 2) {
                    // Now go back to decorate the previous items that are last at prevDepth.
                    // So that we can later easily match them with Position=Last matchers.
                    pos[prevDepth]--;
                    int path = getEncodedField(pos, prevDepth, false);
                    for (auto it = mValues.rbegin(); it != mValues.rend(); ++it) {
                        if (it->mField.getDepth() >= prevDepth &&
                            it->mField.getPath(prevDepth) == path) {
                            it->mField.decorateLastPos(prevDepth);
                        } else {
                            // Safe to break, because the items are in DFS order.
                            break;
                        }
                    }
                    pos[depth]++;
                }
                break;
            }
            case EVENT_TYPE_UNKNOWN:
                break;
            default:
                break;
        }
        i++;
    } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
    if (isKeyValuePairAtom && mValues.size() > 0) {
        mValues[0] = FieldValue(Field(android::util::KEY_VALUE_PAIRS_ATOM, getSimpleField(1)),
                                Value((int32_t)mLogUid));
    }
}

int64_t LogEvent::GetLong(size_t key, status_t* err) const {
    // TODO(b/110561208): encapsulate the magical operations in Field struct as static functions
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == LONG) {
                return value.mValue.long_value;
            } else if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

int LogEvent::GetInt(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

const char* LogEvent::GetString(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == STRING) {
                return value.mValue.str_value.c_str();
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return NULL;
}

bool LogEvent::GetBool(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value != 0;
            } else if (value.mValue.getType() == LONG) {
                return value.mValue.long_value != 0;
            } else {
                *err = BAD_TYPE;
                return false;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return false;
}

float LogEvent::GetFloat(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == FLOAT) {
                return value.mValue.float_value;
            } else {
                *err = BAD_TYPE;
                return 0.0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0.0;
}

string LogEvent::ToString() const {
    string result;
    result += StringPrintf("{ uid(%d) %lld %lld (%d)", mLogUid, (long long)mLogdTimestampNs,
                           (long long)mElapsedTimestampNs, mTagId);
    for (const auto& value : mValues) {
        result +=
                StringPrintf("%#x", value.mField.getField()) + "->" + value.mValue.toString() + " ";
    }
    result += " }";
    return result;
}

void LogEvent::ToProto(ProtoOutputStream& protoOutput) const {
    writeFieldValueTreeToStream(mTagId, getValues(), &protoOutput);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
