package pt.gicristovao.sattransit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.util.List;
import org.junit.Test;

/**
 * Valida o motor contra os resultados de referência (Skyfield/DE421 e transit-finder.com)
 * para o observador 41,06241 / −8,63613 / 105 m, raio 100 km, 26/11–16/12/2026,
 * com o TLE da ISS de 25/11/2026 03:19 UTC.
 */
public class TransitFinderTest {
    static final Tle TLE = new Tle("ISS (ZARYA)",
            "1 25544U 98067A   26329.13827949  .00005332  00000-0  10279-3 0  9990",
            "2 25544  51.6318 223.5844 0007440  62.9833 297.1915 15.49581308596705");

    @Test
    public void sgp4MatchesReference() {
        Sgp4 s = new Sgp4(TLE);
        double[] r = new double[3];
        s.propagate(1440, r);
        assertEquals(5188.822483, r[0], 1e-5);
        assertEquals(4387.427606, r[1], 1e-5);
        assertEquals(-237.391907, r[2], 1e-5);
    }

    @Test
    public void findsTheValidatedTransits() throws Exception {
        Ephemeris eph = new Ephemeris(new FileInputStream("src/main/assets/ephem_sun_moon.bin"));
        TransitFinder f = new TransitFinder(TLE, eph, 109);
        List<Transit> r = f.search(41.06241, -8.63613, 105, 100, Tle.julianDay(2026, 11, 26), 21, null);
        // 5 trânsitos: os 4 do transit-finder (linha central no raio) + Lua 06/12,
        // cuja linha central fica a 102,8 km mas a faixa entra no raio.
        assertEquals(5, r.size());
        assertTrue(r.get(1).centerlineDistanceKm > 100 && r.get(1).distanceToBandKm() < 100);
        r.remove(1);
        // Skyfield (s desde a meia-noite UTC do dia), linha central (km), largura (km)
        double[][] ref = {
                {Tle.julianDay(2026, 11, 28), 11 * 3600 + 59 * 60 + 59.87, 52.13, 13.09},
                {Tle.julianDay(2026, 12, 10), 15 * 3600 + 21 * 60 + 10.82, 50.86, 25.94},
                {Tle.julianDay(2026, 12, 11), 14 * 3600 + 33 * 60 + 25.95, 14.92, 14.54},
                {Tle.julianDay(2026, 12, 15), 12 * 3600 + 57 * 60 + 8.32, 62.83, 15.52},
        };
        for (int i = 0; i < 4; i++) {
            Transit t = r.get(i);
            double sec = (t.jdObserver - ref[i][0]) * 86400.0;
            assertEquals("hora " + i, ref[i][1], sec, 0.1);
            assertEquals("linha " + i, ref[i][2], t.centerlineDistanceKm, 0.3);
            assertEquals("largura " + i, ref[i][3], t.bandWidthKm, 0.3);
        }
        // transit-finder.com, 28/11 12:00:00,58 (o mais próximo da época do TLE)
        double tf = 12 * 3600 + 0.58;
        double ours = (r.get(0).jdObserver - Tle.julianDay(2026, 11, 28)) * 86400.0;
        assertTrue(Math.abs(ours - tf) < 2.0);
    }
}
