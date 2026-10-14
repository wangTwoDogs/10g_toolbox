package com.ergoushushu.toolbox;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

final class OutputFiles {
    private OutputFiles() { }

    /** Only call for the new URI returned by our ACTION_CREATE_DOCUMENT request. */
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
