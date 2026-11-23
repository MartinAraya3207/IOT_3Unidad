package com.example.monitortemperaturaiot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private data class Lectura(
    val id: String,
    val temperatura: Double,
    val humedad: Long?,
    val fecha: String,
    val usuario: String,
    val usuarioUid: String,
    val origen: String
)

/** Estado del Arduino publicado por el puente en arduino/<uid>. */
private data class EstadoArduino(
    val temperatura: Double?,
    val humedad: Long?,
    val led: Boolean,
    val conectado: Boolean,
    val actualizadoMs: Long
)

// ------------------------------ FIREBASE ------------------------------
private fun guardarLectura(
    db: FirebaseFirestore,
    uid: String,
    correo: String,
    temperatura: Double,
    humedad: Long?,
    origen: String,
    tipo: String,
    resultado: (String) -> Unit
) {
    val documento = hashMapOf<String, Any>(
        "temperatura" to temperatura,
        "fecha" to Timestamp.now(),
        "usuarioUid" to uid,
        "usuarioEmail" to correo,
        "origen" to origen,
        "tipoLectura" to tipo
    )
    if (humedad != null) documento["humedad"] = humedad
    db.collection("temperaturas").add(documento)
        .addOnSuccessListener { resultado("Lectura guardada en Firebase.") }
        .addOnFailureListener { e -> resultado("No se pudo guardar: ${e.localizedMessage}") }
}

private fun eliminarLectura(db: FirebaseFirestore, id: String, resultado: (String) -> Unit) {
    db.collection("temperaturas").document(id).delete()
        .addOnSuccessListener { resultado("Lectura eliminada.") }
        .addOnFailureListener { e -> resultado("No se pudo eliminar: ${e.localizedMessage}") }
}

private fun eliminarMisLecturas(db: FirebaseFirestore, uid: String, resultado: (String) -> Unit) {
    db.collection("temperaturas").whereEqualTo("usuarioUid", uid).get()
        .addOnSuccessListener { snap ->
            val docs = snap.documents
            if (docs.isEmpty()) {
                resultado("No tienes lecturas para eliminar.")
                return@addOnSuccessListener
            }
            val lotes = docs.chunked(400)
            val pendientes = AtomicInteger(lotes.size)
            lotes.forEach { lote ->
                val batch = db.batch()
                lote.forEach { batch.delete(it.reference) }
                batch.commit()
                    .addOnSuccessListener {
                        if (pendientes.decrementAndGet() == 0) resultado("Se eliminaron ${docs.size} lectura(s) tuyas.")
                    }
                    .addOnFailureListener { e -> resultado("No se pudo eliminar: ${e.localizedMessage}") }
            }
        }
        .addOnFailureListener { e -> resultado("No se pudo consultar: ${e.localizedMessage}") }
}

/** Envia una orden al Arduino: el puente del PC la lee y la manda por serial. */
private fun enviarOrden(
    db: FirebaseFirestore,
    uid: String,
    campos: Map<String, Any>,
    resultado: (String) -> Unit
) {
    db.collection("arduino").document(uid).set(campos, SetOptions.merge())
        .addOnSuccessListener { resultado("Orden enviada al Arduino.") }
        .addOnFailureListener { e -> resultado("No se pudo enviar la orden: ${e.localizedMessage}") }
}

// ------------------------------ PANTALLA ------------------------------
@Composable
internal fun PantallaMonitoreo(correo: String, uid: String, onSalir: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }

    var historial by remember { mutableStateOf<List<Lectura>>(emptyList()) }
    var arduino by remember { mutableStateOf<EstadoArduino?>(null) }
    var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
    var estadoNube by remember { mutableStateOf("Conectando con Firebase...") }
    var autoRegistro by remember { mutableStateOf(false) }
    var temperaturaManual by remember { mutableStateOf("24.5") }
    var confirmarBorrado by remember { mutableStateOf(false) }

    // Historial de lecturas (compartido).
    DisposableEffect(db) {
        val registro = db.collection("temperaturas")
            .orderBy("fecha", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    estadoNube = "Error al consultar Firebase: ${error.localizedMessage}"
                    return@addSnapshotListener
                }
                val formato = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                historial = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val valor = doc.getDouble("temperatura") ?: return@mapNotNull null
                    Lectura(
                        id = doc.id,
                        temperatura = valor,
                        humedad = doc.getLong("humedad"),
                        fecha = doc.getTimestamp("fecha")?.toDate()?.let { formato.format(it) } ?: "Sin fecha",
                        usuario = doc.getString("usuarioEmail") ?: "Desconocido",
                        usuarioUid = doc.getString("usuarioUid") ?: "",
                        origen = doc.getString("origen") ?: "Sin especificar"
                    )
                }
                estadoNube = "Sincronizado: ${historial.size} registro(s)"
            }
        onDispose { registro.remove() }
    }

    // Estado en vivo del Arduino (publicado por el puente).
    DisposableEffect(uid) {
        val registro = db.collection("arduino").document(uid)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    estadoNube = "Error al leer el Arduino: ${error.localizedMessage}"
                    return@addSnapshotListener
                }
                if (doc == null || !doc.exists()) {
                    arduino = null
                    return@addSnapshotListener
                }
                arduino = EstadoArduino(
                    temperatura = doc.getDouble("temperatura"),
                    humedad = doc.getLong("humedad"),
                    led = doc.getBoolean("ledActual") ?: false,
                    conectado = doc.getBoolean("conectado") ?: false,
                    actualizadoMs = doc.getTimestamp("actualizado")?.toDate()?.time ?: 0L
                )
            }
        onDispose { registro.remove() }
    }

    // Reloj para detectar cuando el puente deja de publicar.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            ahora = System.currentTimeMillis()
        }
    }

    val enLinea = arduino?.let { it.conectado && ahora - it.actualizadoMs < 30_000 } ?: false
    val temperatura = arduino?.temperatura
    val humedad = arduino?.humedad

    // Registro automatico cada 30 s mientras el Arduino este en linea.
    LaunchedEffect(autoRegistro) {
        while (autoRegistro) {
            delay(30_000)
            val a = arduino
            val activo = a != null && a.conectado && System.currentTimeMillis() - a.actualizadoMs < 30_000
            if (autoRegistro && a?.temperatura != null && activo) {
                guardarLectura(db, uid, correo, a.temperatura, a.humedad, "Arduino USB (app)", "REAL") { estadoNube = it }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monitor Temperatura IoT", style = MaterialTheme.typography.headlineSmall)
                Text("Sesión: $correo", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSalir) { Text("Salir") }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Arduino (USB, vía puente en el PC)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enLinea) "Estado: En línea"
                    else "Estado: Sin conexión. Ejecuta Puente/puente_serial.py con el Arduino conectado."
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Temperatura actual (DHT11)")
                Text(
                    temperatura?.let { "%.1f °C".format(it) } ?: "-- °C",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(humedad?.let { "Humedad: $it %" } ?: "Humedad: -- %")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        enviarOrden(db, uid, mapOf("pedirLectura" to System.currentTimeMillis())) { estadoNube = it }
                    },
                    enabled = enLinea,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Solicitar lectura ahora") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val t = temperatura
                        if (t == null) {
                            estadoNube = "Aún no hay una lectura del Arduino."
                        } else {
                            guardarLectura(db, uid, correo, t, humedad, "Arduino USB (app)", "REAL") { estadoNube = it }
                        }
                    },
                    enabled = enLinea && temperatura != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Registrar lectura en Firebase") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Registrar automáticamente cada 30 s", modifier = Modifier.weight(1f))
                    Switch(checked = autoRegistro, onCheckedChange = { autoRegistro = it }, enabled = enLinea)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Actuador: LED", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (arduino?.led == true) "Estado: Encendido" else "Estado: Apagado",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = arduino?.led == true,
                        onCheckedChange = { encender ->
                            enviarOrden(db, uid, mapOf("ledDeseado" to encender)) { estadoNube = it }
                        },
                        enabled = enLinea
                    )
                }
                Text(
                    "El estado cambia cuando el Arduino confirma el comando.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Lectura de prueba (sin Arduino)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = temperaturaManual,
                    onValueChange = { temperaturaManual = it },
                    label = { Text("Temperatura en °C") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val v = temperaturaManual.trim().replace(',', '.').toDoubleOrNull()
                        if (v == null || v < -50 || v > 100) {
                            estadoNube = "Ingresa un valor válido entre -50 y 100 °C."
                        } else {
                            guardarLectura(db, uid, correo, v, null, "Simulación manual", "SIMULADA") { estadoNube = it }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Guardar lectura de prueba") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Firebase: $estadoNube")
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Historial compartido",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { confirmarBorrado = true }) { Text("Eliminar mis lecturas") }
        }

        if (historial.isEmpty()) {
            Text("No hay lecturas registradas todavía.")
        } else {
            historial.forEach { lectura ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${"%.1f".format(lectura.temperatura)} °C", style = MaterialTheme.typography.titleLarge)
                            lectura.humedad?.let { Text("Humedad: $it %") }
                            Text("Fecha: ${lectura.fecha}")
                            Text("Usuario: ${lectura.usuario}")
                            Text("Origen: ${lectura.origen}")
                        }
                        if (lectura.usuarioUid == uid) {
                            TextButton(onClick = { eliminarLectura(db, lectura.id) { estadoNube = it } }) {
                                Text("Eliminar")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmarBorrado) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            title = { Text("Eliminar lecturas") },
            text = { Text("Se eliminarán todas las lecturas registradas con tu cuenta. Las de otros usuarios no se tocan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrado = false
                    eliminarMisLecturas(db, uid) { estadoNube = it }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } }
        )
    }
}
