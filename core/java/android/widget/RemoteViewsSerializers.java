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
package android.widget;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.function.Function;

/**
 * This class provides serialization for certain types used within RemoteViews.
 *
 * @hide
 */
public class RemoteViewsSerializers {
    private static final String TAG = "RemoteViews";

    /**
     * Write Icon to proto.
     */
    public static void writeIconToProto(@NonNull ProtoOutputStream out,
            @NonNull Resources appResources, @NonNull Icon icon) {
        if (icon.getTintList() != null) {
            final long token = out.start(RemoteViewsProto.Icon.TINT_LIST);
            icon.getTintList().writeToProto(out);
            out.end(token);
        }
        out.write(RemoteViewsProto.Icon.BLEND_MODE, BlendMode.toValue(icon.getTintBlendMode()));
        switch (icon.getType()) {
            case Icon.TYPE_BITMAP:
                final ByteArrayOutputStream bitmapBytes = new ByteArrayOutputStream();
                icon.getBitmap().compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bitmapBytes);
                out.write(RemoteViewsProto.Icon.BITMAP, bitmapBytes.toByteArray());
                break;
            case Icon.TYPE_ADAPTIVE_BITMAP:
                final ByteArrayOutputStream adaptiveBitmapBytes = new ByteArrayOutputStream();
                icon.getBitmap().compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100,
                        adaptiveBitmapBytes);
                out.write(RemoteViewsProto.Icon.ADAPTIVE_BITMAP, adaptiveBitmapBytes.toByteArray());
                break;
            case Icon.TYPE_RESOURCE:
                out.write(RemoteViewsProto.Icon.RESOURCE,
                        appResources.getResourceName(icon.getResId()));
                break;
            case Icon.TYPE_DATA:
                out.write(RemoteViewsProto.Icon.DATA, icon.getDataBytes());
                break;
            case Icon.TYPE_URI:
                out.write(RemoteViewsProto.Icon.URI, icon.getUriString());
                break;
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                out.write(RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP, icon.getUriString());
                break;
            default:
                Log.e(TAG, "Tried to serialize unknown Icon type " + icon.getType());
        }
    }

    /**
     * Create Icon from proto.
     */
    @NonNull
    public static Function<Resources, Icon> createIconFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        final LongSparseArray<Object> values = new LongSparseArray<>();
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.Icon.BLEND_MODE:
                    values.put(RemoteViewsProto.Icon.BLEND_MODE,
                            in.readInt(RemoteViewsProto.Icon.BLEND_MODE));
                    break;
                case (int) RemoteViewsProto.Icon.TINT_LIST:
                    final long tintListToken = in.start(RemoteViewsProto.Icon.TINT_LIST);
                    values.put(RemoteViewsProto.Icon.TINT_LIST, ColorStateList.createFromProto(in));
                    in.end(tintListToken);
                    break;
                case (int) RemoteViewsProto.Icon.BITMAP:
                    byte[] bitmapData = in.readBytes(RemoteViewsProto.Icon.BITMAP);
                    values.put(RemoteViewsProto.Icon.BITMAP,
                            BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length));
                    break;
                case (int) RemoteViewsProto.Icon.ADAPTIVE_BITMAP:
                    final byte[] bitmapAdaptiveData = in.readBytes(
                            RemoteViewsProto.Icon.ADAPTIVE_BITMAP);
                    values.put(RemoteViewsProto.Icon.ADAPTIVE_BITMAP,
                            BitmapFactory.decodeByteArray(bitmapAdaptiveData, 0,
                                    bitmapAdaptiveData.length));
                    break;
                case (int) RemoteViewsProto.Icon.RESOURCE:
                    values.put(RemoteViewsProto.Icon.RESOURCE,
                            in.readString(RemoteViewsProto.Icon.RESOURCE));
                    break;
                case (int) RemoteViewsProto.Icon.DATA:
                    values.put(RemoteViewsProto.Icon.DATA,
                            in.readBytes(RemoteViewsProto.Icon.DATA));
                    break;
                case (int) RemoteViewsProto.Icon.URI:
                    values.put(RemoteViewsProto.Icon.URI, in.readString(RemoteViewsProto.Icon.URI));
                    break;
                case (int) RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP:
                    values.put(RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP,
                            in.readString(RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP));
                    break;
                default:
                    Log.w(TAG, "Unhandled field while reading Icon proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }

        return (resources) -> {
            final int blendMode = (int) values.get(RemoteViewsProto.Icon.BLEND_MODE, -1);
            final ColorStateList tintList = (ColorStateList) values.get(
                    RemoteViewsProto.Icon.TINT_LIST);
            final Bitmap bitmap = (Bitmap) values.get(RemoteViewsProto.Icon.BITMAP);
            final Bitmap bitmapAdaptive = (Bitmap) values.get(
                    RemoteViewsProto.Icon.ADAPTIVE_BITMAP);
            final String resName = (String) values.get(RemoteViewsProto.Icon.RESOURCE);
            final int resource = resName != null ? resources.getIdentifier(resName, /* defType= */
                    null,
                    /* defPackage= */ null) : -1;
            final byte[] data = (byte[]) values.get(RemoteViewsProto.Icon.DATA);
            final String uri = (String) values.get(RemoteViewsProto.Icon.URI);
            final String uriAdaptive = (String) values.get(
                    RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP);
            Icon icon;
            if (bitmap != null) {
                icon = Icon.createWithBitmap(bitmap);
            } else if (bitmapAdaptive != null) {
                icon = Icon.createWithAdaptiveBitmap(bitmapAdaptive);
            } else if (resource != -1) {
                icon = Icon.createWithResource(resources, resource);
            } else if (data != null) {
                icon = Icon.createWithData(data, 0, data.length);
            } else if (uri != null) {
                icon = Icon.createWithContentUri(uri);
            } else if (uriAdaptive != null) {
                icon = Icon.createWithAdaptiveBitmapContentUri(uriAdaptive);
            } else {
                // Either this Icon has no data or is of an unknown type.
                return null;
            }

            if (tintList != null) {
                icon.setTintList(tintList);
            }
            if (blendMode != -1) {
                icon.setTintBlendMode(BlendMode.fromValue(blendMode));
            }
            return icon;
        };
    }
}
