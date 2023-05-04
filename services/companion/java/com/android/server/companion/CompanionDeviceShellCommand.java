/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion;

import android.companion.AssociationInfo;
import android.companion.ContextSyncMessage;
import android.companion.Telecom;
import android.companion.datatransfer.PermissionSyncRequest;
import android.net.MacAddress;
import android.os.Binder;
import android.os.ShellCommand;
import android.util.proto.ProtoOutputStream;

import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncController;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;
import com.android.server.companion.transport.CompanionTransportManager;
import com.android.server.companion.transport.Transport;

import java.io.PrintWriter;
import java.util.List;

class CompanionDeviceShellCommand extends ShellCommand {
    private static final String TAG = "CDM_CompanionDeviceShellCommand";

    private final CompanionDeviceManagerService mService;
    private final AssociationStoreImpl mAssociationStore;
    private final CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private final CompanionTransportManager mTransportManager;

    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;

    CompanionDeviceShellCommand(CompanionDeviceManagerService service,
            AssociationStoreImpl associationStore,
            CompanionDevicePresenceMonitor devicePresenceMonitor,
            CompanionTransportManager transportManager,
            SystemDataTransferRequestStore systemDataTransferRequestStore,
            AssociationRequestsProcessor associationRequestsProcessor) {
        mService = service;
        mAssociationStore = associationStore;
        mDevicePresenceMonitor = devicePresenceMonitor;
        mTransportManager = transportManager;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mAssociationRequestsProcessor = associationRequestsProcessor;
    }

    @Override
    public int onCommand(String cmd) {
        final PrintWriter out = getOutPrintWriter();
        final int associationId;
        try {
            switch (cmd) {
                case "list": {
                    final int userId = getNextIntArgRequired();
                    final List<AssociationInfo> associationsForUser =
                            mAssociationStore.getAssociationsForUser(userId);
                    for (AssociationInfo association : associationsForUser) {
                        // TODO(b/212535524): use AssociationInfo.toShortString(), once it's not
                        //  longer referenced in tests.
                        out.println(association.getPackageName() + " "
                                + association.getDeviceMacAddress() + " " + association.getId());
                    }
                }
                break;

                case "associate": {
                    int userId = getNextIntArgRequired();
                    String packageName = getNextArgRequired();
                    String address = getNextArgRequired();
                    final MacAddress macAddress = MacAddress.fromString(address);
                    mService.createNewAssociation(userId, packageName, macAddress,
                            null, null, false);
                }
                break;

                case "disassociate": {
                    final int userId = getNextIntArgRequired();
                    final String packageName = getNextArgRequired();
                    final String address = getNextArgRequired();
                    final AssociationInfo association =
                            mService.getAssociationWithCallerChecks(userId, packageName, address);
                    if (association != null) {
                        mService.disassociateInternal(association.getId());
                    }
                }
                break;

                case "clear-association-memory-cache":
                    mService.persistState();
                    mService.loadAssociationsFromDisk();
                    break;

                case "simulate-device-appeared":
                    associationId = getNextIntArgRequired();
                    mDevicePresenceMonitor.simulateDeviceAppeared(associationId);
                    break;

                case "simulate-device-disappeared":
                    associationId = getNextIntArgRequired();
                    mDevicePresenceMonitor.simulateDeviceDisappeared(associationId);
                    break;

                case "remove-inactive-associations": {
                    // This command should trigger the same "clean-up" job as performed by the
                    // InactiveAssociationsRemovalService JobService. However, since the
                    // InactiveAssociationsRemovalService run as system, we want to run this
                    // as system (not as shell/root) as well.
                    Binder.withCleanCallingIdentity(
                            mService::removeInactiveSelfManagedAssociations);
                }
                break;

                case "create-emulated-transport":
                    // This command creates a RawTransport in order to test Transport listeners
                    associationId = getNextIntArgRequired();
                    mTransportManager.createEmulatedTransport(associationId);
                    break;

                case "send-context-sync-empty-message": {
                    associationId = getNextIntArgRequired();
                    mTransportManager.createEmulatedTransport(associationId)
                            .processMessage(Transport.MESSAGE_REQUEST_CONTEXT_SYNC,
                                    /* sequence= */ 0,
                                    CrossDeviceSyncController.createEmptyMessage());
                    break;
                }

                case "send-context-sync-call-create-message": {
                    associationId = getNextIntArgRequired();
                    String callId = getNextArgRequired();
                    String address = getNextArgRequired();
                    String facilitator = getNextArgRequired();
                    mTransportManager.createEmulatedTransport(associationId)
                            .processMessage(Transport.MESSAGE_REQUEST_CONTEXT_SYNC,
                                    /* sequence= */ 0,
                                    CrossDeviceSyncController.createCallCreateMessage(callId,
                                            address, facilitator));
                    break;
                }

                case "send-context-sync-call-control-message": {
                    associationId = getNextIntArgRequired();
                    String callId = getNextArgRequired();
                    int control = getNextIntArgRequired();
                    mTransportManager.createEmulatedTransport(associationId)
                            .processMessage(Transport.MESSAGE_REQUEST_CONTEXT_SYNC,
                                    /* sequence= */ 0,
                                    CrossDeviceSyncController.createCallControlMessage(callId,
                                            control));
                    break;
                }

                case "send-context-sync-call-facilitators-message": {
                    associationId = getNextIntArgRequired();
                    int numberOfFacilitators = getNextIntArgRequired();
                    final ProtoOutputStream pos = new ProtoOutputStream();
                    pos.write(ContextSyncMessage.VERSION, 1);
                    final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
                    for (int i = 0; i < numberOfFacilitators; i++) {
                        final long facilitatorsToken = pos.start(Telecom.FACILITATORS);
                        pos.write(Telecom.CallFacilitator.NAME, "Call Facilitator App #" + i);
                        pos.write(Telecom.CallFacilitator.IDENTIFIER, "com.android.test" + i);
                        pos.end(facilitatorsToken);
                    }
                    pos.end(telecomToken);
                    mTransportManager.createEmulatedTransport(associationId)
                            .processMessage(Transport.MESSAGE_REQUEST_CONTEXT_SYNC,
                                    /* sequence= */ 0, pos.getBytes());
                    break;
                }

                case "send-context-sync-call-message": {
                    associationId = getNextIntArgRequired();
                    String callId = getNextArgRequired();
                    String facilitatorId = getNextArgRequired();
                    final ProtoOutputStream pos = new ProtoOutputStream();
                    pos.write(ContextSyncMessage.VERSION, 1);
                    final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
                    final long callsToken = pos.start(Telecom.CALLS);
                    pos.write(Telecom.Call.ID, callId);
                    final long originToken = pos.start(Telecom.Call.ORIGIN);
                    pos.write(Telecom.Call.Origin.CALLER_ID, "Caller Name");
                    final long facilitatorToken = pos.start(
                            Telecom.Request.CreateAction.FACILITATOR);
                    pos.write(Telecom.CallFacilitator.NAME, "Test App Name");
                    pos.write(Telecom.CallFacilitator.IDENTIFIER, facilitatorId);
                    pos.end(facilitatorToken);
                    pos.end(originToken);
                    pos.write(Telecom.Call.STATUS, Telecom.Call.RINGING);
                    pos.write(Telecom.Call.CONTROLS, Telecom.ACCEPT);
                    pos.write(Telecom.Call.CONTROLS, Telecom.REJECT);
                    pos.end(callsToken);
                    pos.end(telecomToken);
                    mTransportManager.createEmulatedTransport(associationId)
                            .processMessage(Transport.MESSAGE_REQUEST_CONTEXT_SYNC,
                                    /* sequence= */ 0, pos.getBytes());
                    break;
                }

                case "disable-context-sync": {
                    associationId = getNextIntArgRequired();
                    int flag = getNextIntArgRequired();
                    mAssociationRequestsProcessor.disableSystemDataSync(associationId, flag);
                    break;
                }

                case "enable-context-sync": {
                    associationId = getNextIntArgRequired();
                    int flag = getNextIntArgRequired();
                    mAssociationRequestsProcessor.enableSystemDataSync(associationId, flag);
                    break;
                }

                case "allow-permission-sync": {
                    int userId = getNextIntArgRequired();
                    associationId = getNextIntArgRequired();
                    boolean enabled = getNextBooleanArgRequired();
                    PermissionSyncRequest request = new PermissionSyncRequest(associationId);
                    request.setUserId(userId);
                    request.setUserConsented(enabled);
                    mSystemDataTransferRequestStore.writeRequest(userId, request);
                }
                break;

                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Throwable e) {
            final PrintWriter errOut = getErrPrintWriter();
            errOut.println();
            errOut.println("Exception occurred while executing '" + cmd + "':");
            e.printStackTrace(errOut);
            return 1;
        }
        return 0;
    }

    private int getNextIntArgRequired() {
        return Integer.parseInt(getNextArgRequired());
    }

    private boolean getNextBooleanArgRequired() {
        String arg = getNextArgRequired();
        if ("true".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg)) {
            return Boolean.parseBoolean(arg);
        } else {
            throw new IllegalArgumentException("Expected a boolean argument but was: " + arg);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Companion Device Manager (companiondevice) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  list USER_ID");
        pw.println("      List all Associations for a user.");
        pw.println("  associate USER_ID PACKAGE MAC_ADDRESS");
        pw.println("      Create a new Association.");
        pw.println("  disassociate USER_ID PACKAGE MAC_ADDRESS");
        pw.println("      Remove an existing Association.");
        pw.println("  clear-association-memory-cache");
        pw.println("      Clear the in-memory association cache and reload all association ");
        pw.println("      information from persistent storage. USE FOR DEBUGGING PURPOSES ONLY.");
        pw.println("      USE FOR DEBUGGING AND/OR TESTING PURPOSES ONLY.");

        pw.println("  simulate-device-appeared ASSOCIATION_ID");
        pw.println("      Make CDM act as if the given companion device has appeared.");
        pw.println("      I.e. bind the associated companion application's");
        pw.println("      CompanionDeviceService(s) and trigger onDeviceAppeared() callback.");
        pw.println("      The CDM will consider the devices as present for 60 seconds and then");
        pw.println("      will act as if device disappeared, unless 'simulate-device-disappeared'");
        pw.println("      or 'simulate-device-appeared' is called again before 60 seconds run out"
                + ".");
        pw.println("      USE FOR DEBUGGING AND/OR TESTING PURPOSES ONLY.");

        pw.println("  simulate-device-disappeared ASSOCIATION_ID");
        pw.println("      Make CDM act as if the given companion device has disappeared.");
        pw.println("      I.e. unbind the associated companion application's");
        pw.println("      CompanionDeviceService(s) and trigger onDeviceDisappeared() callback.");
        pw.println("      NOTE: This will only have effect if 'simulate-device-appeared' was");
        pw.println("      invoked for the same device (same ASSOCIATION_ID) no longer than");
        pw.println("      60 seconds ago.");
        pw.println("      USE FOR DEBUGGING AND/OR TESTING PURPOSES ONLY.");

        pw.println("  remove-inactive-associations");
        pw.println("      Remove self-managed associations that have not been active ");
        pw.println("      for a long time (90 days or as configured via ");
        pw.println("      \"debug.cdm.cdmservice.cleanup_time_window\" system property). ");
        pw.println("      USE FOR DEBUGGING AND/OR TESTING PURPOSES ONLY.");

        pw.println("  create-emulated-transport <ASSOCIATION_ID>");
        pw.println("      Create an EmulatedTransport for testing purposes only");
    }
}
