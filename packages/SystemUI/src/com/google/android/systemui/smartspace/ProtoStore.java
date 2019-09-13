package com.google.android.systemui.smartspace;

import android.content.Context;
import android.util.Log;
import com.google.protobuf.nano.MessageNano;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public class ProtoStore {
    private final Context mContext;

    public ProtoStore(Context context) {
        mContext = context.getApplicationContext();
    }

    public void store(MessageNano messageNano, String str) {
        String str2 = "ProtoStore";
        try {
            FileOutputStream openFileOutput = mContext.openFileOutput(str, 0);
            if (messageNano != null) {
                try {
                    openFileOutput.write(MessageNano.toByteArray(messageNano));
                } catch (Throwable th) {
                    if (openFileOutput != null) {
                        openFileOutput.close();
                    }
                    throw th;
                }
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("deleting ");
                sb.append(str);
                Log.d(str2, sb.toString());
                mContext.deleteFile(str);
            }
            if (openFileOutput != null) {
                openFileOutput.close();
            }
        } catch (FileNotFoundException unused) {
            Log.d(str2, "file does not exist");
        } catch (Exception e) {
            Log.e(str2, "unable to write file", e);
        }
    }

    public <T extends MessageNano> boolean load(String str, T t) {
        String str2 = "ProtoStore";
        File fileStreamPath = mContext.getFileStreamPath(str);
        try {
            FileInputStream fileInputStream = new FileInputStream(fileStreamPath);
            byte[] bArr = new byte[((int) fileStreamPath.length())];
            fileInputStream.read(bArr, 0, bArr.length);
            MessageNano.mergeFrom(t, bArr);
            if (fileInputStream != null) {
                fileInputStream.close();
            }
            return true;
        } catch (FileNotFoundException unused) {
            Log.d(str2, "no cached data");
            return false;
        } catch (Exception e) {
            Log.e(str2, "unable to load data", e);
            return false;
        }
    }
}
