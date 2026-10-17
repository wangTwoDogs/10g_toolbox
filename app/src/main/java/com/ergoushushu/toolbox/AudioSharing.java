package com.ergoushushu.toolbox;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;

final class AudioSharing {
    private AudioSharing() { }
    static Intent intent(ContentResolver resolver, ArrayList<Uri> outputs) {
        if (outputs.isEmpty()) throw new IllegalArgumentException("No audio selected");
        Intent intent = new Intent(outputs.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("audio/mp4");
        if (outputs.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, outputs.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, outputs);
        ClipData data = ClipData.newUri(resolver, "压缩录音", outputs.get(0));
        for (int i = 1; i < outputs.size(); i++) data.addItem(new ClipData.Item(outputs.get(i)));
        intent.setClipData(data);
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
