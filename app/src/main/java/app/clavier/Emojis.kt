package app.clavier

import android.content.Context
import java.io.File

/** Emojis par catégorie ; l'icône de l'onglet est le premier emoji de la liste. */
object Emojis {
    val categories: List<List<String>> = listOf(
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾",
        "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 🕵️ 💂 👷 🤴 👸 🧕 🤵 👰 🤰 🤱 👼 🎅 🦸 🦹 🧙 🧚 🧛 🧜 🧝 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧗 👫 👬 👭 💏 💑 👪",
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ 🔀 🔁 ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔼 🔽 ⚠️ 🚸 ⛔ 🚫 ❌ ⭕ ✅ ☑️ ✔️ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 💯 🔥 ✨ ⭐ 🌟 💫 💥 💢 💦 💨 🕳️ 💬 💭 💤 🎵 🎶 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ 🔄 🆗 🆕 🆒 🆓 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤",
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊️ 🐇 🦝 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌙 ⭐ ☀️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ 🌈 🌊 💧",
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥖 🍞 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🧊",
        "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🎱 🏓 🏸 🏒 🏑 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤺 🏌️ 🏇 🧘 🏄 🏊 🚣 🧗 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎫 🎟️ 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚜 🛵 🏍️ 🚲 🛴 🚨 🚆 🚇 🚊 🚝 🚄 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛳️ 🚢 ⚓ 🗽 🗼 🏰 🏯 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🏕️ 🏠 🏡 🏢 🏥 🏦 🏨 🏪 🏫 ⛪ 🕌 🌅 🌄 🌃 🌉",
        "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ ⌛ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💳 💎 ⚖️ 🔧 🔨 🛠️ ⚙️ 🔩 🧲 🔫 💣 🔪 🛡️ 🔮 💈 🔭 🔬 💊 💉 🩹 🌡️ 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎁 🎈 🎀 🎊 🎉 🎎 🏮 ✉️ 📩 📦 📫 📮 📜 📄 📅 📆 🗓️ 📇 📈 📉 📊 📋 📌 📍 📎 📏 📐 ✂️ 🗃️ 🗑️ 🔒 🔓 🔏 🔐 📝 ✏️ 🖊️ 🖋️ 🖌️ 🖍️ 🔍 🔎 📚 📖 🔖 🏷️ 🧾 💼 👓 🕶️ 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 👙 👚 👛 👜 👝 🎒 👞 👟 👠 👡 👢 👑 👒 🎩 🎓 🧢 💄 💍",
        "🏁 🚩 🏳️ 🏴 🏳️‍🌈 🇫🇷 🇧🇪 🇨🇭 🇨🇦 🇱🇺 🇲🇨 🇩🇪 🇬🇧 🇺🇸 🇪🇸 🇮🇹 🇵🇹 🇳🇱 🇮🇪 🇦🇹 🇩🇰 🇸🇪 🇳🇴 🇫🇮 🇵🇱 🇬🇷 🇹🇷 🇲🇦 🇩🇿 🇹🇳 🇸🇳 🇨🇮 🇨🇲 🇲🇬 🇧🇷 🇦🇷 🇲🇽 🇯🇵 🇰🇷 🇨🇳 🇮🇳 🇦🇺 🇪🇺 🇺🇦",
    ).map { it.split(' ') }

    private const val MAX_RECENT = 32

    private fun file(context: Context) = File(context.filesDir, "emojis_recents.txt")

    fun recent(context: Context): List<String> = runCatching { file(context).readText().split(' ').filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    fun remember(context: Context, emoji: String) {
        val list = (listOf(emoji) + recent(context).filter { it != emoji }).take(MAX_RECENT)
        runCatching { file(context).writeText(list.joinToString(" ")) }
    }
}
