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
    /** Meia largura da faixa para cada lado da linha central (km): +90° e −90° em relação ao rumo. */
    public double halfWidthRightKm, halfWidthLeftKm;
    /** Lado da linha central onde está o observador: +1 direita, −1 esquerda (em relação ao rumo). */
    public int observerSide = 1;
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
    /**
     * Trajetória da ISS no céu à volta do centro do disco, vista do observador:
     * pares (dx, dy) em segundos de arco, dx para a direita (azimute crescente), dy para cima.
     * Pontos por ordem do tempo (sentido do movimento).
     */
    public float[] skyTrack;
    /** Meia largura (″) da vista que contém o disco e a passagem. */
    public double skyFieldArcsec;
    /** Direção (rad, 0 = direita, π/2 = cima) do lado iluminado da Lua na vista do céu. */
    public double moonBrightLimbRad;
    /** Idade do TLE (dias) no momento do trânsito. */
    public double tleAgeDays;

    /** O observador está dentro da faixa de visibilidade. */
    public boolean visibleFromObserver() {
        return minSeparationArcsec < bodyRadiusArcsec;
    }

    /** Distância do observador ao ponto mais próximo onde o trânsito é visível (0 se já estiver dentro). */
    public double distanceToBandKm() {
        double half = observerSide >= 0 ? halfWidthRightKm : halfWidthLeftKm;
        return Math.max(0.0, centerlineDistanceKm - half);
    }

    public static long jdToMillis(double jd) {
        return Math.round((jd - 2440587.5) * 86400000.0);
    }

    public static double millisToJd(long ms) {
        return ms / 86400000.0 + 2440587.5;
    }
}
