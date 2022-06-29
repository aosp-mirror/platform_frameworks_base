/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A request for command from broadcast signal.
 */
public final class CommandRequest extends BroadcastInfoRequest implements Parcelable {
    public static final String ARGUMENT_TYPE_XML = "xml";
    public static final String ARGUMENT_TYPE_JSON = "json";
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_COMMAND;

    public static final @NonNull Parcelable.Creator<CommandRequest> CREATOR =
            new Parcelable.Creator<CommandRequest>() {
                @Override
                public CommandRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public CommandRequest[] newArray(int size) {
                    return new CommandRequest[size];
                }
            };

    private final String mNamespace;
    private final String mName;
    private final String mArguments;
    private final String mArgumentType;

    static CommandRequest createFromParcelBody(Parcel in) {
        return new CommandRequest(in);
    }

    public CommandRequest(int requestId, @RequestOption int option, @NonNull String namespace,
            @NonNull String name, @NonNull String arguments, @NonNull String argumentType) {
        super(REQUEST_TYPE, requestId, option);
        mNamespace = namespace;
        mName = name;
        mArguments = arguments;
        mArgumentType = argumentType;
    }

    CommandRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        mNamespace = source.readString();
        mName = source.readString();
        mArguments = source.readString();
        mArgumentType = source.readString();
    }

    /**
     * Gets the namespace of the command.
     */
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    /**
     * Gets the name of the command.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Gets the arguments of the command.
     * It could be serialized from some formats, such as JSON, XML, etc.
     */
    @NonNull
    public String getArguments() {
        return mArguments;
    }

    /**
     * Gets the argument type of the command.
     * It could be either JSON or XML.
     */
    @NonNull
    public String getArgumentType() {
        return mArgumentType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mNamespace);
        dest.writeString(mName);
        dest.writeString(mArguments);
        dest.writeString(mArgumentType);
    }
}
