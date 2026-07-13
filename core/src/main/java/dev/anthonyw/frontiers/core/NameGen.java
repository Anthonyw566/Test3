package dev.anthonyw.frontiers.core;

/** Elite name generator: "Karok", "Belgath", "Selmaw"… */
public final class NameGen {
    private static final String[] START = {
            "Kar", "Mor", "Vel", "Dra", "Ul", "Bel", "Naz", "Thra", "Gor", "Sel", "Az", "Ir"};
    private static final String[] END = {
            "gath", "ok", "ira", "un", "eth", "maw", "rik", "osh", "ul", "ez", "ar", "im"};

    private NameGen() {
    }

    public static String generate(Rand random) {
        return START[random.nextInt(START.length)] + END[random.nextInt(END.length)];
    }
}
