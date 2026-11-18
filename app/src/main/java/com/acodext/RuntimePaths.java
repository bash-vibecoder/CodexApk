package com.acodext;

import android.content.Context;
import java.io.File;

final class RuntimePaths {
    // The full prefix has the same byte length as Termux's original prefix.
    // /data/data/com.termux/files -> /data/data/com.acodext/root
    static File root(Context context) { return new File(context.getApplicationInfo().dataDir, "root"); }
}
