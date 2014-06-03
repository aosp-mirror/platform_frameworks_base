package android.service.voice;

import android.util.ArraySet;

/**
 * A Voice Keyphrase.
 * @hide
 */
public class KeyphraseInfo {
    public final int id;
    public final String keyphrase;
    public final ArraySet<String> supportedLocales;

    public KeyphraseInfo(int id, String keyphrase, String[] supportedLocales) {
        this.id = id;
        this.keyphrase = keyphrase;
        this.supportedLocales = new ArraySet<String>(supportedLocales.length);
        for (String locale : supportedLocales) {
            this.supportedLocales.add(locale);
        }
    }
}
