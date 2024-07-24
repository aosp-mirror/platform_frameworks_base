package com.android.internal.protolog;

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID;

import android.util.ArrayMap;
import android.util.proto.ProtoInputStream;

import com.android.internal.protolog.common.ILogger;

import java.io.IOException;
import java.util.Map;

public class ProtoLogViewerConfigReader {
    private final ViewerConfigInputStreamProvider mViewerConfigInputStreamProvider;
    private Map<Long, String> mLogMessageMap = null;

    public ProtoLogViewerConfigReader(
            ViewerConfigInputStreamProvider viewerConfigInputStreamProvider) {
        this.mViewerConfigInputStreamProvider = viewerConfigInputStreamProvider;
    }

    /**
     * Returns message format string for its hash or null if unavailable
     * or the viewer config is not loaded into memory.
     */
    public synchronized String getViewerString(long messageHash) {
        if (mLogMessageMap != null) {
            return mLogMessageMap.get(messageHash);
        } else {
            return null;
        }
    }

    /**
     * Loads the viewer config into memory. No-op if already loaded in memory.
     */
    public synchronized void loadViewerConfig(ILogger logger) {
        if (mLogMessageMap != null) {
            return;
        }

        try {
            doLoadViewerConfig();
            logger.log("Loaded " + mLogMessageMap.size() + " log definitions");
        } catch (IOException e) {
            logger.log("Unable to load log definitions: "
                    + "IOException while processing viewer config" + e);
        }
    }

    /**
     * Unload the viewer config from memory.
     */
    public synchronized void unloadViewerConfig() {
        mLogMessageMap = null;
    }

    private void doLoadViewerConfig() throws IOException {
        mLogMessageMap = new ArrayMap<>();
        final ProtoInputStream pis = mViewerConfigInputStreamProvider.getInputStream();

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (pis.getFieldNumber() == (int) MESSAGES) {
                final long inMessageToken = pis.start(MESSAGES);

                long messageId = 0;
                String message = null;
                while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (pis.getFieldNumber()) {
                        case (int) MESSAGE_ID:
                            messageId = pis.readLong(MESSAGE_ID);
                            break;
                        case (int) MESSAGE:
                            message = pis.readString(MESSAGE);
                            break;
                    }
                }

                if (messageId == 0) {
                    throw new IOException("Failed to get message id");
                }

                if (message == null) {
                    throw new IOException("Failed to get message string");
                }

                mLogMessageMap.put(messageId, message);

                pis.end(inMessageToken);
            }
        }
    }
}
