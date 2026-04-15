package demo.video.interop;

import java.util.Locale;

/**
 * Small Java helper used to demo Kotlin-to-Java navigation, hover, and rename.
 */
public final class LegacyHandleFormatter {
    private LegacyHandleFormatter() {}

    public static String displayHandle(String rawHandle) {
        String normalized = rawHandle.trim().replace(' ', '_').toLowerCase(Locale.US);
        return "@" + normalized;
    }

    public static String slugFor(String text) {
        return text
                .trim()
                .toLowerCase(Locale.US)
                .replace(' ', '-')
                .replace('_', '-');
    }
}
