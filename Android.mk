#
# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH := $(call my-dir)

# We have a special case here where we build the library's resources
# independently from its code, so we need to find where the resource
# class source got placed in the course of building the resources.
# Thus, the magic here.
# Also, this module cannot depend directly on the R.java file; if it
# did, the PRIVATE_* vars for R.java wouldn't be guaranteed to be correct.
# Instead, it depends on the R.stamp file, which lists the corresponding
# R.java file as a prerequisite.
# TODO: find a more appropriate way to do this.
framework_res_source_path := APPS/framework-res_intermediates/src

# Build the master framework library.
# The framework contains too many method references (>64K) for poor old DEX.
# So we first build the framework as a monolithic static library then split it
# up into smaller pieces.
# ============================================================

# embedded builds use nothing in frameworks/base
ifneq ($(ANDROID_BUILD_EMBEDDED),true)

include $(CLEAR_VARS)

# FRAMEWORKS_BASE_SUBDIRS comes from build/core/pathmap.mk
LOCAL_SRC_FILES := $(call find-other-java-files,$(FRAMEWORKS_BASE_SUBDIRS))

# EventLogTags files.
LOCAL_SRC_FILES += \
       core/java/android/app/admin/SecurityLogTags.logtags \
       core/java/android/content/EventLogTags.logtags \
       core/java/android/speech/tts/EventLogTags.logtags \
       core/java/android/webkit/EventLogTags.logtags \
       core/java/com/android/internal/logging/EventLogTags.logtags \

## READ ME: ########################################################
##
## When updating this list of aidl files, consider if that aidl is
## part of the SDK API.  If it is, also add it to the list below that
## is preprocessed and distributed with the SDK.  This list should
## not contain any aidl files for parcelables, but the one below should
## if you intend for 3rd parties to be able to send those objects
## across process boundaries.
##
## READ ME: ########################################################
LOCAL_SRC_FILES += \
	core/java/android/accessibilityservice/IAccessibilityServiceConnection.aidl \
	core/java/android/accessibilityservice/IAccessibilityServiceClient.aidl \
	core/java/android/accounts/IAccountManager.aidl \
	core/java/android/accounts/IAccountManagerResponse.aidl \
	core/java/android/accounts/IAccountAuthenticator.aidl \
	core/java/android/accounts/IAccountAuthenticatorResponse.aidl \
	core/java/android/app/IActivityContainer.aidl \
	core/java/android/app/IActivityContainerCallback.aidl \
	core/java/android/app/IActivityController.aidl \
	core/java/android/app/IActivityPendingResult.aidl \
	core/java/android/app/IAlarmCompleteListener.aidl \
	core/java/android/app/IAlarmListener.aidl \
	core/java/android/app/IAlarmManager.aidl \
	core/java/android/app/IAppTask.aidl \
	core/java/android/app/ITaskStackListener.aidl \
	core/java/android/app/IBackupAgent.aidl \
	core/java/android/app/IEphemeralResolver.aidl \
	core/java/android/app/IInstrumentationWatcher.aidl \
	core/java/android/app/INotificationManager.aidl \
	core/java/android/app/IProcessObserver.aidl \
	core/java/android/app/ISearchManager.aidl \
	core/java/android/app/ISearchManagerCallback.aidl \
	core/java/android/app/IServiceConnection.aidl \
	core/java/android/app/IStopUserCallback.aidl \
	core/java/android/app/job/IJobCallback.aidl \
	core/java/android/app/job/IJobScheduler.aidl \
	core/java/android/app/job/IJobService.aidl \
	core/java/android/app/ITransientNotification.aidl \
	core/java/android/app/IUidObserver.aidl \
	core/java/android/app/IUiAutomationConnection.aidl \
	core/java/android/app/IUiModeManager.aidl \
	core/java/android/app/IUserSwitchObserver.aidl \
	core/java/android/app/IWallpaperManager.aidl \
	core/java/android/app/IWallpaperManagerCallback.aidl \
	core/java/android/app/admin/IDevicePolicyManager.aidl \
	core/java/android/app/trust/IStrongAuthTracker.aidl \
	core/java/android/app/trust/ITrustManager.aidl \
	core/java/android/app/trust/ITrustListener.aidl \
	core/java/android/app/backup/IBackupManager.aidl \
	core/java/android/app/backup/IBackupObserver.aidl \
	core/java/android/app/backup/IFullBackupRestoreObserver.aidl \
	core/java/android/app/backup/IRestoreObserver.aidl \
	core/java/android/app/backup/IRestoreSession.aidl \
	core/java/android/app/usage/IUsageStatsManager.aidl \
	core/java/android/bluetooth/IBluetooth.aidl \
	core/java/android/bluetooth/IBluetoothA2dp.aidl \
	core/java/android/bluetooth/IBluetoothA2dpSink.aidl \
	core/java/android/bluetooth/IBluetoothAvrcpController.aidl \
	core/java/android/bluetooth/IBluetoothCallback.aidl \
	core/java/android/bluetooth/IBluetoothProfileServiceConnection.aidl \
	core/java/android/bluetooth/IBluetoothHeadset.aidl \
	core/java/android/bluetooth/IBluetoothHeadsetPhone.aidl \
	core/java/android/bluetooth/IBluetoothHealth.aidl \
	core/java/android/bluetooth/IBluetoothHealthCallback.aidl \
	core/java/android/bluetooth/IBluetoothInputDevice.aidl \
	core/java/android/bluetooth/IBluetoothPan.aidl \
	core/java/android/bluetooth/IBluetoothManager.aidl \
	core/java/android/bluetooth/IBluetoothManagerCallback.aidl \
	core/java/android/bluetooth/IBluetoothPbap.aidl \
	core/java/android/bluetooth/IBluetoothPbapClient.aidl \
	core/java/android/bluetooth/IBluetoothMap.aidl \
	core/java/android/bluetooth/IBluetoothSap.aidl \
	core/java/android/bluetooth/IBluetoothStateChangeCallback.aidl \
	core/java/android/bluetooth/IBluetoothHeadsetClient.aidl \
	core/java/android/bluetooth/IBluetoothGatt.aidl \
	core/java/android/bluetooth/IBluetoothGattCallback.aidl \
	core/java/android/bluetooth/IBluetoothGattServerCallback.aidl \
	core/java/android/content/IClipboard.aidl \
	core/java/android/content/IContentService.aidl \
	core/java/android/content/IIntentReceiver.aidl \
	core/java/android/content/IIntentSender.aidl \
	core/java/android/content/IOnPrimaryClipChangedListener.aidl \
	core/java/android/content/IRestrictionsManager.aidl \
	core/java/android/content/ISyncAdapter.aidl \
	core/java/android/content/ISyncContext.aidl \
	core/java/android/content/ISyncServiceAdapter.aidl \
	core/java/android/content/ISyncStatusObserver.aidl \
	core/java/android/content/pm/ILauncherApps.aidl \
	core/java/android/content/pm/IOnAppsChangedListener.aidl \
	core/java/android/content/pm/IOtaDexopt.aidl \
	core/java/android/content/pm/IPackageDataObserver.aidl \
	core/java/android/content/pm/IPackageDeleteObserver.aidl \
	core/java/android/content/pm/IPackageDeleteObserver2.aidl \
	core/java/android/content/pm/IPackageInstallObserver.aidl \
	core/java/android/content/pm/IPackageInstallObserver2.aidl \
	core/java/android/content/pm/IPackageInstaller.aidl \
	core/java/android/content/pm/IPackageInstallerCallback.aidl \
	core/java/android/content/pm/IPackageInstallerSession.aidl \
	core/java/android/content/pm/IPackageManager.aidl \
	core/java/android/content/pm/IPackageMoveObserver.aidl \
	core/java/android/content/pm/IPackageStatsObserver.aidl \
	core/java/android/content/pm/IOnPermissionsChangeListener.aidl \
	core/java/android/content/pm/IShortcutService.aidl \
	core/java/android/content/pm/permission/IRuntimePermissionPresenter.aidl \
	core/java/android/database/IContentObserver.aidl \
	../av/camera/aidl/android/hardware/ICameraService.aidl \
	../av/camera/aidl/android/hardware/ICameraServiceListener.aidl \
	../av/camera/aidl/android/hardware/ICameraServiceProxy.aidl \
	../av/camera/aidl/android/hardware/ICamera.aidl \
	../av/camera/aidl/android/hardware/ICameraClient.aidl \
	../av/camera/aidl/android/hardware/camera2/ICameraDeviceUser.aidl \
	../av/camera/aidl/android/hardware/camera2/ICameraDeviceCallbacks.aidl \
	core/java/android/hardware/IConsumerIrService.aidl \
	core/java/android/hardware/ISerialManager.aidl \
	core/java/android/hardware/display/IDisplayManager.aidl \
	core/java/android/hardware/display/IDisplayManagerCallback.aidl \
	core/java/android/hardware/display/IVirtualDisplayCallback.aidl \
	core/java/android/hardware/fingerprint/IFingerprintDaemon.aidl \
	core/java/android/hardware/fingerprint/IFingerprintDaemonCallback.aidl \
	core/java/android/hardware/fingerprint/IFingerprintService.aidl \
	core/java/android/hardware/fingerprint/IFingerprintServiceLockoutResetCallback.aidl \
	core/java/android/hardware/fingerprint/IFingerprintServiceReceiver.aidl \
	core/java/android/hardware/hdmi/IHdmiControlCallback.aidl \
	core/java/android/hardware/hdmi/IHdmiControlService.aidl \
	core/java/android/hardware/hdmi/IHdmiDeviceEventListener.aidl \
	core/java/android/hardware/hdmi/IHdmiHotplugEventListener.aidl \
	core/java/android/hardware/hdmi/IHdmiInputChangeListener.aidl \
	core/java/android/hardware/hdmi/IHdmiMhlVendorCommandListener.aidl \
	core/java/android/hardware/hdmi/IHdmiRecordListener.aidl \
	core/java/android/hardware/hdmi/IHdmiSystemAudioModeChangeListener.aidl \
	core/java/android/hardware/hdmi/IHdmiVendorCommandListener.aidl \
	core/java/android/hardware/input/IInputManager.aidl \
	core/java/android/hardware/input/IInputDevicesChangedListener.aidl \
	core/java/android/hardware/input/ITabletModeChangedListener.aidl \
	core/java/android/hardware/location/IActivityRecognitionHardware.aidl \
	core/java/android/hardware/location/IActivityRecognitionHardwareClient.aidl \
	core/java/android/hardware/location/IActivityRecognitionHardwareSink.aidl \
	core/java/android/hardware/location/IActivityRecognitionHardwareWatcher.aidl \
	core/java/android/hardware/location/IFusedLocationHardware.aidl \
	core/java/android/hardware/location/IFusedLocationHardwareSink.aidl \
	core/java/android/hardware/location/IGeofenceHardware.aidl \
	core/java/android/hardware/location/IGeofenceHardwareCallback.aidl \
	core/java/android/hardware/location/IGeofenceHardwareMonitorCallback.aidl \
	core/java/android/hardware/location/IContextHubCallback.aidl \
	core/java/android/hardware/location/IContextHubService.aidl \
	core/java/android/hardware/soundtrigger/IRecognitionStatusCallback.aidl \
	core/java/android/hardware/usb/IUsbManager.aidl \
	core/java/android/net/ICaptivePortal.aidl \
	core/java/android/net/IConnectivityManager.aidl \
	core/java/android/net/IConnectivityMetricsLogger.aidl \
	core/java/android/net/IEthernetManager.aidl \
	core/java/android/net/IEthernetServiceListener.aidl \
	core/java/android/net/INetworkManagementEventObserver.aidl \
	core/java/android/net/INetworkPolicyListener.aidl \
	core/java/android/net/INetworkPolicyManager.aidl \
	core/java/android/net/INetworkScoreCache.aidl \
	core/java/android/net/INetworkScoreService.aidl \
	core/java/android/net/INetworkStatsService.aidl \
	core/java/android/net/INetworkStatsSession.aidl \
	core/java/android/net/nsd/INsdManager.aidl \
	core/java/android/nfc/IAppCallback.aidl \
	core/java/android/nfc/INfcAdapter.aidl \
	core/java/android/nfc/INfcAdapterExtras.aidl \
	core/java/android/nfc/INfcTag.aidl \
	core/java/android/nfc/INfcCardEmulation.aidl \
	core/java/android/nfc/INfcFCardEmulation.aidl \
	core/java/android/nfc/INfcUnlockHandler.aidl \
	core/java/android/nfc/ITagRemovedCallback.aidl \
	core/java/android/os/IBatteryPropertiesListener.aidl \
	core/java/android/os/IBatteryPropertiesRegistrar.aidl \
	core/java/android/os/ICancellationSignal.aidl \
	core/java/android/os/IDeviceIdleController.aidl \
	core/java/android/os/IHardwarePropertiesManager.aidl \
	core/java/android/os/IMaintenanceActivityListener.aidl \
	core/java/android/os/IMessenger.aidl \
	core/java/android/os/INetworkActivityListener.aidl \
	core/java/android/os/INetworkManagementService.aidl \
	core/java/android/os/IPermissionController.aidl \
	core/java/android/os/IProcessInfoService.aidl \
	core/java/android/os/IProgressListener.aidl \
	core/java/android/os/IPowerManager.aidl \
	core/java/android/os/IRecoverySystem.aidl \
	core/java/android/os/IRecoverySystemProgressListener.aidl \
	core/java/android/os/IRemoteCallback.aidl \
	core/java/android/os/ISchedulingPolicyService.aidl \
	core/java/android/os/IUpdateLock.aidl \
	core/java/android/os/IUserManager.aidl \
	core/java/android/os/IVibratorService.aidl \
	core/java/android/security/IKeystoreService.aidl \
	core/java/android/service/carrier/ICarrierService.aidl \
	core/java/android/service/carrier/ICarrierMessagingCallback.aidl \
	core/java/android/service/carrier/ICarrierMessagingService.aidl \
	core/java/android/service/gatekeeper/IGateKeeperService.aidl \
	core/java/android/service/notification/INotificationListener.aidl \
	core/java/android/service/notification/IStatusBarNotificationHolder.aidl \
	core/java/android/service/notification/IConditionListener.aidl \
	core/java/android/service/notification/IConditionProvider.aidl \
	core/java/android/service/vr/IVrListener.aidl \
	core/java/android/service/vr/IVrManager.aidl \
	core/java/android/service/vr/IVrStateCallbacks.aidl \
	core/java/android/print/ILayoutResultCallback.aidl \
	core/java/android/print/IPrinterDiscoveryObserver.aidl \
	core/java/android/print/IPrintDocumentAdapter.aidl \
	core/java/android/print/IPrintDocumentAdapterObserver.aidl \
	core/java/android/print/IPrintJobStateChangeListener.aidl \
	core/java/android/print/IPrintServicesChangeListener.aidl \
	core/java/android/printservice/recommendation/IRecommendationsChangeListener.aidl \
	core/java/android/print/IPrintManager.aidl \
	core/java/android/print/IPrintSpooler.aidl \
	core/java/android/print/IPrintSpoolerCallbacks.aidl \
	core/java/android/print/IPrintSpoolerClient.aidl \
	core/java/android/printservice/recommendation/IRecommendationServiceCallbacks.aidl \
	core/java/android/printservice/recommendation/IRecommendationService.aidl \
	core/java/android/print/IWriteResultCallback.aidl \
	core/java/android/printservice/IPrintService.aidl \
	core/java/android/printservice/IPrintServiceClient.aidl \
	core/java/android/service/dreams/IDreamManager.aidl \
	core/java/android/service/dreams/IDreamService.aidl \
	core/java/android/service/persistentdata/IPersistentDataBlockService.aidl \
	core/java/android/service/trust/ITrustAgentService.aidl \
	core/java/android/service/trust/ITrustAgentServiceCallback.aidl \
	core/java/android/service/voice/IVoiceInteractionService.aidl \
	core/java/android/service/voice/IVoiceInteractionSession.aidl \
	core/java/android/service/voice/IVoiceInteractionSessionService.aidl \
	core/java/android/service/wallpaper/IWallpaperConnection.aidl \
	core/java/android/service/wallpaper/IWallpaperEngine.aidl \
	core/java/android/service/wallpaper/IWallpaperService.aidl \
	core/java/android/service/chooser/IChooserTargetService.aidl \
	core/java/android/service/chooser/IChooserTargetResult.aidl \
	core/java/android/view/accessibility/IAccessibilityInteractionConnection.aidl\
	core/java/android/view/accessibility/IAccessibilityInteractionConnectionCallback.aidl\
	core/java/android/view/accessibility/IAccessibilityManager.aidl \
	core/java/android/view/accessibility/IAccessibilityManagerClient.aidl \
	core/java/android/view/IApplicationToken.aidl \
	core/java/android/view/IAppTransitionAnimationSpecsFuture.aidl \
	core/java/android/view/IAssetAtlas.aidl \
	core/java/android/view/IDockedStackListener.aidl \
	core/java/android/view/IGraphicsStats.aidl \
	core/java/android/view/IInputFilter.aidl \
	core/java/android/view/IInputFilterHost.aidl \
	core/java/android/view/IOnKeyguardExitResult.aidl \
	core/java/android/view/IRotationWatcher.aidl \
	core/java/android/view/IWindow.aidl \
	core/java/android/view/IWindowFocusObserver.aidl \
	core/java/android/view/IWindowId.aidl \
	core/java/android/view/IWindowManager.aidl \
	core/java/android/view/IWindowSession.aidl \
	core/java/android/view/IWindowSessionCallback.aidl \
	core/java/android/webkit/IWebViewUpdateService.aidl \
	core/java/android/speech/IRecognitionListener.aidl \
	core/java/android/speech/IRecognitionService.aidl \
	core/java/android/speech/tts/ITextToSpeechCallback.aidl \
	core/java/android/speech/tts/ITextToSpeechService.aidl \
	core/java/com/android/internal/app/IAppOpsCallback.aidl \
	core/java/com/android/internal/app/IAppOpsService.aidl \
	core/java/com/android/internal/app/IAssistScreenshotReceiver.aidl \
	core/java/com/android/internal/app/IBatteryStats.aidl \
	core/java/com/android/internal/app/ISoundTriggerService.aidl \
	core/java/com/android/internal/app/IVoiceInteractionManagerService.aidl \
	core/java/com/android/internal/app/IVoiceInteractionSessionListener.aidl \
	core/java/com/android/internal/app/IVoiceInteractionSessionShowCallback.aidl \
	core/java/com/android/internal/app/IVoiceInteractor.aidl \
	core/java/com/android/internal/app/IVoiceInteractorCallback.aidl \
	core/java/com/android/internal/app/IVoiceInteractorRequest.aidl \
	core/java/com/android/internal/app/IMediaContainerService.aidl \
	core/java/com/android/internal/app/procstats/IProcessStats.aidl \
	core/java/com/android/internal/appwidget/IAppWidgetService.aidl \
	core/java/com/android/internal/appwidget/IAppWidgetHost.aidl \
	core/java/com/android/internal/backup/IBackupTransport.aidl \
	core/java/com/android/internal/backup/IObbBackupService.aidl \
	core/java/com/android/internal/inputmethod/IInputContentUriToken.aidl \
	core/java/com/android/internal/policy/IKeyguardDrawnCallback.aidl \
	core/java/com/android/internal/policy/IKeyguardExitCallback.aidl \
	core/java/com/android/internal/policy/IKeyguardService.aidl \
	core/java/com/android/internal/policy/IKeyguardStateCallback.aidl \
	core/java/com/android/internal/policy/IShortcutService.aidl \
	core/java/com/android/internal/os/IDropBoxManagerService.aidl \
	core/java/com/android/internal/os/IParcelFileDescriptorFactory.aidl \
	core/java/com/android/internal/os/IResultReceiver.aidl \
	core/java/com/android/internal/statusbar/IStatusBar.aidl \
	core/java/com/android/internal/statusbar/IStatusBarService.aidl \
	core/java/com/android/internal/textservice/ISpellCheckerService.aidl \
	core/java/com/android/internal/textservice/ISpellCheckerSession.aidl \
	core/java/com/android/internal/textservice/ISpellCheckerSessionListener.aidl \
	core/java/com/android/internal/textservice/ITextServicesManager.aidl \
	core/java/com/android/internal/textservice/ITextServicesSessionListener.aidl \
	core/java/com/android/internal/view/IDragAndDropPermissions.aidl \
	core/java/com/android/internal/view/IInputContext.aidl \
	core/java/com/android/internal/view/IInputContextCallback.aidl \
	core/java/com/android/internal/view/IInputMethod.aidl \
	core/java/com/android/internal/view/IInputMethodClient.aidl \
	core/java/com/android/internal/view/IInputMethodManager.aidl \
	core/java/com/android/internal/view/IInputMethodSession.aidl \
	core/java/com/android/internal/view/IInputSessionCallback.aidl \
	core/java/com/android/internal/widget/ICheckCredentialProgressCallback.aidl \
	core/java/com/android/internal/widget/ILockSettings.aidl \
	core/java/com/android/internal/widget/IRemoteViewsFactory.aidl \
	core/java/com/android/internal/widget/IRemoteViewsAdapterConnection.aidl \
	keystore/java/android/security/IKeyChainAliasCallback.aidl \
	keystore/java/android/security/IKeyChainService.aidl \
	location/java/android/location/ICountryDetector.aidl \
	location/java/android/location/ICountryListener.aidl \
	location/java/android/location/IFusedProvider.aidl \
	location/java/android/location/IGeocodeProvider.aidl \
	location/java/android/location/IGeofenceProvider.aidl \
	location/java/android/location/IGnssStatusListener.aidl \
	location/java/android/location/IGnssStatusProvider.aidl \
	location/java/android/location/IGnssMeasurementsListener.aidl \
	location/java/android/location/IGnssNavigationMessageListener.aidl \
	location/java/android/location/ILocationListener.aidl \
	location/java/android/location/ILocationManager.aidl \
	location/java/android/location/IFusedGeofenceHardware.aidl \
	location/java/android/location/IGpsGeofenceHardware.aidl \
	location/java/android/location/INetInitiatedListener.aidl \
	location/java/com/android/internal/location/ILocationProvider.aidl \
	media/java/android/media/IAudioService.aidl \
	media/java/android/media/IAudioFocusDispatcher.aidl \
	media/java/android/media/IAudioRoutesObserver.aidl \
	media/java/android/media/IMediaHTTPConnection.aidl \
	media/java/android/media/IMediaHTTPService.aidl \
	media/java/android/media/IMediaResourceMonitor.aidl \
	media/java/android/media/IMediaRouterClient.aidl \
	media/java/android/media/IMediaRouterService.aidl \
	media/java/android/media/IMediaScannerListener.aidl \
	media/java/android/media/IMediaScannerService.aidl \
	media/java/android/media/IRecordingConfigDispatcher.aidl \
	media/java/android/media/IRemoteDisplayCallback.aidl \
	media/java/android/media/IRemoteDisplayProvider.aidl \
	media/java/android/media/IRemoteVolumeController.aidl \
	media/java/android/media/IRemoteVolumeObserver.aidl \
	media/java/android/media/IRingtonePlayer.aidl \
	media/java/android/media/IVolumeController.aidl \
	media/java/android/media/audiopolicy/IAudioPolicyCallback.aidl \
	media/java/android/media/midi/IBluetoothMidiService.aidl \
	media/java/android/media/midi/IMidiDeviceListener.aidl \
	media/java/android/media/midi/IMidiDeviceOpenCallback.aidl \
	media/java/android/media/midi/IMidiDeviceServer.aidl \
	media/java/android/media/midi/IMidiManager.aidl \
	media/java/android/media/projection/IMediaProjection.aidl \
	media/java/android/media/projection/IMediaProjectionCallback.aidl \
	media/java/android/media/projection/IMediaProjectionManager.aidl \
	media/java/android/media/projection/IMediaProjectionWatcherCallback.aidl \
	media/java/android/media/session/IActiveSessionsListener.aidl \
	media/java/android/media/session/ISessionController.aidl \
	media/java/android/media/session/ISessionControllerCallback.aidl \
	media/java/android/media/session/ISession.aidl \
	media/java/android/media/session/ISessionCallback.aidl \
	media/java/android/media/session/ISessionManager.aidl \
	media/java/android/media/tv/ITvInputClient.aidl \
	media/java/android/media/tv/ITvInputHardware.aidl \
	media/java/android/media/tv/ITvInputHardwareCallback.aidl \
	media/java/android/media/tv/ITvInputManager.aidl \
	media/java/android/media/tv/ITvInputManagerCallback.aidl \
	media/java/android/media/tv/ITvInputService.aidl \
	media/java/android/media/tv/ITvInputServiceCallback.aidl \
	media/java/android/media/tv/ITvInputSession.aidl \
	media/java/android/media/tv/ITvInputSessionCallback.aidl \
	media/java/android/media/tv/ITvRemoteProvider.aidl \
	media/java/android/media/tv/ITvRemoteServiceInput.aidl \
	media/java/android/service/media/IMediaBrowserService.aidl \
	media/java/android/service/media/IMediaBrowserServiceCallbacks.aidl \
	telecomm/java/com/android/internal/telecom/ICallScreeningAdapter.aidl \
	telecomm/java/com/android/internal/telecom/ICallScreeningService.aidl \
	telecomm/java/com/android/internal/telecom/IVideoCallback.aidl \
	telecomm/java/com/android/internal/telecom/IVideoProvider.aidl \
	telecomm/java/com/android/internal/telecom/IConnectionService.aidl \
	telecomm/java/com/android/internal/telecom/IConnectionServiceAdapter.aidl \
	telecomm/java/com/android/internal/telecom/IInCallAdapter.aidl \
	telecomm/java/com/android/internal/telecom/IInCallService.aidl \
	telecomm/java/com/android/internal/telecom/ITelecomService.aidl \
	telecomm/java/com/android/internal/telecom/RemoteServiceCallback.aidl \
	telephony/java/com/android/ims/internal/IImsCallSession.aidl \
	telephony/java/com/android/ims/internal/IImsCallSessionListener.aidl \
	telephony/java/com/android/ims/internal/IImsConfig.aidl \
	telephony/java/com/android/ims/internal/IImsRegistrationListener.aidl \
	telephony/java/com/android/ims/internal/IImsEcbm.aidl \
	telephony/java/com/android/ims/internal/IImsEcbmListener.aidl \
        telephony/java/com/android/ims/internal/IImsExternalCallStateListener.aidl \
        telephony/java/com/android/ims/internal/IImsMultiEndpoint.aidl \
	telephony/java/com/android/ims/internal/IImsService.aidl \
	telephony/java/com/android/ims/internal/IImsStreamMediaSession.aidl \
	telephony/java/com/android/ims/internal/IImsUt.aidl \
	telephony/java/com/android/ims/internal/IImsUtListener.aidl \
	telephony/java/com/android/ims/internal/IImsVideoCallCallback.aidl \
	telephony/java/com/android/ims/internal/IImsVideoCallProvider.aidl \
        telephony/java/com/android/ims/internal/uce/uceservice/IUceService.aidl \
        telephony/java/com/android/ims/internal/uce/uceservice/IUceListener.aidl \
        telephony/java/com/android/ims/internal/uce/options/IOptionsService.aidl \
        telephony/java/com/android/ims/internal/uce/options/IOptionsListener.aidl \
        telephony/java/com/android/ims/internal/uce/presence/IPresenceService.aidl \
        telephony/java/com/android/ims/internal/uce/presence/IPresenceListener.aidl \
	telephony/java/com/android/ims/ImsConfigListener.aidl \
	telephony/java/com/android/internal/telephony/ICarrierConfigLoader.aidl \
	telephony/java/com/android/internal/telephony/IMms.aidl \
	telephony/java/com/android/internal/telephony/IOnSubscriptionsChangedListener.aidl \
	telephony/java/com/android/internal/telephony/IPhoneStateListener.aidl \
	telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl \
	telephony/java/com/android/internal/telephony/ISms.aidl \
	telephony/java/com/android/internal/telephony/ISub.aidl \
	telephony/java/com/android/internal/telephony/ITelephony.aidl \
	telephony/java/com/android/internal/telephony/ITelephonyRegistry.aidl \
	telephony/java/com/android/internal/telephony/IWapPushManager.aidl \
	wifi/java/android/net/wifi/IWifiManager.aidl \
	wifi/java/android/net/wifi/nan/IWifiNanEventListener.aidl \
	wifi/java/android/net/wifi/nan/IWifiNanManager.aidl \
	wifi/java/android/net/wifi/nan/IWifiNanSessionListener.aidl \
	wifi/java/android/net/wifi/p2p/IWifiP2pManager.aidl \
	wifi/java/android/net/wifi/IWifiScanner.aidl \
	wifi/java/android/net/wifi/IRttManager.aidl \
	packages/services/PacProcessor/com/android/net/IProxyService.aidl \
	packages/services/Proxy/com/android/net/IProxyCallback.aidl \
	packages/services/Proxy/com/android/net/IProxyPortListener.aidl \
	core/java/android/service/quicksettings/IQSService.aidl \
	core/java/android/service/quicksettings/IQSTileService.aidl \

# The following are native binders that need to go with the native component
# at system/update_engine/binder_bindings/. Use relative path to refer to them.
LOCAL_SRC_FILES += \
	../../system/update_engine/binder_bindings/android/os/IUpdateEngine.aidl \
	../../system/update_engine/binder_bindings/android/os/IUpdateEngineCallback.aidl \

LOCAL_SRC_FILES +=  \
	../../system/netd/server/binder/android/net/INetd.aidl \

LOCAL_AIDL_INCLUDES += system/update_engine/binder_bindings

# FRAMEWORKS_BASE_JAVA_SRC_DIRS comes from build/core/pathmap.mk
LOCAL_AIDL_INCLUDES += \
      $(FRAMEWORKS_BASE_JAVA_SRC_DIRS) \
      frameworks/native/aidl/binder

LOCAL_AIDL_INCLUDES += \
	frameworks/av/camera/aidl \
	frameworks/native/aidl/gui \
	system/netd/server/binder

LOCAL_INTERMEDIATE_SOURCES := \
			$(framework_res_source_path)/android/R.java \
			$(framework_res_source_path)/android/Manifest.java \
			$(framework_res_source_path)/com/android/internal/R.java

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart core-lambda-stubs conscrypt okhttp core-junit bouncycastle ext
LOCAL_STATIC_JAVA_LIBRARIES := framework-protos

LOCAL_MODULE := framework

LOCAL_DX_FLAGS := --core-library --multi-dex
LOCAL_JACK_FLAGS := --multi-dex native

LOCAL_RMTYPEDEFS := true

ifeq ($(EMMA_INSTRUMENT_FRAMEWORK),true)
LOCAL_EMMA_INSTRUMENT := true
endif

include $(BUILD_JAVA_LIBRARY)
framework_module := $(LOCAL_INSTALLED_MODULE)

# Make sure that R.java and Manifest.java are built before we build
# the source for this library.
framework_res_R_stamp := \
	$(call intermediates-dir-for,APPS,framework-res,,COMMON)/src/R.stamp
$(full_classes_compiled_jar): $(framework_res_R_stamp)
$(built_dex_intermediate): $(framework_res_R_stamp)

$(framework_module): | $(dir $(framework_module))framework-res.apk

framework_built := $(call java-lib-deps,framework)

# Copy AIDL files to be preprocessed and included in the SDK,
# specified relative to the root of the build tree.
# ============================================================
include $(CLEAR_VARS)

aidl_files := \
	frameworks/base/telephony/java/android/telephony/ServiceState.aidl \
	frameworks/base/telephony/java/android/telephony/SubscriptionInfo.aidl \
	frameworks/base/telephony/java/android/telephony/CellInfo.aidl \
	frameworks/base/telephony/java/android/telephony/SignalStrength.aidl \
	frameworks/base/telephony/java/android/telephony/IccOpenLogicalChannelResponse.aidl \
	frameworks/base/telephony/java/android/telephony/NeighboringCellInfo.aidl \
	frameworks/base/telephony/java/android/telephony/ModemActivityInfo.aidl \
	frameworks/base/location/java/android/location/Location.aidl \
	frameworks/base/location/java/android/location/Address.aidl \
	frameworks/base/location/java/android/location/Criteria.aidl \
	frameworks/base/media/java/android/media/MediaMetadata.aidl \
	frameworks/base/media/java/android/media/MediaDescription.aidl \
	frameworks/base/media/java/android/media/Rating.aidl \
	frameworks/base/media/java/android/media/AudioAttributes.aidl \
	frameworks/base/media/java/android/media/AudioFocusInfo.aidl \
	frameworks/base/media/java/android/media/session/PlaybackState.aidl \
	frameworks/base/media/java/android/media/session/MediaSession.aidl \
	frameworks/base/media/java/android/media/tv/TvInputInfo.aidl \
	frameworks/base/media/java/android/media/tv/TvTrackInfo.aidl \
	frameworks/base/media/java/android/media/browse/MediaBrowser.aidl \
	frameworks/base/wifi/java/android/net/wifi/ScanSettings.aidl \
	frameworks/base/wifi/java/android/net/wifi/nan/ConfigRequest.aidl \
	frameworks/base/wifi/java/android/net/wifi/nan/PublishData.aidl \
	frameworks/base/wifi/java/android/net/wifi/nan/SubscribeData.aidl \
	frameworks/base/wifi/java/android/net/wifi/nan/PublishSettings.aidl \
	frameworks/base/wifi/java/android/net/wifi/nan/SubscribeSettings.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pDeviceList.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pDevice.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pGroup.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/nsd/WifiP2pServiceRequest.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/nsd/WifiP2pServiceInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/WpsInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/ScanResult.aidl \
	frameworks/base/wifi/java/android/net/wifi/PasspointManagementObjectDefinition.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiEnterpriseConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiConfiguration.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiInfo.aidl \
	frameworks/base/graphics/java/android/graphics/Region.aidl \
	frameworks/base/graphics/java/android/graphics/Bitmap.aidl \
	frameworks/base/graphics/java/android/graphics/Point.aidl \
	frameworks/base/graphics/java/android/graphics/PointF.aidl \
	frameworks/base/graphics/java/android/graphics/RectF.aidl \
	frameworks/base/graphics/java/android/graphics/Rect.aidl \
	frameworks/base/graphics/java/android/graphics/drawable/Icon.aidl \
	frameworks/base/core/java/android/accounts/AuthenticatorDescription.aidl \
	frameworks/base/core/java/android/accounts/Account.aidl \
	frameworks/base/core/java/android/app/admin/SystemUpdatePolicy.aidl \
	frameworks/base/core/java/android/print/PrintDocumentInfo.aidl \
	frameworks/base/core/java/android/print/PageRange.aidl \
	frameworks/base/core/java/android/print/PrintAttributes.aidl \
	frameworks/base/core/java/android/print/PrinterCapabilitiesInfo.aidl \
	frameworks/base/core/java/android/print/PrinterId.aidl \
	frameworks/base/core/java/android/print/PrintJobInfo.aidl \
	frameworks/base/core/java/android/print/PrinterInfo.aidl \
	frameworks/base/core/java/android/print/PrintJobId.aidl \
	frameworks/base/core/java/android/printservice/recommendation/RecommendationInfo.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbDevice.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbInterface.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbEndpoint.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbAccessory.aidl \
	frameworks/base/core/java/android/os/Messenger.aidl \
	frameworks/base/core/java/android/os/PatternMatcher.aidl \
	frameworks/base/core/java/android/os/Message.aidl \
	frameworks/base/core/java/android/os/UserHandle.aidl \
	frameworks/base/core/java/android/os/ParcelUuid.aidl \
	frameworks/base/core/java/android/os/ParcelFileDescriptor.aidl \
	frameworks/base/core/java/android/os/ResultReceiver.aidl \
	frameworks/base/core/java/android/os/WorkSource.aidl \
	frameworks/base/core/java/android/os/DropBoxManager.aidl \
	frameworks/base/core/java/android/os/Bundle.aidl \
	frameworks/base/core/java/android/accessibilityservice/AccessibilityServiceInfo.aidl \
	frameworks/base/core/java/android/net/Network.aidl \
	frameworks/base/core/java/android/net/RouteInfo.aidl \
	frameworks/base/core/java/android/net/NetworkInfo.aidl \
	frameworks/base/core/java/android/net/IpPrefix.aidl \
	frameworks/base/core/java/android/net/NetworkCapabilities.aidl \
	frameworks/base/core/java/android/net/DhcpInfo.aidl \
	frameworks/base/core/java/android/net/ProxyInfo.aidl \
	frameworks/base/core/java/android/net/LinkProperties.aidl \
	frameworks/base/core/java/android/net/Uri.aidl \
	frameworks/base/core/java/android/net/NetworkRequest.aidl \
	frameworks/base/core/java/android/net/LinkAddress.aidl \
	frameworks/base/core/java/android/util/MemoryIntArray.aidl \
	frameworks/base/core/java/android/view/Display.aidl \
	frameworks/base/core/java/android/view/InputDevice.aidl \
	frameworks/base/core/java/android/view/InputEvent.aidl \
	frameworks/native/aidl/gui/android/view/Surface.aidl \
	frameworks/base/core/java/android/view/WindowContentFrameStats.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputMethodSubtype.aidl \
	frameworks/base/core/java/android/view/inputmethod/CursorAnchorInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/CompletionInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/ExtractedText.aidl \
	frameworks/base/core/java/android/view/inputmethod/EditorInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputMethodInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/CorrectionInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputBinding.aidl \
	frameworks/base/core/java/android/view/inputmethod/ExtractedTextRequest.aidl \
	frameworks/base/core/java/android/view/DragEvent.aidl \
	frameworks/base/core/java/android/view/KeyEvent.aidl \
	frameworks/base/core/java/android/view/WindowManager.aidl \
	frameworks/base/core/java/android/view/WindowAnimationFrameStats.aidl \
	frameworks/base/core/java/android/view/MotionEvent.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityRecord.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityWindowInfo.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityEvent.aidl \
	frameworks/base/core/java/android/view/textservice/SpellCheckerSubtype.aidl \
	frameworks/base/core/java/android/view/textservice/TextInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SpellCheckerInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SentenceSuggestionsInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SuggestionsInfo.aidl \
	frameworks/base/core/java/android/service/carrier/CarrierIdentifier.aidl \
	frameworks/base/core/java/android/service/carrier/MessagePdu.aidl \
	frameworks/base/core/java/android/service/notification/StatusBarNotification.aidl \
	frameworks/base/core/java/android/service/chooser/ChooserTarget.aidl \
	frameworks/base/core/java/android/speech/tts/Voice.aidl \
	frameworks/base/core/java/android/app/usage/UsageEvents.aidl \
	frameworks/base/core/java/android/app/Notification.aidl \
	frameworks/base/core/java/android/app/NotificationManager.aidl \
	frameworks/base/core/java/android/app/WallpaperInfo.aidl \
	frameworks/base/core/java/android/app/AppOpsManager.aidl \
	frameworks/base/core/java/android/app/ActivityManager.aidl \
	frameworks/base/core/java/android/app/PendingIntent.aidl \
	frameworks/base/core/java/android/app/AlarmManager.aidl \
	frameworks/base/core/java/android/app/SearchableInfo.aidl \
	frameworks/base/core/java/android/app/VoiceInteractor.aidl \
	frameworks/base/core/java/android/app/assist/AssistContent.aidl \
	frameworks/base/core/java/android/app/assist/AssistStructure.aidl \
	frameworks/base/core/java/android/app/job/JobParameters.aidl \
	frameworks/base/core/java/android/app/job/JobInfo.aidl \
	frameworks/base/core/java/android/appwidget/AppWidgetProviderInfo.aidl \
	frameworks/base/core/java/android/content/ClipDescription.aidl \
	frameworks/base/core/java/android/content/IntentFilter.aidl \
	frameworks/base/core/java/android/content/Intent.aidl \
	frameworks/base/core/java/android/content/res/Configuration.aidl \
	frameworks/base/core/java/android/content/res/ObbInfo.aidl \
	frameworks/base/core/java/android/content/RestrictionEntry.aidl \
	frameworks/base/core/java/android/content/ClipData.aidl \
	frameworks/base/core/java/android/content/SyncAdapterType.aidl \
	frameworks/base/core/java/android/content/SyncRequest.aidl \
	frameworks/base/core/java/android/content/PeriodicSync.aidl \
	frameworks/base/core/java/android/content/SyncResult.aidl \
	frameworks/base/core/java/android/content/pm/FeatureInfo.aidl \
	frameworks/base/core/java/android/content/pm/InstrumentationInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageInstaller.aidl \
	frameworks/base/core/java/android/content/pm/ServiceInfo.aidl \
	frameworks/base/core/java/android/content/pm/Signature.aidl \
	frameworks/base/core/java/android/content/pm/ApplicationInfo.aidl \
	frameworks/base/core/java/android/content/pm/PermissionInfo.aidl \
	frameworks/base/core/java/android/content/pm/ActivityInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageInfo.aidl \
	frameworks/base/core/java/android/content/pm/ResolveInfo.aidl \
	frameworks/base/core/java/android/content/pm/ProviderInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageStats.aidl \
	frameworks/base/core/java/android/content/pm/PermissionGroupInfo.aidl \
	frameworks/base/core/java/android/content/pm/ShortcutInfo.aidl \
	frameworks/base/core/java/android/content/pm/LabeledIntent.aidl \
	frameworks/base/core/java/android/content/ComponentName.aidl \
	frameworks/base/core/java/android/content/SyncStats.aidl \
	frameworks/base/core/java/android/content/ContentValues.aidl \
	frameworks/base/core/java/android/content/SyncInfo.aidl \
	frameworks/base/core/java/android/content/IntentSender.aidl \
	frameworks/base/core/java/android/widget/RemoteViews.aidl \
	frameworks/base/core/java/android/text/style/SuggestionSpan.aidl \
	frameworks/base/core/java/android/nfc/Tag.aidl \
	frameworks/base/core/java/android/nfc/NdefRecord.aidl \
	frameworks/base/core/java/android/nfc/NdefMessage.aidl \
	frameworks/base/core/java/android/bluetooth/BluetoothHealthAppConfiguration.aidl \
	frameworks/base/core/java/android/bluetooth/le/AdvertiseSettings.aidl \
	frameworks/base/core/java/android/bluetooth/le/ScanSettings.aidl \
	frameworks/base/core/java/android/bluetooth/le/AdvertiseData.aidl \
	frameworks/base/core/java/android/bluetooth/le/ScanFilter.aidl \
	frameworks/base/core/java/android/bluetooth/le/ScanResult.aidl \
	frameworks/base/core/java/android/bluetooth/BluetoothDevice.aidl \
	frameworks/base/core/java/android/database/CursorWindow.aidl \
	frameworks/base/core/java/android/service/quicksettings/Tile.aidl \
	frameworks/native/aidl/binder/android/os/PersistableBundle.aidl \
	system/netd/server/binder/android/net/UidRange.aidl \
	frameworks/base/telephony/java/android/telephony/PcoData.aidl \

gen := $(TARGET_OUT_COMMON_INTERMEDIATES)/framework.aidl
$(gen): PRIVATE_SRC_FILES := $(aidl_files)
ALL_SDK_FILES += $(gen)
$(gen): $(aidl_files) | $(AIDL)
		@echo Aidl Preprocess: $@
		$(hide) $(AIDL) --preprocess $@ $(PRIVATE_SRC_FILES)

# the documentation
# ============================================================

# TODO: deal with com/google/android/googleapps
packages_to_document := \
	android \
	javax/microedition/khronos \
	org/apache/http/conn \
	org/apache/http/params


# Search through the base framework dirs for these packages.
# The result will be relative to frameworks/base.
fwbase_dirs_to_document := \
	test-runner/src \
	$(patsubst $(LOCAL_PATH)/%,%, \
	  $(wildcard \
	    $(foreach dir, $(FRAMEWORKS_BASE_JAVA_SRC_DIRS), \
	      $(addprefix $(dir)/, $(packages_to_document)) \
	     ) \
	   ) \
	 )

# include definition of libcore_to_document
include libcore/Docs.mk

# include definition of junit_to_document
include external/junit/Common.mk

non_base_dirs := \
	../opt/telephony/src/java/android/provider \
	../opt/telephony/src/java/android/telephony \
	../opt/telephony/src/java/android/telephony/gsm \
	../opt/net/voip/src/java/android/net/rtp \
	../opt/net/voip/src/java/android/net/sip

# These are relative to frameworks/base
dirs_to_check_apis := \
  $(fwbase_dirs_to_document) \
	$(non_base_dirs)

###########################################################
## Return all directories that have a 'NO_DOCS' file in
## them, appending a '%' to them to form a pattern to
## filter out files under those directories.
## $(1): A list of base directories to look at.
###########################################################
define find-no-docs-pattern
$(addsuffix %, $(dir $(foreach dir, $(1), $(shell cd $(LOCAL_PATH); find $(dir) -name NO_DOCS))))
endef

# These are relative to frameworks/base
# FRAMEWORKS_BASE_SUBDIRS comes from build/core/pathmap.mk
dirs_to_document := \
	$(dirs_to_check_apis) \
  $(addprefix ../../, $(FRAMEWORKS_DATA_BINDING_JAVA_SRC_DIRS)) \
  $(addprefix ../../, $(FRAMEWORKS_SUPPORT_JAVA_SRC_DIRS)) \

patterns_to_not_document := \
	$(call find-no-docs-pattern, $(dirs_to_document))

# These are relative to frameworks/base
html_dirs := \
	$(FRAMEWORKS_BASE_SUBDIRS) \
	$(non_base_dirs)

# Common sources for doc check and api check
common_src_files := \
	$(call find-other-html-files, $(html_dirs)) \
	$(addprefix ../../, $(libcore_to_document)) \
	$(addprefix ../../external/junit/, $(junit_to_document))

# These are relative to frameworks/base
framework_docs_LOCAL_SRC_FILES := \
	$(filter-out $(patterns_to_not_document), $(call find-other-java-files, $(dirs_to_document))) \
	$(common_src_files)

# These are relative to frameworks/base
framework_docs_LOCAL_API_CHECK_SRC_FILES := \
	$(call find-other-java-files, $(dirs_to_check_apis)) \
	$(common_src_files)

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

framework_docs_LOCAL_DROIDDOC_SOURCE_PATH := \
	$(FRAMEWORKS_BASE_JAVA_SRC_DIRS)

framework_docs_LOCAL_INTERMEDIATE_SOURCES := \
	$(framework_res_source_path)/android/R.java \
	$(framework_res_source_path)/android/Manifest.java \
	$(framework_res_source_path)/com/android/internal/R.java

framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES := \
	core-oj \
	core-libart \
	conscrypt \
	bouncycastle \
	okhttp \
	ext \
	icu4j \
	framework \
	telephony-common \
	voip-common

framework_docs_LOCAL_JAVA_LIBRARIES := \
	$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES) \
	$(FRAMEWORKS_SUPPORT_JAVA_LIBRARIES)

framework_docs_LOCAL_MODULE_CLASS := JAVA_LIBRARIES
framework_docs_LOCAL_DROIDDOC_HTML_DIR := docs/html
# The since flag (-since N.xml API_LEVEL) is used to add API Level information
# to the reference documentation. Must be in order of oldest to newest.
#
# Conscrypt (com.android.org.conscrypt) is an implementation detail and should
# not be referenced in the documentation.
framework_docs_LOCAL_DROIDDOC_OPTIONS := \
    -knowntags ./frameworks/base/docs/knowntags.txt \
    -knowntags ./libcore/known_oj_tags.txt \
    -hidePackage com.android.org.conscrypt \
    -since $(SRC_API_DIR)/1.xml 1 \
    -since $(SRC_API_DIR)/2.xml 2 \
    -since $(SRC_API_DIR)/3.xml 3 \
    -since $(SRC_API_DIR)/4.xml 4 \
    -since $(SRC_API_DIR)/5.xml 5 \
    -since $(SRC_API_DIR)/6.xml 6 \
    -since $(SRC_API_DIR)/7.xml 7 \
    -since $(SRC_API_DIR)/8.xml 8 \
    -since $(SRC_API_DIR)/9.xml 9 \
    -since $(SRC_API_DIR)/10.xml 10 \
    -since $(SRC_API_DIR)/11.xml 11 \
    -since $(SRC_API_DIR)/12.xml 12 \
    -since $(SRC_API_DIR)/13.xml 13 \
    -since $(SRC_API_DIR)/14.txt 14 \
    -since $(SRC_API_DIR)/15.txt 15 \
    -since $(SRC_API_DIR)/16.txt 16 \
    -since $(SRC_API_DIR)/17.txt 17 \
    -since $(SRC_API_DIR)/18.txt 18 \
    -since $(SRC_API_DIR)/19.txt 19 \
    -since $(SRC_API_DIR)/20.txt 20 \
    -since $(SRC_API_DIR)/21.txt 21 \
    -since $(SRC_API_DIR)/22.txt 22 \
    -since $(SRC_API_DIR)/23.txt 23 \
    -since $(SRC_API_DIR)/24.txt 24 \
		-werror -hide 111 -hide 113 \
		-overview $(LOCAL_PATH)/core/java/overview.html

framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR:= \
	$(call intermediates-dir-for,JAVA_LIBRARIES,framework,,COMMON)

framework_docs_LOCAL_ADDITIONAL_JAVA_DIR:= \
	$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR) \
	$(foreach lib,$(FRAMEWORKS_SUPPORT_JAVA_LIBRARIES),$(call intermediates-dir-for,JAVA_LIBRARIES,$(lib),,COMMON)) \
	$(foreach lib,$(FRAMEWORKS_SUPPORT_JAVA_LIBRARIES),$(call intermediates-dir-for,JAVA_LIBRARIES,$(lib)-res,,COMMON))

framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES := \
    frameworks/base/docs/knowntags.txt \
    libcore/Docs.mk

samples_dir := development/samples/browseable

# Whitelist of valid groups, used for default TOC grouping. Each sample must
# belong to one (and only one) group. Assign samples to groups by setting
# a sample.group var to one of these groups in the sample's _index.jd.
sample_groups := -samplegroup Admin \
                 -samplegroup Background \
                 -samplegroup Connectivity \
                 -samplegroup Content \
                 -samplegroup Input \
                 -samplegroup Media \
                 -samplegroup Notification \
                 -samplegroup RenderScript \
                 -samplegroup Security \
                 -samplegroup Sensors \
                 -samplegroup System \
                 -samplegroup Testing \
                 -samplegroup UI \
                 -samplegroup Views \
                 -samplegroup Wearable

## SDK version identifiers used in the published docs
  # major[.minor] version for current SDK. (full releases only)
framework_docs_SDK_VERSION:=7.0
  # release version (ie "Release x")  (full releases only)
framework_docs_SDK_REL_ID:=1

framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-hdf sdk.codename N \
		-hdf sdk.preview.version 5 \
		-hdf sdk.version $(framework_docs_SDK_VERSION) \
		-hdf sdk.rel.id $(framework_docs_SDK_REL_ID) \
		-hdf sdk.preview 0

# ====  the api stubs and current.xml ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-api $(INTERNAL_PLATFORM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_REMOVED_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(framework_built) $(gen)
$(INTERNAL_PLATFORM_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))

# ====  the system api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := system-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_system_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-showAnnotation android.annotation.SystemApi \
		-api $(INTERNAL_PLATFORM_SYSTEM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_SYSTEM_REMOVED_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(framework_built) $(gen)
$(INTERNAL_PLATFORM_SYSTEM_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))

# ====  the test api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := test-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
               $(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
               -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src \
               -showAnnotation android.annotation.TestApi \
               -api $(INTERNAL_PLATFORM_TEST_API_FILE) \
               -removedApi $(INTERNAL_PLATFORM_TEST_REMOVED_API_FILE) \
               -nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(framework_built) $(gen)
$(INTERNAL_PLATFORM_TEST_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))

# ====  check javadoc comments but don't generate docs ========
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := doc-comment-check

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-parsecomments

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(framework_built) $(gen)

# Run this for checkbuild
checkbuild: doc-comment-check-docs
# Check comment when you are updating the API
update-api: doc-comment-check-docs

# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): \
	$(LOCAL_PATH)/docs/docs-preview-index.html | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(framework_built)


# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk-referenceonly

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline \
		-referenceonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): \
	$(LOCAL_PATH)/docs/docs-documentation-redirect.html | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(framework_built)


# ==== docs for the web (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the androiddevdocs app engine server) =======================
# TODO: Fix the System API docs build.
ifneq ($(filter online-system-api-sdk-docs,$(MAKECMDGOALS)),)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-system-api-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-showAnnotation android.annotation.SystemApi \
		-title "Android SDK - Including system APIs." \
		-toroot / \
		-hide 101 \
		-hide 104 \
		-hide 108 \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk
# Don't build by default
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)
endif  # online-system-api-sdk-docs in make command line.

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		-devsite \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds-static

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hdf android.whichdoc online \
		-staticonly \
		-toroot / \
		-devsite \
		-ignoreJdLinks

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

# ==== site updates for docs (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk-dev

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-useUpdatedTemplates \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk-dev

include $(BUILD_DROIDDOC)

# ==== docs that have all of the stuff that's @hidden =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := hidden
LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-title "Android SDK - Including hidden APIs."
#		-hidden

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk

include $(BUILD_DROIDDOC)

# Build ext.jar
# ============================================================

ext_dirs := \
	../../external/nist-sip/java \
	../../external/tagsoup/src \

ext_src_files := $(call all-java-files-under,$(ext_dirs))

# ====  the library  =========================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(ext_src_files)

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart
LOCAL_STATIC_JAVA_LIBRARIES := libphonenumber-platform
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := ext

LOCAL_DX_FLAGS := --core-library

ifneq ($(INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
endif

include $(BUILD_JAVA_LIBRARY)


# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
