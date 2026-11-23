/*
  Monitor de Temperatura IoT - Arduino UNO R4 (comunicacion SERIAL por USB)
  Sensor: DHT11 (temperatura y humedad)   Actuador: LED

  No usa Bluetooth: se controla con comandos desde el Monitor Serie de Arduino IDE
  (115200 baudios, "Nueva linea") o desde el puente Puente/puente_serial.py.

  Libreria (Arduino IDE > Gestor de librerias):
    - DHT sensor library (Adafruit)  + Adafruit Unified Sensor
  Placa: Herramientas > Placa > Arduino UNO R4 Boards > Arduino UNO R4 WiFi (o Minima)

  Conexiones:
    DHT11 modulo de 3 pines:  S (senal) -> pin DHT_PIN | + -> 5V | - -> GND
    LED rojo: pin LED_PIN -> resistencia 220 ohm -> pata larga; pata corta -> GND
    (el LED integrado LED_BUILTIN tambien se enciende/apaga)

  Comandos (se escriben en el Monitor Serie):
    LED_ON | LED_OFF | GET_TEMP | AUTO_ON | AUTO_OFF | AYUDA
    Opcionalmente con checksum: COMANDO*CS   (CS = XOR de los caracteres, 2 digitos hex)

  Respuestas del Arduino:  $CUERPO*CS
    $T=24.0,H=45*29   lectura | $OK=LED_ON*..  confirmacion | $ERR=CMD*..  error
*/
#include <DHT.h>

// ---------- CONFIGURACION (cambia aqui si tu cableado es distinto) ----------
#define DHT_PIN   8
#define DHT_TYPE  DHT11
#define LED_PIN   7
const unsigned long INTERVALO_MS = 3000;   // envio automatico de lecturas
// -----------------------------------------------------------------------------

DHT dht(DHT_PIN, DHT_TYPE);
bool envioAuto = true;
unsigned long ultimoEnvio = 0;
String entrada = "";

String checksum(const String &cuerpo) {
  uint8_t x = 0;
  for (unsigned int i = 0; i < cuerpo.length(); i++) x ^= (uint8_t)cuerpo[i];
  char buf[3];
  snprintf(buf, sizeof(buf), "%02X", x);
  return String(buf);
}

void enviar(const String &cuerpo) {
  Serial.print("$");
  Serial.print(cuerpo);
  Serial.print("*");
  Serial.println(checksum(cuerpo));
}

void ponerLed(bool encendido) {
  digitalWrite(LED_PIN, encendido ? HIGH : LOW);
  digitalWrite(LED_BUILTIN, encendido ? HIGH : LOW);
}

void enviarLectura() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) {
    enviar("ERR=SENSOR");
    return;
  }
  enviar("T=" + String(t, 1) + ",H=" + String((int)round(h)));
}

void manejarComando(String texto) {
  texto.trim();
  if (texto.length() == 0) return;

  String cmd = texto;
  int pos = texto.lastIndexOf('*');
  if (pos >= 0) {
    cmd = texto.substring(0, pos);
    String recibido = texto.substring(pos + 1);
    recibido.trim();
    if (!recibido.equalsIgnoreCase(checksum(cmd))) {
      enviar("ERR=CHECKSUM");
      return;
    }
  }
  cmd.trim();
  cmd.toUpperCase();

  if (cmd == "LED_ON") {
    ponerLed(true);
    enviar("OK=LED_ON");
  } else if (cmd == "LED_OFF") {
    ponerLed(false);
    enviar("OK=LED_OFF");
  } else if (cmd == "GET_TEMP") {
    enviarLectura();
  } else if (cmd == "AUTO_ON") {
    envioAuto = true;
    enviar("OK=AUTO_ON");
  } else if (cmd == "AUTO_OFF") {
    envioAuto = false;
    enviar("OK=AUTO_OFF");
  } else if (cmd == "AYUDA") {
    Serial.println("Comandos: LED_ON LED_OFF GET_TEMP AUTO_ON AUTO_OFF");
  } else {
    enviar("ERR=CMD");
  }
}

void setup() {
  Serial.begin(115200);
  unsigned long inicio = millis();
  while (!Serial && millis() - inicio < 2000) { }   // no se queda esperando sin USB

  pinMode(LED_PIN, OUTPUT);
  pinMode(LED_BUILTIN, OUTPUT);
  ponerLed(false);
  dht.begin();

  Serial.println("Monitor IoT listo. Escribe AYUDA para ver los comandos.");
  enviar("OK=LISTO");
}

void loop() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (entrada.length() > 0) {
        manejarComando(entrada);
        entrada = "";
      }
    } else if (entrada.length() < 40) {
      entrada += c;
    } else {
      entrada = "";          // linea demasiado larga: se descarta
      enviar("ERR=CMD");
    }
  }

  if (envioAuto && millis() - ultimoEnvio >= INTERVALO_MS) {
    ultimoEnvio = millis();
    enviarLectura();
  }
}
