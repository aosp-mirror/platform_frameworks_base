package com.google.android.systemui.smartspace;

import android.content.Context;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore.Images.Media;
import android.text.TextUtils;
import android.util.Log;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.CardWrapper;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.Image;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.Image;

import java.io.ByteArrayOutputStream;

public class NewCardInfo {
    private final SmartspaceCard mCard;
    private final Intent mIntent;
    private final boolean mIsPrimary;
    private final PackageInfo mPackageInfo;
    private final long mPublishTime;

    public NewCardInfo(SmartspaceCard smartspaceCard, Intent intent, boolean z, long j, PackageInfo packageInfo) {
        mCard = smartspaceCard;
        mIsPrimary = z;
        mIntent = intent;
        mPublishTime = j;
        mPackageInfo = packageInfo;
    }

    public boolean isPrimary() {
        return mIsPrimary;
    }

    public Bitmap retrieveIcon(Context context) {
        Image image = mCard.icon;
        if (image == null) {
            return null;
        }
        Bitmap bitmap = (Bitmap) retrieveFromIntent(image.key, mIntent);
        if (bitmap != null) {
            return bitmap;
        }
        try {
            if (!TextUtils.isEmpty(image.uri)) {
                return Media.getBitmap(context.getContentResolver(), Uri.parse(image.uri));
            }
            if (!TextUtils.isEmpty(image.gsaResourceName)) {
                ShortcutIconResource shortcutIconResource = new ShortcutIconResource();
                shortcutIconResource.packageName = "com.google.android.googlequicksearchbox";
                shortcutIconResource.resourceName = image.gsaResourceName;
                return createIconBitmap(shortcutIconResource, context);
            }
        } catch (Exception unused) {
            StringBuilder sb = new StringBuilder();
            sb.append("retrieving bitmap uri=");
            sb.append(image.uri);
            sb.append(" gsaRes=");
            sb.append(image.gsaResourceName);
            Log.e("NewCardInfo", sb.toString());
        }
        return null;
    }

    public CardWrapper toWrapper(Context context) {
        CardWrapper cardWrapper = new CardWrapper();
        Bitmap retrieveIcon = retrieveIcon(context);
        if (retrieveIcon != null) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            retrieveIcon.compress(CompressFormat.PNG, 100, byteArrayOutputStream);
            cardWrapper.icon = byteArrayOutputStream.toByteArray();
        }
        cardWrapper.card = mCard;
        cardWrapper.publishTime = mPublishTime;
        PackageInfo packageInfo = mPackageInfo;
        if (packageInfo != null) {
            cardWrapper.gsaVersionCode = packageInfo.versionCode;
            cardWrapper.gsaUpdateTime = packageInfo.lastUpdateTime;
        }
        return cardWrapper;
    }

    private static <T> T retrieveFromIntent(String str, Intent intent) {
        if (!TextUtils.isEmpty(str)) {
            return intent.getParcelableExtra(str);
        }
        return null;
    }

    static Bitmap createIconBitmap(ShortcutIconResource shortcutIconResource, Context context) {
        try {
            Resources resourcesForApplication = context.getPackageManager().getResourcesForApplication(shortcutIconResource.packageName);
            if (resourcesForApplication != null) {
                return BitmapFactory.decodeResource(resourcesForApplication, resourcesForApplication.getIdentifier(shortcutIconResource.resourceName, null, null));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public int getUserId() {
        return mIntent.getIntExtra("uid", -1);
    }

    public boolean shouldDiscard() {
        return mCard == null || mCard.shouldDiscard;
    }
}
