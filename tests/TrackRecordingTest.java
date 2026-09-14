package com.fiskentra.app.data;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Runs the real TrackStore against isolated in-memory preferences, without user trip data. */
public final class TrackRecordingTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                TrackRecordingTest.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class},
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) { values.put((String) arguments[0], arguments[1]); return proxy; }
                    if (name.equals("remove")) { values.remove(arguments[0]); return proxy; }
                    if (name.equals("apply")) return null;
                    throw new UnsupportedOperationException(name);
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                TrackRecordingTest.class.getClassLoader(), new Class[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().startsWith("get")) return values.getOrDefault(arguments[0], arguments[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        TrackStore tracks = new TrackStore(prefs);
        check(!tracks.isActive(), "Initially idle");
        check(tracks.toggleRecording().equals("Track recording started"), "Hold starts without GPS");
        check(tracks.isActive() && !tracks.isPaused(), "Recording after first hold");
        check(!tracks.add(null), "No GPS must not fabricate a route point");
        long started = tracks.startedAt();
        String route = "[{\"lat\":52,\"lon\":13,\"time\":123,\"segment\":false}]";
        values.put("points", route);
        check(tracks.toggleRecording().equals("Track recording paused"), "Second hold pauses");
        check(tracks.isActive() && tracks.isPaused(), "Pause keeps trip open");
        check(route.equals(values.get("points")), "Pause preserves exact route");
        check(tracks.stoppedAt() == 0, "Pause must not finish the trip");
        tracks = new TrackStore(prefs);
        check(tracks.isPaused(), "Pause survives store recreation");
        check(tracks.toggleRecording().equals("Track recording resumed"), "Third hold resumes");
        check(tracks.isActive() && !tracks.isPaused(), "Recording after resume");
        check(route.equals(values.get("points")) && tracks.points().size() == 1, "Resume preserves saved points");
        check(tracks.startedAt() == started, "Resume preserves original trip time");
        check(Boolean.TRUE.equals(values.get("segment_pending")), "Resume starts a separate route segment");
        tracks.stop();
        check(!tracks.isActive() && !tracks.isPaused(), "Finish ends session");
        check(tracks.toggleRecording().equals("Track recording started"), "Hold starts a new session after finish");
        System.out.println("TrackRecording: " + checks + " checks passed");
    }
}
