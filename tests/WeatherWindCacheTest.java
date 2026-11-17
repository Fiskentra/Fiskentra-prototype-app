import android.content.Context;
import android.content.SharedPreferences;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.weather.WeatherClient;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Regression: map wind can restore an older observation without changing current-weather fetch. */
public final class WeatherWindCacheTest {
    private static int checks;
    private static final long MINUTE = 60_000L;
    private static final double LATITUDE = 54.18, LONGITUDE = 12.10;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void observation(Map<String, Object> values, long time) throws Exception {
        WeatherSnapshot weather = new WeatherSnapshot(time, 10, 8, 60, 0, 1010,
                17, 90, 1, "Europe/Berlin", WeatherSnapshot.PROVIDER_OPEN_METEO);
        values.put("weather", weather.toJson().toString());
    }
    public static void main(String[] arguments) throws Exception {
        long now = System.currentTimeMillis();
        Map<String, Object> values = new HashMap<>();
        values.put("latitude", Double.doubleToLongBits(LATITUDE));
        values.put("longitude", Double.doubleToLongBits(LONGITUDE));
        values.put("fetched_at", now - 20 * MINUTE);
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLong": case "getString": return values.getOrDefault(args[0], args[1]);
                        case "edit": throw new AssertionError("Cache reads must not rewrite observation age");
                        default: throw new AssertionError("Unexpected preference access: " + method.getName());
                    }
                });
        WeatherClient client = new WeatherClient(new Context(preferences));
        try {
            long observedAt = now - 25 * MINUTE;
            observation(values, observedAt);
            Map<String, Object> before = new HashMap<>(values);
            WeatherClient.CachedWind saved = client.cachedWind(LATITUDE, LONGITUDE);
            check(saved != null, "20-minute persistent cache restores wind after app restart");
            check(saved.weather.observedAt == observedAt, "saved sample retains actual observation time");
            check(saved.latitude == LATITUDE && saved.longitude == LONGITUDE, "saved sample retains original coordinates");
            check(values.equals(before), "restoring wind does not extend persistent cache age");
            check(client.cachedWind(LATITUDE + .02, LONGITUDE) != null, "nearby observation may be reused");
            check(client.cachedWind(LATITUDE + .06, LONGITUDE) == null, "observation beyond five kilometres is rejected");
            check(client.cachedWind(Double.NaN, LONGITUDE) == null, "invalid requested coordinate is rejected");
            check(client.cachedWind(91, LONGITUDE) == null, "out-of-range requested coordinate is rejected");
            final boolean[] called = {false};
            client.fetch(LATITUDE, LONGITUDE, (weather, message) -> {
                called[0] = true;
                check(weather == null && message.contains("offline"), "ordinary fetch retains fifteen-minute freshness offline");
            });
            check(called[0], "offline fetch returns immediately without HTTP");
            WeatherClient reopened = new WeatherClient(new Context(preferences));
            try { check(reopened.cachedWind(LATITUDE, LONGITUDE) != null, "a new client restores the same persisted sample"); }
            finally { reopened.close(); }
            observation(values, now - 59 * MINUTE);
            check(client.cachedWind(LATITUDE, LONGITUDE) != null, "saved wind remains usable before its one-hour expiry");
            observation(values, now - 61 * MINUTE);
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "expired observation rejected even with a saved cache");
            observation(values, now + 6 * MINUTE);
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "future observation rejected");
            observation(values, observedAt);
            values.put("latitude", Double.doubleToLongBits(Double.NaN));
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "invalid persisted coordinate rejected");
            values.remove("latitude");
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "missing persisted coordinate rejected");
            values.put("latitude", Double.doubleToLongBits(LATITUDE));
            observation(values, observedAt);
            org.json.JSONObject incomplete = new org.json.JSONObject((String) values.get("weather"));
            incomplete.remove("wind_speed_kmh");
            values.put("weather", incomplete.toString());
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "missing wind speed must not become calm wind");
            incomplete.put("wind_speed_kmh", "missing");
            values.put("weather", incomplete.toString());
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "unparseable wind speed rejected");
            incomplete.put("wind_speed_kmh", 17);
            incomplete.remove("wind_direction_degrees");
            values.put("weather", incomplete.toString());
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "missing wind direction must not become north wind");
            values.put("weather", "malformed");
            check(client.cachedWind(LATITUDE, LONGITUDE) == null, "malformed persisted observation rejected");
            System.out.println("WeatherWindCache: " + checks + " checks passed");
        } finally { client.close(); }
    }
}
