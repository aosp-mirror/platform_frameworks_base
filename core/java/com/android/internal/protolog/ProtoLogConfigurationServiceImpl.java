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

package com.android.internal.protolog;

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.TAG;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LEVEL;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LOCATION;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.util.Log;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ProtoLog service is responsible for orchestrating centralized actions of the protolog tracing
 * system. Currently this service has the following roles:
 * - Handle shell commands to toggle logging ProtoLog messages for specified groups to logcat.
 * - Handle viewer config dumping (the mapping from message hash to message string) for all protolog
 *   clients. This is for two reasons: firstly, because client processes might be frozen so might
 *   not response to the request to dump their viewer config when the trace is stopped; secondly,
 *   multiple processes might be running the same code with the same viewer config, this centralized
 *   service ensures we don't dump the same viewer config multiple times across processes.
 * <p>
 * {@link com.android.internal.protolog.IProtoLogClient ProtoLog clients} register themselves to
 * this service on initialization.
 * <p>
 * This service is intended to run on the system server, such that it never gets frozen.
 */
@SystemService(Context.PROTOLOG_CONFIGURATION_SERVICE)
public class ProtoLogConfigurationServiceImpl extends IProtoLogConfigurationService.Stub
        implements ProtoLogConfigurationService {
    private static final String LOG_TAG = "ProtoLogConfigurationService";

    private final ProtoLogDataSource mDataSource;

    /**
     * Keeps track of how many of each viewer config file is currently registered.
     * Use to keep track of which viewer config files are actively being used in tracing and might
     * need to be dumped on flush.
     */
    private final Map<String, Integer> mConfigFileCounts = new HashMap<>();
    /**
     * Keeps track of the viewer config file of each client if available.
     */
    private final Map<IProtoLogClient, String> mClientConfigFiles = new HashMap<>();

    /**
     * Keeps track of all the protolog groups that have been registered by clients and are still
     * being actively traced.
     */
    private final Set<String> mRegisteredGroups = new HashSet<>();
    /**
     * Keeps track of all the clients that are actively tracing a given protolog group.
     */
    private final Map<String, Set<IProtoLogClient>> mGroupToClients = new HashMap<>();

    /**
     * Keeps track of whether or not a given group should be logged to logcat.
     * True when logging to logcat, false otherwise.
     */
    private final Map<String, Boolean> mLogGroupToLogcatStatus = new TreeMap<>();

    /**
     * Keeps track of all the tracing instance ids that are actively running for ProtoLog.
     */
    private final Set<Integer> mRunningInstances = new HashSet<>();

    private final ViewerConfigFileTracer mViewerConfigFileTracer;

    public ProtoLogConfigurationServiceImpl() {
        this(ProtoLogDataSource::new, ProtoLogConfigurationServiceImpl::dumpTransitionTraceConfig);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(@NonNull ProtoLogDataSourceBuilder dataSourceBuilder) {
        this(dataSourceBuilder, ProtoLogConfigurationServiceImpl::dumpTransitionTraceConfig);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(@NonNull ViewerConfigFileTracer tracer) {
        this(ProtoLogDataSource::new, tracer);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(
            @NonNull ProtoLogDataSourceBuilder dataSourceBuilder,
            @NonNull ViewerConfigFileTracer tracer) {
        mDataSource = dataSourceBuilder.build(
            this::onTracingInstanceStart,
            this::onTracingInstanceFlush,
            this::onTracingInstanceStop
        );

        // Initialize the Perfetto producer and register the Perfetto ProtoLog datasource to be
        // receive the lifecycle callbacks of the datasource and write the viewer configs if and
        // when required to the datasource.
        Producer.init(InitArguments.DEFAULTS);
        final var params = new DataSourceParams.Builder()
                .setBufferExhaustedPolicy(DataSourceParams.PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP)
                .build();
        mDataSource.register(params);

        mViewerConfigFileTracer = tracer;
    }

    public static class RegisterClientArgs extends IRegisterClientArgs.Stub {
        /**
         * The viewer config file to be registered for this client ProtoLog process.
         */
        @Nullable
        private String mViewerConfigFile = null;
        /**
         * The list of all groups that this client protolog process supports and might trace.
         */
        @NonNull
        private String[] mGroups = new String[0];
        /**
         * The default logcat status of the ProtoLog client. True is logging to logcat, false
         * otherwise. The indices should match the indices in {@link mGroups}.
         */
        @NonNull
        private boolean[] mLogcatStatus = new boolean[0];

        public record GroupConfig(@NonNull String group, boolean logToLogcat) {}

        /**
         * Specify groups to register with this client that will be used for protologging in this
         * process.
         * @param groups to register with this client.
         * @return self
         */
        public RegisterClientArgs setGroups(GroupConfig... groups) {
            mGroups = new String[groups.length];
            mLogcatStatus = new boolean[groups.length];

            for (int i = 0; i < groups.length; i++) {
                mGroups[i] = groups[i].group;
                mLogcatStatus[i] = groups[i].logToLogcat;
            }

            return this;
        }

        /**
         * Set the viewer config file that the logs in this process are using.
         * @param viewerConfigFile The file path of the viewer config.
         * @return self
         */
        public RegisterClientArgs setViewerConfigFile(@NonNull String viewerConfigFile) {
            mViewerConfigFile = viewerConfigFile;

            return this;
        }

        @Override
        @NonNull
        public String[] getGroups() {
            return mGroups;
        }

        @Override
        @NonNull
        public boolean[] getGroupsDefaultLogcatStatus() {
            return mLogcatStatus;
        }

        @Nullable
        @Override
        public String getViewerConfigFile() {
            return mViewerConfigFile;
        }
    }

    @FunctionalInterface
    public interface ViewerConfigFileTracer {
        /**
         * Write the viewer config data to the trace buffer.
         *
         * @param dataSource The target datasource to write the viewer config to.
         * @param viewerConfigFilePath The path of the viewer config file which contains the data we
         *                             want to write to the trace buffer.
         * @throws FileNotFoundException if the viewerConfigFilePath is invalid.
         */
        void trace(@NonNull ProtoLogDataSource dataSource, @NonNull String viewerConfigFilePath);
    }

    @Override
    public void registerClient(@NonNull IProtoLogClient client, @NonNull IRegisterClientArgs args)
            throws RemoteException {
        client.asBinder().linkToDeath(() -> onClientBinderDeath(client), /* flags */ 0);

        final String viewerConfigFile = args.getViewerConfigFile();
        if (viewerConfigFile != null) {
            registerViewerConfigFile(client, viewerConfigFile);
        }

        registerGroups(client, args.getGroups(), args.getGroupsDefaultLogcatStatus());
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        new ProtoLogCommandHandler(this)
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    /**
     * Get the list of groups clients have registered to the protolog service.
     * @return The list of ProtoLog groups registered with this service.
     */
    @Override
    @NonNull
    public String[] getGroups() {
        return mRegisteredGroups.toArray(new String[0]);
    }

    /**
     * Enable logging target groups to logcat.
     * @param groups we want to enable logging them to logcat for.
     */
    @Override
    public void enableProtoLogToLogcat(@NonNull String... groups) {
        toggleProtoLogToLogcat(true, groups);
    }

    /**
     * Disable logging target groups to logcat.
     * @param groups we want to disable from being logged to logcat.
     */
    @Override
    public void disableProtoLogToLogcat(@NonNull String... groups) {
        toggleProtoLogToLogcat(false, groups);
    }

    /**
     * Check if a group is logging to logcat
     * @param group The group we want to check for
     * @return True iff we are logging this group to logcat.
     */
    @Override
    public boolean isLoggingToLogcat(@NonNull String group) {
        final Boolean isLoggingToLogcat = mLogGroupToLogcatStatus.get(group);

        if (isLoggingToLogcat == null) {
            throw new RuntimeException(
                    "Trying to get logcat logging status of non-registered group " + group);
        }

        return isLoggingToLogcat;
    }

    private void registerViewerConfigFile(
            @NonNull IProtoLogClient client, @NonNull String viewerConfigFile) {
        final var count = mConfigFileCounts.getOrDefault(viewerConfigFile, 0);
        mConfigFileCounts.put(viewerConfigFile, count + 1);
        mClientConfigFiles.put(client, viewerConfigFile);
    }

    private void registerGroups(@NonNull IProtoLogClient client, @NonNull String[] groups,
            @NonNull boolean[] logcatStatuses) throws RemoteException {
        if (groups.length != logcatStatuses.length) {
            throw new RuntimeException(
                    "Expected groups and logcatStatuses to have the same length, "
                        + "but groups has length " + groups.length
                        + " and logcatStatuses has length " + logcatStatuses.length);
        }

        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            boolean logcatStatus = logcatStatuses[i];

            mRegisteredGroups.add(group);

            mGroupToClients.putIfAbsent(group, new HashSet<>());
            mGroupToClients.get(group).add(client);

            if (!mLogGroupToLogcatStatus.containsKey(group)) {
                mLogGroupToLogcatStatus.put(group, logcatStatus);
            }

            boolean requestedLogToLogcat = mLogGroupToLogcatStatus.get(group);
            if (requestedLogToLogcat != logcatStatus) {
                client.toggleLogcat(requestedLogToLogcat, new String[] { group });
            }
        }
    }

    private void toggleProtoLogToLogcat(boolean enabled, @NonNull String[] groups) {
        final var clientToGroups = new HashMap<IProtoLogClient, Set<String>>();

        for (String group : groups) {
            final var clients = mGroupToClients.get(group);

            if (clients == null) {
                // No clients associated to this group
                Log.w(LOG_TAG, "Attempting to toggle log to logcat for group " + group
                        + " with no registered clients.");
                continue;
            }

            for (IProtoLogClient client : clients) {
                clientToGroups.putIfAbsent(client, new HashSet<>());
                clientToGroups.get(client).add(group);
            }
        }

        for (IProtoLogClient client : clientToGroups.keySet()) {
            try {
                client.toggleLogcat(enabled, clientToGroups.get(client).toArray(new String[0]));
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "Failed to toggle logcat status for groups on client", e);
            }
        }

        for (String group : groups) {
            mLogGroupToLogcatStatus.put(group, enabled);
        }
    }

    private void onTracingInstanceStart(int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        mRunningInstances.add(instanceIdx);
    }

    private void onTracingInstanceFlush() {
        for (String fileName : mConfigFileCounts.keySet()) {
            mViewerConfigFileTracer.trace(mDataSource, fileName);
        }
    }

    private void onTracingInstanceStop(int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        mRunningInstances.remove(instanceIdx);
    }

    private static void dumpTransitionTraceConfig(@NonNull ProtoLogDataSource dataSource,
            @NonNull String viewerConfigFilePath) {
        Utils.dumpViewerConfig(dataSource, () -> {
            try {
                return new ProtoInputStream(new FileInputStream(viewerConfigFilePath));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(
                        "Failed to load viewer config file " + viewerConfigFilePath, e);
            }
        });
    }

    private void onClientBinderDeath(@NonNull IProtoLogClient client) {
        // Dump the tracing config now if no other client is going to dump the same config file.
        String configFile = mClientConfigFiles.get(client);
        if (configFile != null) {
            final var newCount = mConfigFileCounts.get(configFile) - 1;
            mConfigFileCounts.put(configFile, newCount);
            boolean lastProcessWithViewerConfig = newCount == 0;
            if (lastProcessWithViewerConfig) {
                mViewerConfigFileTracer.trace(mDataSource, configFile);
            }
        }
    }

    private static void writeViewerConfigGroup(
            @NonNull ProtoInputStream pis, @NonNull ProtoOutputStream os) throws IOException {
        final long inGroupToken = pis.start(GROUPS);
        final long outGroupToken = os.start(GROUPS);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) ID -> {
                    int id = pis.readInt(ID);
                    os.write(ID, id);
                }
                case (int) NAME -> {
                    String name = pis.readString(NAME);
                    os.write(NAME, name);
                }
                case (int) TAG -> {
                    String tag = pis.readString(TAG);
                    os.write(TAG, tag);
                }
                default ->
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inGroupToken);
        os.end(outGroupToken);
    }

    private static void writeViewerConfigMessage(
            @NonNull ProtoInputStream pis, @NonNull ProtoOutputStream os) throws IOException {
        final long inMessageToken = pis.start(MESSAGES);
        final long outMessagesToken = os.start(MESSAGES);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) MESSAGE_ID -> os.write(MESSAGE_ID,
                        pis.readLong(MESSAGE_ID));
                case (int) MESSAGE -> os.write(MESSAGE, pis.readString(MESSAGE));
                case (int) LEVEL -> os.write(LEVEL, pis.readInt(LEVEL));
                case (int) GROUP_ID -> os.write(GROUP_ID, pis.readInt(GROUP_ID));
                case (int) LOCATION -> os.write(LOCATION, pis.readString(LOCATION));
                default ->
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inMessageToken);
        os.end(outMessagesToken);
    }
}
