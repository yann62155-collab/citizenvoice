package fr.citizen.voice;

import java.util.function.BiConsumer;

/** Petit relais de logs : le mod le branche sur le logger NeoForge, les tests sur la console. */
public final class VoiceLog {
    private static volatile BiConsumer<String, String> sink = (lvl, msg) -> System.out.println("[CitizenVoix/" + lvl + "] " + msg);

    private VoiceLog() {
    }

    public static void setSink(BiConsumer<String, String> s) {
        sink = s;
    }

    public static void info(String msg) {
        sink.accept("INFO", msg);
    }

    public static void warn(String msg) {
        sink.accept("WARN", msg);
    }

    public static void error(String msg, Throwable t) {
        sink.accept("ERROR", msg + (t != null ? " : " + t : ""));
    }
}
