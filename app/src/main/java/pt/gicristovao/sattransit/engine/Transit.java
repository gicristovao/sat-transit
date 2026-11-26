package pt.gicristovao.sattransit.engine;

/** Um trânsito previsto, visto de um observador. */
public final class Transit {
    public Ephemeris.Body body;
    /** Instante (JD UTC) de maior aproximação vista do observador. */
    public double jdObserver;
    /** Instante (JD UTC) em que a linha central passa no ponto mais próximo do observador. */
    public double jdCenter;
    /** Separação mínima ISS–centro do disco, vista do observador (″). */
    public double minSeparationArcsec;
    /** Raio angular do Sol/Lua (″). */
    public double bodyRadiusArcsec;
    /** Duração vista do observador (s); 0 se estiver fora da faixa. */
    public double durationObserverS;
    /** Duração na linha central (s). */
    public double durationCenterS;
    /** Distância do observador à linha central (km). */
    public double centerlineDistanceKm;
    /** Largura da faixa de visibilidade no chão (km). */
    public double bandWidthKm;
    /** Distância observador–satélite (km). */
    public double rangeKm;
    /** Tamanho angular do satélite (″). */
    public double satSizeArcsec;
    public double altitudeDeg;
    public double azimuthDeg;
    /** Ponto da linha central mais próximo do observador. */
    public double centerLat, centerLon;
    /** Rumo da linha central no chão (graus, 0 = norte). */
    public double trackBearingDeg;
    /** Linha central (lat/lon alternados) para desenhar em mapa. */
    public double[] centerline;
    /** Fração iluminada da Lua (0 = nova, 1 = cheia); só para trânsitos lunares. */
    public double moonIllumination;
    /** A Lua está a crescer (parte iluminada a oeste do disco, visto do hemisfério norte: lado direito). */
    public boolean moonWaxing;
    /** Idade do TLE (dias) no momento do trânsito. */
    public double tleAgeDays;

    /** O observador está dentro da faixa de visibilidade. */
    public boolean visibleFromObserver() {
        return minSeparationArcsec < bodyRadiusArcsec;
    }

    /** Distância do observador ao ponto mais próximo onde o trânsito é visível (0 se já estiver dentro). */
    public double distanceToBandKm() {
        return Math.max(0.0, centerlineDistanceKm - bandWidthKm / 2.0);
    }

    public static long jdToMillis(double jd) {
        return Math.round((jd - 2440587.5) * 86400000.0);
    }

    public static double millisToJd(long ms) {
        return ms / 86400000.0 + 2440587.5;
    }
}
