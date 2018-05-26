package de.lakoja.roverremote;

import android.graphics.Bitmap;

public interface ImageListener {
    void imagePresent(Bitmap bitmap, long timestampMillis, byte[] rawData, float lastKbps);
}
