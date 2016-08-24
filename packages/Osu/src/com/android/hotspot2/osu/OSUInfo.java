package com.android.hotspot2.osu;

import android.net.wifi.ScanResult;
import android.util.Log;

import com.android.anqp.HSIconFileElement;
import com.android.anqp.I18Name;
import com.android.anqp.IconInfo;
import com.android.anqp.OSUProvider;
import com.android.hotspot2.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OSUInfo {
    public static final String GenericLocale = "zxx";

    public enum IconStatus {
        NotQueried,     //
        InProgress,     // Query pending
        NotAvailable,   // Deterministically unavailable
        Available       // Icon data retrieved
    }

    private final long mBSSID;
    private final long mHESSID;
    private final int mAnqpDomID;
    private final String mSSID;
    private final String mAdvertisingSSID;
    private final OSUProvider mOSUProvider;
    private final int mOsuID;
    private long mOSUBssid;
    private IconStatus mIconStatus = IconStatus.NotQueried;
    private HSIconFileElement mIconFileElement;
    private IconInfo mIconInfo;

    public OSUInfo(ScanResult scanResult, String ssid, OSUProvider osuProvider, int osuID) {
        mOsuID = osuID;
        mBSSID = Utils.parseMac(scanResult.BSSID);
        mHESSID = scanResult.hessid;
        mAnqpDomID = scanResult.anqpDomainId;
        mAdvertisingSSID = scanResult.SSID;
        mSSID = ssid;
        mOSUProvider = osuProvider;
    }

    public long getOSUBssid() {
        return mOSUBssid;
    }

    public void setOSUBssid(long OSUBssid) {
        mOSUBssid = OSUBssid;
    }

    public long getHESSID() {
        return mHESSID;
    }

    public int getAnqpDomID() {
        return mAnqpDomID;
    }

    public String getAdvertisingSSID() {
        return mAdvertisingSSID;
    }

    public Set<Locale> getNameLocales() {
        Set<Locale> locales = new HashSet<>(mOSUProvider.getNames().size());
        for (I18Name name : mOSUProvider.getNames()) {
            locales.add(name.getLocale());
        }
        return locales;
    }

    public Set<Locale> getServiceLocales() {
        Set<Locale> locales = new HashSet<>(mOSUProvider.getServiceDescriptions().size());
        for (I18Name name : mOSUProvider.getServiceDescriptions()) {
            locales.add(name.getLocale());
        }
        return locales;
    }

    public Set<String> getIconLanguages() {
        Set<String> locales = new HashSet<>(mOSUProvider.getIcons().size());
        for (IconInfo iconInfo : mOSUProvider.getIcons()) {
            locales.add(iconInfo.getLanguage());
        }
        return locales;
    }

    public String getName(Locale locale) {
        List<ScoreEntry<String>> scoreList = new ArrayList<>();
        for (I18Name name : mOSUProvider.getNames()) {
            if (locale == null || name.getLocale().equals(locale)) {
                return name.getText();
            }
            scoreList.add(new ScoreEntry<String>(name.getText(),
                    languageScore(name.getLanguage(), locale)));
        }
        Collections.sort(scoreList);
        return scoreList.isEmpty() ? null : scoreList.iterator().next().getData();
    }

    public String getServiceDescription(Locale locale) {
        List<ScoreEntry<String>> scoreList = new ArrayList<>();
        for (I18Name service : mOSUProvider.getServiceDescriptions()) {
            if (locale == null || service.getLocale().equals(locale)) {
                return service.getText();
            }
            scoreList.add(new ScoreEntry<>(service.getText(),
                    languageScore(service.getLanguage(), locale)));
        }
        Collections.sort(scoreList);
        return scoreList.isEmpty() ? null : scoreList.iterator().next().getData();
    }

    public int getOsuID() {
        return mOsuID;
    }

    public void setIconStatus(IconStatus iconStatus) {
        synchronized (mOSUProvider) {
            mIconStatus = iconStatus;
        }
    }

    public IconStatus getIconStatus() {
        synchronized (mOSUProvider) {
            return mIconStatus;
        }
    }

    public HSIconFileElement getIconFileElement() {
        synchronized (mOSUProvider) {
            return mIconFileElement;
        }
    }

    public IconInfo getIconInfo() {
        synchronized (mOSUProvider) {
            return mIconInfo;
        }
    }

    public void setIconFileElement(HSIconFileElement iconFileElement, String fileName) {
        synchronized (mOSUProvider) {
            mIconFileElement = iconFileElement;
            for (IconInfo iconInfo : mOSUProvider.getIcons()) {
                if (iconInfo.getFileName().equals(fileName)) {
                    mIconInfo = iconInfo;
                    break;
                }
            }
            mIconStatus = IconStatus.Available;
        }
    }

    private static class ScoreEntry<T> implements Comparable<ScoreEntry> {
        private final T mData;
        private final int mScore;

        private ScoreEntry(T data, int score) {
            mData = data;
            mScore = score;
        }

        public T getData() {
            return mData;
        }

        @Override
        public int compareTo(ScoreEntry other) {
            return Integer.compare(mScore, other.mScore);
        }
    }

    public List<IconInfo> getIconInfo(Locale locale, Set<String> types, int width, int height) {
        if (mOSUProvider.getIcons().isEmpty()) {
            return null;
        }
        Log.d(OSUManager.TAG, "Matching icons against " + locale
                + ", types " + types + ", " + width + "*" + height);

        List<ScoreEntry<IconInfo>> matches = new ArrayList<>();
        for (IconInfo iconInfo : mOSUProvider.getIcons()) {
            Log.d(OSUManager.TAG, "Checking icon " + iconInfo.toString());
            if (!types.contains(iconInfo.getIconType())) {
                continue;
            }

            int score = languageScore(iconInfo.getLanguage(), locale);
            int delta = iconInfo.getWidth() - width;
            // Best size score is 1024 for a exact match, i.e. 2048 if both sides match
            if (delta >= 0) {
                score += (256 - delta) * 4;  // Prefer down-scaling
            } else {
                score += 256 + delta;    // Before up-scaling
            }
            delta = iconInfo.getHeight() - height;
            if (delta >= 0) {
                score += (256 - delta) * 4;
            } else {
                score += 256 + delta;
            }
            matches.add(new ScoreEntry<>(iconInfo, score));
        }
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(matches);
        List<IconInfo> icons = new ArrayList<>(matches.size());
        for (ScoreEntry<IconInfo> scoredIcon : matches) {
            icons.add(scoredIcon.getData());
        }
        return icons;
    }

    private static int languageScore(String language, Locale locale) {
        if (language.length() == 3 && language.equalsIgnoreCase(locale.getISO3Language()) ||
                language.length() == 2 && language.equalsIgnoreCase(locale.getLanguage())) {
            return 4096;
        } else if (language.equalsIgnoreCase(GenericLocale)) {
            return 3072;
        } else if (language.equalsIgnoreCase("eng")) {
            return 2048;
        } else {
            return 1024;
        }
    }

    public long getBSSID() {
        return mBSSID;
    }

    public String getSSID() {
        return mSSID;
    }

    public OSUProvider getOSUProvider() {
        return mOSUProvider;
    }

    @Override
    public String toString() {
        return String.format("OSU Info '%s' %012x -> %s, icon %s",
                mSSID, mBSSID, getServiceDescription(null), mIconStatus);
    }
}
