# Monitor de Temperatura IoT — Arduino UNO R4 (USB / comandos) + Android + Firebase

TI3042 Aplicaciones Móviles para IoT, Unidad 3.

El Arduino se conecta **por USB** al computador y se controla con **comandos serial** desde Arduino IDE. No usa Bluetooth ni necesita un teléfono. La app Android (por ejemplo en el emulador de Android Studio) muestra la temperatura del **DHT11**, enciende y apaga el **LED**, y registra y elimina lecturas en **Firebase**.

Como el emulador no puede ver el puerto USB del PC, un **puente** en Python une las dos partes a través de Firebase:

```
DHT11 + LED ── Arduino ──USB serial──► puente_serial.py (PC) ◄──► Firestore ◄──► App Android
                  ▲
        Monitor Serie de Arduino IDE (solo para pruebas, no al mismo tiempo que el puente)
```

## Contenido

| Carpeta / archivo | Para qué sirve |
|---|---|
| `Arduino/MonitorTemperaturaIoT/MonitorTemperaturaIoT.ino` | Sketch para Arduino IDE |
| `Puente/puente_serial.py` | Puente Arduino USB ↔ Firebase (PC) |
| `app/` | Proyecto Android (Kotlin + Jetpack Compose) |
| `firebase/firestore.rules` | Reglas de Firestore |
| `PLAN_PRUEBAS.md`, `SEGURIDAD_OT.md` | Pruebas y seguridad para la entrega |

## 1. Hardware

Necesitas: Arduino UNO R4, protoboard, módulo DHT11 (3 pines), LED rojo, resistencia de 220 Ω y unos 5 cables. **No se usan** el TMP36, el HC-05 ni las resistencias de 1 kΩ y 2 kΩ.

| Componente | Conexión |
|---|---|
| DHT11, pin `S` (señal) | Pin digital **8** (`DHT_PIN` en el sketch) |
| DHT11, pin `+` | 5V |
| DHT11, pin `-` | GND |
| LED, pata larga (+) | Resistencia 220 Ω → pin **7** (`LED_PIN`) |
| LED, pata corta (−) | GND |

Si tu cable de señal del DHT11 está en otro pin, cambia `DHT_PIN` en el sketch.

## 2. Arduino IDE (comandos)

1. **Gestor de placas:** instala **Arduino UNO R4 Boards**.
2. **Gestor de librerías:** instala **DHT sensor library** (Adafruit) y **Adafruit Unified Sensor**.
3. Abre `Arduino/MonitorTemperaturaIoT/MonitorTemperaturaIoT.ino`, elige tu placa y puerto, y pulsa **Subir**.
4. Abre el **Monitor Serie** a **115200** baudios y "Nueva línea". Verás lecturas como `$T=24.0,H=45*29` cada 3 s.
5. Prueba escribiendo estos comandos:

| Comando | Qué hace |
|---|---|
| `LED_ON` / `LED_OFF` | Enciende o apaga el LED (responde `$OK=LED_ON*..`) |
| `GET_TEMP` | Pide una lectura inmediata |
| `AUTO_OFF` / `AUTO_ON` | Detiene o reanuda el envío automático |
| `AYUDA` | Lista los comandos |

Cada comando puede llevar un checksum (`LED_ON*13`). Si es incorrecto, el Arduino responde `$ERR=CHECKSUM`.

6. **Cierra el Monitor Serie** cuando termines: el puerto USB solo lo puede usar un programa a la vez.

## 3. Firebase

El proyecto trae `app/google-services.json` del proyecto Firebase `proyecto-android1-a0486`. Si usas otro, reemplázalo.

1. **Authentication → Sign-in method:** habilita **Correo electrónico/contraseña**.
2. **Firestore Database:** créala si no existe.
3. **Firestore → Reglas:** pega `firebase/firestore.rules` y **Publica**. Es obligatorio actualizarlas: agregan el documento `arduino/<uid>` (estado y órdenes) y permiten eliminar lecturas propias.
4. Crea una cuenta desde la app (paso 5) o en Authentication → Users. **El puente y la app deben usar la misma cuenta.**

## 4. Puente en el PC

Requiere Python 3.9 o superior. Con el Arduino conectado y el Monitor Serie cerrado:

```bash
cd Puente
pip install -r requirements.txt
python puente_serial.py
```

Te pedirá el correo y la contraseña de la app, y detecta el puerto solo. Si no lo encuentra, usa `--puerto COM5` (Windows) o `--puerto /dev/ttyACM0` (Linux).

| Opción | Para qué sirve |
|---|---|
| `--registrar-cada 30` | El puente también registra una lectura en Firebase cada 30 s |
| `--poll 2` | Segundos entre consultas de órdenes de la app (por defecto 2) |
| `--intervalo-estado 5` | Segundos entre publicaciones de estado (por defecto 5) |

El puente muestra en la consola lo que dice el Arduino y también acepta comandos escritos ahí (`LED_ON`, `GET_TEMP`…). Déjalo corriendo mientras usas la app y ciérralo con `Ctrl+C`. Mientras esté abierto consume lecturas y escrituras de Firebase; ciérralo al terminar para no gastar la cuota gratuita.

## 5. Android Studio

1. Copia la carpeta a una ruta corta fuera de OneDrive, por ejemplo `C:\AndroidProjects\MonitorTemperaturaIoT`.
2. **File → Open** y elige la carpeta que contiene `settings.gradle.kts`.
3. **Settings → Build Tools → Gradle → Gradle JDK:** elige **JDK 17**.
4. **Sync Now.** El proyecto usa Gradle 8.11.1, compatible con Android Gradle Plugin 8.9.1.
5. Usa un emulador con imagen **Google APIs** o **Google Play** (con internet), o un teléfono, y pulsa **Run**. Ya no necesitas Bluetooth.

## 6. Uso de la app

1. Crea una cuenta o inicia sesión (la misma que usa el puente).
2. Con el puente corriendo, el estado pasa a **En línea** y aparecen temperatura y humedad.
3. El interruptor **LED** enciende y apaga el actuador. El estado cambia cuando el Arduino confirma.
4. **Solicitar lectura ahora** pide un `GET_TEMP` al Arduino.
5. **Registrar lectura en Firebase** guarda la lectura actual. El interruptor "cada 30 s" lo hace automáticamente (o usa `--registrar-cada` en el puente, no ambos).
6. En el historial puedes **Eliminar** tus lecturas una por una, o **Eliminar mis lecturas** para borrar todas las tuyas. Las de otros usuarios no se pueden eliminar.
7. **Lectura de prueba** guarda un valor manual marcado como `SIMULADA`, sin Arduino.

## 7. Seguridad (criterios 3.1.1 a 3.1.4)

- **Integridad:** cada trama del Arduino lleva checksum XOR. El puente descarta las corruptas y el Arduino rechaza comandos con checksum incorrecto.
- **Autenticación:** la app y el puente usan Firebase Authentication. Las reglas permiten leer y escribir `arduino/<uid>` solo a su dueño.
- **Credenciales:** el puente pide la contraseña por teclado (sin mostrarla) o por la variable `FIREBASE_CLAVE`. No queda guardada en archivos.
- **Datos en la nube:** TLS (HTTPS) y reglas que limitan quién crea y elimina lecturas.
- **Límites:** el enlace USB-serie no se cifra (es un cable local) y el checksum detecta errores, no ataques. No es una certificación ISO: preséntalo como controles alineados con buenas prácticas ISO 27000.

## 8. Problemas frecuentes

| Síntoma | Qué revisar |
|---|---|
| El puente dice "puerto ocupado" / "Access denied" | Cierra el Monitor Serie de Arduino IDE |
| No encuentra el puerto | Cable USB con datos, y usa `--puerto COMx` |
| `ERR=SENSOR` / temperatura vacía | Cableado del DHT11, `DHT_PIN` y la librería instalada |
| La app dice "Sin conexión" | Que el puente esté corriendo y con la misma cuenta que la app |
| El puente: "No se pudo iniciar sesión" | Correo y contraseña de una cuenta creada en la app; Email/Password habilitado |
| Error de permisos de Firestore | Reglas publicadas (paso 3.3) |
| La app muestra "Firebase no está configurado" | Falta `app/google-services.json` o el paquete no coincide |
| Falla Gradle Sync | JDK 17 en Gradle JDK, internet disponible y no abrir desde OneDrive |
