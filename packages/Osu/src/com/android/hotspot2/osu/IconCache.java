package com.android.hotspot2.osu;

import android.util.Log;

import com.android.anqp.HSIconFileElement;
import com.android.anqp.IconInfo;
import com.android.hotspot2.Utils;
import com.android.hotspot2.flow.OSUInfo;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.android.anqp.Constants.ANQPElementType.HSIconFile;

public class IconCache extends Thread {
    // Preferred icon parameters
    private static final Set<String> ICON_TYPES =
            new HashSet<>(Arrays.asList("image/png", "image/jpeg"));
    private static final int ICON_WIDTH = 64;
    private static final int ICON_HEIGHT = 64;
    public static final Locale LOCALE = java.util.Locale.getDefault();

    private static final int MAX_RETRY = 3;
    private static final long REQUERY_TIME = 5000L;
    private static final long REQUERY_TIMEOUT = 120000L;

    private final OSUManager mOsuManager;
    private final Map<EssKey, Map<String, FileEntry>> mPending;
    private final Map<EssKey, Map<String, HSIconFileElement>> mCache;

    private static class EssKey {
        private final int mAnqpDomainId;
        private final long mBssid;
        private final long mHessid;
        private final String mSsid;

        private EssKey(OSUInfo osuInfo) {
            mAnqpDomainId = osuInfo.getAnqpDomID();
            mBssid = osuInfo.getBSSID();
            mHessid = osuInfo.getHESSID();
            mSsid = osuInfo.getAdvertisingSsid();
        }

        /*
         *  ANQP ID 1   ANQP ID 2
         *  0           0           BSSID equality
         *  0           X           BSSID equality
         *  Y           X           BSSID equality
         *  X           X           Then:
         *
         *  HESSID1     HESSID2
         *  0           0           compare SSIDs
         *  0           X           not equal
         *  Y           X           not equal
         *  X           X           equal
         */

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (thatObject == null || getClass() != thatObject.getClass()) {
                return false;
            }

            EssKey that = (EssKey) thatObject;
            if (mAnqpDomainId != 0 && mAnqpDomainId == that.mAnqpDomainId) {
                return mHessid == that.mHessid
                        && (mHessid != 0 || mSsid.equals(that.mSsid));
            } else {
                return mBssid == that.mBssid;
            }
        }

        @Override
        public int hashCode() {
            if (mAnqpDomainId == 0) {
                return (int) (mBssid ^ (mBssid >>> 32));
            } else if (mHessid != 0) {
                return mAnqpDomainId * 31 + (int) (mHessid ^ (mHessid >>> 32));
            } else {
                return mAnqpDomainId * 31 + mSsid.hashCode();
            }
        }

        @Override
        public String toString() {
            if (mAnqpDomainId == 0) {
                return String.format("BSS %012x", mBssid);
            } else if (mHessid != 0) {
                return String.format("ESS %012x [%d]", mBssid, mAnqpDomainId);
            } else {
                return String.format("ESS '%s' [%d]", mSsid, mAnqpDomainId);
            }
        }
    }

    private static class FileEntry {
        private final String mFileName;
        private int mRetry = 0;
        private final long mTimestamp;
        private final LinkedList<OSUInfo> mQueued;
        private final Set<Long> mBssids;

        private FileEntry(OSUInfo osuInfo, String fileName) {
            mFileName = fileName;
            mQueued = new LinkedList<>();
            mBssids = new HashSet<>();
            mQueued.addLast(osuInfo);
            mBssids.add(osuInfo.getBSSID());
            mTimestamp = System.currentTimeMillis();
        }

        private void enqueu(OSUInfo osuInfo) {
            mQueued.addLast(osuInfo);
            mBssids.add(osuInfo.getBSSID());
        }

        private int update(long bssid, HSIconFileElement iconFileElement) {
            if (!mBssids.contains(bssid)) {
                return 0;
            }
            Log.d(OSUManager.TAG, "Updating icon on " + mQueued.size() + " osus");
            for (OSUInfo osuInfo : mQueued) {
                osuInfo.setIconFileElement(iconFileElement, mFileName);
            }
            return mQueued.size();
        }

        private int getAndIncrementRetry() {
            return mRetry++;
        }

        private long getTimestamp() {
            return mTimestamp;
        }

        public String getFileName() {
            return mFileName;
        }

        private long getLastBssid() {
            return mQueued.getLast().getBSSID();
        }

        @Override
        public String toString() {
            return String.format("'%s', retry %d, age %d, BSSIDs: %s",
                    mFileName, mRetry,
                    System.currentTimeMillis() - mTimestamp, Utils.bssidsToString(mBssids));
        }
    }

    public IconCache(OSUManager osuManager) {
        mOsuManager = osuManager;
        mPending = new HashMap<>();
        mCache = new HashMap<>();
    }

    public int resolveIcons(Collection<OSUInfo> osuInfos) {
        Set<EssKey> current = new HashSet<>();
        int modCount = 0;
        for (OSUInfo osuInfo : osuInfos) {
            EssKey key = new EssKey(osuInfo);
            current.add(key);

            if (osuInfo.getIconStatus() == OSUInfo.IconStatus.NotQueried) {
                List<IconInfo> iconInfo =
                        osuInfo.getIconInfo(LOCALE, ICON_TYPES, ICON_WIDTH, ICON_HEIGHT);
                if (iconInfo.isEmpty()) {
                    osuInfo.setIconStatus(OSUInfo.IconStatus.NotAvailable);
                    continue;
                }

                String fileName = iconInfo.get(0).getFileName();
                HSIconFileElement iconFileElement = get(key, fileName);
                if (iconFileElement != null) {
                    osuInfo.setIconFileElement(iconFileElement, fileName);
                    Log.d(OSUManager.TAG, "Icon cache hit for " + osuInfo + "/" + fileName);
                    modCount++;
                } else {
                    FileEntry fileEntry = enqueue(key, fileName, osuInfo);
                    if (fileEntry != null) {
                        Log.d(OSUManager.TAG, "Initiating icon query for "
                                + osuInfo + "/" + fileName);
                        mOsuManager.doIconQuery(osuInfo.getBSSID(), fileName);
                    } else {
                        Log.d(OSUManager.TAG, "Piggybacking icon query for "
                                + osuInfo + "/" + fileName);
                    }
                }
            }
        }

        // Drop all non-current ESS's
        Iterator<EssKey> pendingKeys = mPending.keySet().iterator();
        while (pendingKeys.hasNext()) {
            EssKey key = pendingKeys.next();
            if (!current.contains(key)) {
                pendingKeys.remove();
            }
        }
        Iterator<EssKey> cacheKeys = mCache.keySet().iterator();
        while (cacheKeys.hasNext()) {
            EssKey key = cacheKeys.next();
            if (!current.contains(key)) {
                cacheKeys.remove();
            }
        }
        return modCount;
    }

    public HSIconFileElement getIcon(OSUInfo osuInfo) {
        List<IconInfo> iconInfos = osuInfo.getIconInfo(LOCALE, ICON_TYPES, ICON_WIDTH, ICON_HEIGHT);
        if (iconInfos == null || iconInfos.isEmpty()) {
            return null;
        }
        EssKey key = new EssKey(osuInfo);
        Map<String, HSIconFileElement> fileMap = mCache.get(key);
        return fileMap != null ? fileMap.get(iconInfos.get(0).getFileName()) : null;
    }

    public int notifyIconReceived(long bssid, String fileName, byte[] iconData) {
        Log.d(OSUManager.TAG, String.format("Icon '%s':%d received from %012x",
                fileName, iconData != null ? iconData.length : -1, bssid));
        if (fileName == null || iconData == null) {
            return 0;
        }

        HSIconFileElement iconFileElement;
        try {
            iconFileElement = new HSIconFileElement(HSIconFile,
                    ByteBuffer.wrap(iconData).order(ByteOrder.LITTLE_ENDIAN));
        } catch (ProtocolException | BufferUnderflowException e) {
            Log.e(OSUManager.TAG, "Failed to parse ANQP icon file: " + e);
            return 0;
        }

        int updates = 0;
        Iterator<Map.Entry<EssKey, Map<String, FileEntry>>> entries =
                mPending.entrySet().iterator();

        while (entries.hasNext()) {
            Map.Entry<EssKey, Map<String, FileEntry>> entry = entries.next();

            Map<String, FileEntry> fileMap = entry.getValue();
            FileEntry fileEntry = fileMap.get(fileName);
            updates = fileEntry.update(bssid, iconFileElement);
            if (updates > 0) {
                put(entry.getKey(), fileName, iconFileElement);
                fileMap.remove(fileName);
                if (fileMap.isEmpty()) {
                    entries.remove();
                }
                break;
            }
        }
        return updates;
    }

    public void tick(boolean wifiOff) {
        if (wifiOff) {
            mPending.clear();
            mCache.clear();
            return;
        }

        Iterator<Map.Entry<EssKey, Map<String, FileEntry>>> entries =
                mPending.entrySet().iterator();

        long now = System.currentTimeMillis();
        while (entries.hasNext()) {
            Map<String, FileEntry> fileMap = entries.next().getValue();
            Iterator<Map.Entry<String, FileEntry>> fileEntries = fileMap.entrySet().iterator();
            while (fileEntries.hasNext()) {
                FileEntry fileEntry = fileEntries.next().getValue();
                long age = now - fileEntry.getTimestamp();
                if (age > REQUERY_TIMEOUT || fileEntry.getAndIncrementRetry() > MAX_RETRY) {
                    fileEntries.remove();
                } else if (age > REQUERY_TIME) {
                    mOsuManager.doIconQuery(fileEntry.getLastBssid(), fileEntry.getFileName());
                }
            }
            if (fileMap.isEmpty()) {
                entries.remove();
            }
        }
    }

    private HSIconFileElement get(EssKey key, String fileName) {
        Map<String, HSIconFileElement> fileMap = mCache.get(key);
        if (fileMap == null) {
            return null;
        }
        return fileMap.get(fileName);
    }

    private void put(EssKey key, String fileName, HSIconFileElement icon) {
        Map<String, HSIconFileElement> fileMap = mCache.get(key);
        if (fileMap == null) {
            fileMap = new HashMap<>();
            mCache.put(key, fileMap);
        }
        fileMap.put(fileName, icon);
    }

    private FileEntry enqueue(EssKey key, String fileName, OSUInfo osuInfo) {
        Map<String, FileEntry> entryMap = mPending.get(key);
        if (entryMap == null) {
            entryMap = new HashMap<>();
            mPending.put(key, entryMap);
        }

        FileEntry fileEntry = entryMap.get(fileName);
        osuInfo.setIconStatus(OSUInfo.IconStatus.InProgress);
        if (fileEntry == null) {
            fileEntry = new FileEntry(osuInfo, fileName);
            entryMap.put(fileName, fileEntry);
            return fileEntry;
        }
        fileEntry.enqueu(osuInfo);
        return null;
    }
}
