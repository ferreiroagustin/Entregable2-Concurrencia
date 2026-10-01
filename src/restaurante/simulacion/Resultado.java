package restaurante.simulacion;

import java.util.List;

import restaurante.config.Parametros;
import restaurante.estado.Snapshot;

/** Resultado final (inmutable) de una simulación. */
public record Resultado(Parametros parametros, long duracionMs, Snapshot snapshotFinal, List<Invariante> invariantes,
        String rutaLog, long eventos) {

    public Resultado {
        invariantes = List.copyOf(invariantes);
    }

    public boolean todosOk() {
        return invariantes.stream().allMatch(Invariante::ok);
    }
}
