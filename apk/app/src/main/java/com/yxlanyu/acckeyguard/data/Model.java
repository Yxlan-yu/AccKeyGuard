package com.yxlanyu.acckeyguard.data;

/** One installed accessibility service. */
public final class Model {

    public final String pkg;
    public final String cls;
    public final String label;
    public final boolean system;

    public Model(String pkg, String cls, String label, boolean system) {
        this.pkg = pkg;
        this.cls = cls;
        this.label = label;
        this.system = system;
    }

    public String comp() {
        return pkg + "/" + cls;
    }

    /** Expands the {@code .Foo} shorthand so two spellings compare equal. */
    public static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int i = s.indexOf('/');
        if (i <= 0 || i >= s.length() - 1) {
            return s;
        }
        String p = s.substring(0, i);
        String c = s.substring(i + 1);
        if (c.startsWith(".")) {
            c = p + c;
        }
        return p + "/" + c;
    }
}
