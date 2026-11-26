"""Motor de previsão de trânsitos (protótipo de validação)."""
import numpy as np
from skyfield.api import load, EarthSatellite, wgs84
from skyfield.framelib import itrs
from skyfield_data import get_skyfield_data_path

DATA = get_skyfield_data_path()
ts = load.timescale(builtin=True)
eph = load(f"{DATA}/de421.bsp")
earth, sun, moon = eph["earth"], eph["sun"], eph["moon"]
A, B = 6378.137, 6356.752314245  # elipsoide WGS84 (km)
R_SOL, R_LUA = 696_000.0, 1737.4  # raios (km)
ISS_TAMANHO_M = 109.0
H_M = 0.0  # altitude do observador (m)


def ponto_no_chao(sat_xyz, corpo_xyz):
    """Interseção da recta corpo→satélite com o elipsoide (frame ITRS, km).
    Devolve (lat, lon) em graus, NaN onde não há interseção."""
    u = sat_xyz - corpo_xyz
    u /= np.linalg.norm(u, axis=0)
    k = np.array([[1.0], [1.0], [A / B]])  # elipsoide -> esfera de raio A
    p, d = sat_xyz * k, u * k
    a = (d * d).sum(0)
    b = 2 * (p * d).sum(0)
    c = (p * p).sum(0) - A * A
    disc = b * b - 4 * a * c
    s = (-b - np.sqrt(np.where(disc >= 0, disc, np.nan))) / (2 * a)
    s = np.where(s > 0, s, np.nan)
    g = sat_xyz + u * s
    lon = np.degrees(np.arctan2(g[1], g[0]))
    # latitude geodésica a partir do ponto à superfície
    r = np.hypot(g[0], g[1])
    lat = np.degrees(np.arctan2(g[2] * (A / B) ** 2, r))
    return lat, lon


def dist_km(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dl = np.radians(lon2 - lon1)
    h = np.sin((p2 - p1) / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * 6371.0 * np.arcsin(np.sqrt(h))


def procurar(sat, lat0, lon0, raio_km, t0, dias, corpos=("Sol", "Lua"), passo_s=1.0):
    """Pesquisa grossa: linha central dentro do raio, a cada `passo_s` segundos."""
    eventos = []
    n_bloco = 86400  # um dia por bloco
    total = int(dias * 86400 / passo_s)
    for i0 in range(0, total, n_bloco):
        n = min(n_bloco, total - i0)
        segs = (i0 + np.arange(n)) * passo_s
        t = ts.tt_jd(t0.tt + segs / 86400)
        sat_xyz = sat.at(t).frame_xyz(itrs).km
        for nome in corpos:
            corpo = sun if nome == "Sol" else moon
            c_xyz = earth.at(t).observe(corpo).apparent().frame_xyz(itrs).km
            lat, lon = ponto_no_chao(sat_xyz, c_xyz)
            dd = dist_km(lat0, lon0, lat, lon)
            perto = np.where(dd < raio_km + 30)[0]
            if perto.size == 0:
                continue
            grupos = np.split(perto, np.where(np.diff(perto) > 5)[0] + 1)
            for g in grupos:
                j = g[np.nanargmin(dd[g])]
                eventos.append((nome, t0.tt + segs[j] / 86400))
    return eventos


def refinar(sat, nome, jd_tt, lat0, lon0):
    """Análise fina à volta de um evento, vista do observador (passo 2 ms)."""
    corpo = sun if nome == "Sol" else moon
    R = R_SOL if nome == "Sol" else R_LUA
    obs = earth + wgs84.latlon(lat0, lon0, elevation_m=H_M)
    segs = np.arange(-30, 30, 0.002)
    t = ts.tt_jd(jd_tt + segs / 86400)
    # separação topocêntrica ISS–centro do corpo
    topo = wgs84.latlon(lat0, lon0, elevation_m=H_M)
    s_app = (sat - topo).at(t)
    c_app = obs.at(t).observe(corpo).apparent()
    sep = s_app.separation_from(c_app).arcseconds()
    raio_ang = np.degrees(np.arcsin(R / c_app.distance().km)) * 3600
    # linha central: recta corpo->ISS no chão, perpendicular ao observador
    sat_xyz = sat.at(t).frame_xyz(itrs).km
    c_xyz = earth.at(t).observe(corpo).apparent().frame_xyz(itrs).km
    lat, lon = ponto_no_chao(sat_xyz, c_xyz)
    dd = dist_km(lat0, lon0, lat, lon)
    j = np.nanargmin(dd)
    # distância perpendicular à linha central (aprox. local plana)
    k = slice(max(j - 50, 0), j + 50)
    x = (lon[k] - lon0) * 111.32 * np.cos(np.radians(lat0))
    y = (lat[k] - lat0) * 110.57
    vx, vy = x[-1] - x[0], y[-1] - y[0]
    perp = abs(x[0] * vy - y[0] * vx) / np.hypot(vx, vy)
    rumo = (np.degrees(np.arctan2(vx, vy)) + 360) % 360
    dist_iss = s_app.distance().km
    largura = 2 * dist_iss[j] * np.radians(raio_ang[j] / 3600)
    dentro = sep < raio_ang
    alt, az, _ = s_app.altaz()
    i_min = np.argmin(sep)
    res = dict(
        corpo=nome,
        t_central=t[i_min],
        sep_min_arcsec=sep[i_min],
        raio_corpo_arcsec=raio_ang[i_min],
        duracao_s=dentro.sum() * 0.002,
        dist_linha_km=perp,
        largura_faixa_km=largura,
        dist_iss_km=dist_iss[i_min],
        tam_iss_arcsec=np.degrees(ISS_TAMANHO_M / 1000 / dist_iss[i_min]) * 3600,
        elev=alt.degrees[i_min],
        azim=az.degrees[i_min],
        rumo_linha=rumo,
        lat_linha=lat[j], lon_linha=lon[j],
    )
    return res
