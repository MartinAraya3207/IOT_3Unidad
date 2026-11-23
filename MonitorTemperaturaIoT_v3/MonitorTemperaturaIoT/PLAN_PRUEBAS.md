# Plan de pruebas — completar antes de entregar

Registra resultado, fecha, modelo/versión Android y evidencia (captura/video). No marcar como aprobado sin ejecutar la prueba.

| ID | Escenario | Procedimiento | Resultado esperado | Resultado real |
|---|---|---|---|---|
| P01 | Login correcto | Iniciar sesión con cuenta creada en Firebase | Acceso al monitor | Pendiente |
| P02 | Login incorrecto | Contraseña errónea | Error visible; no entra | Pendiente |
| P03 | Crear usuario | Crear cuenta nueva con correo válido | Cuenta aparece en Firebase Authentication | Pendiente |
| P04 | Registro de prueba | Pulsar “Guardar prueba 24,5 °C” | Documento en `temperaturas` con fecha, UID, correo y origen simulado | Pendiente |
| P05 | Lectura real | Ejecutar el puente con el Arduino por USB y solicitar temperatura desde la app | Temperatura real recibida y guardada en Firestore | Pendiente; requiere hardware |
| P06 | Historial entre teléfonos | Iniciar sesión en dos teléfonos del mismo proyecto | Ambos ven el registro compartido | Pendiente |
| P07 | Último usuario | Crear lecturas desde dos cuentas | Se muestra el correo asociado a la lectura más reciente | Pendiente |
| P08 | Actuador | Enviar LED_ON y LED_OFF | LED cambia de estado y devuelve respuesta | Pendiente; requiere hardware |
| P09 | Bluetooth desconectado | Apagar módulo durante uso | App muestra estado/error y no se cierra | Pendiente |
| P10 | Reconexión | Volver a conectar módulo | Se reciben nuevas lecturas | Pendiente |
| P11 | Sin internet | Desactivar internet y probar sincronización | Error de nube visible; no afirmar que guardó en Firestore | Pendiente |
| P12 | Seguridad Firestore | Probar lectura/escritura sin iniciar sesión | Reglas deniegan acceso | Pendiente |
| P13 | Datos inválidos | Revisar entrada de lectura inválida | No se guarda temperatura no numérica | Pendiente |
| P14 | Uso prolongado | Dejar conectado 10–15 min | Sin bloqueo/cierre; anotar fallos o desconexiones | Pendiente |

## Criterio de aceptación
Registrar evidencia de cada prueba. Si falla, documentar el fallo y la corrección. No declarar estabilidad demostrada hasta completar las pruebas en dispositivos reales.
