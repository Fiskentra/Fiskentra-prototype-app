package android.content;

/** Minimal JVM host for WeatherClient cache tests; no Android framework is executed. */
public class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final int MODE_PRIVATE = 0;
    private final SharedPreferences preferences;
    public Context(SharedPreferences preferences) { this.preferences = preferences; }
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
}
