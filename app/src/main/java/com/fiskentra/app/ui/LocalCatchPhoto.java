package com.fiskentra.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import com.fiskentra.app.R;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded local thumbnail decoding. Detached Journal/Saved rows cannot receive late results. */
final class LocalCatchPhoto {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private LocalCatchPhoto() { }
    static void bind(ImageView image, String path, int size) {
        File allowed = image.getContext().getFilesDir();
        image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            int generation;
            Future<?> pending;
            @Override public void onViewAttachedToWindow(View view) {
                int request = ++generation;
                WeakReference<ImageView> weak = new WeakReference<>((ImageView) view);
                pending = IO.submit(() -> {
                    Bitmap result = null;
                    try {
                        File file = new File(path).getCanonicalFile();
                        if (!file.getPath().startsWith(allowed.getCanonicalPath() + File.separator)) return;
                        String key = file.getPath() + ":" + file.lastModified() + ":" + size;
                        synchronized (CACHE) { result = CACHE.get(key); }
                        if (result == null && !Thread.currentThread().isInterrupted()) {
                            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeFile(file.getPath(), bounds);
                            BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inSampleSize = 1;
                            while (bounds.outWidth / decode.inSampleSize > size * 2 || bounds.outHeight / decode.inSampleSize > size * 2)
                                decode.inSampleSize *= 2;
                            result = BitmapFactory.decodeFile(file.getPath(), decode);
                            if (result != null) synchronized (CACHE) { CACHE.put(key, result); }
                        }
                    } catch (Exception ignored) { /* Keep the point icon; the other row data remains available. */ }
                    Bitmap loaded = result;
                    UI.post(() -> {
                        ImageView target = weak.get();
                        if (target == null || !target.isAttachedToWindow() || generation != request) return;
                        if (loaded != null) {
                            target.clearColorFilter(); target.setPadding(0, 0, 0, 0);
                            target.setScaleType(ImageView.ScaleType.CENTER_CROP); target.setImageBitmap(loaded);
                            target.setContentDescription(target.getContext().getString(R.string.data_photo_saved));
                        } else target.setContentDescription(target.getContext().getString(R.string.data_photo_unavailable));
                    });
                });
            }
            @Override public void onViewDetachedFromWindow(View view) {
                generation++; if (pending != null) pending.cancel(true); pending = null;
            }
        });
    }
}
