# Configuración de Firebase — Control Total

Proyecto Android: `cl.controltotal.finanzas`.

1. Crear proyecto Firebase y registrar la aplicación Android con ese identificador.
2. Descargar `google-services.json` desde Firebase y configurarlo de manera segura para compilación (no subir claves privadas ni cuentas de servicio).
3. Activar Authentication con proveedor correo/contraseña.
4. Crear Firestore y publicar reglas que restrinjan cada hogar a sus miembros autenticados. No usar reglas públicas.
5. Probar dos cuentas, pertenencia al mismo hogar, aislamiento de otros hogares y recuperación de sesión.
6. Configurar CI para compilar sin exponer secretos en logs o artefactos.

**Estado:** Pendiente. El prototipo actual no usa Firebase. No ingresar datos financieros reales hasta completar y verificar la seguridad.
