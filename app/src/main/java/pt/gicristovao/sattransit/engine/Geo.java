package pt.gicristovao.sattransit.engine;

/** Geometria terrestre: tempo sideral, rotações TEME↔ECEF, WGS-84. */
public final class Geo {
    private Geo() {}

    public static final double A = 6378.137;
    public static final double F = 1.0 / 298.257223563;
    public static final double B = A * (1.0 - F);
    public static final double E2 = F * (2.0 - F);
    private static final double DEG = Math.PI / 180.0;

    /** Diferença UT1−UTC (s). Pequena (< 0,9 s); mantida em 0 por agora. */
    public static double dut1 = 0.0;

    /** Tempo sideral médio de Greenwich (IAU-82), rad. */
    public static double gmst(double jdUtc) {
        double jdUt1 = jdUtc + dut1 / 86400.0;
        double t = (jdUt1 - 2451545.0) / 36525.0;
        double s = -6.2e-6 * t * t * t + 0.093104 * t * t
                + (876600.0 * 3600.0 + 8640184.812866) * t + 67310.54841;
        double r = (s * DEG / 240.0) % (2 * Math.PI);
        return r < 0 ? r + 2 * Math.PI : r;
    }

    /** TEME → ECEF (rotação pelo GMST; movimento polar ignorado). */
    public static void temeToEcef(double[] v, double g, double[] out) {
        double c = Math.cos(g), s = Math.sin(g);
        double x = c * v[0] + s * v[1];
        double y = -s * v[0] + c * v[1];
        out[0] = x; out[1] = y; out[2] = v[2];
    }

    /** ECEF → TEME. */
    public static void ecefToTeme(double[] v, double g, double[] out) {
        double c = Math.cos(g), s = Math.sin(g);
        double x = c * v[0] - s * v[1];
        double y = s * v[0] + c * v[1];
        out[0] = x; out[1] = y; out[2] = v[2];
    }

    /** Coordenadas geodésicas (graus, metros) → ECEF (km). */
    public static void geodeticToEcef(double latDeg, double lonDeg, double hM, double[] out) {
        double lat = latDeg * DEG, lon = lonDeg * DEG;
        double sl = Math.sin(lat), cl = Math.cos(lat);
        double nRad = A / Math.sqrt(1 - E2 * sl * sl);
        double h = hM / 1000.0;
        out[0] = (nRad + h) * cl * Math.cos(lon);
        out[1] = (nRad + h) * cl * Math.sin(lon);
        out[2] = (nRad * (1 - E2) + h) * sl;
    }

    /**
     * Ponto onde a recta corpo→satélite toca no elipsoide (ECEF, km).
     * Devolve {lat, lon} em graus, ou null se não houver interseção.
     */
    public static double[] groundPoint(double[] satEcef, double[] bodyEcef) {
        double ux = satEcef[0] - bodyEcef[0], uy = satEcef[1] - bodyEcef[1], uz = satEcef[2] - bodyEcef[2];
        double un = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= un; uy /= un; uz /= un;
        double k = A / B;
        double px = satEcef[0], py = satEcef[1], pz = satEcef[2] * k;
        double dx = ux, dy = uy, dz = uz * k;
        double a = dx * dx + dy * dy + dz * dz;
        double b = 2 * (px * dx + py * dy + pz * dz);
        double c = px * px + py * py + pz * pz - A * A;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return null;
        double s = (-b - Math.sqrt(disc)) / (2 * a);
        if (s <= 0) return null;
        double gx = satEcef[0] + ux * s, gy = satEcef[1] + uy * s, gz = satEcef[2] + uz * s;
        double lon = Math.atan2(gy, gx) / DEG;
        double r = Math.hypot(gx, gy);
        double lat = Math.atan2(gz * k * k, r) / DEG;
        return new double[]{lat, lon};
    }

    /** Distância de grande círculo (km). */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = lat1 * DEG, p2 = lat2 * DEG;
        double dl = (lon2 - lon1) * DEG;
        double h = Math.pow(Math.sin((p2 - p1) / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
        return 2 * 6371.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Elevação e azimute (graus) de um vector ECEF visto de lat/lon. */
    public static double[] altAz(double latDeg, double lonDeg, double[] dEcef) {
        double lat = latDeg * DEG, lon = lonDeg * DEG;
        double sl = Math.sin(lat), cl = Math.cos(lat), so = Math.sin(lon), co = Math.cos(lon);
        double e = -so * dEcef[0] + co * dEcef[1];
        double nn = -sl * co * dEcef[0] - sl * so * dEcef[1] + cl * dEcef[2];
        double u = cl * co * dEcef[0] + cl * so * dEcef[1] + sl * dEcef[2];
        double alt = Math.atan2(u, Math.hypot(e, nn)) / DEG;
        double az = Math.atan2(e, nn) / DEG;
        if (az < 0) az += 360;
        return new double[]{alt, az};
    }

    public static double angle(double[] a, double[] b) {
        double cx = a[1] * b[2] - a[2] * b[1];
        double cy = a[2] * b[0] - a[0] * b[2];
        double cz = a[0] * b[1] - a[1] * b[0];
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), dot);
    }
}
