package com.ergoushushu.toolbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test APK only: a real document destination with unique-name creation, no user storage access. */
public final class TestOutputProvider extends ContentProvider {
    static final String AUTHORITY = "com.ergoushushu.toolbox.test.documents";
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String id = DocumentsContract.getDocumentId(uri);
        if (!id.matches("batch-[a-f0-9-]+(/[^/\\\\]+)?") || id.contains("..")) throw new SecurityException("Outside test storage");
        return new File(getContext().getCacheDir(), id);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Uri uri = extras.getParcelable("uri");
        try {
            if ("android:createDocument".equals(method)) {
                File parent = file(uri);
                if (!parent.exists() && !parent.mkdir()) throw new IOException("Could not create fixture root");
                String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (name == null || name.contains("/") || name.contains("\\")) throw new IOException("Invalid name");
                File destination = new File(parent, name);
                int suffix = 2;
                while (destination.exists()) destination = new File(parent, suffix++ + "-" + name);
                if (!destination.createNewFile()) throw new IOException("Could not create fixture output");
                Uri output = DocumentsContract.buildDocumentUriUsingTree(uri,
                        DocumentsContract.getDocumentId(uri) + "/" + destination.getName());
                Bundle result = new Bundle(); result.putParcelable("uri", output); return result;
            }
            if ("android:deleteDocument".equals(method)) {
                if (!file(uri).delete()) throw new IOException("Could not delete fixture");
                return new Bundle();
            }
            throw new UnsupportedOperationException(method);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = file(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "audio/mp4"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return file(uri).delete() ? 1 : 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
