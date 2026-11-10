package com.dlvoice.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

final class Artwork {
    static Bitmap embedded(Context context, String uri, int maxSize) {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, Uri.parse(uri));
            byte[] data = reader.getEmbeddedPicture();
            if (data == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSize) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            try { reader.release(); } catch (Exception ignored) {}
        }
    }

    private Artwork() {}
}
