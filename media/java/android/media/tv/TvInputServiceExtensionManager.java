/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.media.tv.flags.Flags;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * This class provides a list of available standardized TvInputService extension interface names
 * and a container storing IBinder objects that implement these interfaces created by SoC/OEMs.
 * It also provides an API for SoC/OEMs to register implemented IBinder objects.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_TIF_EXTENSION_STANDARDIZATION)
public final class TvInputServiceExtensionManager {
    private static final String TAG = "TvInputServiceExtensionManager";
    private static final String SCAN_PACKAGE = "android.media.tv.extension.scan.";
    private static final String OAD_PACKAGE = "android.media.tv.extension.oad.";
    private static final String CAM_PACKAGE = "android.media.tv.extension.cam.";
    private static final String RATING_PACKAGE = "android.media.tv.extension.rating.";
    private static final String TIME_PACKAGE = "android.media.tv.extension.time.";
    private static final String TELETEXT_PACKAGE = "android.media.tv.extension.teletext.";
    private static final String SCAN_BSU_PACKAGE = "android.media.tv.extension.scanbsu.";
    private static final String CLIENT_TOKEN_PACKAGE = "android.media.tv.extension.clienttoken.";
    private static final String SCREEN_MODE_PACKAGE = "android.media.tv.extension.screenmode.";
    private static final String SIGNAL_PACKAGE = "android.media.tv.extension.signal.";
    private static final String SERVICE_DATABASE_PACKAGE = "android.media.tv.extension.servicedb.";
    private static final String PVR_PACKAGE = "android.media.tv.extension.pvr.";
    private static final String EVENT_PACKAGE = "android.media.tv.extension.event.";
    private static final String ANALOG_PACKAGE = "android.media.tv.extension.analog.";
    private static final String TUNE_PACKAGE = "android.media.tv.extension.tune.";

    /** @hide */
    @IntDef(prefix = {"REGISTER_"}, value = {
            REGISTER_SUCCESS,
            REGISTER_FAIL_NAME_NOT_STANDARDIZED,
            REGISTER_FAIL_IMPLEMENTATION_NOT_STANDARDIZED,
            REGISTER_FAIL_REMOTE_EXCEPTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RegisterResult {}

    /**
     * Registering binder returns success when it abides standardized interface structure
     */
    public static final int REGISTER_SUCCESS = 0;
    /**
     * Registering binder returns failure when the extension name is not in the standardization
     * list
     */
    public static final int REGISTER_FAIL_NAME_NOT_STANDARDIZED = 1;
    /**
     * Registering binder returns failure when the IBinder does not implement standardized interface
     */
    public static final int REGISTER_FAIL_IMPLEMENTATION_NOT_STANDARDIZED = 2;
    /**
     * Registering binder returns failure when remote server is not available
     */
    public static final int REGISTER_FAIL_REMOTE_EXCEPTION = 3;

    /** @hide */
    @StringDef({
            ISCAN_INTERFACE,
            ISCAN_SESSION,
            ISCAN_LISTENER,
            IHDPLUS_INFO,
            IOPERATOR_DETECTION,
            IOPERATOR_DETECTION_LISTENER,
            IREGION_CHANNEL_LIST,
            IREGION_CHANNEL_LIST_LISTENER,
            ITARGET_REGION,
            ITARGET_REGION_LISTENER,
            ILCN_CONFLICT,
            ILCN_CONFLICT_LISTENER,
            ILCNV2_CHANNEL_LIST,
            ILCNV2_CHANNEL_LIST_LISTENER,
            IFAVORITE_NETWORK,
            IFAVORITE_NETWORK_LISTENER,
            ITKGS_INFO,
            ITKGS_INFO_LISTENER,
            ISCAN_SAT_SEARCH,
            IOAD_UPDATE_INTERFACE,
            ICAM_APP_INFO_SERVICE,
            ICAM_APP_INFO_LISTENER,
            ICAM_MONITORING_SERVICE,
            ICAM_INFO_LISTENER,
            ICI_OPERATOR_INTERFACE,
            ICI_OPERATOR_LISTENER,
            ICAM_PROFILE_INTERFACE,
            ICONTENT_CONTROL_SERVICE,
            ICAM_DRM_INFO_LISTENER,
            ICAM_PIN_SERVICE,
            ICAM_PIN_CAPABILITY_LISTENER,
            ICAM_PIN_STATUS_LISTENER,
            ICAM_HOST_CONTROL_SERVICE,
            ICAM_HOST_CONTROL_ASK_RELEASE_REPLY_CALLBACK,
            ICAM_HOST_CONTROL_INFO_LISTENER,
            ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG,
            ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG_LISTENER,
            IMMI_INTERFACE,
            IMMI_SESSION,
            IMMI_STATUS_CALLBACK,
            IENTER_MENU_ERROR_CALLBACK,
            IDOWNLOADABLE_RATING_TABLE_MONITOR,
            IRATING_INTERFACE,
            IPMT_RATING_INTERFACE,
            IPMT_RATING_LISTENER,
            IVBI_RATING_INTERFACE,
            IVBI_RATING_LISTENER,
            IPROGRAM_INFO,
            IPROGRAM_INFO_LISTENER,
            IBROADCAST_TIME,
            IDATA_SERVICE_SIGNAL_INFO,
            IDATA_SERVICE_SIGNAL_INFO_LISTENER,
            ITELETEXT_PAGE_SUB_CODE,
            ISCAN_BACKGROUND_SERVICE_UPDATE,
            ISCAN_BACKGROUND_SERVICE_UPDATE_LISTENER,
            ICLIENT_TOKEN,
            ISCREEN_MODE_SETTINGS,
            IHDMI_SIGNAL_INTERFACE,
            IHDMI_SIGNAL_INFO_LISTENER,
            IAUDIO_SIGNAL_INFO,
            IANALOG_AUDIO_INFO,
            IAUDIO_SIGNAL_INFO_LISTENER,
            IVIDEO_SIGNAL_INFO,
            IVIDEO_SIGNAL_INFO_LISTENER,
            ISERVICE_LIST_EDIT,
            ISERVICE_LIST_EDIT_LISTENER,
            ISERVICE_LIST,
            ISERVICE_LIST_TRANSFER_INTERFACE,
            ISERVICE_LIST_EXPORT_SESSION,
            ISERVICE_LIST_EXPORT_LISTENER,
            ISERVICE_LIST_IMPORT_SESSION,
            ISERVICE_LIST_IMPORT_LISTENER,
            ISERVICE_LIST_SET_CHANNEL_LIST_SESSION,
            ISERVICE_LIST_SET_CHANNEL_LIST_LISTENER,
            ICHANNEL_LIST_TRANSFER,
            IRECORDED_CONTENTS,
            IDELETE_RECORDED_CONTENTS_CALLBACK,
            IGET_INFO_RECORDED_CONTENTS_CALLBACK,
            IEVENT_MONITOR,
            IEVENT_MONITOR_LISTENER,
            IEVENT_DOWNLOAD,
            IEVENT_DOWNLOAD_LISTENER,
            IEVENT_DOWNLOAD_SESSION,
            IANALOG_ATTRIBUTE_INTERFACE,
            ICHANNEL_TUNED_INTERFACE,
            ICHANNEL_TUNED_LISTENER,
            ITUNER_FRONTEND_SIGNAL_INFO_INTERFACE,
            ITUNER_FRONTEND_SIGNAL_INFO_LISTENER,
            IMUX_TUNE_SESSION,
            IMUX_TUNE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandardizedExtensionName {}
    /**
     * Interface responsible for creating scan session and obtaining related parameters.
     */
    public static final String ISCAN_INTERFACE = SCAN_PACKAGE + "IScanInterface";
    /**
     * Interface that handles scan session and get/store related information.
     */
    public static final String ISCAN_SESSION = SCAN_PACKAGE + "IScanSession";
    /**
     * Interface that notifies changes related to a scan session.
     */
    public static final String ISCAN_LISTENER = SCAN_PACKAGE + "IScanListener";
    /**
     * Interface for setting HDPlus information.
     */
    public static final String IHDPLUS_INFO = SCAN_PACKAGE + "IHDPlusInfo";
    /**
     * Interface for handling operator detection for scanning.
     */
    public static final String IOPERATOR_DETECTION = SCAN_PACKAGE + "IOperatorDetection";
    /**
     * Interface for notifying changes related to operator detection searches.
     */
    public static final String IOPERATOR_DETECTION_LISTENER = SCAN_PACKAGE
            + "IOperatorDetectionListener";
    /**
     * Interface for handling region channel list for scanning.
     */
    public static final String IREGION_CHANNEL_LIST = SCAN_PACKAGE + "IRegionChannelList";
    /**
     * Interface for notifying changes related to changes in region channel list search.
     */
    public static final String IREGION_CHANNEL_LIST_LISTENER = SCAN_PACKAGE
            + "IRegionChannelListListener";
    /**
     * Interface for handling target region information.
     */
    public static final String ITARGET_REGION = SCAN_PACKAGE + "ITargetRegion";
    /**
     * Interface for detecting changes related to target regions.
     */
    public static final String ITARGET_REGION_LISTENER = SCAN_PACKAGE + "ITargetRegionListener";
    /**
     * Interface for handling logical channel number conflict groups.
     */
    public static final String ILCN_CONFLICT = SCAN_PACKAGE + "ILcnConflict";
    /**
     * Interface for notifying changes in handling logical channel number conflicts.
     */
    public static final String ILCN_CONFLICT_LISTENER = SCAN_PACKAGE + "ILcnConflictListener";
    /**
     * Interface for handling the updated standard for assigning logical channel numbers.
     */
    public static final String ILCNV2_CHANNEL_LIST = SCAN_PACKAGE + "ILcnV2ChannelList";
    /**
     * Interface for notifying changes in assigning logical channel numbers with updated standard.
     */
    public static final String ILCNV2_CHANNEL_LIST_LISTENER = SCAN_PACKAGE
            + "ILcnV2ChannelListListener";
    /**
     * Interface for handling favorite network related information.
     */
    public static final String IFAVORITE_NETWORK = SCAN_PACKAGE + "IFavoriteNetwork";
    /**
     * Interface for notifying changes favorite network during scanning.
     */
    public static final String IFAVORITE_NETWORK_LISTENER = SCAN_PACKAGE
            + "IFavoriteNetworkListener";
    /**
     * Interface for handling Turksat(TKGS) channel update system service.
     */
    public static final String ITKGS_INFO = SCAN_PACKAGE + "ITkgsInfo";
    /**
     * Interface for notifying changes related to Turksat(TKGS) information.
     */
    public static final String ITKGS_INFO_LISTENER = SCAN_PACKAGE + "ITkgsInfoListener";
    /**
     * Interface for satellite search related to low noise block downconverter.
     */
    public static final String ISCAN_SAT_SEARCH = SCAN_PACKAGE + "IScanSatSearch";
    /**
     * Interface for Over-the-Air Download.
     */
    public static final String IOAD_UPDATE_INTERFACE = OAD_PACKAGE + "IOadUpdateInterface";
    /**
     * Interface for handling conditional access module app related information.
     */
    public static final String ICAM_APP_INFO_SERVICE = CAM_PACKAGE + "ICamAppInfoService";
    /**
     * Interface for notifying changes on conditional access module app related information.
     */
    public static final String ICAM_APP_INFO_LISTENER = CAM_PACKAGE + "ICamAppInfoListener";
    /**
     * Interface for handling conditional access module related information.
     */
    public static final String ICAM_MONITORING_SERVICE = CAM_PACKAGE + "ICamMonitoringService";
    /**
     * Interface for notifying changes on conditional access module related information.
     */
    public static final String ICAM_INFO_LISTENER = CAM_PACKAGE + "ICamInfoListener";
    /**
     * Interface for handling control of common interface plus operations.
     */
    public static final String ICI_OPERATOR_INTERFACE = CAM_PACKAGE + "ICiOperatorInterface";
    /**
     * Interfaces for notifying changes on common interface plus operations.
     */
    public static final String ICI_OPERATOR_LISTENER = CAM_PACKAGE + "ICiOperatorListener";
    /**
     * Interface for handling conditional access module profile related information.
     */
    public static final String ICAM_PROFILE_INTERFACE = CAM_PACKAGE + "ICamProfileInterface";
    /**
     * Interface for handling conditional access module digital rights management (DRM)
     * related information.
     */
    public static final String ICONTENT_CONTROL_SERVICE = CAM_PACKAGE + "IContentControlService";
    /**
     * Interface for notifying changes on digital rights management (DRM).
     */
    public static final String ICAM_DRM_INFO_LISTENER = CAM_PACKAGE + "ICamDrmInfoListener";
    /**
     * Interface for handling conditional access module pin related information.
     */
    public static final String ICAM_PIN_SERVICE = CAM_PACKAGE + "ICamPinService";
    /**
     * Interface for notifying changes on conditional access module pin capability.
     */
    public static final String ICAM_PIN_CAPABILITY_LISTENER = CAM_PACKAGE
            + "ICamPinCapabilityListener";
    /**
     * Interface for notifying changes on conditional access module pin status.
     */
    public static final String ICAM_PIN_STATUS_LISTENER = CAM_PACKAGE + "ICamPinStatusListener";
    /**
     * Interface for handling conditional access module host control service.
     */
    public static final String ICAM_HOST_CONTROL_SERVICE = CAM_PACKAGE + "ICamHostControlService";
    /**
     * Interface for handling conditional access module ask release reply.
     */
    public static final String ICAM_HOST_CONTROL_ASK_RELEASE_REPLY_CALLBACK = CAM_PACKAGE
            + "ICamHostControlAskReleaseReplyCallback";
    /**
     * Interface for notifying changes on conditional access module host control service.
     */
    public static final String ICAM_HOST_CONTROL_INFO_LISTENER = CAM_PACKAGE
            + "ICamHostControlInfoListener";
    /**
     * Interface for handling conditional access module host control service tune_quietly_flag.
     */
    public static final String ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG = CAM_PACKAGE
            + "ICamHostControlTuneQuietlyFlag";
    /**
     * Interface for notifying changes on conditional access module host control service
     * tune_quietly_flag.
     */
    public static final String ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG_LISTENER = CAM_PACKAGE
            + "ICamHostControlTuneQuietlyFlagListener";
    /**
     * Interface for handling conditional access module multi-media interface.
     */
    public static final String IMMI_INTERFACE = CAM_PACKAGE + "IMmiInterface";
    /**
     * Interface for controlling conditional access module multi-media session.
     */
    public static final String IMMI_SESSION = CAM_PACKAGE + "IMmiSession";
    /**
     * Interface for notifying changes on conditional access module multi-media session status.
     */
    public static final String IMMI_STATUS_CALLBACK = CAM_PACKAGE + "IMmiStatusCallback";
    /**
     * Interface for notifying changes on conditional access app info related to entering menu.
     */
    public static final String IENTER_MENU_ERROR_CALLBACK = CAM_PACKAGE + "IEnterMenuErrorCallback";
    /**
     * Interface for handling Region Rating Table downloadable rating data.
     */
    public static final String IDOWNLOADABLE_RATING_TABLE_MONITOR = RATING_PACKAGE
            + "IDownloadableRatingTableMonitor";
    /**
     * Interface for handling Region Rating Table rating system related information.
     */
    public static final String IRATING_INTERFACE = RATING_PACKAGE + "IRatingInterface";
    /**
     * Interface for handling Program Map Table rating related information.
     */
    public static final String IPMT_RATING_INTERFACE = RATING_PACKAGE + "IPmtRatingInterface";
    /**
     * Interface for notifying changes on Program Map Table rating related information.
     */
    public static final String IPMT_RATING_LISTENER = RATING_PACKAGE + "IPmtRatingListener";
    /**
     * Interface for handling Vertical Blanking Interval rating related information.
     */
    public static final String IVBI_RATING_INTERFACE = RATING_PACKAGE + "IVbiRatingInterface";
    /**
     * Interface for notifying changes on Vertical Blanking Interval rating related information.
     */
    public static final String IVBI_RATING_LISTENER = RATING_PACKAGE + "IVbiRatingListener";
    /**
     * Interface for handling program rating related information.
     */
    public static final String IPROGRAM_INFO = RATING_PACKAGE + "IProgramInfo";
    /**
     * Interface for notifying changes on program rating related information.
     */
    public static final String IPROGRAM_INFO_LISTENER = RATING_PACKAGE + "IProgramInfoListener";
    /**
     * Interface for getting broadcast time related information.
     */
    public static final String IBROADCAST_TIME = TIME_PACKAGE + "IBroadcastTime";
    /**
     * Interface for handling data service signal information on teletext.
     */
    public static final String IDATA_SERVICE_SIGNAL_INFO = TELETEXT_PACKAGE
            + "IDataServiceSignalInfo";
    /**
     * Interface for notifying changes on data service signal information on teletext.
     */
    public static final String IDATA_SERVICE_SIGNAL_INFO_LISTENER = TELETEXT_PACKAGE
            + "IDataServiceSignalInfoListener";
    /**
     * Interface for handling teletext page information.
     */
    public static final String ITELETEXT_PAGE_SUB_CODE = TELETEXT_PACKAGE + "ITeletextPageSubCode";
    /**
     * Interface for handling scan background service update.
     */
    public static final String ISCAN_BACKGROUND_SERVICE_UPDATE = SCAN_BSU_PACKAGE
            + "IScanBackgroundServiceUpdate";
    /**
     * Interface for notifying changes on background service update
     */
    public static final String ISCAN_BACKGROUND_SERVICE_UPDATE_LISTENER = SCAN_BSU_PACKAGE
            + "IScanBackgroundServiceUpdateListener";
    /**
     * Interface for generating client token.
     */
    public static final String ICLIENT_TOKEN = CLIENT_TOKEN_PACKAGE + "IClientToken";
    /**
     * Interfaces for handling screen mode information.
     */
    public static final String ISCREEN_MODE_SETTINGS = SCREEN_MODE_PACKAGE + "IScreenModeSettings";
    /**
     * Interfaces for handling HDMI signal information update.
     */
    public static final String IHDMI_SIGNAL_INTERFACE = SIGNAL_PACKAGE + "IHdmiSignalInterface";
    /**
     * Interfaces for notifying changes on HDMI signal information update.
     */
    public static final String IHDMI_SIGNAL_INFO_LISTENER = SIGNAL_PACKAGE
            + "IHdmiSignalInfoListener";
    /**
     * Interfaces for handling audio signal information update.
     */
    public static final String IAUDIO_SIGNAL_INFO = SIGNAL_PACKAGE + "IAudioSignalInfo";
    /**
     * Interfaces for handling analog audio signal information update.
     */
    public static final String IANALOG_AUDIO_INFO = SIGNAL_PACKAGE + "IAnalogAudioInfo";
    /**
     * Interfaces for notifying changes on audio signal information update.
     */
    public static final String IAUDIO_SIGNAL_INFO_LISTENER = SIGNAL_PACKAGE
            + "IAudioSignalInfoListener";
    /**
     * Interfaces for handling video signal information update.
     */
    public static final String IVIDEO_SIGNAL_INFO = SIGNAL_PACKAGE + "IVideoSignalInfo";
    /**
     * Interfaces for notifying changes on video signal information update.
     */
    public static final String IVIDEO_SIGNAL_INFO_LISTENER = SIGNAL_PACKAGE
            + "IVideoSignalInfoListener";
    /**
     * Interfaces for handling service database updates.
     */
    public static final String ISERVICE_LIST_EDIT = SERVICE_DATABASE_PACKAGE + "IServiceListEdit";
    /**
     * Interfaces for notifying changes on service database updates.
     */
    public static final String ISERVICE_LIST_EDIT_LISTENER = SERVICE_DATABASE_PACKAGE
            + "IServiceListEditListener";
    /**
     * Interfaces for getting service database related information.
     */
    public static final String ISERVICE_LIST = SERVICE_DATABASE_PACKAGE + "IServiceList";
    /**
     * Interfaces for transferring service database related information.
     */
    public static final String ISERVICE_LIST_TRANSFER_INTERFACE = SERVICE_DATABASE_PACKAGE
            + "IServiceListTransferInterface";
    /**
     * Interfaces for exporting service database session.
     */
    public static final String ISERVICE_LIST_EXPORT_SESSION = SERVICE_DATABASE_PACKAGE
            + "IServiceListExportSession";
    /**
     * Interfaces for notifying changes on exporting service database session.
     */
    public static final String ISERVICE_LIST_EXPORT_LISTENER = SERVICE_DATABASE_PACKAGE
            + "IServiceListExportListener";
    /**
     * Interfaces for importing service database session.
     */
    public static final String ISERVICE_LIST_IMPORT_SESSION = SERVICE_DATABASE_PACKAGE
            + "IServiceListImportSession";
    /**
     * Interfaces for notifying changes on importing service database session.
     */
    public static final String ISERVICE_LIST_IMPORT_LISTENER = SERVICE_DATABASE_PACKAGE
            + "IServiceListImportListener";
    /**
     * Interfaces for setting channel list resources.
     */
    public static final String ISERVICE_LIST_SET_CHANNEL_LIST_SESSION = SERVICE_DATABASE_PACKAGE
            + "IServiceListSetChannelListSession";
    /**
     * Interfaces for notifying changes on setting channel list resources.
     */
    public static final String ISERVICE_LIST_SET_CHANNEL_LIST_LISTENER = SERVICE_DATABASE_PACKAGE
            + "IServiceListSetChannelListListener";
    /**
     * Interfaces for transferring channel list resources.
     */
    public static final String ICHANNEL_LIST_TRANSFER = SERVICE_DATABASE_PACKAGE
            + "IChannelListTransfer";
    /**
     * Interface for operations related to recorded contents.
     */
    public static final String IRECORDED_CONTENTS = PVR_PACKAGE + "IRecordedContents";
    /**
     * Interfaces for notifying changes on deleting record contents.
     */
    public static final String IDELETE_RECORDED_CONTENTS_CALLBACK = PVR_PACKAGE
            + "IDeleteRecordedContentsCallback";
    /**
     * Interfaces for notifying changes on getting record contents.
     */
    public static final String IGET_INFO_RECORDED_CONTENTS_CALLBACK = PVR_PACKAGE
            + "IGetInfoRecordedContentsCallback";
    /**
     * Interfaces for monitoring present event information.
     */
    public static final String IEVENT_MONITOR = EVENT_PACKAGE + "IEventMonitor";
    /**
     * Interfaces for notifying changes on present event information.
     */
    public static final String IEVENT_MONITOR_LISTENER = EVENT_PACKAGE + "IEventMonitorListener";
    /**
     * Interfaces for handling download event information.
     */
    public static final String IEVENT_DOWNLOAD = EVENT_PACKAGE + "IEventDownload";
    /**
     * Interfaces for notifying changes on downloading event information.
     */
    public static final String IEVENT_DOWNLOAD_LISTENER = EVENT_PACKAGE + "IEventDownloadListener";
    /**
     * Interfaces for handling download event information for Digital Video Broadcast
     * and Digital Terrestrial Multimedia Broadcast.
     */
    public static final String IEVENT_DOWNLOAD_SESSION = EVENT_PACKAGE + "IEventDownloadSession";
    /**
     * Interfaces for handling analog color system.
     */
    public static final String IANALOG_ATTRIBUTE_INTERFACE = ANALOG_PACKAGE
            + "IAnalogAttributeInterface";
    /**
     * Interfaces for monitoring channel tuned information.
     */
    public static final String ICHANNEL_TUNED_INTERFACE = TUNE_PACKAGE + "IChannelTunedInterface";
    /**
     * Interfaces for notifying changes on channel tuned information.
     */
    public static final String ICHANNEL_TUNED_LISTENER = TUNE_PACKAGE + "IChannelTunedListener";
    /**
     * Interfaces for handling tuner frontend signal info.
     */
    public static final String ITUNER_FRONTEND_SIGNAL_INFO_INTERFACE = SIGNAL_PACKAGE
            + "ITunerFrontendSignalInfoInterface";
    /**
     * Interfaces for notifying changes on tuner frontend signal info.
     */
    public static final String ITUNER_FRONTEND_SIGNAL_INFO_LISTENER = SIGNAL_PACKAGE
            + "ITunerFrontendSignalInfoListener";
    /**
     * Interfaces for handling mux tune operations.
     */
    public static final String IMUX_TUNE_SESSION = TUNE_PACKAGE + "IMuxTuneSession";
    /**
     * Interfaces for initing mux tune session.
     */
    public static final String IMUX_TUNE = TUNE_PACKAGE + "IMuxTune";

    // Set of standardized AIDL interface canonical names
    private static final Set<String> sTisExtensions = new HashSet<>(Set.of(
            ISCAN_INTERFACE,
            ISCAN_SESSION,
            ISCAN_LISTENER,
            IHDPLUS_INFO,
            IOPERATOR_DETECTION,
            IOPERATOR_DETECTION_LISTENER,
            IREGION_CHANNEL_LIST,
            IREGION_CHANNEL_LIST_LISTENER,
            ITARGET_REGION,
            ITARGET_REGION_LISTENER,
            ILCN_CONFLICT,
            ILCN_CONFLICT_LISTENER,
            ILCNV2_CHANNEL_LIST,
            ILCNV2_CHANNEL_LIST_LISTENER,
            IFAVORITE_NETWORK,
            IFAVORITE_NETWORK_LISTENER,
            ITKGS_INFO,
            ITKGS_INFO_LISTENER,
            ISCAN_SAT_SEARCH,
            IOAD_UPDATE_INTERFACE,
            ICAM_APP_INFO_SERVICE,
            ICAM_APP_INFO_LISTENER,
            ICAM_MONITORING_SERVICE,
            ICAM_INFO_LISTENER,
            ICI_OPERATOR_INTERFACE,
            ICI_OPERATOR_LISTENER,
            ICAM_PROFILE_INTERFACE,
            ICONTENT_CONTROL_SERVICE,
            ICAM_DRM_INFO_LISTENER,
            ICAM_PIN_SERVICE,
            ICAM_PIN_CAPABILITY_LISTENER,
            ICAM_PIN_STATUS_LISTENER,
            ICAM_HOST_CONTROL_SERVICE,
            ICAM_HOST_CONTROL_ASK_RELEASE_REPLY_CALLBACK,
            ICAM_HOST_CONTROL_INFO_LISTENER,
            ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG,
            ICAM_HOST_CONTROL_TUNE_QUIETLY_FLAG_LISTENER,
            IMMI_INTERFACE,
            IMMI_SESSION,
            IMMI_STATUS_CALLBACK,
            IENTER_MENU_ERROR_CALLBACK,
            IDOWNLOADABLE_RATING_TABLE_MONITOR,
            IRATING_INTERFACE,
            IPMT_RATING_INTERFACE,
            IPMT_RATING_LISTENER,
            IVBI_RATING_INTERFACE,
            IVBI_RATING_LISTENER,
            IPROGRAM_INFO,
            IPROGRAM_INFO_LISTENER,
            IBROADCAST_TIME,
            IDATA_SERVICE_SIGNAL_INFO,
            IDATA_SERVICE_SIGNAL_INFO_LISTENER,
            ITELETEXT_PAGE_SUB_CODE,
            ISCAN_BACKGROUND_SERVICE_UPDATE,
            ISCAN_BACKGROUND_SERVICE_UPDATE_LISTENER,
            ICLIENT_TOKEN,
            ISCREEN_MODE_SETTINGS,
            IHDMI_SIGNAL_INTERFACE,
            IHDMI_SIGNAL_INFO_LISTENER,
            IAUDIO_SIGNAL_INFO,
            IANALOG_AUDIO_INFO,
            IAUDIO_SIGNAL_INFO_LISTENER,
            IVIDEO_SIGNAL_INFO,
            IVIDEO_SIGNAL_INFO_LISTENER,
            ISERVICE_LIST_EDIT,
            ISERVICE_LIST_EDIT_LISTENER,
            ISERVICE_LIST,
            ISERVICE_LIST_TRANSFER_INTERFACE,
            ISERVICE_LIST_EXPORT_SESSION,
            ISERVICE_LIST_EXPORT_LISTENER,
            ISERVICE_LIST_IMPORT_SESSION,
            ISERVICE_LIST_IMPORT_LISTENER,
            ISERVICE_LIST_SET_CHANNEL_LIST_SESSION,
            ISERVICE_LIST_SET_CHANNEL_LIST_LISTENER,
            ICHANNEL_LIST_TRANSFER,
            IRECORDED_CONTENTS,
            IDELETE_RECORDED_CONTENTS_CALLBACK,
            IGET_INFO_RECORDED_CONTENTS_CALLBACK,
            IEVENT_MONITOR,
            IEVENT_MONITOR_LISTENER,
            IEVENT_DOWNLOAD,
            IEVENT_DOWNLOAD_LISTENER,
            IEVENT_DOWNLOAD_SESSION,
            IANALOG_ATTRIBUTE_INTERFACE,
            ICHANNEL_TUNED_INTERFACE,
            ICHANNEL_TUNED_LISTENER,
            ITUNER_FRONTEND_SIGNAL_INFO_INTERFACE,
            ITUNER_FRONTEND_SIGNAL_INFO_LISTENER,
            IMUX_TUNE_SESSION,
            IMUX_TUNE
    ));

    // Store the mapping between interface names and IBinder
    private Map<String, IBinder> mExtensionInterfaceIBinderMapping = new HashMap<>();

    TvInputServiceExtensionManager() {
    }

    /**
     * Function to return available extension interface names
     *
     * @hide
     */
    public static @NonNull List<String> getStandardExtensionInterfaceNames() {
        return new ArrayList<>(sTisExtensions);
    }

    /**
     * Function to check if the extension is in the standardization list
     */
    static boolean checkIsStandardizedInterfaces(@NonNull String extensionName) {
        return sTisExtensions.contains(extensionName);
    }

    /**
     * Registers IBinder objects that implement standardized AIDL interfaces.
     * <p>This function should be used by SoCs/OEMs
     *
     * @param extensionName Extension Interface Name
     * @param binder        IBinder object to be registered
     * @return {@link #REGISTER_SUCCESS} on success of registering IBinder object
     *         {@link #REGISTER_FAIL_NAME_NOT_STANDARDIZED} on failure due to registering extension
     *              with non-standardized name
     *         {@link #REGISTER_FAIL_IMPLEMENTATION_NOT_STANDARDIZED} on failure due to IBinder not
     *              implementing standardized AIDL interface
     *         {@link #REGISTER_FAIL_REMOTE_EXCEPTION} on failure due to remote exception
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    @RegisterResult
    public int registerExtensionIBinder(@StandardizedExtensionName @NonNull String extensionName,
            @NonNull IBinder binder) {
        if (!checkIsStandardizedInterfaces(extensionName)) {
            return REGISTER_FAIL_NAME_NOT_STANDARDIZED;
        }
        try {
            if (binder.getInterfaceDescriptor().equals(extensionName)) {
                mExtensionInterfaceIBinderMapping.put(extensionName, binder);
                return REGISTER_SUCCESS;
            } else {
                return REGISTER_FAIL_IMPLEMENTATION_NOT_STANDARDIZED;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Fetching IBinder object failure due to " + e);
            return REGISTER_FAIL_REMOTE_EXCEPTION;
        }
    }

    /**
     * Function to get corresponding IBinder object
     */
    @Nullable IBinder getExtensionIBinder(@NonNull String extensionName) {
        return mExtensionInterfaceIBinderMapping.get(extensionName);
    }

}
