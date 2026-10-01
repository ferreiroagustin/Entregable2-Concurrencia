package restaurante.modelo;

import restaurante.config.Config;
import restaurante.config.Rango;

/**
 * Lista de menús disponibles. Cada menú tiene su precio y su propio rango de
 * cocción TCmin..TCmax (los valores vienen de {@link Config}).
 * Un enum es inmutable y seguro para compartir entre hilos.
 */
public enum Menu {
    MILANESA(Config.MILANESA_NOMBRE, Config.MILANESA_PRECIO, Config.MILANESA_TC_MIN, Config.MILANESA_TC_MAX),
    ENSALADA(Config.ENSALADA_NOMBRE, Config.ENSALADA_PRECIO, Config.ENSALADA_TC_MIN, Config.ENSALADA_TC_MAX),
    PIZZA(Config.PIZZA_NOMBRE, Config.PIZZA_PRECIO, Config.PIZZA_TC_MIN, Config.PIZZA_TC_MAX),
    RAVIOLES(Config.RAVIOLES_NOMBRE, Config.RAVIOLES_PRECIO, Config.RAVIOLES_TC_MIN, Config.RAVIOLES_TC_MAX),
    HAMBURGUESA(Config.HAMBURGUESA_NOMBRE, Config.HAMBURGUESA_PRECIO, Config.HAMBURGUESA_TC_MIN, Config.HAMBURGUESA_TC_MAX),
    FLAN(Config.FLAN_NOMBRE, Config.FLAN_PRECIO, Config.FLAN_TC_MIN, Config.FLAN_TC_MAX);

    private final String nombre;
    private final long precio;
    private final Rango coccion;

    Menu(String nombre, long precio, long tcMin, long tcMax) {
        this.nombre = nombre;
        this.precio = precio;
        this.coccion = new Rango(tcMin, tcMax);
    }

    public String nombre() {
        return nombre;
    }

    public long precio() {
        return precio;
    }

    /** Rango TCmin..TCmax propio de este menú. */
    public Rango coccion() {
        return coccion;
    }
}
