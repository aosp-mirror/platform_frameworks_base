/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.lint.aidl

const val ANNOTATION_ENFORCE_PERMISSION = "android.annotation.EnforcePermission"
const val ANNOTATION_REQUIRES_NO_PERMISSION = "android.annotation.RequiresNoPermission"
const val ANNOTATION_PERMISSION_MANUALLY_ENFORCED = "android.annotation.PermissionManuallyEnforced"

val AIDL_PERMISSION_ANNOTATIONS = listOf(
        ANNOTATION_ENFORCE_PERMISSION,
        ANNOTATION_REQUIRES_NO_PERMISSION,
        ANNOTATION_PERMISSION_MANUALLY_ENFORCED
)

const val BINDER_CLASS = "android.os.Binder"
const val IINTERFACE_INTERFACE = "android.os.IInterface"

const val PERMISSION_PREFIX_LITERAL = "android.permission."

const val AIDL_PERMISSION_HELPER_SUFFIX = "_enforcePermission"

/**
 * If a non java (e.g. c++) backend is enabled, the @EnforcePermission
 * annotation cannot be used.  At time of writing, the mechanism
 * is not implemented for non java backends.
 * TODO: b/242564874 (have lint know which interfaces have the c++ backend enabled)
 * rather than hard coding this list?
 */
val EXCLUDED_CPP_INTERFACES = listOf(
        "AdbTransportType",
        "FingerprintAndPairDevice",
        "IAdbCallback",
        "IAdbManager",
        "PairDevice",
        "IStatsBootstrapAtomService",
        "StatsBootstrapAtom",
        "StatsBootstrapAtomValue",
        "FixedSizeArrayExample",
        "PlaybackTrackMetadata",
        "RecordTrackMetadata",
        "SinkMetadata",
        "SourceMetadata",
        "IUpdateEngineStable",
        "IUpdateEngineStableCallback",
        "AudioCapabilities",
        "ConfidenceLevel",
        "ModelParameter",
        "ModelParameterRange",
        "Phrase",
        "PhraseRecognitionEvent",
        "PhraseRecognitionExtra",
        "PhraseSoundModel",
        "Properties",
        "RecognitionConfig",
        "RecognitionEvent",
        "RecognitionMode",
        "RecognitionStatus",
        "SoundModel",
        "SoundModelType",
        "Status",
        "IThermalService",
        "IPowerManager",
        "ITunerResourceManager",
        // b/278147400
        "IActivityManager",
        "IUidObserver",
        "IDrm",
        "IVsyncCallback",
        "IVsyncService",
        "ICallback",
        "IIPCTest",
        "ISafeInterfaceTest",
        "IGpuService",
        "IConsumerListener",
        "IGraphicBufferConsumer",
        "ITransactionComposerListener",
        "SensorEventConnection",
        "SensorServer",
        "ICamera",
        "ICameraClient",
        "ICameraRecordingProxy",
        "ICameraRecordingProxyListener",
        "ICrypto",
        "IOMXObserver",
        "IStreamListener",
        "IStreamSource",
        "IAudioService",
        "IDataSource",
        "IDrmClient",
        "IMediaCodecList",
        "IMediaDrmService",
        "IMediaExtractor",
        "IMediaExtractorService",
        "IMediaHTTPConnection",
        "IMediaHTTPService",
        "IMediaLogService",
        "IMediaMetadataRetriever",
        "IMediaMetricsService",
        "IMediaPlayer",
        "IMediaPlayerClient",
        "IMediaPlayerService",
        "IMediaRecorder",
        "IMediaRecorderClient",
        "IMediaResourceMonitor",
        "IMediaSource",
        "IRemoteDisplay",
        "IRemoteDisplayClient",
        "IResourceManagerClient",
        "IResourceManagerService",
        "IComplexTypeInterface",
        "IPermissionController",
        "IPingResponder",
        "IProcessInfoService",
        "ISchedulingPolicyService",
        "IStringConstants",
        "IObbActionListener",
        "IStorageEventListener",
        "IStorageManager",
        "IStorageShutdownObserver",
        "IPersistentVrStateCallbacks",
        "IVrManager",
        "IVrStateCallbacks",
        "ISurfaceComposer",
        "IMemory",
        "IMemoryHeap",
        "IProcfsInspector",
        "IAppOpsCallback",
        "IAppOpsService",
        "IBatteryStats",
        "IResultReceiver",
        "IShellCallback",
        "IDrmManagerService",
        "IDrmServiceListener",
        "IAAudioClient",
        "IAAudioService",
        "VtsFuzzer",
)
