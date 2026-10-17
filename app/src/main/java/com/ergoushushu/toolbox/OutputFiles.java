package com.ergoushushu.toolbox;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

final class OutputFiles {
    private OutputFiles() { }

    /** Only call for new documents created by this task, never an input or a pre-existing file. */
    static boolean deleteCreatedOutput(Context context, Uri createdOutput) {
        if (createdOutput == null) return true;
        try {
            if (DocumentsContract.isDocumentUri(context, createdOutput)) {
                return DocumentsContract.deleteDocument(context.getContentResolver(), createdOutput);
            }
            return context.getContentResolver().delete(createdOutput, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
