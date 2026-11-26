package pt.gicristovao.sattransit.engine;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Posições geocêntricas aparentes do Sol e da Lua (TEME, km), interpoladas
 * a partir de uma tabela horária gerada com o JPL DE421 (tools/gen_ephemeris.py).
 */
public final class Ephemeris {
    public enum Body { SUN, MOON }

    public static final double SUN_RADIUS_KM = 696_000.0;
    public static final double MOON_RADIUS_KM = 1737.4;

    private final double jd0;
    private final double step;
    private final int n;
    private final double[] sun;
    private final double[] moon;

    public Ephemeris(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (magic[0] != 'S' || magic[1] != 'T' || magic[2] != 'E' || magic[3] != 'P') {
            throw new IOException("Ficheiro de efemérides inválido");
        }
        d.readInt();
        jd0 = d.readDouble();
        step = d.readDouble();
        n = d.readInt();
        sun = new double[n * 3];
        moon = new double[n * 3];
        for (int i = 0; i < n * 3; i++) sun[i] = d.readDouble();
        for (int i = 0; i < n * 3; i++) moon[i] = d.readDouble();
    }

    public double firstJd() { return jd0 + step; }
    public double lastJd() { return jd0 + (n - 3) * step; }

    public static double radiusKm(Body b) {
        return b == Body.SUN ? SUN_RADIUS_KM : MOON_RADIUS_KM;
    }

    /** Interpolação de Lagrange com 4 pontos. */
    public void position(Body body, double jdUtc, double[] out) {
        double[] tab = body == Body.SUN ? sun : moon;
        double x = (jdUtc - jd0) / step;
        int i = (int) Math.floor(x) - 1;
        if (i < 0 || i + 3 >= n) throw new IllegalArgumentException("Data fora da tabela de efemérides");
        double p = x - (i + 1); // 0..1 entre os pontos i+1 e i+2
        double w0 = -p * (p - 1) * (p - 2) / 6.0;
        double w1 = (p + 1) * (p - 1) * (p - 2) / 2.0;
        double w2 = -(p + 1) * p * (p - 2) / 2.0;
        double w3 = (p + 1) * p * (p - 1) / 6.0;
        for (int k = 0; k < 3; k++) {
            out[k] = w0 * tab[i * 3 + k] + w1 * tab[(i + 1) * 3 + k]
                    + w2 * tab[(i + 2) * 3 + k] + w3 * tab[(i + 3) * 3 + k];
        }
    }
}
