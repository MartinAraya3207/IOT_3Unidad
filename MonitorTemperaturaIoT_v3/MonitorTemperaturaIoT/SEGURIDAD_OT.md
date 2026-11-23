# Lineamientos básicos de seguridad OT — Monitor de Temperatura IoT

Este documento es una guía académica de controles a implementar y verificar; no es una certificación de cumplimiento.

## Activos y riesgos
- Datos de temperatura y marcas de tiempo: lectura incorrecta, modificación o pérdida.
- Cuentas de usuario: acceso no autorizado por contraseña débil o credenciales compartidas.
- Canal Bluetooth y módulo serial: conexión a dispositivo equivocado, comandos no autorizados o interferencia.
- Actuador de prueba (LED): comando enviado accidentalmente o por un usuario no autorizado.
- Firebase: lectura/escritura indebida si las reglas de Firestore son demasiado permisivas.

## Controles aplicados/propuestos
1. Identidad: Firebase Authentication con correo y contraseña; no guardar contraseñas manualmente en Firestore.
2. Acceso: reglas Firestore requieren usuario autenticado; cada usuario solo crea registros con su propio UID; no se permite actualizar/borrar registros desde cliente.
3. Transporte: SDK Firebase usa TLS/HTTPS. El enlace USB-serie con el Arduino usa checksum XOR en cada trama (integridad) y la nube usa Firebase Authentication; el cable local no se cifra.
4. Mínimo privilegio: no publicar archivos de configuración/credenciales, no usar reglas públicas y revisar permisos Android.
5. Integridad y trazabilidad: guardar temperatura, fecha, UID, correo y origen de lectura.
6. Operación segura: comandos LED_ON/LED_OFF son demostrativos. En una instalación real, limitar actuadores, validar rangos y disponer de apagado seguro.
7. Disponibilidad: mostrar errores de conexión y probar reconexión; no interpretar falta de datos como temperatura cero.
8. Protección física: proteger el Arduino, sensor, alimentación y conexiones; desconectar antes de modificar el circuito.

## Referencias para el análisis
- ISO/IEC 27001: sistema de gestión de seguridad de la información (referencia de buenas prácticas, no certificación de esta app).
- ISO 27400: seguridad y privacidad en IoT.
- IEC 62443: familia de estándares de ciberseguridad para sistemas de automatización y control industrial (OT).

## Evidencia para la presentación
- Capturas de Firebase Authentication con usuarios de prueba (ocultar correos reales si corresponde).
- Reglas de Firestore publicadas y prueba de acceso sin sesión denegado.
- Registro de temperatura con UID/correo/fecha en Firestore.
- Prueba de sincronización de historial entre dos teléfonos.
- Evidencia de permisos Bluetooth y prueba de desconexión/reconexión.
- Lista de riesgos, controles, resultados y mejoras pendientes.
