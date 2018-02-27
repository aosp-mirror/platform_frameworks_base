package android.media.update;

import android.graphics.Bitmap;
import android.media.MediaMetadata2;
import android.media.MediaMetadata2.Builder;
import android.media.Rating2;
import android.os.Bundle;

import java.util.Set;

/**
 * @hide
 */
public interface MediaMetadata2Provider {
    boolean containsKey_impl(String key);
    CharSequence getText_impl(String key);
    String getMediaId_impl();
    String getString_impl(String key);
    long getLong_impl(String key);
    Rating2 getRating_impl(String key);
    Bundle toBundle_impl();
    Set<String> keySet_impl();
    int size_impl();
    Bitmap getBitmap_impl(String key);
    float getFloat_impl(String key);
    Bundle getExtras_impl();

    interface BuilderProvider {
        Builder putText_impl(String key, CharSequence value);
        Builder putString_impl(String key, String value);
        Builder putLong_impl(String key, long value);
        Builder putRating_impl(String key, Rating2 value);
        Builder putBitmap_impl(String key, Bitmap value);
        Builder putFloat_impl(String key, float value);
        Builder setExtras_impl(Bundle bundle);
        MediaMetadata2 build_impl();
    }
}
