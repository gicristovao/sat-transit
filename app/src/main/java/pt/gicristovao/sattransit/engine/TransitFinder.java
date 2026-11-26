package pt.gicristovao.sattransit.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Procura trânsitos de um satélite pelo Sol e pela Lua.
 *
 * 1. Pesquisa grossa: a cada segundo, a recta corpo→satélite é prolongada até ao chão
 *    (linha central). Guardam-se as passagens a menos de raio+30 km do observador.
 * 2. Refinamento: geometria vista do observador (passos de 2 ms), linha central,
 *    duração e largura da faixa no chão.
 */
public final class TransitFinder {
    public interface Progress {
        /** Devolve false para cancelar. */
        boolean onProgress(double fraction);
    }

    private static final double SEC = 1.0 / 86400.0;
    private static final double ARCSEC = 180.0 / Math.PI * 3600.0;

    private final Sgp4 sgp4;
    private final Ephemeris eph;
    private final double satSizeM;
    private final double epochJd;

    private final double[] satT = new double[3], satE = new double[3], bodT = new double[3], bodE = new double[3];
    private final double[] obsT = new double[3], s = new double[3], b = new double[3];

    public TransitFinder(Tle tle, Ephemeris eph, double satSizeM) {
        this.sgp4 = new Sgp4(tle);
        this.eph = eph;
        this.satSizeM = satSizeM;
        this.epochJd = tle.epochJd;
    }

    public List<Transit> search(double lat, double lon, double hM, double radiusKm,
                                double jdStart, double days, Progress progress) {
        List<Transit> out = new ArrayList<>();
        double[] obsE = new double[3];
        Geo.geodeticToEcef(lat, lon, hM, obsE);
        double obsN = norm(obsE);
        double maxAngle = (2600.0 + radiusKm) / 6371.0;
        double cosMax = Math.cos(maxAngle);
        double jdEnd = Math.min(jdStart + days, eph.lastJd());
        jdStart = Math.max(jdStart, eph.firstJd());
        int total = (int) ((jdEnd - jdStart) / SEC);
        double limit = radiusKm + 45.0; // margem para a meia largura da faixa

        for (Ephemeris.Body body : Ephemeris.Body.values()) {
            int bestI = -1;
            double bestD = Double.MAX_VALUE;
            int lastHit = Integer.MIN_VALUE;
            for (int i = 0; i < total; i++) {
                if ((i & 0xFFFF) == 0 && progress != null) {
                    double f = (body.ordinal() + (double) i / total) / 2.0;
                    if (!progress.onProgress(f * 0.9)) return out;
                }
                double jd = jdStart + i * SEC;
                double g = Geo.gmst(jd);
                sgp4.positionAt(jd, satT);
                Geo.temeToEcef(satT, g, satE);
                double cosA = dot(satE, obsE) / (norm(satE) * obsN);
                if (cosA < cosMax) {
                    if (bestI >= 0 && i - lastHit > 5) { addCandidate(out, body, jdStart + bestI * SEC, lat, lon, hM); bestI = -1; bestD = Double.MAX_VALUE; }
                    continue;
                }
                eph.position(body, jd, bodT);
                Geo.temeToEcef(bodT, g, bodE);
                double[] gp = Geo.groundPoint(satE, bodE);
                if (gp == null) continue;
                double d = Geo.distanceKm(lat, lon, gp[0], gp[1]);
                if (d < limit) {
                    if (bestI >= 0 && i - lastHit > 5) { addCandidate(out, body, jdStart + bestI * SEC, lat, lon, hM); bestI = -1; bestD = Double.MAX_VALUE; }
                    if (d < bestD) { bestD = d; bestI = i; }
                    lastHit = i;
                } else if (bestI >= 0 && i - lastHit > 5) {
                    addCandidate(out, body, jdStart + bestI * SEC, lat, lon, hM);
                    bestI = -1; bestD = Double.MAX_VALUE;
                }
            }
            if (bestI >= 0) addCandidate(out, body, jdStart + bestI * SEC, lat, lon, hM);
        }
        List<Transit> filtered = new ArrayList<>();
        // Vale qualquer sítio dentro do raio onde a ISS cruze o disco (não só a linha central).
        for (Transit t : out) if (t.distanceToBandKm() <= radiusKm) filtered.add(t);
        Collections.sort(filtered, (x, y) -> Double.compare(x.jdObserver, y.jdObserver));
        if (progress != null) progress.onProgress(1.0);
        return filtered;
    }

    private void addCandidate(List<Transit> out, Ephemeris.Body body, double jd, double lat, double lon, double hM) {
        Transit t = refine(body, jd, lat, lon, hM);
        if (t != null) out.add(t);
    }

    /** Ponto da linha central no instante jd, ou null. */
    private double[] ground(Ephemeris.Body body, double jd) {
        double g = Geo.gmst(jd);
        sgp4.positionAt(jd, satT);
        Geo.temeToEcef(satT, g, satE);
        eph.position(body, jd, bodT);
        Geo.temeToEcef(bodT, g, bodE);
        return Geo.groundPoint(satE, bodE);
    }

    /** Separação (rad) satélite–centro do corpo e raio angular (rad), vistos de obsE. */
    private double separation(Ephemeris.Body body, double jd, double[] obsE, double[] radiusOut) {
        double g = Geo.gmst(jd);
        Geo.ecefToTeme(obsE, g, obsT);
        sgp4.positionAt(jd, satT);
        eph.position(body, jd, bodT);
        for (int k = 0; k < 3; k++) { s[k] = satT[k] - obsT[k]; b[k] = bodT[k] - obsT[k]; }
        if (radiusOut != null) radiusOut[0] = Math.asin(Ephemeris.radiusKm(body) / norm(b));
        return Geo.angle(s, b);
    }

    /** Menor separação (rad) num intervalo à volta de jdC; devolve {sepMin, jdMin}. */
    private double[] minSeparation(Ephemeris.Body body, double jdC, double halfS, double stepS, double[] obsE) {
        double best = Double.MAX_VALUE, bestJd = jdC;
        for (double dt = -halfS; dt <= halfS; dt += stepS) {
            double jd = jdC + dt * SEC;
            double sep = separation(body, jd, obsE, null);
            if (sep < best) { best = sep; bestJd = jd; }
        }
        return new double[]{best, bestJd};
    }

    private Transit refine(Ephemeris.Body body, double jd0, double lat, double lon, double hM) {
        // 1) instante da linha central mais próxima (10 ms)
        double bestD = Double.MAX_VALUE, jdC = jd0;
        for (double dt = -3.0; dt <= 3.0; dt += 0.01) {
            double[] gp = ground(body, jd0 + dt * SEC);
            if (gp == null) continue;
            double d = Geo.distanceKm(lat, lon, gp[0], gp[1]);
            if (d < bestD) { bestD = d; jdC = jd0 + dt * SEC; }
        }
        double[] p1 = ground(body, jdC - 0.5 * SEC);
        double[] p2 = ground(body, jdC + 0.5 * SEC);
        if (p1 == null || p2 == null) return null;

        // plano local centrado no observador (km)
        double kx = 111.32 * Math.cos(Math.toRadians(lat)), ky = 110.57;
        double x1 = (p1[1] - lon) * kx, y1 = (p1[0] - lat) * ky;
        double x2 = (p2[1] - lon) * kx, y2 = (p2[0] - lat) * ky;
        double vx = x2 - x1, vy = y2 - y1;
        double vv = vx * vx + vy * vy;
        double u = -(x1 * vx + y1 * vy) / vv;
        double fx = x1 + u * vx, fy = y1 + u * vy;
        Transit t = new Transit();
        t.body = body;
        t.centerlineDistanceKm = Math.hypot(fx, fy);
        t.centerLat = lat + fy / ky;
        t.centerLon = lon + fx / kx;
        t.jdCenter = jdC + (u - 0.5) * SEC;
        double bearing = Math.toDegrees(Math.atan2(vx, vy));
        t.trackBearingDeg = bearing < 0 ? bearing + 360 : bearing;
        t.observerSide = (-fx * vy + fy * vx) >= 0 ? 1 : -1;

        // 2) vista do observador
        double[] obsE = new double[3];
        Geo.geodeticToEcef(lat, lon, hM, obsE);
        double[] coarse = minSeparation(body, t.jdCenter, 60.0, 0.1, obsE);
        double[] fine = minSeparation(body, coarse[1], 0.2, 0.002, obsE);
        t.jdObserver = fine[1];
        double[] rad = new double[1];
        double sepMin = separation(body, t.jdObserver, obsE, rad);
        t.minSeparationArcsec = sepMin * ARCSEC;
        t.bodyRadiusArcsec = rad[0] * ARCSEC;
        t.durationObserverS = duration(body, t.jdObserver, obsE);
        // alt/az e distância
        double g = Geo.gmst(t.jdObserver);
        Geo.ecefToTeme(obsE, g, obsT);
        sgp4.positionAt(t.jdObserver, satT);
        for (int k = 0; k < 3; k++) s[k] = satT[k] - obsT[k];
        t.rangeKm = norm(s);
        double[] dE = new double[3];
        Geo.temeToEcef(s, g, dE);
        double[] aa = Geo.altAz(lat, lon, dE);
        t.altitudeDeg = aa[0];
        t.azimuthDeg = aa[1];
        if (t.altitudeDeg <= 0) return null;
        t.satSizeArcsec = satSizeM / 1000.0 / t.rangeKm * ARCSEC;

        // 3) duração na linha central e largura da faixa no chão
        double[] cE = new double[3];
        Geo.geodeticToEcef(t.centerLat, t.centerLon, 0, cE);
        double[] cMin = minSeparation(body, t.jdCenter, 1.0, 0.01, cE);
        t.durationCenterS = duration(body, cMin[1], cE);
        double perp = Math.toRadians(t.trackBearingDeg + 90.0);
        t.halfWidthRightKm = halfWidth(body, t, perp, 1);
        t.halfWidthLeftKm = halfWidth(body, t, perp, -1);
        t.bandWidthKm = t.halfWidthRightKm + t.halfWidthLeftKm;

        // 4) linha central para o mapa (±90 s, passo 1 s)
        List<Double> pts = new ArrayList<>();
        for (int i = -90; i <= 90; i++) {
            double[] gp = ground(body, t.jdCenter + i * SEC);
            if (gp != null) { pts.add(gp[0]); pts.add(gp[1]); }
        }
        t.centerline = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) t.centerline[i] = pts.get(i);
        t.tleAgeDays = t.jdObserver - epochJd;
        if (body == Ephemeris.Body.MOON) moonPhase(t);
        skyView(t, lat, lon, obsE);
        return t;
    }

    /** Alt/az (graus) do satélite e do corpo vistos de obsE no instante jd: {altS, azS, altB, azB}. */
    private double[] altAzPair(Ephemeris.Body body, double jd, double lat, double lon, double[] obsE) {
        double g = Geo.gmst(jd);
        Geo.ecefToTeme(obsE, g, obsT);
        sgp4.positionAt(jd, satT);
        eph.position(body, jd, bodT);
        for (int k = 0; k < 3; k++) { s[k] = satT[k] - obsT[k]; b[k] = bodT[k] - obsT[k]; }
        double[] e1 = new double[3], e2 = new double[3];
        Geo.temeToEcef(s, g, e1);
        Geo.temeToEcef(b, g, e2);
        double[] a1 = Geo.altAz(lat, lon, e1), a2 = Geo.altAz(lat, lon, e2);
        return new double[]{a1[0], a1[1], a2[0], a2[1]};
    }

    /** Trajetória no céu à volta do disco e direção do lado iluminado da Lua. */
    private void skyView(Transit t, double lat, double lon, double[] obsE) {
        double r = t.bodyRadiusArcsec;
        double field = Math.max(1.7 * r, 1.25 * t.minSeparationArcsec + 0.3 * r);
        t.skyFieldArcsec = field;
        double keep = Math.max(1.6 * field, 4.0 * r);
        java.util.ArrayList<Float> pts = new java.util.ArrayList<>();
        java.util.ArrayList<Float> ts = new java.util.ArrayList<>();
        for (double dt = -40.0; dt <= 40.0; dt += 0.05) {
            double[] p = altAzPair(t.body, t.jdObserver + dt * SEC, lat, lon, obsE);
            double daz = p[1] - p[3];
            if (daz > 180) daz -= 360;
            if (daz < -180) daz += 360;
            double dx = daz * Math.cos(Math.toRadians(p[2])) * 3600.0;
            double dy = (p[0] - p[2]) * 3600.0;
            if (Math.hypot(dx, dy) <= keep) { pts.add((float) dx); pts.add((float) dy); ts.add((float) dt); }
        }
        t.skyTrack = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) t.skyTrack[i] = pts.get(i);
        t.skyTrackT = new float[ts.size()];
        for (int i = 0; i < ts.size(); i++) t.skyTrackT[i] = ts.get(i);
        if (t.body == Ephemeris.Body.MOON) {
            // direção do Sol a partir da Lua, no céu do observador
            double g = Geo.gmst(t.jdObserver);
            Geo.ecefToTeme(obsE, g, obsT);
            double[] m = new double[3], sn = new double[3];
            eph.position(Ephemeris.Body.MOON, t.jdObserver, m);
            eph.position(Ephemeris.Body.SUN, t.jdObserver, sn);
            for (int k = 0; k < 3; k++) { m[k] -= obsT[k]; sn[k] -= obsT[k]; }
            double[] em = new double[3], es = new double[3];
            Geo.temeToEcef(m, g, em);
            Geo.temeToEcef(sn, g, es);
            double[] am = Geo.altAz(lat, lon, em), as = Geo.altAz(lat, lon, es);
            // rumo inicial do grande círculo Lua→Sol no referencial alt/az (0 = cima, 90 = direita)
            double a1 = Math.toRadians(am[0]), a2 = Math.toRadians(as[0]);
            double dA = Math.toRadians(as[1] - am[1]);
            double y = Math.sin(dA) * Math.cos(a2);
            double x = Math.cos(a1) * Math.sin(a2) - Math.sin(a1) * Math.cos(a2) * Math.cos(dA);
            double brg = Math.atan2(y, x); // 0 = cima, +π/2 = direita
            t.moonBrightLimbRad = Math.PI / 2 - brg;
        }
    }

    /** Fase da Lua: fração iluminada e se está a crescer. */
    private void moonPhase(Transit t) {
        double[] m = new double[3], sn = new double[3];
        eph.position(Ephemeris.Body.MOON, t.jdObserver, m);
        eph.position(Ephemeris.Body.SUN, t.jdObserver, sn);
        double[] toSun = {sn[0] - m[0], sn[1] - m[1], sn[2] - m[2]};
        double[] toEarth = {-m[0], -m[1], -m[2]};
        double phaseAngle = Geo.angle(toSun, toEarth);
        t.moonIllumination = (1 + Math.cos(phaseAngle)) / 2;
        double ra = Math.atan2(m[1], m[0]) - Math.atan2(sn[1], sn[0]);
        ra = Math.toDegrees(ra) % 360;
        if (ra < 0) ra += 360;
        t.moonWaxing = ra < 180;
    }

    /**
     * Avalia um trânsito já encontrado visto de outro ponto (ex.: o pino no mapa).
     * Preenche hora, separação mínima, raio do disco, duração, distância, elevação e azimute.
     */
    public Transit evaluateAt(Transit base, double lat, double lon, double hM) {
        double[] obsE = new double[3];
        Geo.geodeticToEcef(lat, lon, hM, obsE);
        double[] coarse = minSeparation(base.body, base.jdCenter, 60.0, 0.1, obsE);
        double[] fine = minSeparation(base.body, coarse[1], 0.2, 0.002, obsE);
        Transit t = new Transit();
        t.body = base.body;
        t.jdCenter = base.jdCenter;
        t.jdObserver = fine[1];
        double[] rad = new double[1];
        double sep = separation(base.body, t.jdObserver, obsE, rad);
        t.minSeparationArcsec = sep * ARCSEC;
        t.bodyRadiusArcsec = rad[0] * ARCSEC;
        t.durationObserverS = duration(base.body, t.jdObserver, obsE);
        double g = Geo.gmst(t.jdObserver);
        Geo.ecefToTeme(obsE, g, obsT);
        sgp4.positionAt(t.jdObserver, satT);
        for (int k = 0; k < 3; k++) s[k] = satT[k] - obsT[k];
        t.rangeKm = norm(s);
        double[] dE = new double[3];
        Geo.temeToEcef(s, g, dE);
        double[] aa = Geo.altAz(lat, lon, dE);
        t.altitudeDeg = aa[0];
        t.azimuthDeg = aa[1];
        t.satSizeArcsec = satSizeM / 1000.0 / t.rangeKm * ARCSEC;
        t.moonIllumination = base.moonIllumination;
        t.moonWaxing = base.moonWaxing;
        skyView(t, lat, lon, obsE);
        return t;
    }

    /** Duração (s) em que o satélite está sobre o disco, visto de obsE. */
    private double duration(Ephemeris.Body body, double jdMin, double[] obsE) {
        double[] rad = new double[1];
        int inside = 0;
        for (double dt = -2.5; dt <= 2.5; dt += 0.002) {
            double sep = separation(body, jdMin + dt * SEC, obsE, rad);
            if (sep < rad[0]) inside++;
        }
        return inside * 0.002;
    }

    /** Meia largura da faixa (km) para um dos lados da linha central. */
    private double halfWidth(Ephemeris.Body body, Transit t, double dirRad, int side) {
        double lo = 0, hi = 30;
        double kx = 111.32 * Math.cos(Math.toRadians(t.centerLat)), ky = 110.57;
        double[] e = new double[3], rad = new double[1];
        for (int it = 0; it < 22; it++) {
            double m = (lo + hi) / 2;
            double la = t.centerLat + side * m * Math.cos(dirRad) / ky;
            double lo2 = t.centerLon + side * m * Math.sin(dirRad) / kx;
            Geo.geodeticToEcef(la, lo2, 0, e);
            double[] mn = minSeparation(body, t.jdCenter, 2.0, 0.02, e);
            double[] mf = minSeparation(body, mn[1], 0.03, 0.002, e);
            separation(body, mf[1], e, rad);
            if (mf[0] < rad[0]) lo = m; else hi = m;
        }
        return lo;
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
}
