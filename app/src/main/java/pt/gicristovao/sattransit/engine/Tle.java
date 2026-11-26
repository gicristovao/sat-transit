package pt.gicristovao.sattransit.engine;

/** Conjunto de elementos de duas linhas (TLE) de um satélite. */
public final class Tle {
    public final String name;
    public final String line1;
    public final String line2;
    public final int catalogNumber;
    /** Época em dia juliano UTC. */
    public final double epochJd;
    public final double bstar;
    public final double inclinationRad;
    public final double raanRad;
    public final double eccentricity;
    public final double argPerigeeRad;
    public final double meanAnomalyRad;
    /** Movimento médio (Kozai) em rad/min. */
    public final double meanMotionRadMin;

    public Tle(String name, String line1, String line2) {
        this.name = name == null ? "" : name.trim();
        this.line1 = line1.trim();
        this.line2 = line2.trim();
        if (!this.line1.startsWith("1 ") || !this.line2.startsWith("2 ")) {
            throw new IllegalArgumentException("TLE inválido");
        }
        catalogNumber = Integer.parseInt(this.line1.substring(2, 7).trim());
        int yy = Integer.parseInt(this.line1.substring(18, 20).trim());
        double days = Double.parseDouble(this.line1.substring(20, 32).trim());
        int year = yy < 57 ? 2000 + yy : 1900 + yy;
        epochJd = julianDay(year, 1, 1) - 1.0 + days;
        bstar = parseExp(this.line1.substring(53, 61));
        double deg = Math.PI / 180.0;
        inclinationRad = Double.parseDouble(this.line2.substring(8, 16).trim()) * deg;
        raanRad = Double.parseDouble(this.line2.substring(17, 25).trim()) * deg;
        eccentricity = Double.parseDouble("0." + this.line2.substring(26, 33).trim());
        argPerigeeRad = Double.parseDouble(this.line2.substring(34, 42).trim()) * deg;
        meanAnomalyRad = Double.parseDouble(this.line2.substring(43, 51).trim()) * deg;
        double revPerDay = Double.parseDouble(this.line2.substring(52, 63).trim());
        meanMotionRadMin = revPerDay * 2.0 * Math.PI / 1440.0;
    }

    /** Lê o primeiro TLE de um texto com 2 ou 3 linhas por satélite. */
    public static Tle parse(String text) {
        String[] lines = text.trim().split("\\r?\\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("1 ") && lines[i + 1].startsWith("2 ")) {
                String name = i > 0 ? lines[i - 1] : "";
                return new Tle(name, lines[i], lines[i + 1]);
            }
        }
        throw new IllegalArgumentException("Nenhum TLE encontrado");
    }

    private static double parseExp(String field) {
        String s = field.trim();
        if (s.isEmpty()) return 0.0;
        char sign = s.charAt(0);
        if (sign == '-' || sign == '+') s = s.substring(1); else sign = '+';
        int expPos = Math.max(s.lastIndexOf('-'), s.lastIndexOf('+'));
        double mant = Double.parseDouble("0." + s.substring(0, expPos));
        int exp = Integer.parseInt(s.substring(expPos));
        double v = mant * Math.pow(10, exp);
        return sign == '-' ? -v : v;
    }

    /** Dia juliano à meia-noite UTC de uma data do calendário gregoriano. */
    public static double julianDay(int year, int month, int day) {
        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        long jdn = day + (153L * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
        return jdn - 0.5;
    }
}
