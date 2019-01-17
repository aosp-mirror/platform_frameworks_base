package android.media.audiofx;

import android.util.Log;
import java.util.UUID;

public class DiracSound extends AudioEffect {

    public static final int DIRACSOUND_HEADSET_EM001 = 15;
    public static final int DIRACSOUND_HEADSET_EM006 = 18;
    public static final int DIRACSOUND_HEADSET_EM007 = 16;
    public static final int DIRACSOUND_HEADSET_EM013 = 19;
    public static final int DIRACSOUND_HEADSET_EM015 = 20;
    public static final int DIRACSOUND_HEADSET_EM017 = 21;
    public static final int DIRACSOUND_HEADSET_EM018 = 22;
    public static final int DIRACSOUND_HEADSET_EM019 = 23;
    public static final int DIRACSOUND_HEADSET_EM303 = 13;
    public static final int DIRACSOUND_HEADSET_EM304 = 14;
    public static final int DIRACSOUND_HEADSET_GENERAL = 5;
    public static final int DIRACSOUND_HEADSET_GENERAL_INEAR = 6;
    public static final int DIRACSOUND_HEADSET_HM004 = 17;
    public static final int DIRACSOUND_HEADSET_MEP100 = 1;
    public static final int DIRACSOUND_HEADSET_MEP200 = 2;
    public static final int DIRACSOUND_HEADSET_MK101 = 7;
    public static final int DIRACSOUND_HEADSET_MK301 = 8;
    public static final int DIRACSOUND_HEADSET_MK303 = 9;
    public static final int DIRACSOUND_HEADSET_MO701 = 10;
    public static final int DIRACSOUND_HEADSET_MR102 = 11;
    public static final int DIRACSOUND_HEADSET_NONE = 0;
    public static final int DIRACSOUND_HEADSET_PHD = 12;
    public static final int DIRACSOUND_HEADSET_PISTON100 = 3;
    public static final int DIRACSOUND_HEADSET_PISTON200 = 4;
    public static final int DIRACSOUND_MODE_MOVIE = 1;
    public static final int DIRACSOUND_MODE_MOVIE_CINEMA = 0;
    public static final int DIRACSOUND_MODE_MOVIE_CINIMA = 0;
    public static final int DIRACSOUND_MODE_MOVIE_STUDIO = 1;
    public static final int DIRACSOUND_MODE_MUSIC = 0;
    private static final int DIRACSOUND_PARAM_EQ_LEVEL = 2;
    private static final int DIRACSOUND_PARAM_HEADSET_TYPE = 1;
    private static final int DIRACSOUND_PARAM_HIFI_MODE = 8;
    private static final int DIRACSOUND_PARAM_MODE = 3;
    private static final int DIRACSOUND_PARAM_MOVIE = 5;
    private static final int DIRACSOUND_PARAM_MOVIE_MODE = 7;
    private static final int DIRACSOUND_PARAM_MUSIC = 4;
    private static final int DIRACSOUND_PARAM_SPEAKER_STEREO_MODE = 9;
    private static final UUID EFFECT_TYPE_DIRACSOUND = UUID.fromString("e069d9e0-8329-11df-9168-0002a5d5c51b");
    private static final String TAG = "DiracSound";
    private static final boolean DEBUG = false;

    public DiracSound(int priority, int audioSession) {
        super(EFFECT_TYPE_NULL, EFFECT_TYPE_DIRACSOUND, priority, audioSession);
        if (DEBUG) Log.i(TAG, "Initialized " + TAG);
    }

    public void setMode(int mode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_MODE, mode));
        if (DEBUG) Log.i(TAG, "setMode = " + mode);
    }

    public int getMode() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_MODE, value));
        if (DEBUG) Log.i(TAG, "getMode = " + value[0]);
        return value[0];
    }

    public void setMusic(int enable)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_MUSIC, enable));
        if (DEBUG) Log.i(TAG, "setMusic = " + enable);
    }

    public int getMusic() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_MUSIC, value));
        if (DEBUG) Log.i(TAG, "getMusic = " + value[0]);
        return value[0];
    }

    public void setMovie(int enable)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_MOVIE, enable));
        if (DEBUG) Log.i(TAG, "setMovie = " + enable);
    }

    public int getMovie() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_MOVIE, value));
        if (DEBUG) Log.i(TAG, "getMovie = " + value[0]);
        return value[0];
    }

    public void setHeadsetType(int type)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_HEADSET_TYPE, type));
        if (DEBUG) Log.i(TAG, "setHeadsetType = " + type);
    }

    public int getHeadsetType() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_HEADSET_TYPE, value));
        if (DEBUG) Log.i(TAG, "getHeadsetType = " + value[0]);
        return value[0];
    }

    public void setLevel(int band, float level)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(new int[] { DIRACSOUND_PARAM_EQ_LEVEL, band }, String.valueOf(level).getBytes()));
        if (DEBUG) Log.i(TAG, "setLevel = " + band + " -> " + level);
    }

    public float getLevel(int band)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        byte[] value = new byte[10];
        param[0] = DIRACSOUND_PARAM_EQ_LEVEL;
        param[1] = band;
        checkStatus(getParameter(param, value));
        if (DEBUG) Log.i(TAG, "getLevel = " + band + " -> " + new Float(new String(value)).floatValue());
        return new Float(new String(value)).floatValue();
    }

    public void setMovieMode(int mode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_MOVIE_MODE, mode));
        if (DEBUG) Log.i(TAG, "setMovieMode = " + mode);
    }

    public int getMovieMode() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_MOVIE_MODE, value));
        if (DEBUG) Log.i(TAG, "getMovieMode = " + value[0]);
        return value[0];
    }

    public void setSpeakerStereoMode(int mode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_SPEAKER_STEREO_MODE, mode));
        if (DEBUG) Log.i(TAG, "setSpeakerStereoMode = " + mode);
    }

    public int getSpeakerStereoMode()
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_SPEAKER_STEREO_MODE, value));
        if (DEBUG) Log.i(TAG, "getSpeakerStereoMode = " + value[0]);
        return value[0];
    }

    public void setHifiMode(int mode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(DIRACSOUND_PARAM_HIFI_MODE, mode));
        if (DEBUG) Log.i(TAG, "setHifiMode = " + mode);
    }

    public int getHifiMode() throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(DIRACSOUND_PARAM_HIFI_MODE, value));
        if (DEBUG) Log.i(TAG, "getHifiMode = " + value[0]);
        return value[0];
    }
}
