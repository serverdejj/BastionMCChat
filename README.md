# BastionMCChat v1.0.0 — BASTION MC

Proyecto Java 21 + Gradle, diseñado para **Purpur/Paper 1.21.11**.

## Instalación
1. Extrae el ZIP o sube **su contenido** a la raíz de un repositorio GitHub (incluida `.github/workflows/build.yml`).
2. GitHub → **Actions** → **Compilar BastionMCChat** → **Run workflow** (también se compila al hacer push).
3. Descarga el artefacto `BastionMCChat-jar` y coloca el JAR en `plugins/`.
4. Instala PlaceholderAPI (opcional pero necesario para los campos variables), LuckPerms y BastionMCClans si los usas.
5. Reinicia el servidor. Edita `plugins/BastionMCChat/config.yml`, `messages.yml` y `chatcolor.yml`.
6. Usa `/bastionchat reload` para aplicar cambios (permiso `bastionchat.admin.reload`).

**Importante:** No se ha compilado ni probado dentro de un servidor Purpur en este entorno. Comprueba el resultado de Actions antes de usarlo en producción.

## Formato
`{clan} {rankup} {rank} &f{player} &8» {message}`

- Sin clan se muestra `&c✘`. **La conexión con BastionMCClans depende de que el plugin publique un placeholder a PlaceholderAPI.** Ajusta `clan.placeholder` al identificador real que proporcione tu versión de Clans; `'%bastionclans_clan%'` es un valor inicial, **no está verificado**. Si BastionMCClans no incluye expansión, hace falta adaptar su código para exponer el dato.
- Rankup enseña `&e[1✦]` hasta que configures `rankup.placeholder` con la expansión real.
- `rank.placeholder` usa `%luckperms_prefix%` (requiere la expansión LuckPerms de PAPI), con fallback `[DEFAULT]`. No duplicamos corchetes automáticamente.
- No uses otro formateador del chat simultáneamente; desactiva su módulo de formato global.
- El chat de clan **no se modifica intencionalmente**, pero hay que probar qué evento utiliza tu versión de BastionMCClans para asegurarse de que no haya interferencia.

## Comandos y permisos
| Comando | Permiso | Predeterminado |
| --- | --- | --- |
| `/chatcolor` | `bastionchat.chatcolor` | no |
| `/chatcolor reset` | `bastionchat.chatcolor` | no |
| `[i]`, `[item]` | `bastionchat.showitem` | sí |
| `[inv]`, `[inventario]` | `bastionchat.showinventory` | sí |
| `[p]`, `[ping]` | `bastionchat.showping` | sí |
| `@Nombre` | `bastionchat.mention` | sí |
| Sin cooldown | `bastionchat.bypass.cooldown` | OP |
| `/bastionchat reload` | `bastionchat.admin.reload` | OP |

Ejemplo: `/lp group vip permission set bastionchat.chatcolor true`

## Funciones
- Colores sólidos individuales guardados en `player-colors.yml`, menú GUI totalmente configurable, `reset`. Color elegido también en las burbujas.
- `[item]`: copia el objeto de la mano principal y crea un enlace de chat para inspeccionarlo en el centro de una GUI de 3 filas, con tooltip de Minecraft y todos sus datos NBT/componentes a ese instante.
- `[inv]`: captura 36 espacios, armadura y mano secundaria, en un menú **solo lectura** de 54 slots.
- Ambos enlaces vencen en **300 segundos (5 minutos)**. Después caducan silenciosamente. No se muestra contador ni aviso de expiración. Las capturas son temporales **en RAM**: no sobreviven reinicios ni reload del JAR.
- `[ping]`: muestra el ping actual del emisor.
- `@Nombre`: aparece rosa `&d`, sonido configurable, admite varias menciones, con protección antispam de sonido.
- Burbujas `TextDisplay` con fondo configurable y altura 2.8, visibilidad por espectador, desaparecen tras 5 segundos. El desplazamiento sigue al jugador.
- Cooldown del chat 2s, del showcase 10s; antirrepetición opcional.
- Soporte legacy `&`, HEX `&#RRGGBB` y degradados carácter por carácter **en los textos YAML**. Los usuarios de `/chatcolor` solo seleccionan colores sólidos.

## Limitaciones conocidas / precauciones
- El orden de procesamiento del chat puede diferir al de otros plugins de chat, ya que el chat público se envía en el hilo principal tras cancelar el evento `AsyncChatEvent`.
- Si un plugin de clanes usa el mismo evento de chat a una prioridad posterior o no cancela el evento del chat privado, hay que integrar su API específicamente para evitar difundir mensajes de clanes. **Probar antes de producción.**
- La burbuja es un `TextDisplay` temporal, no una GUI de chat. Palabras largas pueden superar el ancho definido.
- Las menciones se muestran rosa en mensajes y burbujas; otras etiquetas interactivas no se muestran clicables dentro de las burbujas.


## Cambio v1.0.1 — burbujas
- Corrige `TextDisplay.setVisibleByDefault(false)` a `true` para que el servidor los envie normalmente al cliente.
- `show-to-sender: true` predeterminado para probar las burbujas en tercera persona (F5).
- Si ya existe `plugins/BastionMCChat/config.yml`, el plugin **no** lo sobrescribe: modifica ahi `chat-bubbles.show-to-sender: true` y recarga.
- Conserva todas las opciones de fondo, altura y texto.


## Integración con BastionMCClans (v1.0.2)

Si BastionMCClans esta instalado y activado, el chat consulta directamente
`hasClan(UUID)` y `getClanColored(UUID)` para mostrar `[NombreClan]`
con el color configurado mediante `/clan colorname`.

Si no tiene clan aparece `&c✘` sin corchetes. La consulta directa
no necesita expansion de PlaceholderAPI. Si esa API no existe, el plugin
usa `%bastionclans_colored%` como respaldo (requiere PlaceholderAPI).

`config.yml` permite personalizar `clan.format`, `clan.no-clan`,
`clan.placeholder` y `clan.use-direct-api`.

Si ya tienes un `config.yml` de v1.0.1 no se sobreescribe: agrega
`clan.use-direct-api: true` y cambia el placeholder manualmente si quieres
el respaldo correcto. El modo API directa esta activado por defecto.
