package com.google.android.systemui.smartspace;

import android.util.Log;

public class SmartSpaceData {
    SmartSpaceCard mCurrentCard;
    SmartSpaceCard mWeatherCard;

    public boolean hasWeather() {
        return mWeatherCard != null;
    }

    public boolean hasCurrent() {
        return mCurrentCard != null;
    }

    public long getExpirationRemainingMillis() {
        long expiration;
        long currentTimeMillis = System.currentTimeMillis();
        if (hasCurrent() && hasWeather()) {
            expiration = Math.min(mCurrentCard.getExpiration(), mWeatherCard.getExpiration());
        } else if (hasCurrent()) {
            expiration = mCurrentCard.getExpiration();
        } else if (!hasWeather()) {
            return 0;
        } else {
            expiration = mWeatherCard.getExpiration();
        }
        return expiration - currentTimeMillis;
    }

    public long getExpiresAtMillis() {
        if (hasCurrent() && hasWeather()) {
            return Math.min(mCurrentCard.getExpiration(), mWeatherCard.getExpiration());
        }
        if (hasCurrent()) {
            return mCurrentCard.getExpiration();
        }
        if (hasWeather()) {
            return mWeatherCard.getExpiration();
        }
        return 0;
    }

    public void clear() {
        mWeatherCard = null;
        mCurrentCard = null;
    }

    public boolean handleExpire() {
        boolean z;
        String str = "SmartspaceData";
        if (!hasWeather() || !mWeatherCard.isExpired()) {
            z = false;
        } else {
            if (SmartSpaceController.DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("weather expired ");
                sb.append(mWeatherCard.getExpiration());
                Log.d(str, sb.toString());
            }
            mWeatherCard = null;
            z = true;
        }
        if (!hasCurrent() || !mCurrentCard.isExpired()) {
            return z;
        }
        if (SmartSpaceController.DEBUG) {
            StringBuilder sb2 = new StringBuilder();
            sb2.append("current expired ");
            sb2.append(mCurrentCard.getExpiration());
            Log.d(str, sb2.toString());
        }
        mCurrentCard = null;
        return true;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(mCurrentCard);
        sb.append(",");
        sb.append(mWeatherCard);
        sb.append("}");
        return sb.toString();
    }

    public SmartSpaceCard getWeatherCard() {
        return mWeatherCard;
    }

    public SmartSpaceCard getCurrentCard() {
        return mCurrentCard;
    }
}
