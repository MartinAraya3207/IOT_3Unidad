#!/usr/bin/env python3
"""
Puente USB-serie <-> Firebase para Monitor Temperatura IoT.

  Arduino (USB, comandos serial) <--> este script (PC) <--> Firestore <--> App Android

- Lee las tramas del Arduino ($T=24.0,H=45*29) y publica el estado en  arduino/<uid>.
- Lee las ordenes de la app (ledDeseado, pedirLectura) y las envia al Arduino por serial.
- Opcional: registra lecturas en la coleccion  temperaturas  cada N segundos.
- Puedes escribir comandos aqui mismo (LED_ON, LED_OFF, GET_TEMP, AUTO_OFF...).

IMPORTANTE: cierra el Monitor Serie de Arduino IDE antes de ejecutar este script,
porque un puerto USB-serie solo lo puede usar un programa a la vez.
Usa el MISMO correo y contrasena con que inicias sesion en la app.
"""
import argparse
import getpass
import json
import os
import queue
import re
import sys
import threading
import time
from datetime import datetime, timezone

import requests
import serial
from serial.tools import list_ports

BAUDIOS = 115200
RE_LECTURA = re.compile(r"^T=(-?\d+(?:\.\d+)?),H=(\d+)$")


# ---------------------------- protocolo ----------------------------
def checksum(cuerpo: str) -> str:
    x = 0
    for c in cuerpo:
        x ^= ord(c)
    return f"{x & 0xFF:02X}"


def armar(comando: str) -> str:
    return f"{comando}*{checksum(comando)}\n"


def validar(linea: str):
    """Devuelve el cuerpo si el checksum es correcto, o None."""
    s = linea.strip()
    if not s.startswith("$"):
        return None
    i = s.rfind("*")
    if i < 0 or len(s) != i + 3:
        return None
    cuerpo = s[1:i]
    return cuerpo if checksum(cuerpo) == s[i + 1:].upper() else None


def ahora_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ---------------------------- Firebase (REST) ----------------------------
class Ts(str):
    """Marca un texto ISO-8601 como timestamp de Firestore."""


def a_valor(v):
    if isinstance(v, Ts):
        return {"timestampValue": str(v)}
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    return {"stringValue": str(v)}


def de_valor(v):
    if "booleanValue" in v:
        return v["booleanValue"]
    if "integerValue" in v:
        return int(v["integerValue"])
    if "doubleValue" in v:
        return float(v["doubleValue"])
    if "stringValue" in v:
        return v["stringValue"]
    if "timestampValue" in v:
        return v["timestampValue"]
    return None


class Firebase:
    def __init__(self, api_key: str, project_id: str):
        self.api_key = api_key
        self.base = f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/(default)/documents"
        self.http = requests.Session()
        self.id_token = None
        self.refresh_token = None
        self.expira = 0.0
        self.uid = None
        self.email = None

    def _guardar_sesion(self, d: dict):
        self.id_token = d.get("idToken") or d.get("id_token")
        self.refresh_token = d.get("refreshToken") or d.get("refresh_token")
        self.uid = d.get("localId") or d.get("user_id") or self.uid
        self.expira = time.time() + int(d.get("expiresIn") or d.get("expires_in") or 3600)

    def login(self, email: str, clave: str):
        r = self.http.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword",
            params={"key": self.api_key},
            json={"email": email, "password": clave, "returnSecureToken": True},
            timeout=15,
        )
        if r.status_code != 200:
            msg = r.json().get("error", {}).get("message", r.text) if r.content else r.text
            raise RuntimeError(f"No se pudo iniciar sesion en Firebase: {msg}")
        self.email = email
        self._guardar_sesion(r.json())

    def _renovar(self):
        r = self.http.post(
            "https://securetoken.googleapis.com/v1/token",
            params={"key": self.api_key},
            data={"grant_type": "refresh_token", "refresh_token": self.refresh_token},
            timeout=15,
        )
        r.raise_for_status()
        self._guardar_sesion(r.json())

    def _req(self, metodo, url, **kw):
        if time.time() > self.expira - 300:
            self._renovar()
        for intento in range(2):
            r = self.http.request(
                metodo, url, headers={"Authorization": f"Bearer {self.id_token}"}, timeout=15, **kw
            )
            if r.status_code == 401 and intento == 0:
                self._renovar()
                continue
            return r
        return r

    @staticmethod
    def _revisar(r):
        if r.status_code >= 400:
            try:
                msg = r.json()["error"]["message"]
            except Exception:
                msg = r.text[:200]
            raise RuntimeError(f"Firestore {r.status_code}: {msg}")

    def patch_estado(self, campos: dict):
        url = f"{self.base}/arduino/{self.uid}"
        params = [("updateMask.fieldPaths", k) for k in campos]
        body = {"fields": {k: a_valor(v) for k, v in campos.items()}}
        self._revisar(self._req("PATCH", url, params=params, json=body))

    def leer_estado(self) -> dict:
        r = self._req("GET", f"{self.base}/arduino/{self.uid}")
        if r.status_code == 404:
            return {}
        self._revisar(r)
        return {k: de_valor(v) for k, v in r.json().get("fields", {}).items()}

    def crear_temperatura(self, temperatura: float, humedad: int):
        campos = {
            "temperatura": float(temperatura),
            "humedad": int(humedad),
            "fecha": Ts(ahora_iso()),
            "usuarioUid": self.uid,
            "usuarioEmail": self.email,
            "origen": "Arduino USB (puente)",
            "tipoLectura": "REAL",
        }
        body = {"fields": {k: a_valor(v) for k, v in campos.items()}}
        self._revisar(self._req("POST", f"{self.base}/temperaturas", json=body))


# ---------------------------- puente ----------------------------
class Puente:
    def __init__(self, ser, fb, args):
        self.ser = ser
        self.fb = fb
        self.args = args
        self.buffer = b""
        self.teclado = queue.Queue()
        self.detener = threading.Event()
        self.temperatura = None
        self.humedad = None
        self.led = False
        self.ultima_lectura = 0.0
        self.t_push = 0.0
        self.t_poll = 0.0
        self.t_registro = time.time()
        self.pedir_prev = 0
        self.forzar_push = False

    def enviar(self, comando: str):
        self.ser.write(armar(comando).encode("ascii"))

    def procesar_linea(self, linea: str):
        linea = linea.strip()
        if not linea:
            return
        print(f"[Arduino] {linea}")
        if not linea.startswith("$"):
            return
        cuerpo = validar(linea)
        if cuerpo is None:
            print("  ! trama descartada: checksum invalido")
            return
        m = RE_LECTURA.match(cuerpo)
        if m:
            self.temperatura = float(m.group(1))
            self.humedad = int(m.group(2))
            self.ultima_lectura = time.time()
        elif cuerpo == "OK=LED_ON":
            self.led = True
            self.forzar_push = True
        elif cuerpo == "OK=LED_OFF":
            self.led = False
            self.forzar_push = True

    def publicar(self):
        campos = {"ledActual": self.led, "conectado": True, "actualizado": Ts(ahora_iso())}
        if self.temperatura is not None:
            campos["temperatura"] = self.temperatura
            campos["humedad"] = self.humedad
        self.fb.patch_estado(campos)
        self.t_push = time.time()
        self.forzar_push = False

    def revisar_ordenes(self):
        doc = self.fb.leer_estado()
        self.t_poll = time.time()
        deseado = doc.get("ledDeseado")
        if isinstance(deseado, bool) and deseado != self.led:
            print(f"[App] orden LED -> {'ON' if deseado else 'OFF'}")
            self.enviar("LED_ON" if deseado else "LED_OFF")
        pedir = doc.get("pedirLectura", 0)
        if isinstance(pedir, int) and pedir != self.pedir_prev:
            self.pedir_prev = pedir
            print("[App] solicitud de lectura")
            self.enviar("GET_TEMP")

    def iniciar(self):
        self.enviar("LED_OFF")
        doc = self.fb.leer_estado()
        self.pedir_prev = doc.get("pedirLectura", 0) if isinstance(doc.get("pedirLectura", 0), int) else 0
        self.fb.patch_estado(
            {"ledActual": False, "ledDeseado": False, "conectado": True, "actualizado": Ts(ahora_iso())}
        )

    def ciclo(self):
        """Una vuelta del bucle principal."""
        datos = self.ser.read(256)
        if datos:
            self.buffer += datos
            while b"\n" in self.buffer:
                linea, self.buffer = self.buffer.split(b"\n", 1)
                self.procesar_linea(linea.decode("ascii", errors="replace"))
            if len(self.buffer) > 300:
                self.buffer = b""

        while not self.teclado.empty():
            texto = self.teclado.get().strip()
            if texto:
                self.enviar(texto.upper())

        ahora = time.time()
        try:
            hay_lectura_nueva = self.ultima_lectura > self.t_push
            if self.forzar_push or (hay_lectura_nueva and ahora - self.t_push >= self.args.intervalo_estado):
                self.publicar()
            if ahora - self.t_poll >= self.args.poll:
                self.revisar_ordenes()
            if (
                self.args.registrar_cada > 0
                and ahora - self.t_registro >= self.args.registrar_cada
                and self.temperatura is not None
                and ahora - self.ultima_lectura < 10
            ):
                self.fb.crear_temperatura(self.temperatura, self.humedad)
                self.t_registro = ahora
                print(f"[Firebase] lectura registrada: {self.temperatura:.1f} C")
        except (requests.RequestException, RuntimeError) as e:
            print(f"[Firebase] error temporal: {e}")
            self.t_poll = ahora
            self.t_push = ahora

    def correr(self):
        while not self.detener.is_set():
            self.ciclo()


def leer_teclado(cola: queue.Queue):
    for linea in sys.stdin:
        cola.put(linea)


def detectar_puerto():
    puertos = list(list_ports.comports())
    for p in puertos:
        if p.vid == 0x2341:          # Arduino SA
            return p.device
    return puertos[0].device if puertos else None


def main():
    ap = argparse.ArgumentParser(description="Puente Arduino USB <-> Firebase")
    ap.add_argument("--puerto", help="Ej: COM5 o /dev/ttyACM0 (por defecto se detecta solo)")
    ap.add_argument("--google-services", default=os.path.join("..", "app", "google-services.json"))
    ap.add_argument("--email", help="Correo de la cuenta de la app")
    ap.add_argument("--poll", type=float, default=2.0, help="segundos entre consultas de ordenes")
    ap.add_argument("--intervalo-estado", type=float, default=5.0, help="segundos entre publicaciones de estado")
    ap.add_argument("--registrar-cada", type=int, default=0, help="segundos entre registros en 'temperaturas' (0 = no registrar)")
    args = ap.parse_args()

    with open(args.google_services, encoding="utf-8") as f:
        cfg = json.load(f)
    api_key = cfg["client"][0]["api_key"][0]["current_key"]
    project_id = cfg["project_info"]["project_id"]

    email = args.email or input("Correo de la app: ").strip()
    clave = os.environ.get("FIREBASE_CLAVE") or getpass.getpass("Contrasena: ")
    fb = Firebase(api_key, project_id)
    fb.login(email, clave)
    print(f"Sesion iniciada en Firebase como {email}")

    puerto = args.puerto or detectar_puerto()
    if not puerto:
        sys.exit("No se encontro ningun puerto serie. Conecta el Arduino por USB.")
    ser = serial.Serial(puerto, BAUDIOS, timeout=0.1)
    time.sleep(2.0)                  # la placa puede reiniciarse al abrir el puerto
    print(f"Conectado al Arduino en {puerto}. Escribe un comando (LED_ON, LED_OFF, GET_TEMP) o Ctrl+C para salir.")

    puente = Puente(ser, fb, args)
    threading.Thread(target=leer_teclado, args=(puente.teclado,), daemon=True).start()
    try:
        puente.iniciar()
        puente.correr()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            puente.enviar("LED_OFF")
        except Exception:
            pass
        try:
            fb.patch_estado({"conectado": False, "ledDeseado": False})
        except Exception:
            pass
        ser.close()
        print("Puente cerrado.")


if __name__ == "__main__":
    main()
