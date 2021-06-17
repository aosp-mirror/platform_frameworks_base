/*
 * Copyright 2021 The Android Open Source Project
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

#define LOG_TAG "SurfaceFlingerPuller"

#include "SurfaceFlingerPuller.h"

#include <gui/SurfaceComposerClient.h>
#include <log/log.h>
#include <statslog.h>
#include <timestatsatomsproto/TimeStatsAtomsProtoHeader.h>

#include <vector>

namespace android {
namespace server {
namespace stats {

using android::util::BytesField;
using std::optional;

namespace {
optional<BytesField> getBytes(const google::protobuf::MessageLite& proto, std::string& data) {
    if (!proto.SerializeToString(&data)) {
        ALOGW("Unable to serialize surface flinger bytes field");
        return std::nullopt;
    }
    return {BytesField(data.data(), data.size())};
}
} // namespace

AStatsManager_PullAtomCallbackReturn SurfaceFlingerPuller::pull(int32_t atomTag,
                                                                AStatsEventList* data) {
    // Don't need mutexes here, since there is no global state.
    // SurfaceComposerClient is thread safe, and surfaceflinger is internally thread safe.

    bool success = false;
    std::string pullDataProto;
    status_t err = SurfaceComposerClient::onPullAtom(atomTag, &pullDataProto, &success);
    if (!success || err != NO_ERROR) {
        ALOGW("Failed to pull atom %" PRId32
              " from surfaceflinger. Success is %d, binder status is %s",
              atomTag, (int)success, binder::Status::exceptionToString(err).c_str());
        return AStatsManager_PULL_SKIP;
    }

    switch (atomTag) {
        case android::util::SURFACEFLINGER_STATS_GLOBAL_INFO:
            return parseGlobalInfoPull(pullDataProto, data);
        case android::util::SURFACEFLINGER_STATS_LAYER_INFO:
            return parseLayerInfoPull(pullDataProto, data);
        default:
            ALOGW("Invalid atom id for surfaceflinger pullers: %" PRId32, atomTag);
            return AStatsManager_PULL_SKIP;
    }
}

AStatsManager_PullAtomCallbackReturn SurfaceFlingerPuller::parseGlobalInfoPull(
        const std::string& protoData, AStatsEventList* data) {
    android::surfaceflinger::SurfaceflingerStatsGlobalInfoWrapper atomList;
    if (!atomList.ParseFromString(protoData)) {
        ALOGW("Error parsing surface flinger global stats to proto");
        return AStatsManager_PULL_SKIP;
    }

    for (const auto& atom : atomList.atom()) {
        // The strings must outlive the BytesFields, which only have a pointer to the data.
        std::string frameDurationStr, renderEngineTimeStr, deadlineMissesStr, predictionErrorsStr;
        optional<BytesField> frameDuration = getBytes(atom.frame_duration(), frameDurationStr);
        optional<BytesField> renderEngineTime =
                getBytes(atom.render_engine_timing(), renderEngineTimeStr);
        optional<BytesField> deadlineMisses =
                getBytes(atom.sf_deadline_misses(), deadlineMissesStr);
        optional<BytesField> predictionErrors =
                getBytes(atom.sf_prediction_errors(), predictionErrorsStr);

        // Fail if any serialization to bytes failed.
        if (!frameDuration || !renderEngineTime || !deadlineMisses || !predictionErrors) {
            return AStatsManager_PULL_SKIP;
        }

        android::util::addAStatsEvent(data, android::util::SURFACEFLINGER_STATS_GLOBAL_INFO,
                                      atom.total_frames(), atom.missed_frames(),
                                      atom.client_composition_frames(), atom.display_on_millis(),
                                      atom.animation_millis(), atom.event_connection_count(),
                                      frameDuration.value(), renderEngineTime.value(),
                                      atom.total_timeline_frames(), atom.total_janky_frames(),
                                      atom.total_janky_frames_with_long_cpu(),
                                      atom.total_janky_frames_with_long_gpu(),
                                      atom.total_janky_frames_sf_unattributed(),
                                      atom.total_janky_frames_app_unattributed(),
                                      atom.total_janky_frames_sf_scheduling(),
                                      atom.total_jank_frames_sf_prediction_error(),
                                      atom.total_jank_frames_app_buffer_stuffing(),
                                      atom.display_refresh_rate_bucket(), deadlineMisses.value(),
                                      predictionErrors.value(), atom.render_rate_bucket());
    }
    return AStatsManager_PULL_SUCCESS;
}

AStatsManager_PullAtomCallbackReturn SurfaceFlingerPuller::parseLayerInfoPull(
        const std::string& protoData, AStatsEventList* data) {
    android::surfaceflinger::SurfaceflingerStatsLayerInfoWrapper atomList;
    if (!atomList.ParseFromString(protoData)) {
        ALOGW("Error parsing surface flinger layer stats to proto");
        return AStatsManager_PULL_SKIP;
    }

    for (const auto& atom : atomList.atom()) {
        // The strings must outlive the BytesFields, which only have a pointer to the data.
        std::string present2PresentStr, post2presentStr, acquire2PresentStr, latch2PresentStr,
                desired2PresentStr, post2AcquireStr, frameRateVoteStr, appDeadlineMissesStr;
        optional<BytesField> present2Present =
                getBytes(atom.present_to_present(), present2PresentStr);
        optional<BytesField> post2present = getBytes(atom.post_to_present(), post2presentStr);
        optional<BytesField> acquire2Present =
                getBytes(atom.acquire_to_present(), acquire2PresentStr);
        optional<BytesField> latch2Present = getBytes(atom.latch_to_present(), latch2PresentStr);
        optional<BytesField> desired2Present =
                getBytes(atom.desired_to_present(), desired2PresentStr);
        optional<BytesField> post2Acquire = getBytes(atom.post_to_acquire(), post2AcquireStr);
        optional<BytesField> frameRateVote = getBytes(atom.set_frame_rate_vote(), frameRateVoteStr);
        optional<BytesField> appDeadlineMisses =
                getBytes(atom.app_deadline_misses(), appDeadlineMissesStr);

        // Fail if any serialization to bytes failed.
        if (!present2Present || !post2present || !acquire2Present || !latch2Present ||
            !desired2Present || !post2Acquire || !frameRateVote || !appDeadlineMisses) {
            return AStatsManager_PULL_SKIP;
        }

        android::util::addAStatsEvent(data, android::util::SURFACEFLINGER_STATS_LAYER_INFO,
                                      atom.layer_name().c_str(), atom.total_frames(),
                                      atom.dropped_frames(), present2Present.value(),
                                      post2present.value(), acquire2Present.value(),
                                      latch2Present.value(), desired2Present.value(),
                                      post2Acquire.value(), atom.late_acquire_frames(),
                                      atom.bad_desired_present_frames(), atom.uid(),
                                      atom.total_timeline_frames(), atom.total_janky_frames(),
                                      atom.total_janky_frames_with_long_cpu(),
                                      atom.total_janky_frames_with_long_gpu(),
                                      atom.total_janky_frames_sf_unattributed(),
                                      atom.total_janky_frames_app_unattributed(),
                                      atom.total_janky_frames_sf_scheduling(),
                                      atom.total_jank_frames_sf_prediction_error(),
                                      atom.total_jank_frames_app_buffer_stuffing(),
                                      atom.display_refresh_rate_bucket(), atom.render_rate_bucket(),
                                      frameRateVote.value(), appDeadlineMisses.value(),
                                      atom.game_mode());
    }
    return AStatsManager_PULL_SUCCESS;
}

} // namespace stats
} // namespace server
} // namespace android
