package pt.gicristovao.sattransit.engine;

/**
 * Propagador SGP4 (órbitas próximas, período &lt; 225 min), modo "improved",
 * constantes WGS-72. Tradução da implementação de referência de Vallado et al. (2006),
 * a mesma usada pela biblioteca python-sgp4 / Skyfield.
 * Devolve posição em km no referencial TEME.
 */
public final class Sgp4 {
    private static final double RE = 6378.135;
    private static final double MU = 398600.8;
    private static final double XKE = 60.0 / Math.sqrt(RE * RE * RE / MU);
    private static final double J2 = 0.001082616;
    private static final double J3 = -0.00000253881;
    private static final double J4 = -0.00000165597;
    private static final double J3OJ2 = J3 / J2;
    private static final double X2O3 = 2.0 / 3.0;
    private static final double TWOPI = 2.0 * Math.PI;

    private final double epochJd;
    private final double bstar, ecco, inclo, nodeo, argpo, mo;
    private double noUnkozai, con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof,
            sinmao, t2cof, t3cof, t4cof, t5cof, x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof,
            nodecf, aycof;
    private boolean isimp;

    public Sgp4(Tle tle) {
        epochJd = tle.epochJd;
        bstar = tle.bstar;
        ecco = tle.eccentricity;
        inclo = tle.inclinationRad;
        nodeo = tle.raanRad;
        argpo = tle.argPerigeeRad;
        mo = tle.meanAnomalyRad;
        init(tle.meanMotionRadMin);
    }

    private void init(double noKozai) {
        double ss = 78.0 / RE + 1.0;
        double qzms2t = Math.pow((120.0 - 78.0) / RE, 4);
        // initl
        double eccsq = ecco * ecco;
        double omeosq = 1.0 - eccsq;
        double rteosq = Math.sqrt(omeosq);
        double cosio = Math.cos(inclo);
        double cosio2 = cosio * cosio;
        double ak = Math.pow(XKE / noKozai, X2O3);
        double d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0));
        del = d1 / (adel * adel);
        noUnkozai = noKozai / (1.0 + del);
        double ao = Math.pow(XKE / noUnkozai, X2O3);
        double sinio = Math.sin(inclo);
        double po = ao * omeosq;
        double con42 = 1.0 - 5.0 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po;
        double rp = ao * (1.0 - ecco);
        if (TWOPI / noUnkozai >= 225.0) {
            throw new IllegalArgumentException("Órbita de espaço profundo não suportada");
        }

        isimp = rp < (220.0 / RE + 1.0);
        double sfour = ss;
        double qzms24 = qzms2t;
        double perige = (rp - 1.0) * RE;
        if (perige < 156.0) {
            sfour = perige - 78.0;
            if (perige < 98.0) sfour = 20.0;
            qzms24 = Math.pow((120.0 - sfour) / RE, 4);
            sfour = sfour / RE + 1.0;
        }
        double pinvsq = 1.0 / posq;
        double tsi = 1.0 / (ao - sfour);
        eta = ao * ecco * tsi;
        double etasq = eta * eta;
        double eeta = ecco * eta;
        double psisq = Math.abs(1.0 - etasq);
        double coef = qzms24 * Math.pow(tsi, 4);
        double coef1 = coef / Math.pow(psisq, 3.5);
        double cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq))
                + 0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
        cc1 = bstar * cc2;
        double cc3 = 0.0;
        if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco;
        x1mth2 = 1.0 - cosio2;
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq)
                + ecco * (0.5 + 2.0 * etasq) - J2 * tsi / (ao * psisq)
                * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta))
                + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * Math.cos(2.0 * argpo)));
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);
        double cosio4 = cosio2 * cosio2;
        double temp1 = 1.5 * J2 * pinvsq * noUnkozai;
        double temp2 = 0.5 * temp1 * J2 * pinvsq;
        double temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai;
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41
                + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4);
        double xhdot1 = -temp1 * cosio;
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
        omgcof = bstar * cc3 * Math.cos(argpo);
        xmcof = 0.0;
        if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta;
        nodecf = 3.5 * omeosq * xhdot1 * cc1;
        t2cof = 1.5 * cc1;
        if (Math.abs(cosio + 1.0) > 1.5e-12) {
            xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
        } else {
            xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12;
        }
        aycof = -0.5 * J3OJ2 * sinio;
        delmo = Math.pow(1.0 + eta * Math.cos(mo), 3);
        sinmao = Math.sin(mo);
        x7thm1 = 7.0 * cosio2 - 1.0;
        if (!isimp) {
            double cc1sq = cc1 * cc1;
            d2 = 4.0 * ao * tsi * cc1sq;
            double temp = d2 * tsi * cc1 / 3.0;
            d3 = (17.0 * ao + sfour) * temp;
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
            t3cof = d2 + 2.0 * cc1sq;
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
        }
    }

    /** Posição TEME (km) no instante jdUtc. out deve ter 3 elementos. */
    public void positionAt(double jdUtc, double[] out) {
        propagate((jdUtc - epochJd) * 1440.0, out);
    }

    /** Propaga tsince minutos desde a época. */
    public void propagate(double t, double[] r) {
        double xmdf = mo + mdot * t;
        double argpdf = argpo + argpdot * t;
        double nodedf = nodeo + nodedot * t;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = t * t;
        double nodem = nodedf + nodecf * t2;
        double tempa = 1.0 - cc1 * t;
        double tempe = bstar * cc4 * t;
        double templ = t2cof * t2;
        if (!isimp) {
            double delomg = omgcof * t;
            double delmtemp = 1.0 + eta * Math.cos(xmdf);
            double delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t;
            double t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double nm = noUnkozai;
        double em = ecco;
        double inclm = inclo;
        double am = Math.pow(XKE / nm, X2O3) * tempa * tempa;
        nm = XKE / Math.pow(am, 1.5);
        em = em - tempe;
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + noUnkozai * templ;
        double xlm = mm + argpm + nodem;
        nodem = mod2pi(nodem);
        argpm = mod2pi(argpm);
        xlm = mod2pi(xlm);
        mm = mod2pi(xlm - argpm - nodem);

        double sinip = Math.sin(inclm);
        double cosip = Math.cos(inclm);
        double axnl = em * Math.cos(argpm);
        double temp = 1.0 / (am * (1.0 - em * em));
        double aynl = em * Math.sin(argpm) + temp * aycof;
        double xl = mm + argpm + nodem + temp * xlcof * axnl;

        double u = mod2pi(xl - nodem);
        double eo1 = u;
        double tem5 = 9999.9;
        int ktr = 1;
        double sineo1 = 0, coseo1 = 0;
        while (Math.abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
            eo1 = eo1 + tem5;
            ktr++;
        }
        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        if (pl < 0.0) throw new IllegalStateException("SGP4: semi-latus rectum negativo");
        double rl = am * (1.0 - ecose);
        double betal = Math.sqrt(1.0 - el2);
        temp = esine / (1.0 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp);
        double cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu);
        double sin2u = (cosu + cosu) * sinu;
        double cos2u = 1.0 - 2.0 * sinu * sinu;
        temp = 1.0 / pl;
        double temp1 = 0.5 * J2 * temp;
        double temp2 = temp1 * temp;

        double mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodem + 1.5 * temp2 * cosip * sin2u;
        double xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u;

        double sinsu = Math.sin(su), cossu = Math.cos(su);
        double snod = Math.sin(xnode), cnod = Math.cos(xnode);
        double sini = Math.sin(xinc), cosi = Math.cos(xinc);
        double xmx = -snod * cosi;
        double xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu;
        double uy = xmy * sinsu + snod * cossu;
        double uz = sini * sinsu;
        r[0] = mrt * ux * RE;
        r[1] = mrt * uy * RE;
        r[2] = mrt * uz * RE;
    }

    private static double mod2pi(double x) {
        double r = x % TWOPI;
        return r < 0 ? r + TWOPI : r;
    }
}
