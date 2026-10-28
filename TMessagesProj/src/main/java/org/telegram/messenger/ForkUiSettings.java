package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

/**
 * Look of this device's app, not synced: floating buttons of the chat list.
 */
public class ForkUiSettings {

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_ui", Activity.MODE_PRIVATE);
    }

    /** Stories bar above the chat list; when it is off, stories are not preloaded for it either. */
    public static boolean isStoriesBarShown() {
        return getPrefs().getBoolean("stories_bar", true);
    }

    public static void setStoriesBarShown(boolean shown) {
        getPrefs().edit().putBoolean("stories_bar", shown).apply();
    }

    /** "Post a story" camera button above the compose one. */
    public static boolean isStoryButtonShown() {
        return getPrefs().getBoolean("story_button", true);
    }

    public static void setStoryButtonShown(boolean shown) {
        getPrefs().edit().putBoolean("story_button", shown).apply();
    }

    /** "New message" button, it opens the contact picker. */
    public static boolean isComposeButtonShown() {
        return getPrefs().getBoolean("compose_button", true);
    }

    public static void setComposeButtonShown(boolean shown) {
        getPrefs().edit().putBoolean("compose_button", shown).apply();
    }
}
