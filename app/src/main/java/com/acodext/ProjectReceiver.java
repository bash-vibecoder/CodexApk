package com.acodext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Returns the visible app task to its project picker after an interactive Codex command exits. */
public final class ProjectReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"com.acodext.SHOW_PROJECTS".equals(intent.getAction())) return;
        Intent open = new Intent(context, MainActivity.class);
        open.setAction(intent.getAction());
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(open);
    }
}
