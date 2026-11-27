# SatTransit

App Android que prevê trânsitos de satélites (para já a ISS) à frente do Sol e da Lua, pensada para fotógrafos.

## Instalar no telemóvel

Cada alteração ao código gera automaticamente uma versão nova em **Releases** (coluna da direita da página do repositório). Abre a versão mais recente no telemóvel, descarrega o ficheiro `SatTransit-x.y.z.apk` e instala-o. Na primeira vez, o Android pede autorização para instalar apps do browser.

## Versões

Formato `MAIOR.MENOR.CORREÇÃO`, por exemplo 0.3.0, 0.3.1, 0.4.0.
- `MAIOR.MENOR` está no ficheiro `VERSION`. Sobe o MENOR com cada funcionalidade nova e o MAIOR com mudanças grandes.
- `CORREÇÃO` é calculada automaticamente: conta as alterações desde a última mudança de `VERSION` e volta a 0 quando `VERSION` muda.
- A app usa o contador de compilações do GitHub (o último número da etiqueta, por exemplo `v0.3.0.12`) para saber se há uma versão nova.

## Estrutura

- `app/src/main/java/.../engine/` — motor de cálculo (Java puro, sem dependências): SGP4, efemérides do Sol e da Lua, linha central, duração e largura da faixa.
- `app/src/main/java/.../data/weather/` — previsão do céu: IPMA (Portugal) e Open-Meteo.
- `app/src/main/java/.../ui/` — interface (Jetpack Compose).
- `app/src/main/assets/ephem_sun_moon.bin` — posições do Sol e da Lua de hora a hora (2026-11 a 2030-12), geradas com `tools/gen_ephemeris.py` a partir do JPL DE421.
- `app/src/test/` — testes do motor contra as previsões de referência (Skyfield e transit-finder.com).
- `tools/` — notebook de validação e gerador das efemérides.

## Dados

- Órbitas: CelesTrak (com cópia em cache; no máximo um download a cada 2 h), com a ARISS como alternativa para a ISS.
- Sol e Lua: calculados no telemóvel, sem rede.
- Céu: em Portugal, IPMA (localidade de previsão mais próxima, ficheiros do ipma.pt) com as % de nuvens por camada do Open-Meteo; fora de Portugal, só Open-Meteo. Até 10 dias antes do trânsito. Os dados do Open-Meteo são CC BY 4.0 e a API gratuita é só para uso não comercial.
