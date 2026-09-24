package com.danceworldquest.app;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;

public final class PoseBridge {
    private final Activity activity;

    public PoseBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void start(String mode) {
        final String safeMode = (mode == null || mode.trim().isEmpty()) ? "free" : mode.trim();
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(activity, PoseActivity.class);
            intent.putExtra(PoseActivity.EXTRA_MODE, safeMode);
            activity.startActivity(intent);
        });
    }

    @JavascriptInterface
    public String getLastResult() {
        return activity
                .getSharedPreferences(PoseActivity.PREFS, Activity.MODE_PRIVATE)
                .getString(PoseActivity.KEY_LAST_RESULT, "{}");
    }
}
