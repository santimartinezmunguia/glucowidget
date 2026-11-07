# GlucoWidget

Widget de pantalla de inicio para Android que muestra la última lectura de glucosa
de una cuenta **LibreLinkUp** (valor, flecha de tendencia, hora y color según rango).

> ⚠️ Usa la API **no oficial** que emplea la app LibreLinkUp. Abbott puede cambiarla
> o bloquearla sin aviso. No es un dispositivo médico: no tomes decisiones de
> tratamiento basándote en este widget.

## Qué hace

- Inicia sesión con tu correo y contraseña de LibreLinkUp (cuenta de seguidor) y sigue
  automáticamente la redirección a tu servidor regional (EU, US, etc.).
- Muestra: valor en mg/dL o mmol/L, flecha (↓ ↘ → ↗ ↑), hora de la lectura y un punto de
  color: verde en rango, rojo bajo, naranja alto, gris si la lectura tiene más de 20 min.
- Se actualiza cada 15 min (mínimo que permite Android) y al **tocar el widget**.
- Si sigues a varias personas, eliges a quién mostrar.
- Las credenciales se guardan cifradas (Android Keystore) solo en el teléfono.

## Instalar

1. En GitHub, pestaña **Actions** → último run de *Build APK* → descarga el artefacto
   `GlucoWidget-debug-apk` y descomprímelo.
2. Copia el `.apk` al móvil e instálalo (permite "instalar apps de origen desconocido").
3. Abre **GlucoWidget**, escribe tu cuenta, pulsa **Probar conexión** y luego **Guardar**.
4. Mantén pulsada la pantalla de inicio → Widgets → **Glucosa (LibreLinkUp)**.

También puedes abrir el proyecto en Android Studio y ejecutarlo (`./gradlew assembleDebug`).

## iPhone (Scriptable)

En iPhone no se pueden instalar apps sin firmarlas con Apple, así que la versión de iPhone
es un script para la app gratuita **Scriptable**:

1. Instala **Scriptable** desde la App Store.
2. Abre [`ios/GlucoWidget.js`](ios/GlucoWidget.js), pulsa *Raw* y copia todo el texto.
3. En Scriptable pulsa **+**, pega el texto y ponle de nombre *GlucoWidget*.
4. Pulsa ▶︎ para ejecutarlo: te pedirá correo, contraseña, unidades y límites.
5. Mantén pulsada la pantalla de inicio → **+** → *Scriptable* → elige el tamaño pequeño.
   Mantén pulsado el widget → *Editar widget* → *Script*: **GlucoWidget**.
   También funciona en la pantalla de bloqueo.

iOS decide cuándo refrescar el widget (normalmente cada 5–15 minutos); el texto
"hace X min" se actualiza solo. La cuenta se guarda en el Llavero del iPhone.

## Si deja de funcionar

- *"Abre la app LibreLinkUp y acepta los términos"*: entra en la app oficial y acepta lo
  que pida.
- Errores HTTP 4xx tras una actualización de LibreLinkUp: sube `APP_VERSION` en
  `app/src/main/java/com/glucowidget/LluClient.kt` a la versión actual de la app.
- *Demasiados intentos*: LibreView bloquea temporalmente; espera unos minutos.

## Estructura

| Archivo | Función |
|---|---|
| `LluClient.kt` | Login, redirección regional, `Account-Id`, lectura de `/llu/connections` |
| `RefreshWorker.kt` | Actualización periódica con WorkManager |
| `GlucoseWidgetProvider.kt` | Ciclo de vida del widget y toque para actualizar |
| `WidgetRenderer.kt` | Dibuja el widget (RemoteViews) |
| `ConfigActivity.kt` | Pantalla de cuenta, unidades, límites y paciente |
| `Prefs.kt` | Ajustes cifrados y caché de sesión/lectura |
