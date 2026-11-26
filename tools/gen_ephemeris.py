"""Gera a tabela de efemérides do Sol e da Lua usada pela app.

Posições geocêntricas aparentes (com aberração e tempo-luz) no referencial TEME,
em km, de hora a hora (UTC), a partir do JPL DE421 via Skyfield.
Formato binário big-endian:
  4 bytes  'STEP'
  int32    versão (1)
  float64  JD UTC do primeiro ponto
  float64  passo em dias
  int32    n pontos
  float64  Sol  [n][3]
  float64  Lua  [n][3]
Uso: python3 gen_ephemeris.py saída.bin
"""
import sys, struct
import numpy as np
from skyfield.api import load
from skyfield.sgp4lib import TEME
from skyfield_data import get_skyfield_data_path

ts = load.timescale(builtin=True)
eph = load(get_skyfield_data_path() + "/de421.bsp")
earth = eph["earth"]

INICIO = (2026, 11, 1)
FIM = (2031, 1, 1)
PASSO_H = 1

t0 = ts.utc(*INICIO)
t1 = ts.utc(*FIM)
n = int(round((t1.ut1 - t0.ut1) * 24 / PASSO_H)) + 1
horas = np.arange(n) * PASSO_H
t = ts.utc(INICIO[0], INICIO[1], INICIO[2], horas)
jd0 = 2440587.5 + (t0.utc_datetime().timestamp() / 86400.0)

out = [b"STEP", struct.pack(">i", 1), struct.pack(">d", jd0), struct.pack(">d", PASSO_H / 24.0), struct.pack(">i", n)]
for corpo in ("sun", "moon"):
    xyz = earth.at(t).observe(eph[corpo]).apparent().frame_xyz(TEME).km.T
    out.append(xyz.astype(">f8").tobytes())
with open(sys.argv[1], "wb") as f:
    for b in out:
        f.write(b)
print(n, "pontos, JD0", jd0)
