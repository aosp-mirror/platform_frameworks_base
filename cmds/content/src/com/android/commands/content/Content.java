/*
** Copyright 2012, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.commands.content;

import android.app.ActivityManager;
import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Pair;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is a command line utility for manipulating content. A client
 * can insert, update, and remove records in a content provider. For example,
 * some settings may be configured before running the CTS tests, etc.
 * <p>
 * Examples:
 * <ul>
 * <li>
 * # Add "new_setting" secure setting with value "new_value".</br>
 * adb shell content insert --uri content://settings/secure --bind name:s:new_setting
 *  --bind value:s:new_value
 * </li>
 * <li>
 * # Change "new_setting" secure setting to "newer_value" (You have to escape single quotes in
 * the where clause).</br>
 * adb shell content update --uri content://settings/secure --bind value:s:newer_value
 *  --where "name=\'new_setting\'"
 * </li>
 * <li>
 * # Remove "new_setting" secure setting.</br>
 * adb shell content delete --uri content://settings/secure --where "name=\'new_setting\'"
 * </li>
 * <li>
 * # Query \"name\" and \"value\" columns from secure settings where \"name\" is equal to"
 *    \"new_setting\" and sort the result by name in ascending order.\n"
 * adb shell content query --uri content://settings/secure --projection name:value
 *  --where "name=\'new_setting\'" --sort \"name ASC\"
 * </li>
 * </ul>
 * </p>
 */
public class Content {

    private static final String USAGE =
            "usage: adb shell content [subcommand] [options]\n"
                    + "\n"
                    + "usage: adb shell content insert --uri <URI> [--user <USER_ID>]"
                    + " --bind <BINDING> [--bind <BINDING>...] [--extra <BINDING>...]\n"
                    + "  <URI> a content provider URI.\n"
                    + "  <BINDING> binds a typed value to a column and is formatted:\n"
                    + "  <COLUMN_NAME>:<TYPE>:<COLUMN_VALUE> where:\n"
                    + "  <TYPE> specifies data type such as:\n"
                    + "  b - boolean, s - string, i - integer, l - long, f - float, d - double, n - null\n"
                    + "  Note: Omit the value for passing an empty string, e.g column:s:\n"
                    + "  Example:\n"
                    + "  # Add \"new_setting\" secure setting with value \"new_value\".\n"
                    + "  adb shell content insert --uri content://settings/secure --bind name:s:new_setting"
                    + " --bind value:s:new_value\n"
                    + "\n"
                    + "usage: adb shell content update --uri <URI> [--user <USER_ID>]"
                    + " [--where <WHERE>] [--extra <BINDING>...]\n"
                    + "  <WHERE> is a SQL style where clause in quotes (You have to escape single quotes"
                    + " - see example below).\n"
                    + "  Example:\n"
                    + "  # Change \"new_setting\" secure setting to \"newer_value\".\n"
                    + "  adb shell content update --uri content://settings/secure --bind"
                    + " value:s:newer_value --where \"name=\'new_setting\'\"\n"
                    + "\n"
                    + "usage: adb shell content delete --uri <URI> [--user <USER_ID>] --bind <BINDING>"
                    + " [--bind <BINDING>...] [--where <WHERE>] [--extra <BINDING>...]\n"
                    + "  Example:\n"
                    + "  # Remove \"new_setting\" secure setting.\n"
                    + "  adb shell content delete --uri content://settings/secure "
                    + "--where \"name=\'new_setting\'\"\n"
                    + "\n"
                    + "usage: adb shell content query --uri <URI> [--user <USER_ID>]"
                    + " [--projection <PROJECTION>] [--where <WHERE>] [--sort <SORT_ORDER>]"
                    + " [--extra <BINDING>...]\n"
                    + "  <PROJECTION> is a list of colon separated column names and is formatted:\n"
                    + "  <COLUMN_NAME>[:<COLUMN_NAME>...]\n"
                    + "  <SORT_ORDER> is the order in which rows in the result should be sorted.\n"
                    + "  Example:\n"
                    + "  # Select \"name\" and \"value\" columns from secure settings where \"name\" is "
                    + "equal to \"new_setting\" and sort the result by name in ascending order.\n"
                    + "  adb shell content query --uri content://settings/secure --projection name:value"
                    + " --where \"name=\'new_setting\'\" --sort \"name ASC\"\n"
                    + "\n"
                    + "usage: adb shell content call --uri <URI> --method <METHOD> [--arg <ARG>]\n"
                    + "       [--extra <BINDING> ...]\n"
                    + "  <METHOD> is the name of a provider-defined method\n"
                    + "  <ARG> is an optional string argument\n"
                    + "  <BINDING> is like --bind above, typed data of the form <KEY>:{b,s,i,l,f,d}:<VAL>\n"
                    + "\n"
                    + "usage: adb shell content read --uri <URI> [--user <USER_ID>]\n"
                    + "  Example:\n"
                    + "  adb shell 'content read --uri content://settings/system/ringtone_cache' > host.ogg\n"
                    + "\n"
                    + "usage: adb shell content write --uri <URI> [--user <USER_ID>]\n"
                    + "  Example:\n"
                    + "  adb shell 'content write --uri content://settings/system/ringtone_cache' < host.ogg\n"
                    + "\n"
                    + "usage: adb shell content gettype --uri <URI> [--user <USER_ID>]\n"
                    + "  Example:\n"
                    + "  adb shell content gettype --uri content://media/internal/audio/media/\n"
                    + "\n";

    private static class Parser {
        private static final String ARGUMENT_INSERT = "insert";
        private static final String ARGUMENT_DELETE = "delete";
        private static final String ARGUMENT_UPDATE = "update";
        private static final String ARGUMENT_QUERY = "query";
        private static final String ARGUMENT_CALL = "call";
        private static final String ARGUMENT_READ = "read";
        private static final String ARGUMENT_WRITE = "write";
        private static final String ARGUMENT_GET_TYPE = "gettype";
        private static final String ARGUMENT_WHERE = "--where";
        private static final String ARGUMENT_BIND = "--bind";
        private static final String ARGUMENT_URI = "--uri";
        private static final String ARGUMENT_USER = "--user";
        private static final String ARGUMENT_PROJECTION = "--projection";
        private static final String ARGUMENT_SORT = "--sort";
        private static final String ARGUMENT_METHOD = "--method";
        private static final String ARGUMENT_ARG = "--arg";
        private static final String ARGUMENT_EXTRA = "--extra";
        private static final String TYPE_BOOLEAN = "b";
        private static final String TYPE_STRING = "s";
        private static final String TYPE_INTEGER = "i";
        private static final String TYPE_LONG = "l";
        private static final String TYPE_FLOAT = "f";
        private static final String TYPE_DOUBLE = "d";
        private static final String TYPE_NULL = "n";
        private static final String COLON = ":";
        private static final String ARGUMENT_PREFIX = "--";

        private final Tokenizer mTokenizer;

        public Parser(String[] args) {
            mTokenizer = new Tokenizer(args);
        }

        public Command parseCommand() {
            try {
                String operation = mTokenizer.nextArg();
                if (ARGUMENT_INSERT.equals(operation)) {
                    return parseInsertCommand();
                } else if (ARGUMENT_DELETE.equals(operation)) {
                    return parseDeleteCommand();
                } else if (ARGUMENT_UPDATE.equals(operation)) {
                    return parseUpdateCommand();
                } else if (ARGUMENT_QUERY.equals(operation)) {
                    return parseQueryCommand();
                } else if (ARGUMENT_CALL.equals(operation)) {
                    return parseCallCommand();
                } else if (ARGUMENT_READ.equals(operation)) {
                    return parseReadCommand();
                } else if (ARGUMENT_WRITE.equals(operation)) {
                    return parseWriteCommand();
                } else if (ARGUMENT_GET_TYPE.equals(operation)) {
                    return parseGetTypeCommand();
                } else {
                    throw new IllegalArgumentException("Unsupported operation: " + operation);
                }
            } catch (IllegalArgumentException iae) {
                System.out.println(USAGE);
                System.out.println("[ERROR] " + iae.getMessage());
                return null;
            }
        }

        private InsertCommand parseInsertCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            ContentValues values = new ContentValues();
            Bundle extras = new Bundle();
            for (String argument; (argument = mTokenizer.nextArg()) != null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else if (ARGUMENT_BIND.equals(argument)) {
                    parseBindValue(values);
                } else if (ARGUMENT_EXTRA.equals(argument)) {
                    parseBindValue(extras);
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            if (values.size() == 0) {
                throw new IllegalArgumentException("Bindings not specified."
                        + " Did you specify --bind argument(s)?");
            }
            return new InsertCommand(uri, userId, values, extras);
        }

        private DeleteCommand parseDeleteCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            Bundle extras = new Bundle();
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else if (ARGUMENT_WHERE.equals(argument)) {
                    extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                            argumentValueRequired(argument));
                } else if (ARGUMENT_EXTRA.equals(argument)) {
                    parseBindValue(extras);
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            return new DeleteCommand(uri, userId, extras);
        }

        private UpdateCommand parseUpdateCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            ContentValues values = new ContentValues();
            Bundle extras = new Bundle();
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else if (ARGUMENT_WHERE.equals(argument)) {
                    extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                            argumentValueRequired(argument));
                } else if (ARGUMENT_BIND.equals(argument)) {
                    parseBindValue(values);
                } else if (ARGUMENT_EXTRA.equals(argument)) {
                    parseBindValue(extras);
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            if (values.size() == 0) {
                throw new IllegalArgumentException("Bindings not specified."
                        + " Did you specify --bind argument(s)?");
            }
            return new UpdateCommand(uri, userId, values, extras);
        }

        public CallCommand parseCallCommand() {
            String method = null;
            int userId = UserHandle.USER_SYSTEM;
            String arg = null;
            Uri uri = null;
            Bundle extras = new Bundle();
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else if (ARGUMENT_METHOD.equals(argument)) {
                    method = argumentValueRequired(argument);
                } else if (ARGUMENT_ARG.equals(argument)) {
                    arg = argumentValueRequired(argument);
                } else if (ARGUMENT_EXTRA.equals(argument)) {
                    parseBindValue(extras);
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            if (method == null) {
                throw new IllegalArgumentException("Content provider method not specified.");
            }
            return new CallCommand(uri, userId, method, arg, extras);
        }

        private GetTypeCommand parseGetTypeCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;

            for (String argument; (argument = mTokenizer.nextArg()) != null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            return new GetTypeCommand(uri, userId);
        }

        private ReadCommand parseReadCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            return new ReadCommand(uri, userId);
        }

        private WriteCommand parseWriteCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            return new WriteCommand(uri, userId);
        }

        public QueryCommand parseQueryCommand() {
            Uri uri = null;
            int userId = UserHandle.USER_SYSTEM;
            String[] projection = null;
            Bundle extras = new Bundle();
            for (String argument; (argument = mTokenizer.nextArg())!= null;) {
                if (ARGUMENT_URI.equals(argument)) {
                    uri = Uri.parse(argumentValueRequired(argument));
                } else if (ARGUMENT_USER.equals(argument)) {
                    userId = Integer.parseInt(argumentValueRequired(argument));
                } else if (ARGUMENT_WHERE.equals(argument)) {
                    extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                            argumentValueRequired(argument));
                } else if (ARGUMENT_SORT.equals(argument)) {
                    extras.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                            argumentValueRequired(argument));
                } else if (ARGUMENT_PROJECTION.equals(argument)) {
                    projection = argumentValueRequired(argument).split("[\\s]*:[\\s]*");
                } else if (ARGUMENT_EXTRA.equals(argument)) {
                    parseBindValue(extras);
                } else {
                    throw new IllegalArgumentException("Unsupported argument: " + argument);
                }
            }
            if (uri == null) {
                throw new IllegalArgumentException("Content provider URI not specified."
                        + " Did you specify --uri argument?");
            }
            return new QueryCommand(uri, userId, projection, extras);
        }

        private List<String> splitWithEscaping(String argument, char splitChar) {
            final List<String> res = new ArrayList<>();
            final StringBuilder cur = new StringBuilder();
            for (int i = 0; i < argument.length(); i++) {
                char c = argument.charAt(i);
                if (c == '\\') {
                    if (++i == argument.length()) {
                        throw new IllegalArgumentException("Invalid escaping");
                    } else {
                        // Skip escaping char and insert next
                        c = argument.charAt(i);
                        cur.append(c);
                    }
                } else if (c == splitChar) {
                    // Splitting char means next string
                    res.add(cur.toString());
                    cur.setLength(0);
                } else {
                    // Copy non-escaping and non-splitting char
                    cur.append(c);
                }
            }
            res.add(cur.toString());
            return res;
        }

        private Pair<String, Object> parseBindValue() {
            String argument = mTokenizer.nextArg();
            if (TextUtils.isEmpty(argument)) {
                throw new IllegalArgumentException("Binding not well formed: " + argument);
            }
            final List<String> split = splitWithEscaping(argument, COLON.charAt(0));
            if (split.size() != 3) {
                throw new IllegalArgumentException("Binding not well formed: " + argument);
            }
            String column = split.get(0);
            String type = split.get(1);
            String value = split.get(2);
            if (TYPE_STRING.equals(type)) {
                return Pair.create(column, value);
            } else if (TYPE_BOOLEAN.equalsIgnoreCase(type)) {
                return Pair.create(column, Boolean.parseBoolean(value));
            } else if (TYPE_INTEGER.equalsIgnoreCase(type)) {
                return Pair.create(column, Integer.parseInt(value));
            } else if (TYPE_LONG.equalsIgnoreCase(type)) {
                return Pair.create(column, Long.parseLong(value));
            } else if (TYPE_FLOAT.equalsIgnoreCase(type)) {
                return Pair.create(column, Float.parseFloat(value));
            } else if (TYPE_DOUBLE.equalsIgnoreCase(type)) {
                return Pair.create(column, Double.parseDouble(value));
            } else if (TYPE_NULL.equalsIgnoreCase(type)) {
                return Pair.create(column, null);
            } else {
                throw new IllegalArgumentException("Unsupported type: " + type);
            }
        }

        private void parseBindValue(ContentValues values) {
            final Pair<String, Object> columnValue = parseBindValue();
            values.putObject(columnValue.first, columnValue.second);
        }

        private void parseBindValue(Bundle extras) {
            final Pair<String, Object> columnValue = parseBindValue();
            extras.putObject(columnValue.first, columnValue.second);
        }

        private String argumentValueRequired(String argument) {
            String value = mTokenizer.nextArg();
            if (TextUtils.isEmpty(value) || value.startsWith(ARGUMENT_PREFIX)) {
                throw new IllegalArgumentException("No value for argument: " + argument);
            }
            return value;
        }
    }

    private static class Tokenizer {
        private final String[] mArgs;
        private int mNextArg;

        public Tokenizer(String[] args) {
            mArgs = args;
        }

        private String nextArg() {
            if (mNextArg < mArgs.length) {
                return mArgs[mNextArg++];
            } else {
                return null;
            }
        }
    }

    private static abstract class Command {
        final Uri mUri;
        final int mUserId;

        public Command(Uri uri, int userId) {
            mUri = uri;
            mUserId = userId;
        }

        public final void execute() {
            String providerName = mUri.getAuthority();
            try {
                IActivityManager activityManager = ActivityManager.getService();
                IContentProvider provider = null;
                IBinder token = new Binder();
                try {
                    ContentProviderHolder holder = activityManager.getContentProviderExternal(
                            providerName, mUserId, token, "*cmd*");
                    if (holder == null) {
                        throw new IllegalStateException("Could not find provider: " + providerName);
                    }
                    provider = holder.provider;
                    onExecute(provider);
                } finally {
                    if (provider != null) {
                        activityManager.removeContentProviderExternalAsUser(
                                providerName, token, mUserId);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error while accessing provider:" + providerName);
                e.printStackTrace();
            }
        }

        public static String resolveCallingPackage() {
            switch (Process.myUid()) {
                case Process.ROOT_UID: {
                    return "root";
                }

                case Process.SHELL_UID: {
                    return "com.android.shell";
                }

                default: {
                    return null;
                }
            }
        }

        protected abstract void onExecute(IContentProvider provider) throws Exception;
    }

    private static class InsertCommand extends Command {
        final ContentValues mContentValues;
        final Bundle mExtras;

        public InsertCommand(Uri uri, int userId, ContentValues contentValues, Bundle extras) {
            super(uri, userId);
            mContentValues = contentValues;
            mExtras = extras;
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            provider.insert(new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri, mContentValues, mExtras);
        }
    }

    private static class DeleteCommand extends Command {
        final Bundle mExtras;

        public DeleteCommand(Uri uri, int userId, Bundle extras) {
            super(uri, userId);
            mExtras = extras;
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            provider.delete(new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri, mExtras);
        }
    }

    private static class CallCommand extends Command {
        final String mMethod, mArg;
        final Bundle mExtras;

        public CallCommand(Uri uri, int userId, String method, String arg, Bundle extras) {
            super(uri, userId);
            mMethod = method;
            mArg = arg;
            mExtras = extras;
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            Bundle result = provider.call(new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri.getAuthority(), mMethod, mArg, mExtras);
            if (result != null) {
                result.size(); // unpack
            }
            System.out.println("Result: " + result);
        }
    }

    private static class GetTypeCommand extends Command {
        public GetTypeCommand(Uri uri, int userId) {
            super(uri, userId);
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            String type = provider.getType(mUri);
            System.out.println("Result: " + type);
        }
    }

    private static class ReadCommand extends Command {
        public ReadCommand(Uri uri, int userId) {
            super(uri, userId);
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            try (ParcelFileDescriptor fd = provider.openFile(
                    new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri, "r", null)) {
                FileUtils.copy(fd.getFileDescriptor(), FileDescriptor.out);
            }
        }
    }

    private static class WriteCommand extends Command {
        public WriteCommand(Uri uri, int userId) {
            super(uri, userId);
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            try (ParcelFileDescriptor fd = provider.openFile(new AttributionSource(
                    Binder.getCallingUid(), resolveCallingPackage(), null), mUri, "w", null)) {
                FileUtils.copy(FileDescriptor.in, fd.getFileDescriptor());
            }
        }
    }

    private static class QueryCommand extends Command {
        final String[] mProjection;
        final Bundle mExtras;

        public QueryCommand(Uri uri, int userId, String[] projection, Bundle extras) {
            super(uri, userId);
            mProjection = projection;
            mExtras = extras;
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            Cursor cursor = provider.query(new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri, mProjection, mExtras, null);
            if (cursor == null) {
                System.out.println("No result found.");
                return;
            }
            try {
                if (cursor.moveToFirst()) {
                    int rowIndex = 0;
                    StringBuilder builder = new StringBuilder();
                    do {
                        builder.setLength(0);
                        builder.append("Row: ").append(rowIndex).append(" ");
                        rowIndex++;
                        final int columnCount = cursor.getColumnCount();
                        for (int i = 0; i < columnCount; i++) {
                            if (i > 0) {
                                builder.append(", ");
                            }
                            String columnName = cursor.getColumnName(i);
                            String columnValue = null;
                            final int columnIndex = cursor.getColumnIndex(columnName);
                            final int type = cursor.getType(columnIndex);
                            switch (type) {
                                case Cursor.FIELD_TYPE_FLOAT:
                                    columnValue = String.valueOf(cursor.getFloat(columnIndex));
                                    break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    columnValue = String.valueOf(cursor.getLong(columnIndex));
                                    break;
                                case Cursor.FIELD_TYPE_STRING:
                                    columnValue = cursor.getString(columnIndex);
                                    break;
                                case Cursor.FIELD_TYPE_BLOB:
                                    columnValue = "BLOB";
                                    break;
                                case Cursor.FIELD_TYPE_NULL:
                                    columnValue = "NULL";
                                    break;
                            }
                            builder.append(columnName).append("=").append(columnValue);
                        }
                        System.out.println(builder);
                    } while (cursor.moveToNext());
                } else {
                    System.out.println("No result found.");
                }
            } finally {
                cursor.close();
            }
        }
    }

    private static class UpdateCommand extends Command {
        final ContentValues mValues;
        final Bundle mExtras;

        public UpdateCommand(Uri uri, int userId, ContentValues values, Bundle extras) {
            super(uri, userId);
            mValues = values;
            mExtras = extras;
        }

        @Override
        public void onExecute(IContentProvider provider) throws Exception {
            provider.update(new AttributionSource(Binder.getCallingUid(),
                    resolveCallingPackage(), null), mUri, mValues, mExtras);
        }
    }

    public static void main(String[] args) {
        Parser parser = new Parser(args);
        Command command = parser.parseCommand();
        if (command != null) {
            command.execute();
        }
    }
}
