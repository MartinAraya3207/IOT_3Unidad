package com.example.monitortemperaturaiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si google-services.json falta o no corresponde, mostramos un aviso en vez de cerrar la app.
        val firebaseListo = try {
            FirebaseApp.getApps(this).isNotEmpty() || FirebaseApp.initializeApp(this) != null
        } catch (e: Exception) {
            false
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (firebaseListo) MonitorTemperaturaApp() else PantallaErrorFirebase()
                }
            }
        }
    }
}

@Composable
private fun PantallaErrorFirebase() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Firebase no esta configurado", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Falta app/google-services.json o no coincide con el paquete " +
                "com.example.monitortemperaturaiot. Revisa el README, paso de Firebase, " +
                "y vuelve a compilar."
        )
    }
}

@Composable
private fun MonitorTemperaturaApp() {
    val auth = remember { FirebaseAuth.getInstance() }
    var usuario by remember { mutableStateOf(auth.currentUser) }
    var error by remember { mutableStateOf("") }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener {
            usuario = it.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    if (usuario == null) {
        PantallaLogin(
            error = error,
            onAccion = { correo, clave, crear ->
                error = ""

                if (correo.isBlank() || clave.length < 6) {
                    error = "Ingresa un correo y una contraseña de al menos 6 caracteres."
                } else {
                    val tarea = if (crear) {
                        auth.createUserWithEmailAndPassword(
                            correo.trim(), clave
                        )
                    } else {
                        auth.signInWithEmailAndPassword(
                            correo.trim(), clave
                        )
                    }

                    tarea.addOnCompleteListener { resultado ->
                        if (!resultado.isSuccessful) {
                            error = resultado.exception?.localizedMessage
                                ?: "No se pudo iniciar sesión. Revisa Firebase."
                        }
                    }
                }
            }
        )
    } else {
        PantallaMonitoreo(
            correo = usuario?.email ?: "Usuario",
            uid = usuario?.uid ?: "",
            onSalir = { auth.signOut() }
        )
    }
}

@Composable
private fun PantallaLogin(
    error: String,
    onAccion: (String, String, Boolean) -> Unit
) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var crearCuenta by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Monitor de Temperatura IoT",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(8.dp))
        Text(if (crearCuenta) "Crear cuenta" else "Iniciar sesión")
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it },
            label = { Text("Correo electrónico") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it },
            label = { Text("Contraseña (mínimo 6 caracteres)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onAccion(correo, clave, crearCuenta) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (crearCuenta) "Crear cuenta" else "Iniciar sesión")
        }

        TextButton(
            onClick = {
                crearCuenta = !crearCuenta
            }
        ) {
            Text(
                if (crearCuenta) "Ya tengo una cuenta"
                else "Crear cuenta nueva"
            )
        }

        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Los usuarios deben estar habilitados en Firebase Authentication.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
