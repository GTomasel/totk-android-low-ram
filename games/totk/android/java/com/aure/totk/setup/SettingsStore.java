package com.aure.totk.setup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;
import com.aure.totk.companion.DisplayClass;
import com.aure.totk.companion.PresentationController;
import java.io.IOException;
import java.util.Map;

/** Menu settings in SharedPreferences, mirrored to <private>/settings.env (= ANativeActivity.internalDataPath) for
 * the game process, which parses it in start_game before <game>/totk.env. */
final class SettingsStore {
    private SettingsStore() { }
    static final String ENV = "settings.env";
    private static final String PREFS = "settings", ADVANCED = "advanced_settings";
    private static final String SKIP_MENU = "skip_menu", INITIALIZED = "initialized";

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String soc() { return Build.SOC_MODEL; }

    /** The stored settings; the first launch stores the profile for this chip (Settings.profile). */
    static Settings load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(INITIALIZED, false)) {
            Settings first = Settings.firstRun(soc());
            save(context, first);
            return first;
        }
        return new Settings(prefs.getAll(), soc());
    }
    /** Store every value and rewrite settings.env (on every change, so a game started any other way sees it too). */
    static void save(Context context, Settings settings) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (Map.Entry<String, String> e : settings.values().entrySet()) edit.putString(e.getKey(), e.getValue());
        edit.putBoolean(INITIALIZED, true).apply();
        writeEnv(context, settings);
    }
    private static SharedPreferences advancedPrefs(Context context) {
        return context.getSharedPreferences(ADVANCED, Context.MODE_PRIVATE);
    }
    static AdvancedSettings loadAdvanced(Context context) {
        return new AdvancedSettings(advancedPrefs(context).getAll());
    }
    private static boolean storeAdvanced(SharedPreferences prefs, AdvancedSettings advanced) {
        SharedPreferences.Editor edit = prefs.edit();
        for (AdvancedSettings.Option option : AdvancedSettings.OPTIONS) {
            String value = advanced.get(option);
            if (value.isEmpty()) edit.remove(option.key);
            else edit.putString(option.key, value);
        }
        return edit.commit();
    }
    static synchronized String saveAdvanced(Context context, AdvancedSettings advanced, Settings settings) {
        SharedPreferences prefs = advancedPrefs(context);
        AdvancedSettings previous = new AdvancedSettings(prefs.getAll());
        String failure = storeAdvanced(prefs, advanced) ? writeEnv(context, settings) : "Couldn't write preferences.";
        if (failure != null && !storeAdvanced(prefs, previous)) {
            Log.w("TotkMenu", "Couldn't restore advanced preferences");
        }
        return failure;
    }
    static boolean skipMenu(Context context) { return prefs(context).getBoolean(SKIP_MENU, false); }
    static void skipMenu(Context context, boolean skip) { prefs(context).edit().putBoolean(SKIP_MENU, skip).apply(); }

    static String locale() {
        LocaleList list = LocaleList.getDefault();
        return list.isEmpty() ? null : list.get(0).toLanguageTag();
    }
    /** Rewrite settings.env (atomic, Settings.writeAtomic). Returns null on success, else why it failed. */
    static synchronized String writeEnv(Context context, Settings settings) {
        try {
            String base = settings.env(locale(), StorageLocation.envValue(context));
            Settings.writeAtomic(context.getFilesDir(), ENV, loadAdvanced(context).applyTo(base, settings.frameGen()));
            return null;
        } catch (IOException | SecurityException failed) {
            Log.w("TotkMenu", "settings.env not written", failed);
            String why = failed.getMessage();
            return why == null || why.isEmpty() ? failed.getClass().getSimpleName() : why;
        }
    }

    /** The second panel relative to the main display, even when this menu was opened on the second one. */
    static int secondScreen(Context context) { return PresentationController.deviceSecondScreen(context); }
    /** A built-in second panel the companion uses (external, cast and overlay displays are ignored). */
    static boolean secondaryDisplay(Context context) { return secondScreen(context) == DisplayClass.BUILT_IN; }
}
