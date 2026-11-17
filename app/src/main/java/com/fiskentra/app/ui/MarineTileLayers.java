package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.fiskentra.app.model.MarineTilePolicy;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transparent reference layers. Source tiles are never interpreted as navigable-water clearance. */
final class MarineTileLayers implements AutoCloseable {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final GoogleMap map;
    private final AtomicBoolean notificationPosted = new AtomicBoolean();
    private final Source depth = new Source(true), seamarks = new Source(false);
    private final TileOverlay depthOverlay, seamarkOverlay;
    private volatile boolean closed;
    private String notifiedStatus = "";
    private String viewSignature = "";

    MarineTileLayers(Context context, GoogleMap map, Runnable onStatusChanged) {
        this.map = map;
        changed = onStatusChanged;
        depthOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(depth)
                .zIndex(-2).transparency(.45f).fadeIn(false).visible(false));
        seamarkOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(seamarks)
                .zIndex(-.5f).fadeIn(false).visible(false));
    }

    void setVisible(boolean depths, boolean marks) {
        if (closed) return;
        boolean depthChanged = depth.activate(depths), marksChanged = seamarks.activate(marks);
        if (depthOverlay != null) {
            depthOverlay.setVisible(depths);
            if (depthChanged && depths) depthOverlay.clearTileCache();
        }
        if (seamarkOverlay != null) {
            seamarkOverlay.setVisible(marks);
            if (marksChanged && marks) seamarkOverlay.clearTileCache();
        }
        notifyChanged();
    }

    /** Opacity affects depth shading; chart symbols stay legible at full opacity. */
    void setOpacity(float opacity) {
        if (!closed && depthOverlay != null) depthOverlay.setTransparency(1 - Math.max(0, Math.min(1, opacity)));
    }

    /** Ignore camera-idle callbacks that keep the same source-tile coverage and zoom level. */
    void refreshView() {
        if (closed) return;
        LatLngBounds bounds = map.getProjection().getVisibleRegion().latLngBounds;
        String nextSignature = MarineTilePolicy.viewSignature(bounds.southwest.latitude, bounds.southwest.longitude,
                bounds.northeast.latitude, bounds.northeast.longitude, map.getCameraPosition().zoom);
        if (nextSignature.isEmpty() || nextSignature.equals(viewSignature)) return;
        viewSignature = nextSignature;
        clearView();
    }

    private void clearView() {
        depth.resetView(); seamarks.resetView();
        if (depth.active && depthOverlay != null) depthOverlay.clearTileCache();
        if (seamarks.active && seamarkOverlay != null) seamarkOverlay.clearTileCache();
        notifyChanged();
    }

    void retry() {
        if (closed) return;
        depth.retry(); seamarks.retry(); clearView();
    }

    String status() {
        String a = depth.status(), b = seamarks.status();
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "\n" + b;
    }

    private void notifyChanged() {
        if (closed || !notificationPosted.compareAndSet(false, true)) return;
        ui.post(() -> {
            notificationPosted.set(false);
            if (closed) return;
            String value = status();
            if (!value.equals(notifiedStatus)) {
                notifiedStatus = value;
                if (changed != null) changed.run();
            }
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true; depth.dispose(); seamarks.dispose();
        if (depthOverlay != null) depthOverlay.remove();
        if (seamarkOverlay != null) seamarkOverlay.remove();
        ui.removeCallbacksAndMessages(null);
    }

    private final class Source implements TileProvider {
        private static final int MAX_BYTES = 1024 * 1024, MAX_CACHE_BYTES = 6 * 1024 * 1024;
        private final boolean isDepth;
        private final Semaphore permits = new Semaphore(3);
        private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
        private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        private final LinkedHashMap<String, Image> cache = new LinkedHashMap<>(32, .75f, true);
        private volatile boolean active;
        private volatile long generation, retryAfter;
        private int cacheBytes, loaded, withData, failed;

        Source(boolean depth) { isDepth = depth; }

        boolean activate(boolean value) {
            if (active == value) return false;
            active = value; resetView();
            if (!value) for (HttpURLConnection connection : new ArrayList<>(connections)) connection.disconnect();
            return true;
        }

        synchronized void resetView() { generation++; loaded = withData = failed = 0; }
        synchronized void retry() { retryAfter = 0; }

        synchronized String status() {
            if (!active) return "";
            String name = isDepth ? "Depths" : "Sea marks";
            if (failed > 0 || SystemClock.elapsedRealtime() < retryAfter)
                return name + (loaded > 0 ? ": partial data; retry when online" : ": unavailable; check internet or retry");
            if (loaded == 0) return name + ": loading...";
            if (withData == 0) return name + (isDepth ? ": no depth data in this view" : ": no mapped signs in this view");
            return name + ": loaded" + (isDepth ? " · marine coverage" : "");
        }

        private synchronized void result(long requestGeneration, boolean hasData, boolean error) {
            if (!active || closed || requestGeneration != generation) return;
            if (error) failed++; else { loaded++; if (hasData) withData++; }
            notifyChanged();
        }

        @Override public Tile getTile(int x, int y, int zoom) {
            if (!active || closed || !MarineTilePolicy.valid(x, y, zoom)) return NO_TILE;
            // Maps calls providers on background threads. Never allow a network request from UI code.
            if (Looper.myLooper() == Looper.getMainLooper()) return null;
            int sourceZoom = Math.min(18, zoom), shift = zoom - sourceZoom;
            int sourceX = MarineTilePolicy.wrapX(x, zoom) >> shift, sourceY = y >> shift;
            String key = sourceZoom + "/" + sourceX + "/" + sourceY;
            long requestedGeneration = generation;
            Image stored;
            synchronized (this) { stored = cache.get(key); }
            if (stored != null) {
                result(requestedGeneration, stored.hasData, false);
                return tile(stored.bytes, x, y, zoom, shift);
            }
            if (SystemClock.elapsedRealtime() < retryAfter || !permits.tryAcquire()) return null;
            if (!inFlight.add(key)) { permits.release(); return null; }
            HttpURLConnection connection = null;
            try {
                if (!active || closed) return NO_TILE;
                String address = isDepth ? MarineTilePolicy.depthUrl(sourceX, sourceY, sourceZoom)
                        : MarineTilePolicy.seamarkUrl(sourceX, sourceY, sourceZoom);
                connection = (HttpURLConnection) new URL(address).openConnection();
                connections.add(connection);
                connection.setConnectTimeout(7000); connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "Fiskentra-Android/0.28 (map reference layers)");
                connection.setRequestProperty("Accept", "image/png");
                connection.setInstanceFollowRedirects(false);
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) {
                    if (response == 429) retryAfter = SystemClock.elapsedRealtime() + 60000;
                    throw new IOException("Tile unavailable");
                }
                String type = connection.getContentType();
                if (type == null || !type.toLowerCase(java.util.Locale.US).startsWith("image/png"))
                    throw new IOException("Invalid tile format");
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] block = new byte[8192]; int count;
                    while ((count = input.read(block)) != -1) {
                        if (buffer.size() + count > MAX_BYTES) throw new IOException("Tile too large");
                        if (!active || closed) return NO_TILE;
                        buffer.write(block, 0, count);
                    }
                    bytes = buffer.toByteArray();
                }
                Image image = validate(bytes);
                if (image == null) throw new IOException("Invalid tile image");
                if (!closed) remember(key, image);
                result(requestedGeneration, image.hasData, false);
                return active && !closed ? tile(bytes, x, y, zoom, shift) : NO_TILE;
            } catch (IOException | RuntimeException error) {
                if (active && !closed) {
                    retryAfter = Math.max(retryAfter, SystemClock.elapsedRealtime() + 10000);
                    result(requestedGeneration, false, true);
                }
                return null; // A transient failure must remain retryable by Maps / explicit Retry.
            } finally {
                if (connection != null) { connections.remove(connection); connection.disconnect(); }
                inFlight.remove(key); permits.release();
            }
        }

        private Image validate(byte[] bytes) {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth != 256 || bounds.outHeight != 256) return null;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;
            boolean hasData = false;
            int[] row = new int[256];
            for (int y = 0; y < 256 && !hasData; y++) {
                bitmap.getPixels(row, 0, 256, 0, y, 256, 1);
                for (int pixel : row) if ((pixel >>> 24) > 0) { hasData = true; break; }
            }
            bitmap.recycle(); return new Image(bytes, hasData);
        }

        private synchronized void remember(String key, Image image) {
            Image previous = cache.put(key, image);
            if (previous != null) cacheBytes -= previous.bytes.length;
            cacheBytes += image.bytes.length;
            java.util.Iterator<Map.Entry<String, Image>> entries = cache.entrySet().iterator();
            while ((cacheBytes > MAX_CACHE_BYTES || cache.size() > 96) && entries.hasNext()) {
                Map.Entry<String, Image> first = entries.next(); cacheBytes -= first.getValue().bytes.length; entries.remove();
            }
        }

        private Tile tile(byte[] bytes, int x, int y, int zoom, int shift) {
            if (shift == 0) return new Tile(256, 256, bytes);
            Bitmap parent = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (parent == null) return null;
            int factor = 1 << shift;
            float size = 256f / factor;
            float left = (MarineTilePolicy.wrapX(x, zoom) % factor) * size, top = (y % factor) * size;
            Bitmap output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.scale(256 / size, 256 / size); canvas.translate(-left, -top);
            canvas.drawBitmap(parent, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(); output.compress(Bitmap.CompressFormat.PNG, 100, encoded);
            parent.recycle(); output.recycle(); return new Tile(256, 256, encoded.toByteArray());
        }

        void dispose() {
            active = false;
            for (HttpURLConnection connection : new ArrayList<>(connections)) connection.disconnect();
            synchronized (this) { cache.clear(); cacheBytes = 0; generation++; }
        }
    }

    private static final class Image {
        final byte[] bytes;
        final boolean hasData;
        Image(byte[] bytes, boolean hasData) { this.bytes = bytes; this.hasData = hasData; }
    }
}
